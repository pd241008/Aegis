# Phase 3: The Brain (Scala/Akka)

- [x] **Setup:** Setup Scala/Akka Cluster project.
    - ScalaPB + grpc-netty build verified (`sbt compile`), generated `TelemetryServiceGrpc` stubs.
- [x] **Actors:** Implement Ingestion & State Actors (Actor per Sentinel).
    - `SentinelState` per agent (60s sliding buffer + rate tracker) isolated per stream.
- [ ] **Correlation:** Implement Sliding-Window Correlation Logic.
- [x] **Backpressure:** Implement Backpressure Throttling (`Action.SLOW_DOWN`).
    - Rate-based: `SLOW_DOWN` over 100 msg/s, `RESUME` under 50 msg/s, with `throttle_interval_ms`.
- [x] **Flush Trigger:** Implement gRPC Flush Request Trigger.
    - `FlushBuffer` streams serialized ring-buffer contents in ~1MB `FlushChunk`s, filterable by time range and event type.
