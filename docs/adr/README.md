# 🗂️ Aegis Architecture Decision Records

> **Status:** Infrastructure set up — decisions pending documentation.
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
| ADR-003 | Twisted: Go sentinel + Scala/Akka brain split | Proposed | _to write_ |
| ADR-004 | Actor-per-sentinel ingestion with backpressure (`SLOW_DOWN`/`RESUME`) | Proposed | _to write_ |
| ADR-005 | Rolling z-score anomaly detection (2.5σ WARNING / 4.0σ CRITICAL) with cooldown | Proposed | _to write_ |
| ADR-006 | Sliding-window multi-agent correlation into incidents | Proposed | _to write_ |
| ADR-007 | Flat-file persistence (agent spool + brain stores) over DB | Proposed | _to write_ |
| ADR-008 | RAG briefing pipeline: hash-embed → in-memory vector store → rule-based LLM → notifier | Proposed | _to write_ |
| ADR-009 | Backend templated on Design-Dungeons `backend-template.md` | Proposed | _to write_ |
| ADR-010 | Zero-dep static dashboard served by the brain at :9091 | Decided | [ADR-010](./ADR-010-static-dashboard.md) |

> [!NOTE]
> Every row is a **decision that already exists in the code** (see `agent/`,
> `cluster/`, `proto/`). Rows marked `Proposed` are retroactive backfill — write
> them in dependency order (ADR-004 before ADR-005/006, ADR-007 before ADR-008).