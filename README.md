# Aegis: Distributed Telemetry Engine 🛡️
### Solving the "Context Gap" in Distributed Observability

Aegis is a high-fidelity telemetry engine that moves beyond passive monitoring. It captures the "Why" behind system anomalies by maintaining a rolling 60-second window of deep system state at the edge, flushing it to a Scala-powered brain for real-time correlation and LLM-driven diagnostic briefings.

---

## 🧠 The Core Philosophy

### The Edge (Go) — *The Intelligent Sentinel*
Instead of a passive collector, the Aegis Go agent acts as a sentinel. It uses a **Ring-Buffer Strategy** to store the last 60 seconds of high-fidelity state (syscalls, network packets, stack traces) locally. Data is only flushed when a threshold is hit or when explicitly requested by the Brain.

### The Brain (Scala) — *The Global State Map*
Utilizing the **Actor Model**, the Scala cluster treats incoming telemetry as a continuous stream of events. It runs sliding-window analysis to correlate events across different agents (e.g., matching a latency spike on Agent A with a connection drop on Agent B).

### The Insight (RAG Layer) — *Diagnostic Briefings*
When an anomaly is detected, Aegis retrieves the 60-second buffer, indexes it into a vector store, and provides a **Diagnostic Briefing** via an LLM, bridging the gap between metrics and root causes.

---

## 🛠 Project Structure

- **`agent/`**: Go-based intelligent sentinels.
  - `cmd/agent/` — Agent entrypoint with signal handling and graceful shutdown.
  - `internal/scraper/` — CPU, memory, FD, TCP/TCP6 connection, and syscall scraping from `/proc`.
  - `internal/buffer/` — Thread-safe ring buffer with 60s time-window and byte-budget eviction.
  - `internal/transport/` — gRPC bidirectional stream client with backpressure handling (`SLOW_DOWN`, `RESUME`, `FLUSH_NOW`, `DROP_LOW_PRIORITY`).
  - `internal/persistence/` — Flat-file store for local zero-drop caching during brain outages.
  - `internal/config/` — Environment-driven agent configuration.
  - `pkg/telemetry/pb/` — Generated Protobuf/GRPC Go bindings.
- **`cluster/`**: Scala/Akka-based correlation brain.
  - `TelemetryServiceImpl` — gRPC server: bi-directional stream ingestion + chunked flush (~1MB chunks).
  - `SentinelState` — Per-agent 60s sliding buffer + 5s rate tracker (actor-per-sentinel pattern).
  - `AnomalyDetector` — Rolling z-score detection (WARNING ≥ 2.5σ, CRITICAL ≥ 4.0σ) with per-metric cooldown.
  - `CorrelationEngine` — Multi-agent incident synthesis from the anomaly event bus.
  - `FlushOrchestrator` — Persists triggering windows on anomaly and feeds the RAG pipeline.
  - `BriefingService` — Per-agent diagnostic briefing generation (translate → embed → retrieve → prompt → generate → persist → notify).
  - `IncidentBriefingService` — Aggregated cross-agent incident briefings (alert-storm reduction).
  - `HttpApi` — REST surface for retrieval, briefing listing, and incident listing.
  - `StateManager` — Global registry of connected sentinels.
  - `AnomalyEventBus` / `IncidentBus` — In-process pub/sub decoupling ingestion from downstream consumers.
  - `VectorStore` — In-memory cosine-similarity search (swap-ready for FAISS/Qdrant/pgvector).
  - `Embedder` — Pluggable embedding; default `HashEmbedder` (bag-of-words, L2-normalized, 256-dim).
  - `Llm` — Pluggable text generation; default `RuleBasedLlm` (zero-dependency deterministic briefings).
  - `Notifier` — Delivery boundary; default `LogNotifier` (stdout), swappable for Slack/PagerDuty/email.
  - `BufferStore` / `BriefingStore` / `IncidentStore` — Flat-file persistence layers for windows, briefings, and incidents.
- **`proto/`**: High-performance gRPC/Protobuf definitions.
  - `v1/telemetry.proto` — Bi-directional stream, oneof data envelope (Metric / Syscall / Network / Anomaly), chunked FlushBuffer, backpressure actions.
- **`docs/`**: Engineering proposals and architectural blueprints.

---

## 📡 Communication Contract: gRPC & Protobuf

Aegis uses **gRPC** with **Protocol Buffers (proto3)** for the Go-to-Scala boundary.

**Why Protobuf?**
1. **Binary Serialization**: Vital for high-volume streaming of syscall and packet data. Protobuf is significantly smaller and faster to parse than JSON.
2. **Strict Type Safety**: Ensures the Go agent and Scala cluster always agree on the data schema, preventing runtime crashes due to malformed logs.
3. **Code Generation**: Native support for both Go and Scala (via ScalaPB) ensures internal logic remains DRY.

**Service Surface:**
- `StreamTelemetry` — Bi-directional stream. Agents send `TelemetryRequest` (with `oneof` payload: `Metric`, `Syscall`, `Network`, `Anomaly`). Brain responds with `TelemetryResponse` containing backpressure actions (`ACK`, `SLOW_DOWN`, `RESUME`, `FLUSH_NOW`, `DROP_LOW_PRIORITY`).
- `FlushBuffer` — Server-streaming. Returns the requested agent's 60s window in ~1MB `FlushChunk`s, filterable by time range and event type.

---

## 🕹 Interactive Simulator

Visualize how Aegis handles massive scale and anomaly detection under load.

```bash
# Run the Aegis Simulator to test scaling and chaos scenarios
go run ./simulator/main.go --agents 1000 --chaos network-partition
```

### Simulation Scenarios:
- **Scaling:** Monitor how the Scala cluster handles backpressure from 10,000+ agents.
- **Zero-Drop:** Test local caching on Go agents during network partitions.
- **Anomaly Flush:** Trigger an alert and watch the high-fidelity buffer transmission.

---

## 📊 Architecture Deep Dive

### C4 Component Architecture

#### Level 1: System Context
The high-level interaction between users and the Aegis ecosystem.

```mermaid
graph TD
    User((Operator / SRE))
    CLI[Aegis CLI]
    Dash[Aegis Dashboard]
    AegisSystem[Aegis Telemetry Engine]

    User -->|Queries / Alerts| CLI
    User -->|Visualize / RAG Briefing| Dash
    CLI --> AegisSystem
    Dash --> AegisSystem
```

#### Level 2: Container (Edge vs. Core)
The interaction between the Go-based Sentinels and the Scala-based Brain.

```mermaid
graph LR
    subgraph "The Edge (Go)"
        Agent[Aegis Sentinel Agent]
    end

    subgraph "The Brain (Scala/Akka)"
        Cluster[Aegis Core Cluster]
    end

    subgraph "Storage & Intelligence"
        TSDB[(Time-Series DB)]
        Vector[(Vector Store / RAG)]
    end

    Agent -->|gRPC / Protobuf Stream| Cluster
    Cluster -->|Events / Metadata| TSDB
    Cluster -->|Anomaly Buffers| Vector
    Cluster -->|On-Demand Flush Request| Agent
```

#### Level 3: Component (Internal Logic)

**Go Sentinel (The Edge):**
- **Scraper:** Hooks into eBPF/Syscalls to capture system state.
- **Ring Buffer:** Stores 60s of raw telemetry in-memory with byte-budget eviction.
- **Local Analytics:** Threshold-based triggers for anomaly detection.
- **gRPC Client:** Managed streaming with circuit breaking and backpressure handling.
- **Local Persistence:** Flat-file spooling during brain outages for zero-drop guarantees.

**Scala Cluster (The Brain):**
- **Ingestion Actor:** Handles thousands of concurrent gRPC streams.
- **State Map Actor:** Maintains a global view of agent health.
- **Correlation Engine:** Sliding-window analysis across multiple streams.
- **Persistence Layer:** Asynchronous writes to TSDB/Persistence.

---

## ⚖️ License
MIT © 2026 Aegis Team

---

## 🎯 Project Roadmap
- [x] **Phase 1**: Distributed Cluster & Agent Topology Scaffold.
- [x] **Phase 2**: Core Telemetry Infrastructure & Protobufs.
- [x] **Phase 3**: Centralized API & Microservices Integration.
- [ ] **Phase 4**: Frontend Dashboard Implementation.

---

## 📖 Documentation
For the full engineering proposal, see [docs/architecture.md](docs/architecture.md).

- **System Philosophy**: Reactive vs. Passive tracing.
- **C4 Diagrams**: Multi-level component visualization.
- **Communication Contract**: gRPC/Protobuf rationale.
- **Scaling & Failure**: Backpressure and Zero-Drop strategies.
