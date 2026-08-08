package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryServiceGrpc
import io.grpc.ServerBuilder

import scala.concurrent.ExecutionContext

object Main {
  def main(args: Array[String]): Unit = {
    val port = sys.env.get("AEGIS_BRAIN_PORT").flatMap(_.toIntOption).getOrElse(9090)
    val bufferDir = sys.env.getOrElse("AEGIS_BUFFER_DIR", "/tmp/aegis-buffers")
    val httpPort = sys.env.get("AEGIS_HTTP_PORT").flatMap(_.toIntOption).getOrElse(9091)

    val embedder: Embedder = new HashEmbedder()
    val vectorStore = new VectorStore()
    val store = BufferStore(bufferDir)
    val indexer = new RetrievalIndexer(embedder, vectorStore)
    val orchestrator = new FlushOrchestrator(store, indexer)
    val retrievalApi = new RetrievalApi(embedder, vectorStore, httpPort)

    val server = ServerBuilder
      .forPort(port)
      .addService(TelemetryServiceGrpc.bindService(new TelemetryServiceImpl, ExecutionContext.global))
      .build()
      .start()

    retrievalApi.start()

    println(s"Aegis Central Cluster (Brain) listening on port $port")
    println(s"Buffer store: $bufferDir")
    println("Anomaly event bus + flush orchestrator active")
    println("Retrieval pipeline active (translate -> embed -> index)")

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = {
        println("Aegis Central Cluster shutting down...")
        orchestrator.close()
        retrievalApi.close()
        server.shutdown()
      }
    }))

    server.awaitTermination()
  }
}
