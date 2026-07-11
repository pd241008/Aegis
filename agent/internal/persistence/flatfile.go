package persistence

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/aegis/agent/internal/config"
	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
)

type FlatFileStore struct {
	cfg     *config.Config
	mu      sync.Mutex
	running bool
}

func NewFlatFileStore(cfg *config.Config) *FlatFileStore {
	return &FlatFileStore{cfg: cfg}
}

func (s *FlatFileStore) Start() error {
	if err := os.MkdirAll(s.cfg.PersistDir, 0755); err != nil {
		return fmt.Errorf("failed to create persist directory: %w", err)
	}
	s.running = true
	log.Printf("Flat file persistence started at %s", s.cfg.PersistDir)
	return nil
}

func (s *FlatFileStore) Stop() {
	s.running = false
	log.Println("Flat file persistence stopped")
}

func (s *FlatFileStore) Persist(reqs []*telemetryv1.TelemetryRequest) error {
	if !s.running {
		return nil
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if len(reqs) == 0 {
		return nil
	}

	filename := fmt.Sprintf("telemetry_%s_%d.json",
		time.Now().UTC().Format("20060102_150405"),
		time.Now().UnixNano())

	filepath := filepath.Join(s.cfg.PersistDir, filename)

	data, err := json.MarshalIndent(reqs, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal telemetry data: %w", err)
	}

	if err := os.WriteFile(filepath, data, 0644); err != nil {
		return fmt.Errorf("failed to write telemetry file: %w", err)
	}

	log.Printf("Persisted %d telemetry entries to %s", len(reqs), filepath)
	return nil
}

func (s *FlatFileStore) LoadAll() ([]*telemetryv1.TelemetryRequest, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	entries, err := os.ReadDir(s.cfg.PersistDir)
	if err != nil {
		return nil, fmt.Errorf("failed to read persist directory: %w", err)
	}

	var files []string
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".json" {
			files = append(files, entry.Name())
		}
	}
	sort.Strings(files)

	var allReqs []*telemetryv1.TelemetryRequest
	for _, filename := range files {
		filepath := filepath.Join(s.cfg.PersistDir, filename)
		data, err := os.ReadFile(filepath)
		if err != nil {
			log.Printf("Failed to read file %s: %v", filename, err)
			continue
		}

		var reqs []*telemetryv1.TelemetryRequest
		if err := json.Unmarshal(data, &reqs); err != nil {
			log.Printf("Failed to unmarshal file %s: %v", filename, err)
			continue
		}
		allReqs = append(allReqs, reqs...)
	}

	return allReqs, nil
}

func (s *FlatFileStore) Cleanup(maxAge time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	entries, err := os.ReadDir(s.cfg.PersistDir)
	if err != nil {
		return err
	}

	cutoff := time.Now().Add(-maxAge)
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			filepath := filepath.Join(s.cfg.PersistDir, entry.Name())
			if err := os.Remove(filepath); err != nil {
				log.Printf("Failed to remove old file %s: %v", filepath, err)
			} else {
				log.Printf("Cleaned up old persistence file: %s", entry.Name())
			}
		}
	}
	return nil
}
