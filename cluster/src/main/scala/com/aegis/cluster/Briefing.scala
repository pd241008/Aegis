package com.aegis.cluster

import java.time.Instant

/** A generated diagnostic briefing, linked to a triggering anomaly.
  *
  * `version` supports regeneration: regenerating against the same anomaly
  * increments the version rather than overwriting prior briefings.
  */
final case class Briefing(
    agentId: String,
    anomalyType: String,
    severity: String,
    score: Double,
    timestampNs: Long,
    windowStartNs: Long,
    version: Int,
    markdown: String,
    createdAtNs: Long = System.currentTimeMillis() * 1000000L
) {
  def withHeader: String =
    s"""# Aegis Diagnostic Briefing (v$version)
       |**Agent:** `$agentId`
       |**Anomaly:** $anomalyType — $severity (score=$score)
       |**Triggered:** ${Briefing.ts(timestampNs)}
       |**Window:** ${Briefing.ts(windowStartNs)} → ${Briefing.ts(timestampNs)}
       |
       |$markdown
       |""".stripMargin
}

object Briefing {
  def ts(ns: Long): String = Instant.ofEpochMilli(ns / 1000000).toString
}
