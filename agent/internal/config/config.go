package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	AgentID    string
	Hostname   string
	BrainAddr  string
	BufferSec  time.Duration
	ScrapeInt  time.Duration
	PersistDir string
	MaxBufBytes int64
	// Anomaly detection (agent-side local analytics).
	CPUThreshold     float64
	MemThreshold     float64
	MinConsecutive   int
	AnomalyCooldown  time.Duration
}

func Load() *Config {
	cfg := &Config{
		AgentID:    getEnv("AEGIS_AGENT_ID", "sentinel-001"),
		Hostname:   getHostname(),
		BrainAddr:  getEnv("AEGIS_BRAIN_ADDR", "localhost:9090"),
		BufferSec:  getEnvDuration("AEGIS_BUFFER_SEC", 60*time.Second),
		ScrapeInt:  getEnvDuration("AEGIS_SCRAPE_INTERVAL", 100*time.Millisecond),
		PersistDir: getEnv("AEGIS_PERSIST_DIR", "/tmp/aegis-persist"),
		MaxBufBytes: getEnvInt64("AEGIS_MAX_BUF_BYTES", 256*1024*1024), // 256MB
		CPUThreshold:    getEnvFloat("AEGIS_CPU_ANOMALY_THRESHOLD", 95.0),
		MemThreshold:    getEnvFloat("AEGIS_MEM_ANOMALY_THRESHOLD", 90.0),
		MinConsecutive:  int(getEnvInt64("AEGIS_ANOMALY_MIN_CONSECUTIVE", 3)),
		AnomalyCooldown: getEnvDuration("AEGIS_ANOMALY_COOLDOWN", 30*time.Second),
	}
	return cfg
}

func getEnvDuration(key string, fallback time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return fallback
}

func getEnvInt64(key string, fallback int64) int64 {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return fallback
}

func getEnvFloat(key string, fallback float64) float64 {
	if v := os.Getenv(key); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
	}
	return fallback
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getHostname() string {
	h, err := os.Hostname()
	if err != nil {
		return "unknown"
	}
	return h
}
