package com.aegis.cluster

import java.time.Duration

/** Environment-driven selection of the [[Embedder]] implementation (4B.2).
  *
  *   - `AEGIS_EMBED_PROVIDER=openai` (or unset with a base URL or key
  *     present) activates the API-backed `OpenAiCompatEmbedder` against any
  *     OpenAI-compatible `/v1/embeddings` endpoint.
  *   - `AEGIS_EMBED_PROVIDER=none` (or no base URL / key) selects the
  *     deterministic `HashEmbedder`, the CI and offline default.
  *
  * The API implementation is always wrapped in [[EmbedderFactory.FaultTolerantEmbedder]]:
  * on any failure it logs and degrades to the hash embedder. A degraded
  * embedding beats a failed pipeline — the retrieval route keeps serving,
  * indexing keeps flowing, and reindex stays non-fatal.
  */
object EmbedderFactory {

  /** Wraps a primary [[Embedder]] with deterministic fallback, entry-wise. */
  final class FaultTolerantEmbedder(primary: Embedder, fallback: HashEmbedder) extends Embedder {
    override def embed(text: String): Array[Double] =
      embedAll(Seq(text)).head

    override def embedAll(texts: Seq[String]): Seq[Array[Double]] =
      try primary.embedAll(texts)
      catch
        case e: Throwable =>
          if isFatal(e) then throw e
          val reason = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          System.err.println(
            s"[EmbedderFactory] API embedder unavailable ($reason); falling back to hash embeddings"
          )
          texts.map(fallback.embed)

    private def isFatal(e: Throwable): Boolean =
      e.isInstanceOf[OutOfMemoryError] || e.isInstanceOf[StackOverflowError]
  }

  def fromEnv(): Embedder = {
    val provider = sys.env.getOrElse("AEGIS_EMBED_PROVIDER", "").trim
    val apiKey   = sys.env.getOrElse("AEGIS_EMBED_API_KEY", "").trim
    val baseUrl  = envOr("AEGIS_EMBED_BASE_URL", "")

    val enabled =
      if provider.equalsIgnoreCase("none") then false
      else if provider.isEmpty then baseUrl.nonEmpty || apiKey.nonEmpty
      else provider.equalsIgnoreCase("openai")

    if enabled then
      val model = envOr("AEGIS_EMBED_MODEL", "text-embedding-3-small")
      val url   = envOr("AEGIS_EMBED_BASE_URL", "https://api.openai.com/v1/embeddings")
      val dims  = sys.env.get("AEGIS_EMBED_DIMS").flatMap(_.toIntOption).filter(_ > 0)
      val timeoutMs =
        sys.env.get("AEGIS_EMBED_TIMEOUT_MS").flatMap(_.toLongOption).getOrElse(30000L)
      val primary = new OpenAiCompatEmbedder(
        apiKey,
        model,
        url,
        Duration.ofMillis(timeoutMs),
        truncateTo = dims
      )
      println(s"[EmbedderFactory] API-backed embeddings enabled (model=$model, endpoint=$url)")
      new FaultTolerantEmbedder(primary, new HashEmbedder())
    else
      println(
        "[EmbedderFactory] hash embeddings active (set AEGIS_EMBED_API_KEY or AEGIS_EMBED_BASE_URL to enable API-backed embeddings)"
      )
      new HashEmbedder()
  }

  /** Reads an env var, treating blank (e.g. compose passthrough `""`) as
    * unset so defaults always win.
    */
  private def envOr(key: String, default: String): String = {
    val v = sys.env.getOrElse(key, "").trim
    if v.isEmpty then default else v
  }
}
