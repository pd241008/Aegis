package com.aegis.cluster

import java.util.concurrent.ConcurrentLinkedQueue

class CorrelationEngineTest extends munit.FunSuite {

  private def anomaly(agentId: String, eventType: String, severity: String, tsNs: Long) =
    AnomalyEventBus.AnomalyEvent(
      agentId = agentId,
      eventType = eventType,
      description = "test anomaly",
      severity = severity,
      score = 3.0,
      timestampNs = tsNs
    )

  test("raises an incident when minAgents report the same event type within the window") {
    val captured = new ConcurrentLinkedQueue[Incident]()
    val engine = new CorrelationEngine(minAgents = 2, windowNs = 300_000_000L, tickMs = 50)
    val sub = IncidentBus.subscribe(i => captured.add(i))
    try {
      val now = System.currentTimeMillis() * 1_000_000L
      AnomalyEventBus.publish(anomaly("sentinel-a", "latency_spike", "WARNING", now))
      AnomalyEventBus.publish(anomaly("sentinel-b", "latency_spike", "WARNING", now + 100_000_000L))

      val deadline = System.currentTimeMillis() + 5000
      while (captured.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)
      assertEquals(captured.size(), 1)
      val incident = captured.poll()
      assertEquals(incident.eventType, "latency_spike")
      assertEquals(incident.agents.toSet, Set("sentinel-a", "sentinel-b"))
      assertEquals(incident.maxSeverity, "WARNING")
    } finally {
      sub.close()
      engine.close()
    }
  }

  test("does not raise an incident for a single agent") {
    val captured = new ConcurrentLinkedQueue[Incident]()
    val engine = new CorrelationEngine(minAgents = 2, windowNs = 300_000_000L, tickMs = 50)
    val sub = IncidentBus.subscribe(i => captured.add(i))
    try {
      val now = System.currentTimeMillis() * 1_000_000L
      AnomalyEventBus.publish(anomaly("sentinel-a", "conn_drop", "WARNING", now))
      Thread.sleep(700)
      assert(captured.isEmpty, "single-agent signal must not form an incident")
    } finally {
      sub.close()
      engine.close()
    }
  }

  test("escalates severity across correlated agents") {
    val captured = new ConcurrentLinkedQueue[Incident]()
    val engine = new CorrelationEngine(minAgents = 2, windowNs = 300_000_000L, tickMs = 50)
    val sub = IncidentBus.subscribe(i => captured.add(i))
    try {
      val now = System.currentTimeMillis() * 1_000_000L
      AnomalyEventBus.publish(anomaly("sentinel-a", "disk_full", "WARNING", now))
      AnomalyEventBus.publish(anomaly("sentinel-b", "disk_full", "CRITICAL", now + 50_000_000L))

      val deadline = System.currentTimeMillis() + 5000
      while (captured.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)
      assert(captured.size() == 1)
      assertEquals(captured.poll().maxSeverity, "CRITICAL")
    } finally {
      sub.close()
      engine.close()
    }
  }
}
