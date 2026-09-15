# 📜 ADR-005: Rolling Z-Score Anomaly Detection with Per-Metric Cooldown

> **Pattern:** cheap streaming statistics before heavyweight models.
> Retroactive backfill: implemented in Phase 3 (commit `4fb7b00`).

> **Status:** `Decided`
> **Date:** `August 2026` (code), `September 2026` (documented)

---

## 🌎 Context

The brain needs to decide, per incoming metric sample, whether the agent is
behaving anomalously — that decision drives the flush → briefing → incident
pipeline. Constraints: the detector runs on every message from every agent
(10 Hz each), must adapt to each agent's baseline (a busy node and an idle
node have very different "normal"), and must not spam the pipeline with
duplicate events while a real anomaly persists.

## 🛤️ Options Considered

1. **Fixed thresholds** (CPU > 90%) — simple, but "normal" differs per agent
   and per hour; false positives flood the pipeline.
2. **Rolling z-score** over a per-agent, per-metric sliding window with
   severity bands and cooldowns.
3. **Seasonal decomposition / online ML models** — detection quality
   potential, but dependency weight and warm-up complexity unjustifiable
   while the pipeline itself is still being validated.

---

## 🎯 Decision

> [!IMPORTANT]
> **Rolling mean/σ baseline per `(agent, metric)`; WARNING at ≥ 2.5σ,
> CRITICAL at ≥ 4.0σ, with a per-metric cooldown after each emitted event.**

## 🧠 Reasoning

The z-score is computable in O(window) per sample with primitive arithmetic,
adapts per agent automatically, and its two-band output maps directly onto
the proto's `Severity` enum. The cooldown converts a sustained anomaly into
*one* pipeline trigger instead of hundreds — which matters because each
trigger persists a 60 s window, generates a briefing, and attempts
correlation. Agent-side thresholds
([ADR-009](./ADR-009-agent-local-analytics-edge-detection.md)) are a
separate, cruder net; this detector is the brain's calibrated one.

> [!NOTE]
> **Known input-quality dependency:** the detector is only as good as the
> gauge feeding it. When the agent's CPU metric was computed from cumulative
> `/proc/stat` counters (lifetime ratio), the signal was near-constant and
> σ collapsed; the agent now reports interval deltas so the detector sees a
> real distribution.

## ⚖️ Consequences

- **Good:** 🟢 Zero-dependency, adaptive, deterministic in tests; severity
  bands flow through briefings and incidents unchanged.
- **Bad:** 🔴 Baseline warm-up means the first samples can't trigger;
  sustained regime shifts (a workload change, not a fault) eventually
  re-baseline and *stop* firing — cooldowns and correlation mask this, but
  it is a real blind spot vs. fixed thresholds.

## 🔄 Revisit When

Detection quality becomes a measured bottleneck (missed incidents in
postmortems), or per-agent volume is high enough to justify an online model
with proper warm-up handling.
