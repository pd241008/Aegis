package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryRequest

/** Converts raw binary telemetry into human-readable sentences (4B.1).
  *
  * Bridges the gap between syscall/network/metric payloads and text that
  * can be embedded and indexed for similarity retrieval.
  */
object TelemetryTranslator {

  def translate(req: TelemetryRequest): String = {
    val who = req.agent.map(a => s"agent ${a.agentId}").getOrElse("unknown agent")
    req.payload match
      case TelemetryRequest.Payload.Metric(m) =>
        s"$who reported cpu=${m.cpuUsagePercent}% memory=${m.memoryUsagePercent}% connections=${m.activeConnections} open-fds=${m.openFileDescriptors}"
      case TelemetryRequest.Payload.Syscall(s) =>
        s"$who pid ${s.pid} called ${s.syscallName} duration=${s.durationNs}ns return-code=${s.returnCode}"
      case TelemetryRequest.Payload.Network(n) =>
        s"$who ${n.direction.name.toLowerCase} ${n.protocol} connection ${n.localAddress}:${n.localPort} to ${n.remoteAddress}:${n.remotePort} bytes=${n.bytesTransferred}"
      case TelemetryRequest.Payload.Anomaly(a) =>
        s"$who anomaly ${a.eventType} severity=${a.severity.name} score=${a.score} description=${a.description}"
      case _ => s"$who reported unknown telemetry"
  }

  def eventType(req: TelemetryRequest): String = req.payload match
    case TelemetryRequest.Payload.Metric(_)  => "metric"
    case TelemetryRequest.Payload.Syscall(_) => "syscall"
    case TelemetryRequest.Payload.Network(_) => "network"
    case TelemetryRequest.Payload.Anomaly(_) => "anomaly"
    case _                                   => "unknown"
}
