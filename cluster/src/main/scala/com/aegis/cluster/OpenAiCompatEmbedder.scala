package com.aegis.cluster

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import scala.jdk.CollectionConverters.*

/** API-backed [[Embedder]] speaking the OpenAI embeddings wire format
  * (`POST /v1/embeddings`).
  *
  * Works with any OpenAI-compatible endpoint: OpenAI, Ollama (`/v1`),
  * vLLM, Jina, Mixedbread, LM Studio, LocalAI. Selected by [[EmbedderFactory]]
  * from:
  *
  *   - `AEGIS_EMBED_PROVIDER`  = `openai` (default when a base URL or key is present) | `none`
  *   - `AEGIS_EMBED_API_KEY`   = bearer token (optional; local runtimes skip it)
  *   - `AEGIS_EMBED_MODEL`     = model id (default `text-embedding-3-small`)
  *   - `AEGIS_EMBED_BASE_URL`  = embeddings URL
  *   - `AEGIS_EMBED_TIMEOUT_MS`= request timeout (default 30000)
  *   - `AEGIS_EMBED_DIMS`      = truncate returned vectors to this many dims
  *
  * Batching: [[embedAll]] groups texts into bounded request batches, so a
  * 60-second telemetry window (dozens of entries) costs a handful of calls
  * instead of one per entry.
  *
  * Any transport failure, non-2xx, malformed body, count mismatch or
  * dimension mismatch throws [[EmbedderUnavailableException]] — callers
  * (retrieval route, indexer, reindexer) degrade per-entry instead of
  * failing the pipeline, mirroring ADR-011's "a degraded artifact beats
  * a failed one" stance.
  */
final class OpenAiCompatEmbedder(
    apiKey: String,
    model: String,
    baseUrl: String,
    timeout: Duration,
    truncateTo: Option[Int] = None,
    maxBatch: Int = 64
) extends Embedder {

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  override def embed(text: String): Array[Double] = embedAll(Seq(text)).head

  /** Embeds texts in order; index i of the result corresponds to input i. */
  def embedAll(texts: Seq[String]): Seq[Array[Double]] =
    if texts.isEmpty then Seq.empty
    else
      texts
        .grouped(math.max(1, maxBatch))
        .flatMap(embedBatch)
        .toSeq

  private def embedBatch(batch: Seq[String]): Seq[Array[Double]] = {
    val input = JsonArray()
    batch.foreach(t => input.add(new JsonPrimitive(t)))

    val body = JsonObject()
    body.addProperty("model", model)
    body.add("input", input)
    truncateTo.foreach(d => body.addProperty("dimensions", Integer.valueOf(d)))

    val builder = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl))
      .timeout(timeout)
      .header("Content-Type", "application/json")
    if apiKey.nonEmpty then builder.header("Authorization", s"Bearer $apiKey")
    val request = builder
      .POST(HttpRequest.BodyPublishers.ofString(body.toString))
      .build()

    val response =
      try http.send(request, HttpResponse.BodyHandlers.ofString())
      catch case e: Exception =>
        throw EmbedderUnavailableException(s"transport error: ${e.getMessage}", e)

    if response.statusCode() < 200 || response.statusCode() >= 300 then
      throw EmbedderUnavailableException(
        s"embeddings API returned HTTP ${response.statusCode()}: ${truncate(response.body())}"
      )

    val vectors =
      try
        JsonParser.parseString(response.body())
          .getAsJsonObject
          .getAsJsonArray("data").asScala
          .map { el =>
            val obj = el.getAsJsonObject
            val idx = obj.get("index").getAsInt
            val vec = obj.getAsJsonArray("embedding").asScala.map(_.getAsDouble).toArray
            (idx, vec)
          }
          .toSeq
          .sortBy(_._1)
          .map(_._2)
          .toIndexedSeq
      catch case e: Exception =>
        throw EmbedderUnavailableException(s"malformed embeddings response: ${e.getMessage}", e)

    if vectors.length != batch.length then
      throw EmbedderUnavailableException(
        s"embeddings API returned ${vectors.length} vectors for ${batch.length} inputs"
      )

    vectors.map { vec =>
      if vec.isEmpty then throw EmbedderUnavailableException("embeddings API returned an empty vector")
      vec
    }
  }

  private def truncate(s: String, max: Int = 300): String =
    if s == null then "null"
    else if s.length <= max then s
    else s.take(max) + "..."
}

/** Signals the API-backed embedder could not produce embeddings; callers
  * fall back to the deterministic hash embedder per-entry.
  */
final case class EmbedderUnavailableException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
