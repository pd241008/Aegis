package com.aegis.cluster

import java.time.Instant

/** Subscribes to the anomaly event bus and persists the triggering
  * agent's 60s high-fidelity window to the buffer store.
  *
  * Sub-phase 4A.2: when an anomaly is published, the orchestrator pulls
  * the target agent's ring buffer (held per-sentinel in the Brain) and
  * writes it as a reconstructable window for the RAG layer (Phase 4B+).
  * In a full deployment this step would issue an on-demand `FlushBuffer`
  * gRPC request to the agent and reassemble the chunk stream.
  */
final class FlushOrchestrator(store: BufferStore) extends AutoCloseable {

  private val subscription: AutoCloseable = AnomalyEventBus.subscribe(handle)

  private def handle(event: AnomalyEventBus.AnomalyEvent): Unit = {
    val windowStart = event.timestampNs - 60_000_000_000L // 60s window
    val entries = StateManager
      .get(event.agentId)
      .map(_.snapshot(windowStart, event.timestampNs, Seq.empty))
      .getOrElse(Seq.empty)

    if (entries.isEmpty) {
      System.out.println(
        s"[FlushOrchestrator] anomaly from ${event.agentId} (${event.eventType}) at ${Instant.ofEpochMilli(event.timestampNs / 1000000)} — no telemetry in window, skipping persist"
      )
      return
    }

    val path = store.save(event.agentId, windowStart, event.timestampNs, entries)
    System.out.println(
      s"[FlushOrchestrator] persisted ${entries.size} entries for ${event.agentId} window @ ${Instant.ofEpochMilli(event.timestampNs / 1000000)} -> $path"
    )
  }

  override def close(): Unit = subscription.close()
}
