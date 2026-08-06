package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryRequest

import java.util.ArrayDeque
import scala.jdk.CollectionConverters.*

/** Sliding-window buffer and rate state for a single sentinel (agent).
  *
  * Each gRPC stream gets its own isolated `SentinelState` — the
  * "actor per sentinel" pattern, implemented as an immutable snapshot
  * source guarded by a monitor.
  */
final class SentinelState(val agentId: String) {
  val windowNs: Long = 60_000_000_000L // 60s sliding window
  val rateWindowNs: Long = 5_000_000_000L // 5s rate window

  private val buf = new ArrayDeque[(Long, TelemetryRequest)]()
  private val recvTimes = new ArrayDeque[Long]()

  @volatile var lastSeenNano: Long = 0L
  @volatile var anomalyCount: Long = 0L
  @volatile var throttled: Boolean = false

  /** Records an incoming request into the 60s buffer and updates rate state. */
  def record(req: TelemetryRequest): Unit = synchronized {
    val ts = req.agent.map(_.timestampNs).filter(_ > 0L).getOrElse(System.currentTimeMillis() * 1_000_000L)
    buf.addLast((ts, req))
    while (!buf.isEmpty && ts - buf.peekFirst()._1 > windowNs) buf.pollFirst()

    val now = System.nanoTime()
    recvTimes.addLast(now)
    while (!recvTimes.isEmpty && now - recvTimes.peekFirst() > rateWindowNs) recvTimes.pollFirst()
    lastSeenNano = now

    req.payload match
      case TelemetryRequest.Payload.Anomaly(_) => anomalyCount += 1
      case _                                   => ()
  }

  /** Messages per second over the trailing 5s window. */
  def ratePerSecond: Double = synchronized {
    val now = System.nanoTime()
    while (!recvTimes.isEmpty && now - recvTimes.peekFirst() > rateWindowNs) recvTimes.pollFirst()
    recvTimes.size / (rateWindowNs / 1e9)
  }

  /** Snapshot filtered by time range and payload type. */
  def snapshot(startNs: Long, endNs: Long, eventTypes: Seq[String]): Seq[TelemetryRequest] = synchronized {
    val types = eventTypes.toSet
    buf.asScala.toVector
      .filter { case (ts, req) =>
        val inRange = (startNs <= 0L || ts >= startNs) && (endNs <= 0L || ts <= endNs)
        val inTypes = types.isEmpty || types.contains(payloadType(req))
        inRange && inTypes
      }
      .map(_._2)
  }

  def size: Int = synchronized(buf.size())

  private def payloadType(req: TelemetryRequest): String = req.payload match
    case TelemetryRequest.Payload.Metric(_)   => "metric"
    case TelemetryRequest.Payload.Syscall(_)  => "syscall"
    case TelemetryRequest.Payload.Network(_)  => "network"
    case TelemetryRequest.Payload.Anomaly(_)  => "anomaly"
    case _                                    => "unknown"
}
