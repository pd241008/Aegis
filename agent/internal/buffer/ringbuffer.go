package buffer

import (
	"sync"
	"time"

	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
	"google.golang.org/protobuf/proto"
)

type Entry struct {
	Timestamp time.Time
	Payload   *telemetryv1.TelemetryRequest
}

type RingBuffer struct {
	mu          sync.RWMutex
	entries     []Entry
	capacity    int
	windowSize  time.Duration
	writeIdx    int
	count       int
	totalBytes  int64
	maxBytes    int64
}

func New(windowSize time.Duration, maxBytes int64) *RingBuffer {
	capacity := 10000
	return &RingBuffer{
		entries:    make([]Entry, capacity),
		capacity:   capacity,
		windowSize: windowSize,
		maxBytes:   maxBytes,
	}
}

func (rb *RingBuffer) Append(req *telemetryv1.TelemetryRequest) {
	rb.mu.Lock()
	defer rb.mu.Unlock()

	data, _ := proto.Marshal(req)
	entrySize := int64(len(data))

	rb.entries[rb.writeIdx] = Entry{
		Timestamp: time.Now(),
		Payload:   req,
	}

	rb.writeIdx = (rb.writeIdx + 1) % rb.capacity
	if rb.count < rb.capacity {
		rb.count++
	}
	rb.totalBytes += entrySize

	if rb.totalBytes > rb.maxBytes {
		rb.evictOldest()
	}

	rb.evictExpired()
}

func (rb *RingBuffer) evictOldest() {
	if rb.count == 0 {
		return
	}
	oldestIdx := (rb.writeIdx - rb.count + rb.capacity) % rb.capacity
	data, _ := proto.Marshal(rb.entries[oldestIdx].Payload)
	rb.totalBytes -= int64(len(data))
	rb.entries[oldestIdx] = Entry{}
	rb.count--
}

func (rb *RingBuffer) evictExpired() {
	now := time.Now()
	for rb.count > 0 {
		oldestIdx := (rb.writeIdx - rb.count + rb.capacity) % rb.capacity
		if now.Sub(rb.entries[oldestIdx].Timestamp) <= rb.windowSize {
			break
		}
		data, _ := proto.Marshal(rb.entries[oldestIdx].Payload)
		rb.totalBytes -= int64(len(data))
		rb.entries[oldestIdx] = Entry{}
		rb.count--
	}
}

func (rb *RingBuffer) Snapshot() []*telemetryv1.TelemetryRequest {
	rb.mu.RLock()
	defer rb.mu.RUnlock()

	result := make([]*telemetryv1.TelemetryRequest, 0, rb.count)
	now := time.Now()

	startIdx := (rb.writeIdx - rb.count + rb.capacity) % rb.capacity
	for i := 0; i < rb.count; i++ {
		idx := (startIdx + i) % rb.capacity
		if now.Sub(rb.entries[idx].Timestamp) <= rb.windowSize {
			result = append(result, rb.entries[idx].Payload)
		}
	}
	return result
}

func (rb *RingBuffer) SnapshotRange(start, end time.Time) []*telemetryv1.TelemetryRequest {
	rb.mu.RLock()
	defer rb.mu.RUnlock()

	result := make([]*telemetryv1.TelemetryRequest, 0)
	startIdx := (rb.writeIdx - rb.count + rb.capacity) % rb.capacity

	for i := 0; i < rb.count; i++ {
		idx := (startIdx + i) % rb.capacity
		entry := rb.entries[idx]
		if entry.Timestamp.After(start) && entry.Timestamp.Before(end) {
			result = append(result, entry.Payload)
		}
	}
	return result
}

func (rb *RingBuffer) Size() int64 {
	rb.mu.RLock()
	defer rb.mu.RUnlock()
	return rb.totalBytes
}

func (rb *RingBuffer) Count() int {
	rb.mu.RLock()
	defer rb.mu.RUnlock()
	return rb.count
}

func (rb *RingBuffer) SerializeSnapshot() ([]byte, error) {
	snapshot := rb.Snapshot()
	return proto.Marshal(&telemetryv1.FlushChunk{
		Data:     serializeEntries(snapshot),
		TotalSize: int64(len(snapshot)),
		Offset:   0,
		IsLast:   true,
	})
}

func serializeEntries(entries []*telemetryv1.TelemetryRequest) []byte {
	var result []byte
	for _, e := range entries {
		data, _ := proto.Marshal(e)
		result = append(result, data...)
	}
	return result
}
