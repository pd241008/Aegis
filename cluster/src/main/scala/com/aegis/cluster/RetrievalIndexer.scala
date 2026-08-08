package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryRequest

/** Indexes persisted telemetry windows into the vector store (4B.4).
  *
  * Each entry in a flushed window is translated to text, embedded, and
  * indexed with metadata linking it back to `(agent_id, window_start_ns)`.
  */
final class RetrievalIndexer(embedder: Embedder, store: VectorStore) {

  def indexWindow(agentId: String, windowStartNs: Long, entries: Seq[TelemetryRequest]): Unit = {
    entries.zipWithIndex.foreach { case (req, i) =>
      val text = TelemetryTranslator.translate(req)
      val metadata = Map(
        "agent_id"      -> agentId,
        "window_start"  -> windowStartNs.toString,
        "event_type"    -> TelemetryTranslator.eventType(req),
        "timestamp_ns"  -> req.agent.map(_.timestampNs).getOrElse(0L).toString
      )
      store.index(s"$agentId:$windowStartNs:$i", embedder.embed(text), text, metadata)
    }
  }
}
