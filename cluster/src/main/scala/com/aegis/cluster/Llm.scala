package com.aegis.cluster

/** Text-generation boundary for diagnostic briefings (4C.2).
  *
  * An API-backed implementation (OpenAI/Anthropic/Ollama) sends
  * `BriefingPrompt.text` to the model; the default rule-based one
  * synthesizes a deterministic briefing from the structured fields so the
  * pipeline runs with zero external dependencies.
  */
trait Llm {
  def brief(prompt: BriefingPrompt): String
}

/** Deterministic briefing generator (4C.2).
  *
  * Stands in for an LLM call during local/dev operation: derives a
  * root-cause hypothesis from the anomaly signature, lists the triggering
  * window as a timeline, surfaces retrieval hits as related context, and
  * emits severity-gated suggested actions. Swappable for an API model
  * without pipeline changes.
  */
final class RuleBasedLlm extends Llm {

  override def brief(p: BriefingPrompt): String = {
    val a = p.anomaly
    val sb = new StringBuilder
    sb.append("## Root-Cause Hypothesis\n\n")
    sb.append(hypothesis(a)).append("\n\n")
    sb.append("## Timeline\n\n")
    if (p.windowText.isEmpty) sb.append("No telemetry in the triggering window.\n")
    else p.windowText.zipWithIndex.foreach { case (line, i) => sb.append(f"${i + 1}%2d. $line\n") }
    sb.append("\n## Related Context\n\n")
    if (p.hits.isEmpty) sb.append("No prior similar telemetry found.\n")
    else p.hits.foreach(h => sb.append(f"- (similarity=${h.score}%.3f) ${h.text}\n"))
    sb.append("\n## Suggested Actions\n\n")
    actions(a.severity).foreach(x => sb.append(s"- $x\n"))
    sb.toString
  }

  private def hypothesis(a: AnomalyEventBus.AnomalyEvent): String = {
    val kind = a.eventType.toLowerCase
    if (kind.contains("cpu")) "Elevated CPU utilization consistent with a workload or process spike."
    else if (kind.contains("mem") || kind.contains("memory")) "Elevated memory pressure consistent with a leak or unbound allocation."
    else if (kind.contains("net") || kind.contains("conn")) "Connection growth consistent with a traffic surge or connection leak."
    else if (kind.contains("disk") || kind.contains("io")) "Blocked or saturated I/O consistent with storage contention."
    else s"The recorded `${a.eventType}` signature (score=${a.score}) on agent `${a.agentId}`."
  }

  private def actions(severity: String): Seq[String] = {
    val base = Seq("Verify the anomaly in the persisted window.", "Correlate with peer-agent telemetry.")
    severity match
      case "CRITICAL" => base ++ Seq("Escalate to on-call immediately.", "Consider pausing affected workloads.")
      case "WARNING"  => base ++ Seq("Page the service owner within 30 minutes.")
      case _          => base ++ Seq("Triage during the next operational window.")
  }
}
