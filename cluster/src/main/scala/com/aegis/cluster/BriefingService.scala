package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryRequest

/** Orchestrates diagnostic briefing generation (4C.2).
  *
  * For an anomaly: snapshots the triggering 60s window, translates it to
  * text, retrieves semantically similar past windows, assembles the
  * prompt, generates a briefing, persists it, and notifies delivery
  * channels. Invoked by the FlushOrchestrator after a window is persisted
  * and indexed, guaranteeing deterministic ordering.
  */
final class BriefingService(
    embedder: Embedder,
    vectorStore: VectorStore,
    llm: Llm,
    store: BriefingStore,
    notifier: Notifier
) {

  def generate(event: AnomalyEventBus.AnomalyEvent): Option[Briefing] = {
    val windowStart = event.timestampNs - 60_000_000_000L
    val entries: Seq[TelemetryRequest] = StateManager
      .get(event.agentId)
      .map(_.snapshot(windowStart, event.timestampNs, Seq.empty))
      .getOrElse(Seq.empty)

    if (entries.isEmpty) {
      System.out.println(
        s"[BriefingService] anomaly from ${event.agentId} (${event.eventType}) — no telemetry in window, skipping briefing"
      )
      return None
    }

    val windowText = entries.map(TelemetryTranslator.translate)
    val hits = vectorStore.search(
      embedder.embed(windowText.lastOption.getOrElse(event.description)),
      5,
      Some(windowStart)
    )
    val prompt = BriefingPrompts.build(event, windowText, hits)
    val briefing = Briefing(
      agentId = event.agentId,
      anomalyType = event.eventType,
      severity = event.severity,
      score = event.score,
      timestampNs = event.timestampNs,
      windowStartNs = windowStart,
      version = store.nextVersion(event.agentId, event.timestampNs),
      markdown = llm.brief(prompt)
    )

    val path = store.save(briefing)
    notifier.notify(briefing)
    System.out.println(s"[BriefingService] generated v${briefing.version} for ${event.agentId} -> $path")
    Some(briefing)
  }
}
