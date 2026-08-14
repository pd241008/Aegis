package com.aegis.cluster

import java.nio.file.{Files, Path}

class IncidentStoreTest extends munit.FunSuite {

  private def withTempDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("aegis-incident-test")
    try f(dir)
    finally {
      val stream = Files.walk(dir)
      try stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
      finally stream.close()
    }
  }

  private def incident(eventType: String, startNs: Long, endNs: Long) =
    Incident(
      id = s"$eventType@$startNs",
      eventType = eventType,
      startNs = startNs,
      endNs = endNs,
      agents = Seq("sentinel-a", "sentinel-b"),
      maxSeverity = "CRITICAL",
      createdAtNs = endNs
    )

  test("saves incident metadata and briefings") {
    withTempDir { dir =>
      val store = new IncidentStore(dir)
      val inc = incident("latency_spike", 1000L, 2000L)

      store.save(inc)
      store.saveBriefing(inc, "# Incident briefing\n")

      assertEquals(store.list.size, 1)
      assert(store.latest.isDefined, "expected a latest briefing after save")
      assert(new String(Files.readAllBytes(store.latest.get)).startsWith("# Incident"))
    }
  }

  test("lists incidents oldest first") {
    withTempDir { dir =>
      val store = new IncidentStore(dir)
      store.save(incident("alpha", 1000L, 2000L))
      store.save(incident("beta", 3000L, 4000L))

      val order = store.list.map(_.getFileName.toString)
      assert(order.head.startsWith("alpha"), s"expected alpha first, got $order")
    }
  }

  test("returns no latest before any save") {
    withTempDir { dir =>
      val store = new IncidentStore(dir)
      assert(store.latest.isEmpty)
      assert(store.list.isEmpty)
    }
  }
}
