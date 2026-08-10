package com.aegis.cluster

/** Generates aggregated briefings for correlated incidents (4D.3).
  *
  * On an incident, gathers the triggering window evidence from every
  * affected agent, generates a single cross-agent briefing, persists it
  * alongside the incident metadata, and delivers it. Replaces per-agent
  * briefing storms with one aggregated briefing per incident.
  */
final class IncidentBriefingService(llm: Llm, store: IncidentStore, notifier: Notifier) extends AutoCloseable {

  private val subscription: AutoCloseable = IncidentBus.subscribe(handle)

  private def handle(inc: Incident): Unit = {
    val evidence = inc.agents.sorted.flatMap { agent =>
      StateManager
        .get(agent)
        .map(_.snapshot(inc.startNs, inc.endNs, Seq.empty))
        .getOrElse(Seq.empty)
        .map(e => s"[$agent] ${TelemetryTranslator.translate(e)}")
    }

    if (evidence.isEmpty) {
      System.out.println(s"[IncidentBriefingService] incident ${inc.id} — no evidence, skipping briefing")
      return
    }

    val markdown = header(inc) + llm.brief(IncidentPrompts.build(inc, evidence))
    store.save(inc)
    val path = store.saveBriefing(inc, markdown)
    notifier.notifyIncident(inc, path)
    System.out.println(s"[IncidentBriefingService] generated incident briefing ${inc.id} -> $path")
  }

  private def header(inc: Incident): String =
    s"""# Aegis Incident Briefing (${inc.eventType})
       |**Incident:** ${inc.id}
       |**Affected agents:** ${inc.agents.mkString(", ")}
       |**Severity:** ${inc.maxSeverity}
       |**Window:** ${Briefing.ts(inc.startNs)} → ${Briefing.ts(inc.endNs)}
       |
       |""".stripMargin

  override def close(): Unit = subscription.close()
}
