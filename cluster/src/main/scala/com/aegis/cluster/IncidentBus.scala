package com.aegis.cluster

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** In-process publish/subscribe bus for correlated multi-agent incidents (4D).
  *
  * Mirrors [[AnomalyEventBus]]; decouples the CorrelationEngine from
  * downstream incident consumers (aggregated briefing generation, alerting).
  */
object IncidentBus {

  private val subscribers = new ConcurrentLinkedQueue[Incident => Unit]()

  def publish(incident: Incident): Unit =
    subscribers.asScala.foreach { sub =>
      try sub(incident)
      catch case t: Throwable => System.err.println(s"IncidentBus subscriber error: ${t.getMessage}")
    }

  def subscribe(sub: Incident => Unit): AutoCloseable = {
    subscribers.add(sub)
    () => subscribers.remove(sub)
  }
}
