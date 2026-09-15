// Package anomaly implements lightweight agent-side local analytics.
//
// The Brain owns statistical detection (rolling z-score), but the edge
// sentinel emits threshold-based anomaly events so the flush → brief →
// correlate pipeline can be triggered at the source. This mirrors the
// "Local Analytics" component from the C4 Level 3 architecture view.
package anomaly

import (
	"fmt"
	"sync"
	"time"

	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
)

// Detector emits an AnomalyEvent when a gauge crosses its configured
// threshold for a number of consecutive samples. Per-metric cooldowns
// prevent event storms.
type Detector struct {
	mu sync.Mutex

	cpuThreshold    float64
	memThreshold    float64
	minConsecutive  int
	cooldown        time.Duration

	cpuStreak   int
	memStreak   int
	lastEmitted map[string]time.Time
}

// Option customises the Detector.
type Option func(*Detector)

// WithCPUThreshold sets the CPU percent that triggers an anomaly (default 95).
func WithCPUThreshold(pct float64) Option {
	return func(d *Detector) { d.cpuThreshold = pct }
}

// WithMemThreshold sets the memory percent that triggers an anomaly (default 90).
func WithMemThreshold(pct float64) Option {
	return func(d *Detector) { d.memThreshold = pct }
}

// WithMinConsecutive sets how many consecutive breaches are required before
// an anomaly is emitted (default 3). In smoke tests a value of 1 makes the
// trigger deterministic on the first breach.
func WithMinConsecutive(n int) Option {
	return func(d *Detector) { d.minConsecutive = n }
}

// WithCooldown sets the minimum interval between two anomalies of the same
// type (default 30s).
func WithCooldown(d time.Duration) Option {
	return func(det *Detector) { det.cooldown = d }
}

// NewDetector builds a Detector with sane defaults, applied over opts.
func NewDetector(opts ...Option) *Detector {
	d := &Detector{
		cpuThreshold:   95.0,
		memThreshold:   90.0,
		minConsecutive: 3,
		cooldown:       30 * time.Second,
		lastEmitted:    make(map[string]time.Time),
	}
	for _, opt := range opts {
		opt(d)
	}
	return d
}

// Evaluate inspects one metrics sample and returns an anomaly event when a
// gauge has breached its threshold for minConsecutive consecutive samples
// and the per-type cooldown has elapsed. nil means "no anomaly".
func (d *Detector) Evaluate(m *telemetryv1.MetricPayload) *telemetryv1.AnomalyEvent {
	if m == nil {
		return nil
	}

	d.mu.Lock()
	defer d.mu.Unlock()

	if ev := d.evalGauge("cpu", m.GetCpuUsagePercent(), d.cpuThreshold, d.cpuStreak, &d.cpuStreak,
		"high-cpu", fmt.Sprintf("cpu_usage_percent=%.1f crossed %.1f", m.GetCpuUsagePercent(), d.cpuThreshold)); ev != nil {
		return ev
	}
	return d.evalGauge("mem", m.GetMemoryUsagePercent(), d.memThreshold, d.memStreak, &d.memStreak,
		"high-memory", fmt.Sprintf("memory_usage_percent=%.1f crossed %.1f", m.GetMemoryUsagePercent(), d.memThreshold))
}

// evalGauge is the shared streak/cooldown logic for one gauge.
// streakIn is passed by value and streakOut updated via pointer because Go
// has no reference parameters for struct fields.
func (d *Detector) evalGauge(
	gauge string, value, threshold float64, streakIn int, streakOut *int,
	eventType, description string,
) *telemetryv1.AnomalyEvent {
	if value >= threshold {
		*streakOut = streakIn + 1
	} else {
		*streakOut = 0
		return nil
	}

	if *streakOut < d.minConsecutive {
		return nil
	}

	now := time.Now()
	if last, ok := d.lastEmitted[eventType]; ok && now.Sub(last) < d.cooldown {
		return nil
	}
	d.lastEmitted[eventType] = now

	return &telemetryv1.AnomalyEvent{
		EventType:   eventType,
		Description: description,
		Severity:    telemetryv1.AnomalyEvent_WARNING,
		Score:       value,
		Metadata: map[string]string{
			"gauge":     gauge,
			"threshold": fmt.Sprintf("%.1f", threshold),
			"streak":    fmt.Sprintf("%d", *streakOut),
		},
	}
}

// Reset clears streaks and cooldowns (used on stream re-establishment).
func (d *Detector) Reset() {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.cpuStreak = 0
	d.memStreak = 0
	d.lastEmitted = make(map[string]time.Time)
}
