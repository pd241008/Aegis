package com.aegis.cluster

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.file.{Files, Path, Paths}

/** Brain HTTP surface (4B.4 + 4C.4 + 4D.3): retrieval, briefing, incident
  * delivery and the Phase 4 dashboard.
  *
  * Routes (one JDK HttpServer, zero new dependencies):
  *   - `GET /`                              static dashboard (frontend/)
  *   - `GET /api/v1/retrieve?q=<text>&top_k=5`  top-k similar telemetry
  *   - `GET /api/v1/briefings?agent_id=X`       briefing metadata list
  *   - `GET /api/v1/briefings/latest?agent_id=X` newest briefing markdown
  *   - `GET /api/v1/incidents`                  incident metadata list
  *   - `GET /api/v1/incidents/latest`           newest incident briefing
  *   - `POST /api/v1/index`                     reindex persisted windows into the vector store
  */
final class HttpApi(
    embedder: Embedder,
    store: VectorStore,
    briefingStore: BriefingStore,
    incidentStore: IncidentStore,
    port: Int,
    webRoot: Path = Paths.get("frontend"),
    reindex: () => Int = () => 0
) extends AutoCloseable {

  private var server: HttpServer = _
  private val root: Path = webRoot.toAbsolutePath.normalize

  def start(): Unit = {
    server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/api/v1/retrieve", handleRetrieve(_))
    server.createContext("/api/v1/briefings", handleBriefings(_))
    server.createContext("/api/v1/incidents", handleIncidents(_))
    server.createContext("/api/v1/index", handleIndex(_))
    server.createContext("/", handleStatic(_))
    server.setExecutor(null)
    server.start()
    println(s"HTTP API listening on port $port (webroot: $root)")
  }

  /** Serves the zero-dependency static dashboard from `webRoot`. The JDK
    * HttpServer matches the longest context prefix, so `/` only sees
    * non-API paths. Path traversal is rejected up front.
    */
  private def handleStatic(exchange: HttpExchange): Unit = {
    try {
      val raw = Option(exchange.getRequestURI.getPath).getOrElse("/").stripPrefix("/")
      val segments = raw.split('/').toList.filter(s => s.nonEmpty && s != ".")
      if (segments.exists(_ == "..")) {
        respond(exchange, 400, """{"error":"bad path"}""")
        return
      }
      val file =
        if (segments.isEmpty) root.resolve("index.html")
        else root.resolve(segments.mkString("/"))
      if (Files.isRegularFile(file) && Files.isReadable(file)) {
        val bytes = Files.readAllBytes(file)
        exchange.getResponseHeaders.set("Content-Type", contentType(file.getFileName.toString))
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
      } else {
        respond(exchange, 404, """{"error":"not found"}""")
      }
    } catch {
      case t: Throwable => respondError(exchange, t)
    } finally {
      exchange.close()
    }
  }

  private def contentType(name: String): String = {
    val idx = name.lastIndexOf('.')
    val ext = if (idx >= 0) name.substring(idx + 1).toLowerCase else ""
    ext match {
      case "html" => "text/html; charset=utf-8"
      case "css"  => "text/css; charset=utf-8"
      case "js"   => "application/javascript; charset=utf-8"
      case "json" => "application/json"
      case "svg"  => "image/svg+xml"
      case "png"  => "image/png"
      case "ico"  => "image/x-icon"
      case "txt"  => "text/plain; charset=utf-8"
      case _      => "application/octet-stream"
    }
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

  /** Re-runs the startup reindex (POST only). Lets an operator rebuild the
    * retrieval index from persisted windows without restarting the brain.
    */
  private def handleIndex(exchange: HttpExchange): Unit = {
    try {
      if (exchange.getRequestMethod != "POST") {
        respond(exchange, 405, """{"error":"method not allowed, use POST"}""")
        return
      }
      val indexed = reindex()
      respond(exchange, 200, s"""{"indexed":$indexed}""")
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
