package buffer

import (
	"strings"
	"testing"
	"time"

	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
)

func metricReq(id string) *telemetryv1.TelemetryRequest {
	return &telemetryv1.TelemetryRequest{
		Agent: &telemetryv1.AgentMetadata{AgentId: id, TimestampNs: time.Now().UnixNano()},
		Payload: &telemetryv1.TelemetryRequest_Metric{
			Metric: &telemetryv1.MetricPayload{CpuUsagePercent: 10, MemoryUsagePercent: 20},
		},
	}
}

func syscallReq(size int) *telemetryv1.TelemetryRequest {
	return &telemetryv1.TelemetryRequest{
		Agent: &telemetryv1.AgentMetadata{AgentId: "sentinel", TimestampNs: time.Now().UnixNano()},
		Payload: &telemetryv1.TelemetryRequest_Syscall{
			Syscall: &telemetryv1.SyscallPayload{SyscallName: "read", StackTrace: strings.Repeat("x", size)},
		},
	}
}

func TestAppendAndCount(t *testing.T) {
	rb := New(60*time.Second, 1<<30)
	for i := 0; i < 5; i++ {
		rb.Append(metricReq("a"))
	}
	if got := rb.Count(); got != 5 {
		t.Fatalf("expected 5 entries, got %d", got)
	}
}

func TestSnapshotReturnsInsertionOrder(t *testing.T) {
	rb := New(60*time.Second, 1<<30)
	for i := 0; i < 3; i++ {
		rb.Append(metricReq("a"))
	}
	snap := rb.Snapshot()
	if len(snap) != 3 {
		t.Fatalf("expected 3 snapshot entries, got %d", len(snap))
	}
	// Entries must be ordered oldest -> newest.
	for i := 1; i < len(snap); i++ {
		if snap[i].Agent.TimestampNs < snap[i-1].Agent.TimestampNs {
			t.Fatalf("snapshot out of order at index %d", i)
		}
	}
}

func TestWindowEviction(t *testing.T) {
	rb := New(50*time.Millisecond, 1<<30)
	rb.Append(metricReq("a"))
	time.Sleep(80 * time.Millisecond)
	rb.Append(metricReq("a"))
	if got := rb.Count(); got != 1 {
		t.Fatalf("expected expired entry evicted (count 1), got %d", got)
	}
}

func TestByteBudgetEviction(t *testing.T) {
	rb := New(60*time.Second, 400)
	for i := 0; i < 20; i++ {
		rb.Append(syscallReq(100))
	}
	if rb.Count() >= 20 {
		t.Fatalf("expected byte-budget eviction, still have %d entries", rb.Count())
	}
	if rb.Size() > 400 {
		t.Fatalf("total bytes %d exceeds budget %d", rb.Size(), 400)
	}
}

func TestSnapshotRange(t *testing.T) {
	rb := New(60*time.Second, 1<<30)
	rb.Append(metricReq("a"))
	mid := time.Now()
	time.Sleep(5 * time.Millisecond)
	rb.Append(metricReq("a"))
	rangeSnap := rb.SnapshotRange(time.Now().Add(-time.Hour), mid.Add(time.Millisecond))
	if len(rangeSnap) != 1 {
		t.Fatalf("expected exactly 1 entry in range, got %d", len(rangeSnap))
	}
}

func TestSerializeSnapshot(t *testing.T) {
	rb := New(60*time.Second, 1<<30)
	rb.Append(metricReq("a"))
	rb.Append(metricReq("a"))
	data, err := rb.SerializeSnapshot()
	if err != nil {
		t.Fatalf("serialize failed: %v", err)
	}
	if len(data) == 0 {
		t.Fatal("expected non-empty serialized snapshot")
	}
}
