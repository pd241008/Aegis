package com.aegis.cluster

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.collection.mutable

/** Correlates anomalies across sentinels into multi-agent incidents (4D.1).
  *
  * Subscribes to the anomaly event bus and groups anomalies of the same
  * signature (`eventType`) that land within a sliding correlation window.
  * When a window closes, an incident is raised if at least `minAgents`
  * distinct sentinels were affected — otherwise it is discarded as a
  * single-host problem.
  */
final class CorrelationEngine(minAgents: Int, windowNs: Long, tickMs: Long) extends AutoCloseable {

  def this() = this(minAgents = 2, windowNs = 10_000_000_000L, tickMs = 2000L)

  private final class OpenWindow(
      val eventType: String,
      var startNs: Long,
      var endNs: Long,
      var maxSeverity: String,
      val agents: mutable.Set[String]
  )

  private val open = mutable.Map[String, OpenWindow]()
  private val subscription: AutoCloseable = AnomalyEventBus.subscribe(onAnomaly)
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  scheduler.scheduleWithFixedDelay(
    new Runnable {
      override def run(): Unit = sweep()
    },
    tickMs,
    tickMs,
    TimeUnit.MILLISECONDS
  )

  private def onAnomaly(e: AnomalyEventBus.AnomalyEvent): Unit = synchronized {
    open.get(e.eventType) match {
      case Some(w) if e.timestampNs - w.endNs <= windowNs =>
        w.startNs = math.min(w.startNs, e.timestampNs)
        w.endNs = math.max(w.endNs, e.timestampNs)
        w.agents += e.agentId
        w.maxSeverity = higher(w.maxSeverity, e.severity)
      case _ =>
        sweep() // retire stale windows of this signature before opening a new one
        open(e.eventType) = new OpenWindow(
          e.eventType,
          e.timestampNs,
          e.timestampNs,
          e.severity,
          mutable.Set(e.agentId)
        )
    }
  }

  private def sweep(): Unit = synchronized {
    val now = System.currentTimeMillis() * 1_000_000L
    val stale = open.filter { case (_, w) => now - w.endNs > windowNs }.keys.toSeq
    stale.foreach(k => evaluate(open.remove(k).get))
  }

  private def evaluate(w: OpenWindow): Unit = {
    if (w.agents.size >= minAgents) {
      val incident = Incident(
        id = s"${w.eventType}@${w.startNs}",
        eventType = w.eventType,
        startNs = w.startNs,
        endNs = w.endNs,
        agents = w.agents.toSeq.sorted,
        maxSeverity = w.maxSeverity,
        createdAtNs = System.currentTimeMillis() * 1_000_000L
      )
      IncidentBus.publish(incident)
      System.out.println(
        s"[CorrelationEngine] incident ${incident.id}: ${incident.agents.size} agents affected (${incident.maxSeverity})"
      )
    }
  }

  private def higher(a: String, b: String): String =
    if (severityRank(a) >= severityRank(b)) a else b

  private def severityRank(s: String): Int = s match
    case "CRITICAL" => 3
    case "WARNING"  => 2
    case "INFO"     => 1
    case _          => 0

  override def close(): Unit = {
    subscription.close()
    scheduler.shutdownNow()
    sweep()
  }
}
