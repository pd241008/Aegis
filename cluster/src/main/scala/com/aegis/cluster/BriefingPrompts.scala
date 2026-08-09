package com.aegis.cluster

/** Structured input for briefing generation.
  *
  * `text` is the fully-assembled prompt meant for an API-backed LLM; the
  * structured fields let the local rule-based implementation produce a
  * deterministic briefing without a model call.
  */
final case class BriefingPrompt(
    anomaly: AnomalyEventBus.AnomalyEvent,
    windowText: Seq[String],
    hits: Seq[SearchHit],
    text: String
)

/** Prompt engineering for diagnostic briefings (4C.1).
  *
  * Injects the anomaly, the 60s high-fidelity telemetry window, and
  * related past incidents from semantic retrieval, then instructs the
  * model to produce a root-cause hypothesis and a timeline.
  */
object BriefingPrompts {

  def build(
      anomaly: AnomalyEventBus.AnomalyEvent,
      windowText: Seq[String],
      hits: Seq[SearchHit]
  ): BriefingPrompt = {
    val context =
      if (hits.isEmpty) "No prior similar telemetry found."
      else hits.map(h => f"- (similarity=${h.score}%.3f) ${h.text}").mkString("\n")

    val text =
      s"""You are an SRE diagnostic assistant for the Aegis telemetry platform.
         |Produce a concise diagnostic briefing from the anomaly and the telemetry
         |window that triggered it. Structure your answer with:
         |## Root-Cause Hypothesis
         |## Timeline
         |## Related Context
         |## Suggested Actions
         |
         |Anomaly:
         |- agent: ${anomaly.agentId}
         |- type: ${anomaly.eventType}
         |- severity: ${anomaly.severity}
         |- score: ${anomaly.score}
         |- description: ${anomaly.description}
         |
         |60s high-fidelity telemetry window (oldest first):
         |${windowText.mkString("\n")}
         |
         |Related past incidents from semantic search:
         |$context
         |""".stripMargin

    BriefingPrompt(anomaly, windowText, hits, text)
  }
}
