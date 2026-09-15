package transport

import (
	"context"
	"fmt"
	"io"
	"log"
	"sync"
	"time"

	"github.com/aegis/agent/internal/buffer"
	"github.com/aegis/agent/internal/config"
	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// ReplaySource provides telemetry that was spooled to disk while the brain
// was unreachable. On every (re)established stream the client drains the
// source first, honouring the "replay / backfill upload" strategy from the
// architecture doc's zero-drop design.
type ReplaySource interface {
	LoadAll() ([]*telemetryv1.TelemetryRequest, error)
}

// Client manages the gRPC stream to the brain: connects, reconnects with
// exponential backoff, replays spooled telemetry after outages, applies
// brain backpressure signals (SLOW_DOWN / RESUME / DROP_LOW_PRIORITY) and
// triggers ring-buffer flushes on FLUSH_NOW.
type Client struct {
	cfg     *config.Config
	ringBuf *buffer.RingBuffer
	replay  ReplaySource

	mu        sync.Mutex
	conn      *grpc.ClientConn
	connected bool
	// throttle is set by SLOW_DOWN and cleared by RESUME; while set, sends
	// are paced at the brain-suggested interval.
	throttleInterval time.Duration
	// dropLowPriority is set by DROP_LOW_PRIORITY; syscall/network events
	// are shed (metrics are never shed) until the next RESUME/ACK.
	dropLowPriority bool
}

func NewClient(cfg *config.Config, ringBuf *buffer.RingBuffer) *Client {
	return &Client{cfg: cfg, ringBuf: ringBuf}
}

// NewClientWithReplay attaches a spool to replay after outages.
func NewClientWithReplay(cfg *config.Config, ringBuf *buffer.RingBuffer, replay ReplaySource) *Client {
	return &Client{cfg: cfg, ringBuf: ringBuf, replay: replay}
}

// dialTimeout bounds each (re)dial attempt so the Run backoff loop stays
// responsive when the brain is down (WithBlock would otherwise wait for the
// full caller context).
const dialTimeout = 5 * time.Second

// Connect dials the brain. It returns an error if the initial dial fails;
// callers may then fall back to local (spool-only) mode.
func (c *Client) Connect(ctx context.Context) error {
	dialCtx, cancel := context.WithTimeout(ctx, dialTimeout)
	defer cancel()

	opts := []grpc.DialOption{
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	}

	conn, err := grpc.DialContext(dialCtx, c.cfg.BrainAddr, opts...)
	if err != nil {
		return fmt.Errorf("failed to connect to brain at %s: %w", c.cfg.BrainAddr, err)
	}

	c.mu.Lock()
	c.conn = conn
	c.connected = true
	c.mu.Unlock()
	log.Printf("Connected to brain at %s", c.cfg.BrainAddr)
	return nil
}

// Run maintains the stream for the lifetime of ctx: it opens the stream,
// replays spooled telemetry, forwards live requests, and re-establishes the
// stream with exponential backoff whenever it drops. It returns when ctx is
// cancelled.
func (c *Client) Run(ctx context.Context, incoming chan *telemetryv1.TelemetryRequest) {
	backoff := time.Second
	const maxBackoff = 30 * time.Second

	for {
		if ctx.Err() != nil {
			return
		}
		if err := c.ensureConnected(ctx); err != nil {
			log.Printf("Brain unreachable (%v); retrying in %s", err, backoff)
			select {
			case <-ctx.Done():
				return
			case <-time.After(backoff):
			}
			backoff = min(backoff*2, maxBackoff)
			continue
		}
		backoff = time.Second

		err := c.streamOnce(ctx, incoming)
		if ctx.Err() != nil {
			return
		}
		log.Printf("Stream dropped (%v); reconnecting in %s", err, backoff)
		c.markDisconnected()
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, maxBackoff)
	}
}

// StreamTelemetry opens a single stream session; kept for direct use and
// tests. Run is the preferred entrypoint for the agent main loop.
func (c *Client) StreamTelemetry(ctx context.Context, incoming chan *telemetryv1.TelemetryRequest) error {
	if err := c.ensureConnected(ctx); err != nil {
		return err
	}
	return c.streamOnce(ctx, incoming)
}

func (c *Client) ensureConnected(ctx context.Context) error {
	c.mu.Lock()
	if c.conn != nil && c.connected {
		c.mu.Unlock()
		return nil
	}
	stale := c.conn
	c.conn = nil
	c.mu.Unlock()
	if stale != nil {
		stale.Close() // drop the broken connection before redialing
	}
	return c.Connect(ctx)
}

func (c *Client) markDisconnected() {
	c.mu.Lock()
	c.connected = false
	c.throttleInterval = 0
	c.dropLowPriority = false
	c.mu.Unlock()
}

// streamOnce runs one full stream session: replay spooled data, then pump
// live traffic until the stream or the context ends.
func (c *Client) streamOnce(ctx context.Context, incoming chan *telemetryv1.TelemetryRequest) error {
	c.mu.Lock()
	rpc := telemetryv1.NewTelemetryServiceClient(c.conn)
	c.mu.Unlock()

	stream, err := rpc.StreamTelemetry(ctx)
	if err != nil {
		return fmt.Errorf("failed to open stream: %w", err)
	}

	c.replaySpooled(stream)

	var wg sync.WaitGroup
	wg.Add(2)

	// Sender goroutine: paces sends while throttled and sheds low-priority
	// payloads when the brain asks for it.
	go func() {
		defer wg.Done()
		for {
			select {
			case <-ctx.Done():
				return
			case req, ok := <-incoming:
				if !ok {
					return
				}
				c.mu.Lock()
				pause := c.throttleInterval
				drop := c.dropLowPriority && req.GetSyscall() != nil || c.dropLowPriority && req.GetNetwork() != nil
				c.mu.Unlock()
				if pause > 0 {
					select {
					case <-ctx.Done():
						return
					case <-time.After(pause):
					}
				}
				if drop {
					continue
				}
				if err := stream.Send(req); err != nil {
					log.Printf("Send error: %v", err)
					return
				}
			}
		}
	}()

	// Receiver goroutine: applies brain backpressure signals.
	go func() {
		defer wg.Done()
		for {
			resp, err := stream.Recv()
			if err == io.EOF {
				return
			}
			if err != nil {
				log.Printf("Recv error: %v", err)
				return
			}
			c.handleResponse(resp)
		}
	}()

	wg.Wait()
	return nil
}

// replaySpooled uploads telemetry persisted during a brain outage, oldest
// first ("backfill upload, prioritising the most recent anomaly data").
// Replay failures are logged, never fatal: live traffic must keep flowing.
func (c *Client) replaySpooled(stream telemetryv1.TelemetryService_StreamTelemetryClient) {
	if c.replay == nil {
		return
	}
	spool, err := c.replay.LoadAll()
	if err != nil {
		log.Printf("Replay: could not read spool: %v", err)
		return
	}
	if len(spool) == 0 {
		return
	}
	sent := 0
	for _, req := range spool {
		if err := stream.Send(req); err != nil {
			log.Printf("Replay aborted after %d/%d entries: %v", sent, len(spool), err)
			return
		}
		sent++
	}
	log.Printf("Replayed %d spooled telemetry entries to the brain", sent)
}

func (c *Client) handleResponse(resp *telemetryv1.TelemetryResponse) {
	switch resp.Action {
	case telemetryv1.TelemetryResponse_ACK:
		c.mu.Lock()
		c.dropLowPriority = false
		c.mu.Unlock()
	case telemetryv1.TelemetryResponse_SLOW_DOWN:
		interval := time.Duration(resp.ThrottleIntervalMs) * time.Millisecond
		if interval <= 0 {
			interval = 500 * time.Millisecond
		}
		c.mu.Lock()
		c.throttleInterval = interval
		c.mu.Unlock()
		log.Printf("Brain requested slow down: %s (interval: %dms)", resp.Message, resp.ThrottleIntervalMs)
	case telemetryv1.TelemetryResponse_FLUSH_NOW:
		log.Printf("Brain requested flush: %s", resp.Message)
		c.triggerFlush()
	case telemetryv1.TelemetryResponse_RESUME:
		c.mu.Lock()
		c.throttleInterval = 0
		c.dropLowPriority = false
		c.mu.Unlock()
		log.Printf("Brain resumed normal operation: %s", resp.Message)
	case telemetryv1.TelemetryResponse_DROP_LOW_PRIORITY:
		c.mu.Lock()
		c.dropLowPriority = true
		c.mu.Unlock()
		log.Printf("Brain requested drop low priority: %s", resp.Message)
	}
}

func (c *Client) triggerFlush() {
	c.mu.Lock()
	conn := c.conn // snapshot: Close() may nil the field concurrently
	if conn == nil {
		c.mu.Unlock()
		log.Println("Flush skipped: connection already closed")
		return
	}

	snapshot := c.ringBuf.Snapshot()
	log.Printf("Flushing %d telemetry entries to brain", len(snapshot))

	var startNs, endNs int64
	if len(snapshot) > 0 {
		startNs = snapshot[0].Agent.TimestampNs
		endNs = snapshot[len(snapshot)-1].Agent.TimestampNs
	} else {
		now := time.Now().UnixNano()
		startNs = now - 60*int64(time.Second)
		endNs = now
	}
	c.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	rpc := telemetryv1.NewTelemetryServiceClient(conn)
	stream, err := rpc.FlushBuffer(ctx, &telemetryv1.FlushRequest{
		AgentId:     c.cfg.AgentID,
		StartTimeNs: startNs,
		EndTimeNs:   endNs,
	})
	if err != nil {
		log.Printf("Flush request error: %v", err)
		return
	}

	totalBytes := int64(0)
	for {
		chunk, err := stream.Recv()
		if err != nil {
			break
		}
		totalBytes += int64(len(chunk.Data))
	}
	log.Printf("Flush complete: %d bytes sent", totalBytes)
}

func (c *Client) Close() {
	c.mu.Lock()
	conn := c.conn
	c.conn = nil
	c.connected = false
	c.mu.Unlock()
	if conn != nil {
		conn.Close()
	}
}

func (c *Client) IsConnected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.connected
}
