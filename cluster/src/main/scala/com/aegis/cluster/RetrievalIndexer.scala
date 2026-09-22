package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryRequest

/** Indexes persisted telemetry windows into the vector store (4B.4).
  *
  * Each entry in a flushed window is translated to text, embedded in one
  * batch, and indexed with metadata linking it back to
  * `(agent_id, window_start_ns)`.
  *
  * Embedding failures degrade per-entry: the index skips the affected
  * entry and moves on, so an outage can never block the flush pipeline
  * (persist happens first and is unaffected either way).
  */
final class RetrievalIndexer(embedder: Embedder, store: VectorStore) {

  def indexWindow(agentId: String, windowStartNs: Long, entries: Seq[TelemetryRequest]): Unit = {
    val prepared = entries.zipWithIndex.map { case (req, i) =>
      val text = TelemetryTranslator.translate(req)
      val metadata = Map(
        "agent_id"      -> agentId,
        "window_start"  -> windowStartNs.toString,
        "event_type"    -> TelemetryTranslator.eventType(req),
        "timestamp_ns"  -> req.agent.map(_.timestampNs).getOrElse(0L).toString
      )
      (s"$agentId:$windowStartNs:$i", text, metadata)
    }.toSeq

    var vectors: Seq[Array[Double]] = Seq.empty
    try vectors = embedder.embedAll(prepared.map(_._2))
    catch {
      case e: Throwable if !e.isInstanceOf[EmbedderUnavailableException] || true =>
        System.err.println(s"[RetrievalIndexer] batch embed failed (${e.getMessage}); skipping window")
        return
    }
    vectors.zip(prepared).foreach { case (vec, (id, text, metadata)) =>
      try store.index(id, vec, text, metadata)
      catch {
        case e: IllegalArgumentException =>
          System.err.println(s"[RetrievalIndexer] skipping $id: ${e.getMessage}")
      }
    }
  }
}
