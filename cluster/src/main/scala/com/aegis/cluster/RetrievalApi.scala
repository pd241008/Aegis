package com.aegis.cluster

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetSocketAddress, URLDecoder}

/** HTTP retrieval endpoint (4B.4): `GET /api/v1/retrieve?q=<text>&top_k=5`.
  *
  * Embeds the query text and returns the top-k most similar telemetry
  * windows from the vector store. A gRPC binding can be added later
  * without changing the retrieval core.
  */
final class RetrievalApi(embedder: Embedder, store: VectorStore, port: Int) extends AutoCloseable {

  private var server: HttpServer = _

  def start(): Unit = {
    server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/api/v1/retrieve", handle(_))
    server.setExecutor(null)
    server.start()
    println(s"Retrieval API listening on port $port")
  }

  def retrieve(query: String, topK: Int): Seq[SearchHit] =
    if (query.trim.isEmpty) Seq.empty
    else store.search(embedder.embed(query), topK)

  private def handle(exchange: HttpExchange): Unit = {
    try {
      val params = queryParams(exchange.getRequestURI.getRawQuery)
      val q = params.getOrElse("q", "")
      val topK = params.get("top_k").flatMap(_.toIntOption).getOrElse(5)

      val body = retrieve(q, topK).map(_.toJson).mkString("[", ",", "]")
      respond(exchange, 200, body)
    } catch {
      case t: Throwable =>
        respond(exchange, 500, s"""{"error":"${SearchHit.escapeJson(Option(t.getMessage).getOrElse("internal error"))}"}""")
    } finally {
      exchange.close()
    }
  }

  private def respond(exchange: HttpExchange, code: Int, body: String): Unit = {
    val bytes = body.getBytes("UTF-8")
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length)
    exchange.getResponseBody.write(bytes)
  }

  private def queryParams(raw: String): Map[String, String] = {
    if (raw == null || raw.isEmpty) Map.empty
    else
      raw.split("&").flatMap { kv =>
        val parts = kv.split("=", 2)
        if (parts.length == 2) Some(parts(0) -> URLDecoder.decode(parts(1), "UTF-8"))
        else None
      }.toMap
  }

  override def close(): Unit = if (server != null) server.stop(0)
}
