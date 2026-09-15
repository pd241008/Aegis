package scraper

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
)

type Scraper struct {
	scrapeInterval time.Duration
	procPath       string
	lastBusy       float64
	lastTotal      float64
	haveLast       bool
}

func New(interval time.Duration) *Scraper {
	return &Scraper{
		scrapeInterval: interval,
		procPath:       "/proc",
	}
}

func (s *Scraper) ScrapeMetrics() *telemetryv1.MetricPayload {
	return &telemetryv1.MetricPayload{
		CpuUsagePercent:    s.readCPUUsage(),
		MemoryUsagePercent: s.readMemoryUsage(),
		MemoryUsedBytes:    s.readMemoryUsed(),
		MemoryTotalBytes:   s.readMemoryTotal(),
		ActiveConnections:  s.readActiveConnections(),
		OpenFileDescriptors: s.readOpenFDs(),
	}
}

func (s *Scraper) ScrapeSyscalls(pid int64) []*telemetryv1.SyscallPayload {
	events := make([]*telemetryv1.SyscallPayload, 0)

	traceDir := filepath.Join(s.procPath, strconv.FormatInt(pid, 10), "syscall")
	data, err := os.ReadFile(traceDir)
	if err != nil {
		return events
	}

	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	for _, line := range lines {
		parts := strings.Fields(line)
		if len(parts) < 3 {
			continue
		}
		events = append(events, &telemetryv1.SyscallPayload{
			SyscallName: parts[0],
			Pid:         pid,
			ReturnCode:  parseInt32(parts[len(parts)-1]),
		})
	}
	return events
}

// readCPUUsage returns CPU busy percent as a delta over the scrape
// interval. /proc/stat counters are cumulative since boot, so the ratio
// must be computed between consecutive samples — otherwise the z-score
// detector on the Brain sees a near-constant signal.
func (s *Scraper) readCPUUsage() float64 {
	statPath := filepath.Join(s.procPath, "stat")
	f, err := os.Open(statPath)
	if err != nil {
		return 0
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	if !scanner.Scan() {
		return 0
	}
	fields := strings.Fields(scanner.Text())
	if len(fields) < 5 {
		return 0
	}
	user, _ := strconv.ParseFloat(fields[1], 64)
	nice, _ := strconv.ParseFloat(fields[2], 64)
	system, _ := strconv.ParseFloat(fields[3], 64)
	idle, _ := strconv.ParseFloat(fields[4], 64)
	busy := user + nice + system
	total := busy + idle

	if !s.haveLast || total <= s.lastTotal {
		// First sample (or counters reset): seed the delta baseline and
		// report the lifetime ratio once so the gauge is never negative.
		s.lastBusy, s.lastTotal, s.haveLast = busy, total, true
		if total > 0 {
			return (busy / total) * 100
		}
		return 0
	}
	dBusy, dTotal := busy-s.lastBusy, total-s.lastTotal
	s.lastBusy, s.lastTotal = busy, total
	if dTotal <= 0 {
		return 0
	}
	return (dBusy / dTotal) * 100
}

func (s *Scraper) readMemoryUsage() float64 {
	total := s.readMemoryTotal()
	used := s.readMemoryUsed()
	if total > 0 {
		return (float64(used) / float64(total)) * 100
	}
	return 0
}

func (s *Scraper) readMemoryUsed() int64 {
	info := s.readMemInfo()
	total := info["MemTotal"]
	free := info["MemFree"]
	buffers := info["Buffers"]
	cached := info["Cached"]
	return (total - free - buffers - cached) * 1024
}

func (s *Scraper) readMemoryTotal() int64 {
	info := s.readMemInfo()
	return info["MemTotal"] * 1024
}

func (s *Scraper) readMemInfo() map[string]int64 {
	result := make(map[string]int64)
	memInfoPath := filepath.Join(s.procPath, "meminfo")
	f, err := os.Open(memInfoPath)
	if err != nil {
		return result
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.TrimSpace(parts[0])
		valStr := strings.TrimSpace(parts[1])
		valStr = strings.TrimSuffix(valStr, " kB")
		val, _ := strconv.ParseInt(strings.TrimSpace(valStr), 10, 64)
		result[key] = val
	}
	return result
}

func (s *Scraper) readActiveConnections() int32 {
	tcpPath := filepath.Join(s.procPath, "net", "tcp")
	data, err := os.ReadFile(tcpPath)
	if err != nil {
		return 0
	}
	lines := strings.Split(string(data), "\n")
	count := int32(0)
	for _, line := range lines[1:] {
		if strings.TrimSpace(line) != "" {
			count++
		}
	}
	tcp6Path := filepath.Join(s.procPath, "net", "tcp6")
	data6, err := os.ReadFile(tcp6Path)
	if err != nil {
		return count
	}
	lines6 := strings.Split(string(data6), "\n")
	for _, line := range lines6[1:] {
		if strings.TrimSpace(line) != "" {
			count++
		}
	}
	return count
}

func (s *Scraper) readOpenFDs() int32 {
	procEntries, err := os.ReadDir(s.procPath)
	if err != nil {
		return 0
	}
	count := int32(0)
	for _, entry := range procEntries {
		if entry.IsDir() {
			if _, err := strconv.Atoi(entry.Name()); err == nil {
				fdPath := filepath.Join(s.procPath, entry.Name(), "fd")
				fds, err := os.ReadDir(fdPath)
				if err == nil {
					count += int32(len(fds))
				}
			}
		}
	}
	return count
}

func parseInt32(s string) int32 {
	v, _ := strconv.ParseInt(s, 10, 32)
	return int32(v)
}

func (s *Scraper) ScrapeProcesses() []int64 {
	procEntries, err := os.ReadDir(s.procPath)
	if err != nil {
		return nil
	}
	var pids []int64
	for _, entry := range procEntries {
		if entry.IsDir() {
			if pid, err := strconv.ParseInt(entry.Name(), 10, 64); err == nil {
				pids = append(pids, pid)
			}
		}
	}
	return pids
}

func (s *Scraper) ScrapeNetworkEvents() []*telemetryv1.NetworkPayload {
	events := make([]*telemetryv1.NetworkPayload, 0)
	for _, proto := range []string{"tcp", "tcp6"} {
		netPath := filepath.Join(s.procPath, "net", proto)
		data, err := os.ReadFile(netPath)
		if err != nil {
			continue
		}
		lines := strings.Split(string(data), "\n")
		for _, line := range lines[1:] {
			fields := strings.Fields(line)
			if len(fields) < 10 {
				continue
			}
			localAddr := parseNetAddr(fields[1])
			remoteAddr := parseNetAddr(fields[2])
			events = append(events, &telemetryv1.NetworkPayload{
				LocalAddress:  localAddr.host,
				LocalPort:     localAddr.port,
				RemoteAddress: remoteAddr.host,
				RemotePort:    remoteAddr.port,
				Protocol:      proto,
				Direction:     telemetryv1.NetworkPayload_OUTBOUND,
			})
		}
	}
	return events
}

type netAddr struct {
	host string
	port int32
}

func parseNetAddr(hex string) netAddr {
	parts := strings.SplitN(hex, ":", 2)
	if len(parts) != 2 {
		return netAddr{}
	}
	port, _ := strconv.ParseInt(parts[1], 16, 32)
	return netAddr{
		host: fmt.Sprintf("0x%s", parts[0]),
		port: int32(port),
	}
}
