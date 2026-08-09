package com.aegis.cluster

/** Delivery boundary for generated briefings (4C.4).
  *
  * Production implementations push to Slack/PagerDuty/email with
  * severity-based routing; the default logs to stdout so the pipeline
  * runs with zero external dependencies.
  */
trait Notifier {
  def notify(briefing: Briefing): Unit
}

/** Default delivery: logs briefings to stdout. */
final class LogNotifier extends Notifier {
  override def notify(b: Briefing): Unit =
    System.out.println(
      s"[BriefingDelivery] ${b.severity} ${b.anomalyType} for ${b.agentId} (v${b.version}) @ ${Briefing.ts(b.timestampNs)}"
    )
}
