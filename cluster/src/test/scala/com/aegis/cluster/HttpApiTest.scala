package com.aegis.cluster

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}

/** Exercises the REST surface over a real JDK HttpServer on an ephemeral
  * port — the same boundary the dashboard/CLI would consume.
  */
class HttpApiTest extends munit.FunSuite {

  private def withTempDir[T](f: Path => T): T = {
    val dir = Files.createTempDirectory("aegis-http-test")
    try f(dir)
    finally {
      val stream = Files.walk(dir)
      try stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
      finally stream.close()
    }
  }

  private def freePort(): Int = {
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()
  }

  test("serves retrieve, briefings and incidents routes") {
    withTempDir { dir =>
      val embedder = new HashEmbedder()
      val vs = new VectorStore()
      vs.index(
        "1",
        embedder.embed("database connection pool exhausted"),
        "db pool full",
        Map("agent" -> "sentinel-a")
      )
      val briefingStore = BriefingStore(dir.resolve("briefings").toString)
      val incidentStore = IncidentStore(dir.resolve("incidents").toString)
      val port = freePort()
      val api = new HttpApi(embedder, vs, briefingStore, incidentStore, port)
      api.start()

      try {
        val client = HttpClient.newHttpClient()
        def get(path: String): (Int, String) = {
          val req = HttpRequest.newBuilder(new URI(s"http://127.0.0.1:$port$path")).GET().build()
          val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
          (resp.statusCode(), resp.body())
        }

        val (retCode, retBody) = get("/api/v1/retrieve?q=database%20connection")
        assertEquals(retCode, 200)
        assert(retBody.contains("db pool full"), s"retrieve should find the indexed window, got $retBody")

        val (bCode, bBody) = get("/api/v1/briefings?agent_id=sentinel-a")
        assertEquals(bCode, 200)
        assertEquals(bBody, "[]")

        val (iCode, _) = get("/api/v1/incidents")
        assertEquals(iCode, 200)

        val (lCode, _) = get("/api/v1/incidents/latest")
        assertEquals(lCode, 404, "no incidents yet, latest must be a 404")
      } finally {
        api.close()
      }
    }
  }
}
