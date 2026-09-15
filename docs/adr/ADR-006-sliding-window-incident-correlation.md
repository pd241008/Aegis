# 📜 ADR-006: Sliding-Window Correlation of Anomalies into Incidents

> **Pattern:** alert-storm reduction by temporal + population aggregation.
> Retroactive backfill: implemented in Phase 4D (commits `517c496`, `323609c`,
> `580ecc7`).

> **Status:** `Decided`
> **Date:** `August 2026` (code), `September 2026` (documented)

---

## 🌎 Context

Once multiple sentinels detect anomalies, the naive outcome is one briefing
per anomaly per agent — an alert storm that buries the operator under
duplicates of the same underlying event. Real multi-agent faults (a bad
deploy, a network partition, a shared dependency melting down) manifest as
*several agents alarming within a short time of each other*. The pipeline
needs a second stage that decides: many anomalies, one incident.

## 🛤️ Options Considered

1. **One incident per anomaly** — zero aggregation logic, but N agents with
   the same problem produce N pages.
2. **Temporal + population sliding window:** group anomalies that occur
   within a time window and require a minimum number of distinct agents
   before promoting them to an incident.
3. **Topology-aware correlation** (dependency graphs, service maps) —
   highest fidelity, but requires data Aegis does not collect yet.

---

## 🎯 Decision

> [!IMPORTANT]
> **The `CorrelationEngine` groups anomalies from ≥ `AEGIS_CORR_MIN_AGENTS`
> (default 2) distinct agents within `AEGIS_CORR_WINDOW_MS` (default
> 10000 ms) into a single `Incident`, published on the incident bus.**

## 🧠 Reasoning

With a two-parameter window the engine is honest about what it knows: same
fault + same time + multiple victims = one incident. The distinct-agent
requirement is the important half — a single agent misbehaving is a briefing,
not an incident, and this boundary keeps incident notifications rare and
meaningful. Both parameters are env-tunable so the smoke stack can form an
incident deterministically in CI. Topology-aware correlation remains the
natural next stage once agents report service tags.

## ⚖️ Consequences

- **Good:** 🟢 N-page storms collapse to one incident; trivially testable;
  parameters tunable per deployment without code changes.
- **Bad:** 🔴 Time-window correlation cannot distinguish "shared root cause"
  from "coincident faults" — operators inherit that ambiguity; single-agent
  incidents (one noisy victim) are invisible to this stage by design.

## 🔄 Revisit When

Agents report service/deployment metadata, enabling dependency-graph
correlation, or incident volume grows enough that the min-agents=2 floor
becomes noisy.
