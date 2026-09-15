# 📜 ADR-003: Go Sentinel + Scala Brain Language Split

> **Pattern:** polyglot boundary — picking the right runtime per side of one
> gRPC contract. Retroactive backfill: the decision predates this document
> (see commit `40126b2`, project scaffolding).

> **Status:** `Decided`
> **Date:** `May 2026` (code), `September 2026` (documented)

---

## 🌎 Context

Aegis has two very different workloads separated by the gRPC contract
([ADR-002](./ADR-002-grpc-protobuf-contract.md)):

1. **The Edge:** hundreds-to-thousands of sentinels per host, each scraping
   `/proc` at 10 Hz, holding a 60 s ring buffer in memory, spooling to disk
   during outages. Needs a tiny static binary, trivial container image,
   predictable GC pauses, and one-command deployment.
2. **The Brain:** a long-running ingestion/correlation service holding
   per-sentinel state, running sliding-window math, and hosting an HTTP API
   and dashboard. Needs expressive domain modelling, strong immutability
   defaults, and a mature ecosystem for building services quickly.

Both sides must share one strictly-typed message schema without hand-rolled
serialization.

## 🛤️ Options Considered

1. **All Go** — one language, but the correlation/briefing domain is
   state-heavy and interface-heavy; the brain would accumulate boilerplate.
2. **All Scala/JVM on the edge** — heavyweight runtime per sentinel, poor
   story for a small static binary, and `/proc` scraping gains nothing from
   the JVM.
3. **Go edge + Scala brain over gRPC/Protobuf** — each runtime where it is
   strongest; the shared schema is generated for both.

---

## 🎯 Decision

> [!IMPORTANT]
> **Go for the edge sentinels, Scala 3 for the cluster brain, joined by the
> generated Protobuf bindings from `proto/v1/telemetry.proto`.**

## 🧠 Reasoning

The edge workload is I/O-bound, deployment-sensitive, and value-fragile
(bytes of memory per buffered event matter for the byte-budget eviction);
Go's goroutines and static binaries are exactly that shape. The brain
workload is domain-model-heavy; Scala's case classes, sealed traits and
pattern matching over the `oneof` payloads keep the ingestion → detection →
correlation pipeline readable. The gRPC boundary makes the split cheap:
schema drift is a compile error on either side.

> [!NOTE]
> **One honest caveat:** the brain uses plain Scala concurrency
> (`synchronized`, `ConcurrentHashMap`), not the Akka toolkit the early
> docs advertised. The actor-*pattern* (one state object per sentinel) is
> there; the actor *framework* is not.

## ⚖️ Consequences

- **Good:** 🟢 ~15 MB static agent container; brain domain logic stays
  expressive; schema is generated, not maintained by hand.
- **Bad:** 🔴 Two toolchains to build and test in CI (Go + sbt); contributors
  need fluency in both ecosystems.

## 🔄 Revisit When

The agent needs in-process ML/analytics that outgrows Go's ecosystem, or the
brain's concurrency outgrows hand-rolled synchronization (the case for
introducing Akka/Pekko or fs2 for real).
