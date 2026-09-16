# 🗂️ Aegis Architecture Decision Records

> **Status:** Active — 11 decisions catalogued (10 documented, 1 pending).
> The canonical template and curated examples live in the
> [Design-Dungeons playbook](https://github.com/pd241008/Design-Dungeons)
> (`01-documentation/adrs/`).

---

## ✍️ How to Write an ADR

Aegis ADRs follow the format in the
[Design-Dungeons engineering playbook](https://github.com/pd241008/Design-Dungeons)
(canonical template + curated examples in `01-documentation/adrs/`). No local
template is kept — the playbook is the source of truth.

- One decision per ADR.
- Record: **Context** (constraints, facts, no bias) → **Options Considered** →
  **Decision** (one-sentence why) → **Consequences** (good / bad) →
  **Revisit When**.
- Name files `ADR-XXX-short-slug.md` and keep a one-line table entry here;
  this catalog is the routing point, the body is the single source of truth.
- Mark status `Draft` → `Proposed` → `Decided` → `Deprecated`.

---

## 📚 Catalog

| # | Pattern / Decision | Status | File |
| :- | :--- | :--- | :--- |
| ADR-001 | Edge ring-buffer "black box" (60s window, flush-on-anomaly) | Decided | [ADR-001](./ADR-001-ring-buffer-black-box.md) |
| ADR-002 | gRPC + Protobuf (proto3) communication contract | Decided | [ADR-002](./ADR-002-grpc-protobuf-contract.md) |
| ADR-003 | Go sentinel + Scala brain split over the gRPC contract | Decided | [ADR-003](./ADR-003-go-sentinel-scala-brain-split.md) |
| ADR-004 | Per-sentinel state with rate-based backpressure (`SLOW_DOWN`/`RESUME`) | Decided | [ADR-004](./ADR-004-per-sentinel-state-rate-backpressure.md) |
| ADR-005 | Rolling z-score anomaly detection (2.5σ WARNING / 4.0σ CRITICAL) with cooldown | Decided | [ADR-005](./ADR-005-rolling-z-score-anomaly-detection.md) |
| ADR-006 | Sliding-window multi-agent correlation into incidents | Decided | [ADR-006](./ADR-006-sliding-window-incident-correlation.md) |
| ADR-007 | Flat-file persistence (agent spool + brain stores) over DB | Decided | [ADR-007](./ADR-007-flat-file-persistence.md) |
| ADR-008 | RAG briefing pipeline: deterministic defaults behind pluggable interfaces | Decided | [ADR-008](./ADR-008-rag-briefing-deterministic-defaults.md) |
| ADR-009 | Agent-side local analytics: edge triggers + backpressure honesty | Decided | [ADR-009](./ADR-009-agent-local-analytics-edge-detection.md) |
| ADR-010 | Zero-dep static dashboard served by the brain at :9091 | Decided | [ADR-010](./ADR-010-static-dashboard.md) |
| ADR-011 | OpenAI-compatible API-backed briefings with deterministic fallback | Decided | [ADR-011](./ADR-011-openai-compatible-api-backed-briefings.md) |

> [!NOTE]
> ADR-003..009 were retroactively backfilled in September 2026: every row is
> a **decision that already exists in the code** (see `agent/`, `cluster/`,
> `proto/`). ADR-001's flush-on-anomaly trigger is now fed by the edge
> detector (ADR-009) as well as the brain's z-score detector (ADR-005).
> ADR-011 (September 2026) resolves the deferred upgrade from ADR-008:
> `RuleBasedLlm` is now the *fallback*, not the only briefing source.