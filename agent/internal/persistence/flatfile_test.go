package persistence

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/aegis/agent/internal/config"
	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
)

func req(id string) *telemetryv1.TelemetryRequest {
	return &telemetryv1.TelemetryRequest{
		Agent: &telemetryv1.AgentMetadata{AgentId: id, TimestampNs: time.Now().UnixNano()},
		Payload: &telemetryv1.TelemetryRequest_Metric{
			Metric: &telemetryv1.MetricPayload{CpuUsagePercent: 1.5, MemoryUsagePercent: 2.5},
		},
	}
}

func newStore(t *testing.T) *FlatFileStore {
	t.Helper()
	cfg := &config.Config{PersistDir: t.TempDir()}
	s := NewFlatFileStore(cfg)
	if err := s.Start(); err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	t.Cleanup(s.Stop)
	return s
}

func TestPersistAndLoadAll(t *testing.T) {
	s := newStore(t)

	if err := s.Persist([]*telemetryv1.TelemetryRequest{req("a"), req("b")}); err != nil {
		t.Fatalf("Persist failed: %v", err)
	}
	if err := s.Persist([]*telemetryv1.TelemetryRequest{req("c")}); err != nil {
		t.Fatalf("Persist failed: %v", err)
	}

	loaded, err := s.LoadAll()
	if err != nil {
		t.Fatalf("LoadAll failed: %v", err)
	}
	if len(loaded) != 3 {
		t.Fatalf("expected 3 loaded entries, got %d", len(loaded))
	}
	if loaded[0].Agent.AgentId != "a" {
		t.Fatalf("expected first entry from agent 'a', got %q", loaded[0].Agent.AgentId)
	}
}

func TestPersistBeforeStartIsNoop(t *testing.T) {
	cfg := &config.Config{PersistDir: t.TempDir()}
	s := NewFlatFileStore(cfg)
	if err := s.Persist([]*telemetryv1.TelemetryRequest{req("a")}); err != nil {
		t.Fatalf("Persist before Start should not error, got %v", err)
	}
	entries, err := os.ReadDir(cfg.PersistDir)
	if err != nil {
		t.Fatalf("expected persist dir to exist: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("expected no files persisted before Start, got %d", len(entries))
	}
}

func TestCleanupRemovesOldFiles(t *testing.T) {
	s := newStore(t)

	if err := s.Persist([]*telemetryv1.TelemetryRequest{req("old")}); err != nil {
		t.Fatalf("Persist failed: %v", err)
	}

	// Age the persisted file so Cleanup considers it stale.
	entries, err := os.ReadDir(s.cfg.PersistDir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected 1 file, got %d", len(entries))
	}
	old := filepath.Join(s.cfg.PersistDir, entries[0].Name())
	past := time.Now().Add(-48 * time.Hour)
	if err := os.Chtimes(old, past, past); err != nil {
		t.Fatal(err)
	}

	if err := s.Cleanup(time.Hour); err != nil {
		t.Fatalf("Cleanup failed: %v", err)
	}
	left, err := os.ReadDir(s.cfg.PersistDir)
	if err != nil {
		t.Fatal(err)
	}
	if len(left) != 0 {
		t.Fatalf("expected stale file removed, %d remain", len(left))
	}
}
