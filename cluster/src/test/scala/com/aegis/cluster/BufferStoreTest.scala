package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.{AgentMetadata, MetricPayload, TelemetryRequest}

import java.nio.file.{Files, Path}

class BufferStoreTest extends munit.FunSuite {

  private def withTempDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("aegis-buffer-test")
    try f(dir)
    finally {
      val stream = Files.walk(dir)
      try stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
      finally stream.close()
    }
  }

  private def req(agentId: String, tsNs: Long): TelemetryRequest =
    TelemetryRequest(
      agent = Some(AgentMetadata(agentId = agentId, timestampNs = tsNs)),
      payload = TelemetryRequest.Payload.Metric(
        MetricPayload(cpuUsagePercent = 1.0, memoryUsagePercent = 2.0)
      )
    )

  test("persists, lists and counts windows per agent") {
    withTempDir { dir =>
      val store = new BufferStore(dir)
      store.save("sentinel-a", 100L, 200L, Seq(req("sentinel-a", 100L), req("sentinel-a", 150L)))

      assertEquals(store.count("sentinel-a"), 1)
      assertEquals(store.list("sentinel-a").size, 1)
      assertEquals(store.count("sentinel-b"), 0, "agents must be isolated")

      val contents = new String(Files.readAllBytes(store.list("sentinel-a").head))
      assert(contents.startsWith("["), "windows are persisted as a JSON array")
    }
  }
}
