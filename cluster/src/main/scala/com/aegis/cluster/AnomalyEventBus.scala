package com.aegis.cluster

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** In-process publish/subscribe bus for anomaly events detected by the Brain.
  *
  * Sub-phase 4A.1: the anomaly event bus decouples the ingestion path
  * (`StreamTelemetry`) from downstream consumers such as the Flush
  * Orchestrator. A production deployment would back this with a message
  * broker (Kafka/NATS); an in-memory bus keeps the foundation portable.
  */
object AnomalyEventBus {

  final case class AnomalyEvent(
      agentId: String,
      eventType: String,
      description: String,
      severity: String,
      score: Double,
      timestampNs: Long
  )

  private val subscribers = new ConcurrentLinkedQueue[AnomalyEvent => Unit]()

  /** Publishes an anomaly to all current subscribers. */
  def publish(event: AnomalyEvent): Unit =
    subscribers.asScala.foreach { sub =>
      try sub(event)
      catch case t: Throwable => System.err.println(s"AnomalyEventBus subscriber error: ${t.getMessage}")
    }

  /** Registers a subscriber; returns an AutoCloseable to unsubscribe. */
  def subscribe(sub: AnomalyEvent => Unit): AutoCloseable = {
    subscribers.add(sub)
    () => subscribers.remove(sub)
  }
}
