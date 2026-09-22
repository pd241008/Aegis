package com.aegis.cluster

import scala.collection.concurrent.TrieMap

/** A search result from the vector store. */
final case class SearchHit(
    id: String,
    score: Double,
    text: String,
    metadata: Map[String, String]
) {
  def toJson: String = {
    val meta = metadata
      .map { case (k, v) => s""""${SearchHit.escapeJson(k)}": "${SearchHit.escapeJson(v)}"""" }
      .mkString("{", ",", "}")
    s"""{"id":"${SearchHit.escapeJson(id)}","score":$score,"text":"${SearchHit.escapeJson(text)}","metadata":$meta}"""
  }
}

object SearchHit {
  def escapeJson(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
    }
}

/** In-memory vector store with exact cosine similarity search (4B.3).
  *
  * Sufficient for the RAG foundation; a production deployment swaps in
  * a dedicated ANN index (FAISS/Qdrant/pgvector) behind the same API.
  *
  * All vectors in the store must share one dimensionality: cosine over
  * mismatched dims would silently compare only the overlapping prefix.
  * A mismatch is rejected at write time (fail-fast on config error) and
  * guarded at read time so a stale store can never crash a query.
  */
final class VectorStore {
  private val docs = TrieMap[String, (Array[Double], String, Map[String, String])]()

  def index(id: String, vector: Array[Double], text: String, metadata: Map[String, String]): Unit =
    vectorDimension.foreach { expected =>
      if vector.length != expected then
        throw new IllegalArgumentException(
          s"vector dimension mismatch: store holds $expected-dim vectors, got ${vector.length} (id=$id)"
        )
    }
    docs(id) = (vector, text, metadata)

  /** Dimensionality of the first indexed vector, once any exist. */
  def vectorDimension: Option[Int] = docs.values.headOption.map(_._1.length)

  def search(query: Array[Double], topK: Int, excludeWindowStart: Option[Long] = None): Seq[SearchHit] =
    docs.toSeq
      .filterNot { case (_, (_, _, meta)) =>
        meta.get("window_start").flatMap(_.toLongOption).exists(ws => excludeWindowStart.contains(ws))
      }
      .collect { case (id, (vec, text, meta)) if vec.length == query.length =>
        SearchHit(id, cosine(query, vec), text, meta)
      }
      .sortBy(-_.score)
      .take(topK)

  def size: Int = docs.size

  private def cosine(a: Array[Double], b: Array[Double]): Double = {
    if (a.isEmpty || b.isEmpty) return 0.0
    val dot = a.zip(b).foldLeft(0.0) { case (acc, (x, y)) => acc + x * y }
    val na = math.sqrt(a.foldLeft(0.0)((acc, v) => acc + v * v))
    val nb = math.sqrt(b.foldLeft(0.0)((acc, v) => acc + v * v))
    if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
  }
}
