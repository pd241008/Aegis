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
  */
final class VectorStore {
  private val docs = TrieMap[String, (Array[Double], String, Map[String, String])]()

  def index(id: String, vector: Array[Double], text: String, metadata: Map[String, String]): Unit =
    docs(id) = (vector, text, metadata)

  def search(query: Array[Double], topK: Int): Seq[SearchHit] =
    docs.toSeq
      .map { case (id, (vec, text, meta)) => SearchHit(id, cosine(query, vec), text, meta) }
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
