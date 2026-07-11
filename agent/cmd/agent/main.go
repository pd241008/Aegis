package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

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

	client := transport.NewClient(cfg, ringBuf)
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
		log.Printf("Warning: Could not connect to brain: %v. Running in local mode.", err)
	}

	s := scraper.New(cfg.ScrapeInt)
	ticker := time.NewTicker(cfg.ScrapeInt)
	defer ticker.Stop()

	incoming := make(chan *telemetryv1.TelemetryRequest, 1000)

	go func() {
		if err := client.StreamTelemetry(ctx, incoming); err != nil {
			log.Printf("Stream error: %v", err)
		}
	}()

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
			req := &telemetryv1.TelemetryRequest{
				Agent: &telemetryv1.AgentMetadata{
					AgentId:   cfg.AgentID,
					Hostname:  cfg.Hostname,
					Os:        "linux",
					TimestampNs: time.Now().UnixNano(),
				},
				Payload: &telemetryv1.TelemetryRequest_Metric{
					Metric: metrics,
				},
			}
			ringBuf.Append(req)

			select {
			case incoming <- req:
			default:
				log.Println("Incoming channel full, dropping metric")
			}

			events := s.ScrapeSyscalls(1)
			for _, ev := range events {
				syscallReq := &telemetryv1.TelemetryRequest{
					Agent: &telemetryv1.AgentMetadata{
						AgentId:   cfg.AgentID,
						Hostname:  cfg.Hostname,
						Os:        "linux",
						TimestampNs: time.Now().UnixNano(),
					},
					Payload: &telemetryv1.TelemetryRequest_Syscall{
						Syscall: ev,
					},
				}
				ringBuf.Append(syscallReq)
			}
		}
	}
}
