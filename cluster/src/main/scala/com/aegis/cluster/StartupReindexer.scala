package com.aegis.cluster

import java.nio.file.Path

/** Rebuilds the in-memory vector store from persisted buffer windows at
  * brain startup.
  *
  * The vector store is in-memory by design (ADR-008); without this pass a
  * brain restart would silently empty the retrieval index even though the
  * raw windows survive on disk. Called from `Main` before the HTTP API
  * starts serving traffic.
  */
final class StartupReindexer(store: BufferStore, indexer: RetrievalIndexer) {

  /** Re-indexes every persisted window; returns the number of entries
    * indexed. Malformed files are logged and skipped, never fatal — a bad
    * window must not stop the brain from serving.
    */
  def reindexAll(): Int = {
    var indexed = 0
    store.listAll().foreach { (path: Path) =>
      try {
        val (agentId, windowStartNs, entries) = store.readWindow(path)
        if (entries.nonEmpty) {
          indexer.indexWindow(agentId, windowStartNs, entries)
          indexed += entries.size
        }
      } catch {
        case t: Throwable =>
          System.err.println(
            s"[StartupReindexer] skipping malformed window ${path.getFileName}: ${t.getMessage}"
          )
      }
    }
    indexed
  }
}
