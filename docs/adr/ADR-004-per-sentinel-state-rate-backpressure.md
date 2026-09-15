# 📜 ADR-004: Per-Sentinel State with Rate-Based Backpressure

> **Pattern:** isolate per-connection state; signal the *sender* instead of
> dropping. Retroactive backfill: implemented in Phase 3 (commit `0fa29cf`).

> **Status:** `Decided`
> **Date:** `August 2026` (code), `September 2026` (documented)

---

## 🌎 Context

Every connected sentinel streams metrics at a configured interval (default
100 ms) plus syscall and network events. The brain must hold a 60-second
sliding window per agent for anomaly detection and on-demand flushes —
potentially for thousands of agents — without one fast agent degrading
ingestion for everyone. When the brain saturates, it must shed load *without*
violating the zero-drop promise ([ADR-001](./ADR-001-ring-buffer-black-box.md)):
the data stays at the edge until the brain can take it.

## 🛤️ Options Considered

1. **Global shared buffer** with per-record agent tags — one lock, simple,
   but contention and eviction are cross-agent hazards.
2. **Reject connections when saturated** — brutal, breaks streaming clients
   and reconnection storms make things worse.
3. **State per sentinel + rate-based throttling signals** back down the open
   stream — no new connections, no data dropped, senders self-pace.

---

## 🎯 Decision

> [!IMPORTANT]
> **One `SentinelState` per agent (60 s sliding buffer + message-rate
> tracker), and the brain emits `SLOW_DOWN` above 100 msg/s, `RESUME` below
> 50 msg/s, with a suggested `throttle_interval_ms`.**

## 🧠 Reasoning

Per-sentinel state gives lock isolation by construction (one agent's burst
never touches another's buffer) and makes `FlushBuffer` trivial — the
window *is* the state object. Hysteresis (slow down at 100, resume at 50)
prevents flapping at the threshold. Because the signal rides the already-open
bidirectional stream, applying it costs one protobuf message and the agent
paces itself without any reconnection churn. The agent side treats the
signal honestly: sends are paced at the suggested interval
([ADR-009](./ADR-009-agent-local-analytics-edge-detection.md) covers the
drop side).

## ⚖️ Consequences

- **Good:** 🟢 O(agents) isolation, zero-drop throttling, trivially testable
  (ingest rate above threshold ⇒ assert `SLOW_DOWN`).
- **Bad:** 🔴 State lives in heap per connection — very long-lived fleets
  need paging to disk; thresholds are static, not adaptive.

## 🔄 Revisit When

Aggregate ingress regularly saturates a single brain node (the case for a
clustered store), or traffic patterns make static 100/50 thresholds too
blunt (the case for AIMD-style adaptive rates, as used in
Design-Dungeons' OrbitLite closed-loop pattern).
