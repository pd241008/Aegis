# 📜 ADR-007: Flat-File Persistence Over a Database

> **Pattern:** simplify-then-verify — files until a query engine is actually
> needed. Retroactive backfill: agent spool from Phase 2 (commit `a7e971c`),
> brain stores from Phase 4 (commits `5605f70`, `399fc86`, `323609c`).

> **Status:** `Decided`
> **Date:** `July–August 2026` (code), `September 2026` (documented)

---

## 🌎 Context

Four subsystems need durability: the agent's outage spool, the brain's
persisted 60 s windows, generated briefings, and correlated incidents.
Aegis is pre-production: the write pattern is append-heavy, the read pattern
is "list recent" or "replay everything", and operational simplicity of the
demo stack (docker compose, no external dependencies) is a design goal.
The architecture doc's C4 diagram shows a "TSDB" — that box is aspirational.

## 🛤️ Options Considered

1. **PostgreSQL/TSDB everywhere** — real querying, but every deployment
   (including the CI smoke check) drags a database along.
2. **BadgerDB / embedded KV on the agent, DB on the brain** — removes the
   CGo/external-dep problem on the edge but adds an embedded storage engine
   to maintain.
3. **Flat files with a strict naming scheme** (JSON arrays of protojson
   objects on the agent; one JSON window file per `(agent, window)` on the
   brain), cleaned by age.

---

## 🎯 Decision

> [!IMPORTANT]
> **All persistence is flat files. The agent spools protojson arrays to
> `<dir>/telemetry_<ts>.json`; the brain writes one
> `<agentId>_<startNs>_<endNs>.json` file per flushed window and one file
> per briefing/incident. Cleanup is age-based deletion.**

## 🧠 Reasoning

Every access pattern so far is sequential: append, list newest, replay all,
or re-read everything (startup reindex). Files make the stores
human-auditable (you can `cat` a briefing or a window), trivially volume-
mounted in compose, and zero-dependency on both runtimes. The protojson
format specifically was chosen after a round-trip bug (see
[postmortem](../postmortems/2026-09-14-protojson-oneof-round-trip.md)):
`encoding/json` silently flattens `oneof` payloads, protojson does not.
The startup reindex and replay paths treat files as the source of truth and
rebuild in-memory state from them, which keeps the file format the single
canonical representation.

> [!NOTE]
> **Premature abstraction is a tax.** A database would buy indexes we have
> no queries for, at the cost of a service we must run in every environment,
> including CI.

## ⚖️ Consequences

- **Good:** 🟢 Zero operational dependencies; auditable-by-eye artifacts;
  compose volumes just work; agent spool survives brain outages with no
  daemon.
- **Bad:** 🔴 No partial queries (a "all network events from agent X last
  hour" scan is O(files)); no compaction; per-file atomicity only; the
  custom `readWindow` parser must stay in lockstep with the writer's format
  (tested, but a real coupling).

## 🔄 Revisit When

Operators query persisted history interactively (introduce a TSDB for
metrics only), or spool replay volume makes linear scans a bottleneck
(introduce an index file or embedded KV).
