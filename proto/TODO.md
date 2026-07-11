# Phase 1: Foundation (Proto)

- [x] **Service Contract:** Finalize gRPC Service Contract (`proto/v1/telemetry.proto`).
    - Define Bi-directional Stream for continuous metrics and backpressure.
    - Define `oneof` Data Envelope to handle Metrics, Syscalls, Network, and Anomaly events.
    - Define Flush Method for on-demand high-fidelity data retrieval.
    - Added AgentMetadata, chunked FlushChunk response, backpressure RESUME/DROP_LOW_PRIORITY actions.
