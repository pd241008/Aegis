package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/aegis/agent/internal/anomaly"
	"github.com/aegis/agent/internal/buffer"
	"github.com/aegis/agent/internal/config"
	"github.com/aegis/agent/internal/persistence"
	"github.com/aegis/agent/internal/scraper"
	"github.com/aegis/agent/internal/transport"
	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	log.Println("Aegis Edge Agent starting...")

	cfg := config.Load()
	log.Printf("Agent ID: %s, Brain: %s", cfg.AgentID, cfg.BrainAddr)

	ringBuf := buffer.New(cfg.BufferSec, cfg.MaxBufBytes)
	log.Printf("Ring buffer initialized: %v window, %d bytes max", cfg.BufferSec, cfg.MaxBufBytes)

	store := persistence.NewFlatFileStore(cfg)
	if err := store.Start(); err != nil {
		log.Fatalf("Failed to start persistence: %v", err)
	}
	defer store.Stop()

	// The spool doubles as the replay source: entries persisted while the
	// brain was unreachable are backfilled on every (re)established stream.
	client := transport.NewClientWithReplay(cfg, ringBuf, store)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		log.Println("Shutdown signal received")
		cancel()
	}()

	if err := client.Connect(ctx); err != nil {
		log.Printf("Warning: Could not connect to brain: %v. Running in local mode; reconnect loop will retry.", err)
	}

	s := scraper.New(cfg.ScrapeInt)
	detector := anomaly.NewDetector(
		anomaly.WithCPUThreshold(cfg.CPUThreshold),
		anomaly.WithMemThreshold(cfg.MemThreshold),
		anomaly.WithMinConsecutive(cfg.MinConsecutive),
		anomaly.WithCooldown(cfg.AnomalyCooldown),
	)
	ticker := time.NewTicker(cfg.ScrapeInt)
	defer ticker.Stop()

	incoming := make(chan *telemetryv1.TelemetryRequest, 1000)

	go client.Run(ctx, incoming)

	go func() {
		persistTicker := time.NewTicker(30 * time.Second)
		defer persistTicker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-persistTicker.C:
				snapshot := ringBuf.Snapshot()
				if len(snapshot) > 0 {
					if err := store.Persist(snapshot); err != nil {
						log.Printf("Persist error: %v", err)
					}
				}
				store.Cleanup(24 * time.Hour)
			}
		}
	}()

	log.Println("Agent running. Scraping telemetry...")
	for {
		select {
		case <-ctx.Done():
			log.Println("Agent shutting down...")
			snapshot := ringBuf.Snapshot()
			if len(snapshot) > 0 {
				if err := store.Persist(snapshot); err != nil {
					log.Printf("Final persist error: %v", err)
				}
			}
			time.Sleep(500 * time.Millisecond)
			client.Close()
			return
		case <-ticker.C:
			metrics := s.ScrapeMetrics()
			metricReq := &telemetryv1.TelemetryRequest{
				Agent:   newAgentMeta(cfg),
				Payload: &telemetryv1.TelemetryRequest_Metric{Metric: metrics},
			}
			ringBuf.Append(metricReq)
			trySend(incoming, metricReq)

			// Agent-side local analytics: threshold breaches emit anomaly
			// events that drive the brain's flush -> brief -> correlate
			// pipeline (and give the e2e smoke check a deterministic trigger).
			if ev := detector.Evaluate(metrics); ev != nil {
				log.Printf("Local anomaly detected: %s (%s)", ev.GetEventType(), ev.GetDescription())
				evReq := &telemetryv1.TelemetryRequest{
					Agent:   newAgentMeta(cfg),
					Payload: &telemetryv1.TelemetryRequest_Anomaly{Anomaly: ev},
				}
				ringBuf.Append(evReq)
				trySend(incoming, evReq)
			}

			for _, ev := range s.ScrapeSyscalls(1) {
				syscallReq := &telemetryv1.TelemetryRequest{
					Agent:   newAgentMeta(cfg),
					Payload: &telemetryv1.TelemetryRequest_Syscall{Syscall: ev},
				}
				ringBuf.Append(syscallReq)
				trySend(incoming, syscallReq)
			}

			for _, n := range s.ScrapeNetworkEvents() {
				netReq := &telemetryv1.TelemetryRequest{
					Agent:   newAgentMeta(cfg),
					Payload: &telemetryv1.TelemetryRequest_Network{Network: n},
				}
				ringBuf.Append(netReq)
				trySend(incoming, netReq)
			}
		}
	}
}

// newAgentMeta stamps a fresh AgentMetadata for one outgoing request.
func newAgentMeta(cfg *config.Config) *telemetryv1.AgentMetadata {
	return &telemetryv1.AgentMetadata{
		AgentId:     cfg.AgentID,
		Hostname:    cfg.Hostname,
		Os:          "linux",
		TimestampNs: time.Now().UnixNano(),
	}
}

// trySend forwards to the stream channel without ever blocking the scrape
// loop; when the channel is full the brain is applying backpressure and the
// in-memory ring buffer still holds the entry.
func trySend(incoming chan *telemetryv1.TelemetryRequest, req *telemetryv1.TelemetryRequest) {
	select {
	case incoming <- req:
	default:
		log.Println("Incoming channel full, dropping telemetry (retained in ring buffer)")
	}
}
