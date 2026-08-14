package com.aegis.cluster

class VectorStoreTest extends munit.FunSuite {

  private val embedder = new HashEmbedder()

  test("indexes and searches by cosine similarity") {
    val store = new VectorStore()
    store.index("1", embedder.embed("database connection pool exhausted"), "db pool full", Map("agent" -> "a"))
    store.index("2", embedder.embed("kitchen sink drain"), "unrelated", Map("agent" -> "b"))

    val hits = store.search(embedder.embed("database connection"), topK = 2)
    assertEquals(hits.head.id, "1", "expected the database doc to rank first")
    assert(hits.head.score > hits(1).score)
  }

  test("respects topK") {
    val store = new VectorStore()
    store.index("1", embedder.embed("alpha"), "alpha", Map.empty)
    store.index("2", embedder.embed("beta"), "beta", Map.empty)
    assertEquals(store.search(embedder.embed("alpha"), topK = 1).size, 1)
  }

  test("excludeWindowStart filters out the triggering window") {
    val store = new VectorStore()
    store.index("1", embedder.embed("alpha beta gamma"), "doc one", Map("window_start" -> "100"))
    store.index("2", embedder.embed("alpha beta gamma"), "doc two", Map("window_start" -> "200"))

    val hits = store.search(embedder.embed("alpha beta gamma"), topK = 5, excludeWindowStart = Some(100L))
    assertEquals(hits.map(_.id), Seq("2"))
  }

  test("empty query vector yields zero-score hits") {
    val store = new VectorStore()
    store.index("1", embedder.embed("alpha"), "alpha", Map.empty)
    val hits = store.search(Array.emptyDoubleArray, topK = 5)
    assert(hits.nonEmpty, "store returns all docs for an empty query vector")
    assert(hits.forall(_.score == 0.0), "empty query must produce zero-similarity scores")
  }

  test("re-indexing overwrites an existing document") {
    val store = new VectorStore()
    store.index("1", embedder.embed("alpha"), "old", Map.empty)
    store.index("1", embedder.embed("alpha"), "new", Map.empty)
    assertEquals(store.size, 1)
  }
}
