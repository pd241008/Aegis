package anomaly

import (
	"testing"
	"time"

	telemetryv1 "github.com/aegis/agent/pkg/telemetry/pb"
)

func metric(cpu, mem float64) *telemetryv1.MetricPayload {
	return &telemetryv1.MetricPayload{CpuUsagePercent: cpu, MemoryUsagePercent: mem}
}

func TestDetectorFiresAfterConsecutiveBreaches(t *testing.T) {
	d := NewDetector(WithCPUThreshold(80), WithMemThreshold(99), WithCooldown(time.Second))

	if ev := d.Evaluate(metric(79, 10)); ev != nil {
		t.Fatalf("first breach under threshold should not fire: %+v", ev)
	}
	if ev := d.Evaluate(metric(85, 10)); ev != nil {
		t.Fatalf("single breach should not fire before streak is reached: %+v", ev)
	}
	if ev := d.Evaluate(metric(85, 10)); ev != nil {
		t.Fatalf("second consecutive breach should not fire yet: %+v", ev)
	}
	if ev := d.Evaluate(metric(85, 10)); ev == nil {
		t.Fatal("third consecutive breach should fire")
	}
}

func TestDetectorCooldownSuppressesStorm(t *testing.T) {
	d := NewDetector(WithCPUThreshold(50), WithMemThreshold(99), WithMinConsecutive(1), WithCooldown(time.Minute))

	if ev := d.Evaluate(metric(60, 10)); ev == nil {
		t.Fatal("first breach should fire")
	}
	if ev := d.Evaluate(metric(60, 10)); ev != nil {
		t.Fatalf("second breach within cooldown should be suppressed: %+v", ev)
	}
}

func TestDetectorStreakResetsOnRecovery(t *testing.T) {
	d := NewDetector(WithCPUThreshold(80), WithMemThreshold(99), WithCooldown(time.Second))

	_ = d.Evaluate(metric(90, 10)) // streak 1
	_ = d.Evaluate(metric(10, 10)) // recovery, streak reset
	_ = d.Evaluate(metric(90, 10)) // streak 1 again
	if ev := d.Evaluate(metric(90, 10)); ev != nil {
		t.Fatalf("streak should have been reset by recovery, fired early: %+v", ev)
	}
}

func TestDetectorMemoryThreshold(t *testing.T) {
	d := NewDetector(WithCPUThreshold(99), WithMemThreshold(70), WithMinConsecutive(1), WithCooldown(time.Minute))

	ev := d.Evaluate(metric(10, 85))
	if ev == nil {
		t.Fatal("memory breach should fire")
	}
	if ev.EventType != "high-memory" {
		t.Fatalf("expected high-memory event, got %q", ev.EventType)
	}
	if ev.Severity != telemetryv1.AnomalyEvent_WARNING {
		t.Fatalf("expected WARNING severity, got %v", ev.Severity)
	}
}

func TestDetectorNilAndDefaults(t *testing.T) {
	d := NewDetector()
	if ev := d.Evaluate(nil); ev != nil {
		t.Fatal("nil metric should never fire")
	}
	// Defaults: cpu 95, mem 90, 3 consecutive — 50/50 should never fire.
	for i := 0; i < 10; i++ {
		if ev := d.Evaluate(metric(50, 50)); ev != nil {
			t.Fatalf("healthy sample should never fire: %+v", ev)
		}
	}
}
