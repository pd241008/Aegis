package com.aegis.cluster

/** A multi-agent incident synthesized from correlated anomalies (4D).
  *
  * Raised when the same anomaly signature is observed across several
  * sentinels within a short time window — a fleet-wide signal rather than
  * a single-host problem (e.g. a shared infrastructure dependency).
  */
final case class Incident(
    id: String,
    eventType: String,
    startNs: Long,
    endNs: Long,
    agents: Seq[String],
    maxSeverity: String,
    createdAtNs: Long
)
