package com.aegis.cluster

/** Embedding service (4B.2): maps translated telemetry text to a vector.
  *
  * The trait allows swapping in a real embedding API (e.g. an LLM
  * text-embedding endpoint) without touching the retrieval pipeline.
  * The default `HashEmbedder` is self-contained and deterministic:
  * a bag-of-words feature-hashing vector, L2-normalized.
  */
trait Embedder {

  def embed(text: String): Array[Double]

  /** Embeds a batch of texts in order; index i of the result corresponds
    * to input i. Batch-capable implementations (e.g. the OpenAI-compatible
    * embedder) override this to amortize transport cost; the default
    * delegates to [[embed]] per text.
    */
  def embedAll(texts: Seq[String]): Seq[Array[Double]] = texts.map(embed)
}

final class HashEmbedder(dims: Int = 256) extends Embedder {

  override def embed(text: String): Array[Double] = {
    val vec = new Array[Double](dims)
    val tokens = text
      .toLowerCase
      .replaceAll("[^a-z0-9 ]", " ")
      .split("\\s+")
      .filter(_.nonEmpty)

    tokens.foreach { t =>
      val h = t.hashCode & 0x7fffffff
      vec(h % dims) += 1.0
    }

    val norm = math.sqrt(vec.foldLeft(0.0)((acc, v) => acc + v * v))
    if (norm > 0.0) vec.map(_ / norm) else vec
  }
}
