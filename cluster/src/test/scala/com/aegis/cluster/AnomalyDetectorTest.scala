package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.{AgentMetadata, MetricPayload, TelemetryRequest}

class AnomalyDetectorTest extends munit.FunSuite {

  private def metricReq(cpu: Double, mem: Double, conns: Int, tsNs: Long): TelemetryRequest =
    TelemetryRequest(
      agent = Some(AgentMetadata(agentId = "sentinel-a", timestampNs = tsNs)),
      payload = TelemetryRequest.Payload.Metric(
        MetricPayload(
          cpuUsagePercent = cpu,
          memoryUsagePercent = mem,
          activeConnections = conns,
          diskIoReadBytesSec = 0.0,
          diskIoWriteBytesSec = 0.0
        )
      )
    )

  private val start = 1_700_000_000_000_000_000L
  private val sec = 1_000_000_000L

  test("no anomaly on a stable baseline") {
    val d = new AnomalyDetector(windowSamples = 10, minSamples = 5)
    var ts = start
    (1 to 10).foreach { _ =>
      ts += sec
      assert(d.detect("sentinel-a", metricReq(10.0, 10.0, 5, ts)).isEmpty)
    }
  }

  test("fires anomaly when cpu deviates from the sliding-window baseline") {
    val d = new AnomalyDetector(windowSamples = 10, minSamples = 5)
    var ts = start
    (1 to 10).foreach { _ =>
      ts += sec
      d.detect("sentinel-a", metricReq(10.0, 10.0, 5, ts))
    }
    ts += sec
    val anomaly = d.detect("sentinel-a", metricReq(100.0, 10.0, 5, ts))
    assert(anomaly.isDefined, "expected a spike anomaly")
    assertEquals(anomaly.get.eventType, "cpu_spike")
    assertEquals(anomaly.get.severity, "CRITICAL")
    assert(anomaly.get.score >= 4.0)
  }

  test("per-metric cooldown suppresses repeated emissions") {
    val d = new AnomalyDetector(windowSamples = 10, minSamples = 5, cooldownNs = 30 * sec)
    var ts = start
    (1 to 10).foreach { _ =>
      ts += sec
      d.detect("sentinel-a", metricReq(10.0, 10.0, 5, ts))
    }
    ts += sec
    assert(d.detect("sentinel-a", metricReq(100.0, 10.0, 5, ts)).isDefined)

    ts += 5 * sec
    assert(d.detect("sentinel-a", metricReq(100.0, 10.0, 5, ts)).isEmpty, "cooldown should suppress the second spike")
  }

  test("non-metric payloads never trigger detection") {
    val d = new AnomalyDetector()
    val req = TelemetryRequest(
      agent = Some(AgentMetadata(agentId = "sentinel-a", timestampNs = start)),
      payload = TelemetryRequest.Payload.Syscall(
        com.aegis.telemetry.v1.telemetry.SyscallPayload(syscallName = "read", pid = 1L)
      )
    )
    assert(d.detect("sentinel-a", req).isEmpty)
  }
}
