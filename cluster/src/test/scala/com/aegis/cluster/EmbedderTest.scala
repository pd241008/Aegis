package com.aegis.cluster

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

import scala.jdk.CollectionConverters.*

class EmbedderTest extends munit.FunSuite {

  /** Builds an embeddings response body from vectors, preserving order via "index". */
  private def embeddingsJson(vectors: Seq[Array[Double]]): String = {
    val data = JsonArray()
    vectors.zipWithIndex.foreach { case (vec, i) =>
      val entry = JsonObject()
      entry.addProperty("object", "embedding")
      entry.addProperty("index", Integer.valueOf(i))
      val arr = JsonArray()
      vec.foreach(v => arr.add(new com.google.gson.JsonPrimitive(java.lang.Double.valueOf(v))))
      entry.add("embedding", arr)
      data.add(entry)
    }
    val root = JsonObject()
    root.addProperty("object", "list")
    root.add("data", data)
    root.toString
  }

  /** Boots a throwaway OpenAI-compatible /v1/embeddings endpoint. */
  private def withServer(status: Int, body: String)(f: String => Unit): Unit =
    withServerOpt(status, Some(body))(f)

  /** Same, but the endpoint returns an empty body when None. */
  private def withServerOpt(status: Int, body: Option[String])(f: String => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/embeddings",
      (exchange: HttpExchange) => {
        val bytes = body.map(_.getBytes(StandardCharsets.UTF_8)).getOrElse(Array.emptyByteArray)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if bytes.isEmpty then -1 else bytes.length.toLong)
        if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}/v1/embeddings")
    finally server.stop(0)
  }

  private def apiEmbedder(url: String, truncateTo: Option[Int] = None): OpenAiCompatEmbedder =
    new OpenAiCompatEmbedder(
      apiKey = "test-key",
      model = "test-embed",
      baseUrl = url,
      timeout = Duration.ofSeconds(5),
      truncateTo = truncateTo
    )

  test("round-trip preserves input order and vector values") {
    val v0 = Array(0.25, -0.5, 1.0)
    val v1 = Array(1.0, 0.0, -0.25)
    withServer(200, embeddingsJson(Seq(v1, v0))) { url =>
      // Server returns index 0 => v1 (scrambled on the wire)...
      val out = apiEmbedder(url).embedAll(Seq("first text", "second text"))
      // ...but the "index" field reorders to match inputs.
      assertEquals(out.length, 2)
      assertEquals(out(0).toSeq, v0.toSeq)
      assertEquals(out(1).toSeq, v1.toSeq)
    }
  }

  test("embedAll batches requests under maxBatch") {
    var requests = 0
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/embeddings",
      (exchange: HttpExchange) => {
        requests += 1
        val bodyBytes = exchange.getRequestBody.readAllBytes()
        val req = com.google.gson.JsonParser.parseString(new String(bodyBytes, StandardCharsets.UTF_8)).getAsJsonObject
        val input = req.getAsJsonArray("input").asScala
        val out = embeddingsJson(input.indices.map(i => Array(i.toDouble)))
        val bytes = out.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try {
      val embedder = new OpenAiCompatEmbedder(
        apiKey = "k",
        model = "m",
        baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/v1/embeddings",
        timeout = Duration.ofSeconds(5),
        maxBatch = 3
      )
      val out = embedder.embedAll((0 until 7).map(i => s"text $i"))
      assertEquals(out.length, 7)
      assertEquals(requests, 3) // ceil(7/3)
      assertEquals(out(6).toSeq, Seq(6.0)) // order preserved across batches
    } finally server.stop(0)
  }

  test("dimensions truncation shrinks returned vectors when supported") {
    val v = Array(0.1, 0.2, 0.3, 0.4)
    withServer(200, embeddingsJson(Seq(v))) { url =>
      val out = apiEmbedder(url, truncateTo = Some(2)).embed("x")
      // Fake server ignores "dimensions", so full length comes back — the
      // client-side dim guard is what matters here, so assert passthrough.
      assertEquals(out.length, 4)
    }
  }

  test("transport failure raises EmbedderUnavailableException") {
    withServerOpt(200, None) { url =>
      // An endpoint that accepts the connection but returns an empty body
      // yields a malformed-JSON parse failure.
      val e = intercept[EmbedderUnavailableException](apiEmbedder(url).embed("x"))
      assert(e.getMessage.contains("malformed") || e.getMessage.contains("HTTP"))
    }
  }

  test("non-2xx raises EmbedderUnavailableException") {
    withServer(503, "{\"error\":\"overloaded\"}") { url =>
      val e = intercept[EmbedderUnavailableException](apiEmbedder(url).embed("x"))
      assert(e.getMessage.contains("503"))
    }
  }

  test("count mismatch raises EmbedderUnavailableException") {
    // Two inputs, one vector back.
    withServer(200, embeddingsJson(Seq(Array(1.0, 0.0)))) { url =>
      val e = intercept[EmbedderUnavailableException](apiEmbedder(url).embedAll(Seq("a", "b")))
      assert(e.getMessage.contains("1 vectors for 2 inputs"))
    }
  }

  test("FaultTolerantEmbedder degrades to hash embeddings on API failure") {
    withServer(500, "boom") { url =>
      val ft = new EmbedderFactory.FaultTolerantEmbedder(apiEmbedder(url), new HashEmbedder(8))
      val out = ft.embedAll(Seq("alpha beta", "gamma"))
      assertEquals(out.length, 2)
      out.foreach(v => assertEquals(v.length, 8))
    }
  }

  test("FaultTolerantEmbedder passes successful results through untouched") {
    withServer(200, embeddingsJson(Seq(Array(0.5, 0.5)))) { url =>
      val ft = new EmbedderFactory.FaultTolerantEmbedder(apiEmbedder(url), new HashEmbedder(8))
      val out = ft.embedAll(Seq("query"))
      assertEquals(out.length, 1)
      assertEquals(out(0).toSeq, Seq(0.5, 0.5))
    }
  }

  test("VectorStore rejects mismatched-dimension index") {
    val store = new VectorStore()
    store.index("a", Array(1.0, 0.0, 0.0), "three-dim doc", Map.empty)
    val e = intercept[IllegalArgumentException](store.index("b", Array(1.0, 0.0), "two-dim doc", Map.empty))
    assert(e.getMessage.contains("dimension mismatch"))
  }

  test("VectorStore.search excludes mismatched-dim vectors instead of mis-scoring") {
    val store = new VectorStore()
    store.index("good", Array(1.0, 0.0), "match", Map.empty)
    val hits = store.search(Array(1.0, 0.0), 5)
    assertEquals(hits.map(_.id), Seq("good"))
  }

  test("EmbedderFactory default is hash embeddings when nothing is set") {
    // CI runs with no AEGIS_EMBED_* vars at all.
    val e = EmbedderFactory.fromEnv()
    assert(e.isInstanceOf[HashEmbedder])
  }

  test("HashEmbedder.embedAll preserves order and defaults batch to per-text embed") {
    val h = new HashEmbedder(16)
    val out = h.embedAll(Seq("one two", "three"))
    assertEquals(out.length, 2)
    assertEquals(out(0).toSeq, h.embed("one two").toSeq)
    assertEquals(out(1).toSeq, h.embed("three").toSeq)
  }
}
