package transport

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/aegis/agent/internal/buffer"
	"github.com/aegis/agent/internal/config"
	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
	"google.golang.org/grpc"
)

// fakeBrain is an in-process stand-in for the Scala cluster implementing the
// TelemetryService gRPC contract. Used to exercise the real agent client
// end-to-end over a real TCP connection.
type fakeBrain struct {
	telemetryv1.UnimplementedTelemetryServiceServer
	received  chan *telemetryv1.TelemetryRequest
	flushReqs chan *telemetryv1.FlushRequest
	mu        sync.Mutex
	sentCount int
}

func (f *fakeBrain) StreamTelemetry(stream telemetryv1.TelemetryService_StreamTelemetryServer) error {
	for {
		req, err := stream.Recv()
		if err != nil {
			return nil
		}
		select {
		case f.received <- req:
		default:
		}

		f.mu.Lock()
		f.sentCount++
		action := telemetryv1.TelemetryResponse_ACK
		if f.sentCount == 2 {
			action = telemetryv1.TelemetryResponse_FLUSH_NOW
		}
		f.mu.Unlock()

		if err := stream.Send(&telemetryv1.TelemetryResponse{Action: action}); err != nil {
			return err
		}
	}
}

func (f *fakeBrain) FlushBuffer(req *telemetryv1.FlushRequest, stream telemetryv1.TelemetryService_FlushBufferServer) error {
	select {
	case f.flushReqs <- req:
	default:
	}
	payload := []byte("flushed-window")
	if err := stream.Send(&telemetryv1.FlushChunk{
		Data:      payload,
		TotalSize: int64(len(payload)),
		Offset:    0,
		IsLast:    true,
	}); err != nil {
		return err
	}
	return nil
}

func startFakeBrain(t *testing.T) (*fakeBrain, string, func()) {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	srv := grpc.NewServer()
	brain := &fakeBrain{
		received:  make(chan *telemetryv1.TelemetryRequest, 100),
		flushReqs: make(chan *telemetryv1.FlushRequest, 10),
	}
	telemetryv1.RegisterTelemetryServiceServer(srv, brain)
	go func() { _ = srv.Serve(lis) }()
	return brain, lis.Addr().String(), func() { srv.Stop() }
}

func testReq() *telemetryv1.TelemetryRequest {
	return &telemetryv1.TelemetryRequest{
		Agent: &telemetryv1.AgentMetadata{
			AgentId:     "sentinel-test",
			Hostname:    "test-host",
			Os:          "linux",
			TimestampNs: time.Now().UnixNano(),
		},
		Payload: &telemetryv1.TelemetryRequest_Metric{
			Metric: &telemetryv1.MetricPayload{CpuUsagePercent: 12.5, MemoryUsagePercent: 45.0},
		},
	}
}

func newConnectedClient(t *testing.T, addr, agentID string) (*Client, *buffer.RingBuffer, context.Context) {
	t.Helper()
	cfg := &config.Config{BrainAddr: addr, AgentID: agentID}
	rb := buffer.New(60*time.Second, 1<<30)
	client := NewClient(cfg, rb)

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	if err := client.Connect(ctx); err != nil {
		cancel()
		t.Fatalf("Connect failed: %v", err)
	}
	t.Cleanup(func() {
		cancel()
		client.Close()
	})
	return client, rb, ctx
}

func TestClientStreamsTelemetryToBrain(t *testing.T) {
	brain, addr, stop := startFakeBrain(t)
	defer stop()

	client, rb, ctx := newConnectedClient(t, addr, "sentinel-test")

	incoming := make(chan *telemetryv1.TelemetryRequest, 100)
	go client.StreamTelemetry(ctx, incoming)

	const total = 3
	for i := 0; i < total; i++ {
		req := testReq()
		rb.Append(req)
		incoming <- req
	}

	for i := 0; i < total; i++ {
		select {
		case got := <-brain.received:
			if got.Agent.AgentId != "sentinel-test" {
				t.Fatalf("expected agent 'sentinel-test', got %q", got.Agent.AgentId)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("brain received only %d of %d requests", i, total)
		}
	}
}

func TestClientTriggersFlushOnFlushNow(t *testing.T) {
	brain, addr, stop := startFakeBrain(t)
	defer stop()

	client, rb, _ := newConnectedClient(t, addr, "sentinel-flush")

	incoming := make(chan *telemetryv1.TelemetryRequest, 100)
	go client.StreamTelemetry(context.Background(), incoming)

	req := testReq()
	rb.Append(req)
	incoming <- req

	req = testReq()
	rb.Append(req)
	incoming <- req

	select {
	case fr := <-brain.flushReqs:
		if fr.AgentId != "sentinel-flush" {
			t.Fatalf("expected flush for 'sentinel-flush', got %q", fr.AgentId)
		}
		if fr.StartTimeNs <= 0 {
			t.Fatalf("expected a start time range on flush request, got %d", fr.StartTimeNs)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("brain never received a flush request from the client")
	}
}
