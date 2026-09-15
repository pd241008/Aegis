# 📜 ADR-009: Agent-Side Local Analytics at the Edge

> **Pattern:** push cheap triggers to the edge; keep calibrated analysis in
> the core. Added alongside the zero-drop/reconnect work (September 2026);
> the wiring closes a gap the original build left open — `AnomalyEvent` and
> `NetworkPayload` existed in the proto but nothing emitted them.

> **Status:** `Decided`
> **Date:** `September 2026`

---

## 🌎 Context

The architecture always described a two-tier detection model: the C4 Level 3
view listed "Local Analytics: threshold-based triggers" on the Go sentinel,
and the proto reserved an `AnomalyEvent` payload for exactly this. In
practice the agent shipped without it: detection was brain-side only, the
edge anomaly payload sat unused, network events were scraped but never
streamed, and the whole flush → briefing → incident pipeline had no edge
trigger — it only ran when the brain's own z-score detector fired. Two
consequences: (a) the e2e smoke check could not make the pipeline fire
deterministically (see [postmortem](../postmortems/2026-09-14-e2e-retrieval-never-indexed.md)),
and (b) an edge outage invisible to the brain's metric baselines could never
request a flush of its own black box.

## 🛤️ Options Considered

1. **Brain-side detection only** — one calibrated detector ([ADR-005](./ADR-005-rolling-z-score-anomaly-detection.md)),
   but the edge stays mute about its own state and the pipeline is starved
   of deterministic triggers.
2. **Mirror the full z-score detector on every agent** — detection parity,
   but duplicated baselines and two sources of truth for "what is an
   anomaly".
3. **Crude edge triggers + calibrated brain detection:** the agent emits
   threshold-based `AnomalyEvent`s (env-tunable thresholds, consecutive-
   breach streak, per-type cooldown) and streams network telemetry; the
   brain remains the statistical authority.

---

## 🎯 Decision

> [!IMPORTANT]
> **Give the sentinel a lightweight `anomaly.Detector` (CPU/memory thresholds
> with a consecutive-breach streak and cooldowns) and wire network-event
> scraping into the stream. Edge anomalies flow through the same
> `TelemetryRequest.Payload.anomaly` path as brain-detected ones, and the
> agent honours brain backpressure (`SLOW_DOWN` paces sends,
> `DROP_LOW_PRIORITY` sheds syscall/network payloads, metrics are never
> shed).**

## 🧠 Reasoning

The edge is the right place for *triggers* — it has the freshest data, and a
trigger is cheap — while the brain is the right place for *judgment*
(baselines, severity, correlation). The consecutive-breach streak filters
single-sample spikes; the cooldown prevents event storms during a sustained
breach. Thresholds are env-configurable with production defaults (95% CPU /
90% mem / 3 breaches), and the compose stack exposes overrides so smoke
tests can make the pipeline fire deterministically — which is exactly how
the CI e2e check now exercises retrieval, briefings and incidents. The
`DROP_LOW_PRIORITY` handling completes the backpressure contract from
[ADR-004](./ADR-004-per-sentinel-state-rate-backpressure.md): the agent
finally *does* what the brain's signals ask.

## ⚖️ Consequences

- **Good:** 🟢 The full 4A→4D pipeline is triggerable from the edge and
  verifiable in CI; network telemetry actually streams; the black box can
  request its own flush.
- **Bad:** 🔴 Static edge thresholds are dumb by design — a busy-but-normal
  node above the line produces false positives that the brain must filter;
  one more moving part in the agent to configure.

## 🔄 Revisit When

False positives from fixed edge thresholds become noise (the case for
edge-side baselining), or agents need richer local context (per-process
triggers) to flush before the brain notices.
