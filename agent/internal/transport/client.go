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

type Client struct {
	cfg      *config.Config
	conn     *grpc.ClientConn
	rpc      telemetryv1.TelemetryServiceClient
	ringBuf  *buffer.RingBuffer
	mu       sync.Mutex
	cancel   context.CancelFunc
	connected bool
}

func NewClient(cfg *config.Config, ringBuf *buffer.RingBuffer) *Client {
	return &Client{
		cfg:     cfg,
		ringBuf: ringBuf,
	}
}

func (c *Client) Connect(ctx context.Context) error {
	var cancel context.CancelFunc
	ctx, cancel = context.WithCancel(ctx)
	c.cancel = cancel

	opts := []grpc.DialOption{
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	}

	var err error
	c.conn, err = grpc.DialContext(ctx, c.cfg.BrainAddr, opts...)
	if err != nil {
		return fmt.Errorf("failed to connect to brain at %s: %w", c.cfg.BrainAddr, err)
	}

	c.rpc = telemetryv1.NewTelemetryServiceClient(c.conn)
	c.connected = true
	log.Printf("Connected to brain at %s", c.cfg.BrainAddr)
	return nil
}

func (c *Client) StreamTelemetry(ctx context.Context, incoming chan *telemetryv1.TelemetryRequest) error {
	stream, err := c.rpc.StreamTelemetry(ctx)
	if err != nil {
		return fmt.Errorf("failed to open stream: %w", err)
	}

	var wg sync.WaitGroup
	wg.Add(2)

	// Sender goroutine
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
				if err := stream.Send(req); err != nil {
					log.Printf("Send error: %v", err)
					return
				}
			}
		}
	}()

	// Receiver goroutine (handles backpressure)
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

func (c *Client) handleResponse(resp *telemetryv1.TelemetryResponse) {
	switch resp.Action {
	case telemetryv1.TelemetryResponse_ACK:
		// Normal operation, no action needed
	case telemetryv1.TelemetryResponse_SLOW_DOWN:
		log.Printf("Brain requested slow down: %s (interval: %dms)", resp.Message, resp.ThrottleIntervalMs)
	case telemetryv1.TelemetryResponse_FLUSH_NOW:
		log.Printf("Brain requested flush: %s", resp.Message)
		c.triggerFlush()
	case telemetryv1.TelemetryResponse_RESUME:
		log.Printf("Brain resumed normal operation: %s", resp.Message)
	case telemetryv1.TelemetryResponse_DROP_LOW_PRIORITY:
		log.Printf("Brain requested drop low priority: %s", resp.Message)
	}
}

func (c *Client) triggerFlush() {
	c.mu.Lock()
	defer c.mu.Unlock()

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

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	stream, err := c.rpc.FlushBuffer(ctx, &telemetryv1.FlushRequest{
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
	if c.cancel != nil {
		c.cancel()
	}
	if c.conn != nil {
		c.conn.Close()
	}
}

func (c *Client) IsConnected() bool {
	return c.connected
}
