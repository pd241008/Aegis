package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.{MetricPayload, TelemetryRequest}

import scala.collection.mutable

/** Sliding-window anomaly detection on the Brain side (Phase 3).
  *
  * Maintains a rolling baseline per metric for each sentinel and fires an
  * anomaly onto the event bus when a new sample deviates by more than a
  * z-score threshold. A per-metric cooldown prevents alert storms.
  * Detected anomalies flow through the same pipeline as agent-reported
  * ones (flush -> brief -> correlate), so the Brain no longer depends on
  * the sentinel's own local analytics.
  */
final class AnomalyDetector(
    windowSamples: Int = 20,
    minSamples: Int = 5,
    warnZ: Double = 2.5,
    critZ: Double = 4.0,
    cooldownNs: Long = 30_000_000_000L
) {

  private final class Series {
    val samples = mutable.ArrayBuffer[Double]()
    var lastEmitNs = 0L
  }

  private val series = mutable.Map[String, Series]()

  /** Returns a detected anomaly for this request, if any. */
  def detect(agentId: String, req: TelemetryRequest): Option[AnomalyEventBus.AnomalyEvent] = synchronized {
    val ts = req.agent.map(_.timestampNs).filter(_ > 0L).getOrElse(System.currentTimeMillis() * 1_000_000L)
    req.payload match
      case TelemetryRequest.Payload.Metric(m) =>
        metricSamples(m).flatMap { case (name, value) =>
          val z = zScore(name, value)
          if (z < warnZ) None
          else {
            val s = series(name)
            if (ts - s.lastEmitNs <= cooldownNs) None
            else {
              s.lastEmitNs = ts
              val zz = if (z.isInfinite) 99.0 else z
              Some(
                AnomalyEventBus.AnomalyEvent(
                  agentId = agentId,
                  eventType = s"${name}_spike",
                  description = f"$name%s deviated ${zz}%.1f sigma from sliding-window baseline",
                  severity = if (z >= critZ) "CRITICAL" else "WARNING",
                  score = zz,
                  timestampNs = ts
                )
              )
            }
          }
        }.headOption
      case _ => None
  }

  private def metricSamples(m: MetricPayload): Seq[(String, Double)] =
    Seq(
      "cpu"         -> m.cpuUsagePercent,
      "memory"      -> m.memoryUsagePercent,
      "connections" -> m.activeConnections.toDouble,
      "disk_read"   -> m.diskIoReadBytesSec,
      "disk_write"  -> m.diskIoWriteBytesSec
    ).filter(_._2 >= 0.0)

  private def zScore(name: String, value: Double): Double = {
    val s = series.getOrElseUpdate(name, new Series)
    if (s.samples.size < minSamples) {
      s.samples += value
      if (s.samples.size > windowSamples) s.samples.remove(0)
      0.0
    } else {
      val mean = s.samples.sum / s.samples.size
      val variance = s.samples.map(x => (x - mean) * (x - mean)).sum / s.samples.size
      val std = math.sqrt(variance)
      s.samples += value
      if (s.samples.size > windowSamples) s.samples.remove(0)
      if (std < 1e-9) { if (math.abs(value - mean) < 1e-9) 0.0 else Double.PositiveInfinity }
      else math.abs(value - mean) / std
    }
  }
}
