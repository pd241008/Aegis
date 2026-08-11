package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.*
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver

/** gRPC server for the TelemetryService.
  *
  * - `StreamTelemetry`: bi-directional stream. Records each request into the
  *   per-sentinel sliding buffer and emits backpressure signals
  *   (`SLOW_DOWN` / `RESUME`) based on measured message rate.
  * - `FlushBuffer`: returns the requested agent's 60s window in ~1MB chunks.
  */
final class TelemetryServiceImpl extends TelemetryServiceGrpc.TelemetryService {

  private val slowDownThreshold = 100.0 // msgs/sec that trips SLOW_DOWN
  private val resumeThreshold = 50.0    // msgs/sec that lifts throttling
  private val throttleIntervalMs = 500  // suggested send interval when throttled
  private val chunkSizeBytes = 1_000_000

  override def streamTelemetry(
      responseObserver: StreamObserver[TelemetryResponse]
  ): StreamObserver[TelemetryRequest] =
    new StreamObserver[TelemetryRequest] {
      private var lastAction: TelemetryResponse.Action = TelemetryResponse.Action.ACK

      override def onNext(req: TelemetryRequest): Unit = {
        val agentId = req.agent.map(_.agentId).getOrElse("unknown")
        val state = StateManager.sentinelFor(agentId)
        state.record(req)

        publishAnomaly(agentId, req)
        state.detect(req).foreach(AnomalyEventBus.publish)

        val action =
          if (state.ratePerSecond > slowDownThreshold) {
            state.throttled = true
            TelemetryResponse.Action.SLOW_DOWN
          } else if (state.throttled && state.ratePerSecond < resumeThreshold) {
            state.throttled = false
            TelemetryResponse.Action.RESUME
          } else TelemetryResponse.Action.ACK

        if (action != lastAction) {
          responseObserver.onNext(
            TelemetryResponse(
              action = action,
              message = describe(action, state.ratePerSecond),
              throttleIntervalMs = throttleIntervalMs
            )
          )
          lastAction = action
        }
      }

      override def onError(t: Throwable): Unit =
        System.err.println(s"Stream error: ${t.getMessage}")

      override def onCompleted(): Unit = {
        responseObserver.onCompleted()
      }
    }

  override def flushBuffer(
      request: FlushRequest,
      responseObserver: StreamObserver[FlushChunk]
  ): Unit = {
    val entries = StateManager
      .get(request.agentId)
      .map(_.snapshot(request.startTimeNs, request.endTimeNs, request.eventTypes))
      .getOrElse(Seq.empty)

    val chunks = entries.map(_.toByteString)
    val totalSize = chunks.map(_.size().toLong).sum

    var offset = 0L
    var current = ByteString.EMPTY

    def emit(last: Boolean): Unit = {
      responseObserver.onNext(
        FlushChunk(data = current, totalSize = totalSize, offset = offset, isLast = last)
      )
      offset += current.size()
      current = ByteString.EMPTY
    }

    chunks.foreach { bs =>
      val candidate = current.concat(bs)
      if (candidate.size() >= chunkSizeBytes) {
        emit(last = false)
        current = bs
      } else current = candidate
    }
    emit(last = true)

    responseObserver.onCompleted()
  }

  private def describe(action: TelemetryResponse.Action, rate: Double): String = action match
    case TelemetryResponse.Action.SLOW_DOWN => s"Brain saturated (${rate.toInt} msg/s). Slow down."
    case TelemetryResponse.Action.RESUME    => s"Brain recovered (${rate.toInt} msg/s). Resume normal rate."
    case _                                  => ""

  /** Publishes anomaly payloads to the event bus for downstream consumers. */
  private def publishAnomaly(agentId: String, req: TelemetryRequest): Unit = req.payload match
    case TelemetryRequest.Payload.Anomaly(a) =>
      AnomalyEventBus.publish(
        AnomalyEventBus.AnomalyEvent(
          agentId = agentId,
          eventType = a.eventType,
          description = a.description,
          severity = a.severity.name,
          score = a.score,
          timestampNs = req.agent.map(_.timestampNs).filter(_ > 0L).getOrElse(System.currentTimeMillis() * 1_000_000L)
        )
      )
    case _ => ()
}
