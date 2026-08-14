package scraper

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func fakeProcTree(t *testing.T) string {
	t.Helper()
	root := t.TempDir()

	if err := os.MkdirAll(filepath.Join(root, "net"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "42", "fd"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "7", "fd"), 0o755); err != nil {
		t.Fatal(err)
	}

	files := map[string]string{
		"stat": "cpu  100 0 50 50 0 0 0 0 0 0\n",
		"meminfo": "MemTotal:       1000000 kB\n" +
			"MemFree:        200000 kB\n" +
			"Buffers:        100000 kB\n" +
			"Cached:         100000 kB\n",
		"net/tcp": "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n" +
			"   0: 0100007F:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0\n" +
			"   1: 0100007F:0277 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12346 1 0000000000000000 100 0 0 10 0\n",
		"net/tcp6":   "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n",
		"42/syscall": "read 0 0 42\n",
	}

	for name, content := range files {
		path := filepath.Join(root, name)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func TestScrapeMetrics(t *testing.T) {
	s := &Scraper{scrapeInterval: 0, procPath: fakeProcTree(t)}

	m := s.ScrapeMetrics()
	if m.CpuUsagePercent != 75.0 {
		t.Fatalf("expected 75%% CPU, got %.1f", m.CpuUsagePercent)
	}
	if m.MemoryTotalBytes != 1000000*1024 {
		t.Fatalf("expected memory total %d, got %d", 1000000*1024, m.MemoryTotalBytes)
	}
	if m.MemoryUsedBytes != (1000000-200000-100000-100000)*1024 {
		t.Fatalf("unexpected memory used: %d", m.MemoryUsedBytes)
	}
	if m.ActiveConnections != 2 {
		t.Fatalf("expected 2 active connections, got %d", m.ActiveConnections)
	}
	if m.OpenFileDescriptors < 0 {
		t.Fatalf("negative FD count: %d", m.OpenFileDescriptors)
	}
}

func TestScrapeSyscalls(t *testing.T) {
	s := &Scraper{scrapeInterval: 0, procPath: fakeProcTree(t)}
	events := s.ScrapeSyscalls(42)
	if len(events) != 1 {
		t.Fatalf("expected 1 syscall event, got %d", len(events))
	}
	if events[0].SyscallName != "read" {
		t.Fatalf("expected syscall 'read', got %q", events[0].SyscallName)
	}
	if events[0].ReturnCode != 42 {
		t.Fatalf("expected return code 42, got %d", events[0].ReturnCode)
	}
}

func TestScrapeMissingProcIsSafe(t *testing.T) {
	s := &Scraper{scrapeInterval: time.Second, procPath: t.TempDir()}
	if m := s.ScrapeMetrics(); m == nil {
		t.Fatal("expected non-nil metrics even with empty /proc")
	}
	if ev := s.ScrapeSyscalls(999999); len(ev) != 0 {
		t.Fatalf("expected no syscalls for missing pid, got %d", len(ev))
	}
}

func TestParseNetAddr(t *testing.T) {
	a := parseNetAddr("0100007F:0016")
	if a.host != "0x0100007F" || a.port != 22 {
		t.Fatalf("unexpected parse: %+v", a)
	}
}
