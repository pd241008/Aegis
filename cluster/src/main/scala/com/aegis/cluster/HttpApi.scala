package com.aegis.cluster

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.file.Files

/** Brain HTTP surface (4B.4 + 4C.4 + 4D.3): retrieval, briefing and
  * incident delivery.
  *
  * Routes (one JDK HttpServer, zero new dependencies):
  *   - `GET /api/v1/retrieve?q=<text>&top_k=5`  top-k similar telemetry
  *   - `GET /api/v1/briefings?agent_id=X`       briefing metadata list
  *   - `GET /api/v1/briefings/latest?agent_id=X` newest briefing markdown
  *   - `GET /api/v1/incidents`                  incident metadata list
  *   - `GET /api/v1/incidents/latest`           newest incident briefing
  */
final class HttpApi(
    embedder: Embedder,
    store: VectorStore,
    briefingStore: BriefingStore,
    incidentStore: IncidentStore,
    port: Int
) extends AutoCloseable {

  private var server: HttpServer = _

  def start(): Unit = {
    server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/api/v1/retrieve", handleRetrieve(_))
    server.createContext("/api/v1/briefings", handleBriefings(_))
    server.createContext("/api/v1/incidents", handleIncidents(_))
    server.setExecutor(null)
    server.start()
    println(s"HTTP API listening on port $port")
  }

  private def handleRetrieve(exchange: HttpExchange): Unit = {
    try {
      val params = queryParams(exchange.getRequestURI.getRawQuery)
      val q = params.getOrElse("q", "")
      val topK = params.get("top_k").flatMap(_.toIntOption).getOrElse(5)

      val body =
        if (q.trim.isEmpty) "[]"
        else store.search(embedder.embed(q), topK).map(_.toJson).mkString("[", ",", "]")
      respond(exchange, 200, body)
    } catch {
      case t: Throwable => respondError(exchange, t)
    } finally {
      exchange.close()
    }
  }

  private def handleBriefings(exchange: HttpExchange): Unit = {
    try {
      val path = exchange.getRequestURI.getPath
      val params = queryParams(exchange.getRequestURI.getRawQuery)
      val agentId = params.getOrElse("agent_id", "")

      if (path.endsWith("/latest")) {
        briefingStore.latest(agentId) match {
          case Some(f) => respond(exchange, 200, Files.readString(f))
          case None    => respond(exchange, 404, """{"error":"no briefing for agent"}""")
        }
      } else {
        val body = briefingStore
          .list(agentId)
          .map(p => Files.readString(p))
          .mkString("[", ",", "]")
        respond(exchange, 200, body)
      }
    } catch {
      case t: Throwable => respondError(exchange, t)
    } finally {
      exchange.close()
    }
  }

  private def handleIncidents(exchange: HttpExchange): Unit = {
    try {
      val path = exchange.getRequestURI.getPath
      if (path.endsWith("/latest")) {
        incidentStore.latest match {
          case Some(f) => respond(exchange, 200, Files.readString(f))
          case None    => respond(exchange, 404, """{"error":"no incident"}""")
        }
      } else {
        val body = incidentStore.list.map(p => Files.readString(p)).mkString("[", ",", "]")
        respond(exchange, 200, body)
      }
    } catch {
      case t: Throwable => respondError(exchange, t)
    } finally {
      exchange.close()
    }
  }

  private def respondError(exchange: HttpExchange, t: Throwable): Unit =
    respond(exchange, 500, s"""{"error":"${SearchHit.escapeJson(Option(t.getMessage).getOrElse("internal error"))}"}""")

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
