package config

import (
	"os"
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
}

func Load() *Config {
	cfg := &Config{
		AgentID:    getEnv("AEGIS_AGENT_ID", "sentinel-001"),
		Hostname:   getHostname(),
		BrainAddr:  getEnv("AEGIS_BRAIN_ADDR", "localhost:9090"),
		BufferSec:  60 * time.Second,
		ScrapeInt:  100 * time.Millisecond,
		PersistDir: getEnv("AEGIS_PERSIST_DIR", "/tmp/aegis-persist"),
		MaxBufBytes: 256 * 1024 * 1024, // 256MB
	}
	return cfg
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
