# 📜 ADR-002: gRPC + Protobuf (proto3) Communication Contract

> **Status:** `Decided`
> **Date:** `July, 2026`

---

## 🌎 Context

The Go sentinel and the Scala brain are different languages on different
runtimes, connected by a high-volume bidirectional stream. The edge streams
syscall/packet/metric telemetry continuously (10k+ agents expected); the brain
must send back control signals (backpressure, flush triggers) over the same
connection, and on demand pull a ~1MB ring-buffer window. Any schema mismatch
between the two sides would cause a runtime crash or silent corruption.

## 🛤️ Options Considered

1. **REST/JSON over HTTP** - _Human-readable and easy to debug, but verbose on the wire, unbounded stream semantics, and no shared type contract between Go and Scala — drift risk at scale and under load._
2. **Custom TCP binary protocol** - _Fast, but we hand-write codecs + versioning for both languages; no codegen, high maintenance._
3. **gRPC + Protobuf (proto3)** - _Binary serialization, first-class bidirectional streaming, strict typed schema shared by both sides via codegen (protoc for Go, ScalaPB for Scala)._

## 🎯 Decision

> [!IMPORTANT]  
> We will use **gRPC with Protocol Buffers (proto3)** for the Go ↔ Scala
> boundary, because it gives binary efficiency, native streaming, and a single
> authoritative schema (`proto/v1/telemetry.proto`) that both sides codegen from.

## 🧠 Reasoning

The contract (`proto/v1/telemetry.proto`) defines two RPCs: `StreamTelemetry`
(bi-directional) and `FlushBuffer` (server-streaming). The `TelemetryRequest`
envelope uses a `oneof` payload (`Metric` / `Syscall` / `Network` / `Anomaly`)
so all event types share one stream without tag bloat. The brain replies with
`TelemetryResponse.action` (`ACK`, `SLOW_DOWN`, `RESUME`, `FLUSH_NOW`,
`DROP_LOW_PRIORITY`) — the backpressure surface for ADR-004. `FlushBuffer`
returns `FlushChunk`s (~1MB, `offset`/`is_last`) to bound memory on both ends.
proto3 keeps both sides DRY (protoc-gen-go, ScalaPB) so schema drift is a
compile error, not a runtime explosion.

## ⚖️ Consequences

- **Good:** 🟢 Binary efficiency for high-volume syscall/packet data; native bi-di streaming with backpressure; schema as a single source of truth; both bindings are generated (no hand-rolled codecs).
- **Bad:** 🔴 proto3's zero-value defaults lose field presence (e.g., can't distinguish unset metric from 0 without wrappers/edge metadata); gRPC adds a runtime dependency on both sides; `oneof` payloads require explicit dispatch in every consumer.

## 🔄 Revisit When

When the payload taxonomy grows enough that a single `oneof` becomes unwieldy
(split into per-domain RPCs/messages), when field presence semantics actually
matter for a metric, or when a non-gRPC consumer (browser dashboard) needs the
data — at which point a JSON gateway over the same proto can be added without
replacing the contract.