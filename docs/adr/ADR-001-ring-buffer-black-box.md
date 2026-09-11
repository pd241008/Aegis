# 📜 ADR-001: Edge Ring-Buffer "Black Box" (60s Window, Flush-On-Anomaly)

> **Status:** `Decided`
> **Date:** `July, 2026`

---

## 🌎 Context

Passive observability stacks sample metrics every 15–60s and discard the raw
high-fidelity data (syscalls, packets, stack traces) to save bandwidth and
storage. When a service fails, an SRE has to dig through logs post-mortem to
answer *why* — the "context gap." Aegis wanted a reactive model: keep the last
60 seconds of deep state at the edge and only transmit it when an anomaly fires
or an operator asks.

Constraints: edge agents run on cheap hosts with bounded memory; the brain may
be unreachable (partition/outage) and we cannot drop the anomaly-triggering
window.

## 🛤️ Options Considered

1. **Sample-and-discard (Prometheus-style)** - _Cheap, but keeps the context gap; no raw state survives for diagnosis._
2. **Stream everything continuously to the brain** - _Full context always available, but bandwidth/storage scales with traffic, not anomalies; 10k+ agents × syscall volume is uneconomical._
3. **Bounded in-memory ring buffer at the edge, flush on demand** - _Memory is bounded by window + byte budget; transmission cost is proportional to anomalies. Adds edge complexity._
4. **Local WAL/DB at the edge (BadgerDB / flat files) for everything** - _Zero loss even across long outages, but writes everything; heavier, disk-bound, overkill for the 60s window._

## 🎯 Decision

> [!IMPORTANT]  
> We will keep the last 60 seconds of telemetry in a **bounded in-memory ring
> buffer at the edge** and flush it only on anomaly/request, because it bounds
> memory and makes the expensive part (transmission + storage) proportional to
> *anomalies*, not *traffic*.

## 🧠 Reasoning

The ring buffer (`agent/internal/buffer/ringbuffer.go`) is O(1) per event with
two eviction guards: a 60s time window and a byte budget (`maxBytes`), with a
10k capacity headroom so the buffer can never balloon. The edge cost is a small
fixed memory footprint per sentinel. Transmission stays cheap because the
buffer is only shipped via `FlushBuffer` when the brain detects an anomaly or
an operator requests it. Flat-file persistence (ADR-007) is layered underneath
only for the flush/outage path, keeping disk writes proportional to anomalies
rather than every event.

## ⚖️ Consequences

- **Good:** 🟢 Anomaly windows always carry full pre-event context; bandwidth/storage scale with anomalies; memory is strictly bounded; the buffer survives brain outages via spooling.
- **Bad:** 🔴 Raw high-fidelity state *not* in the window is unrecoverable after 60s (no full-history retention at the edge); edge agents carry more state than a passive collector.

## 🔄 Revisit When

When fleet-wide anomaly *rates* climb high enough that flush volume rivals
continuous streaming (~the "transmit everything" threshold), or when a use case
requires >60s of edge context — revisit window size, or add tiered disk
retention on the edge.