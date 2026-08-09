package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryServiceGrpc
import io.grpc.ServerBuilder

import scala.concurrent.ExecutionContext

object Main {
  def main(args: Array[String]): Unit = {
    val port = sys.env.get("AEGIS_BRAIN_PORT").flatMap(_.toIntOption).getOrElse(9090)
    val bufferDir = sys.env.getOrElse("AEGIS_BUFFER_DIR", "/tmp/aegis-buffers")
    val briefingDir = sys.env.getOrElse("AEGIS_BRIEFING_DIR", "/tmp/aegis-briefings")
    val httpPort = sys.env.get("AEGIS_HTTP_PORT").flatMap(_.toIntOption).getOrElse(9091)

    val embedder: Embedder = new HashEmbedder()
    val vectorStore = new VectorStore()
    val store = BufferStore(bufferDir)
    val indexer = new RetrievalIndexer(embedder, vectorStore)

    val llm: Llm = new RuleBasedLlm()
    val notifier: Notifier = new LogNotifier()
    val briefingStore = BriefingStore(briefingDir)
    val briefingService = new BriefingService(embedder, vectorStore, llm, briefingStore, notifier)
    val orchestrator = new FlushOrchestrator(store, indexer, briefingService)
    val api = new HttpApi(embedder, vectorStore, briefingStore, httpPort)

    val server = ServerBuilder
      .forPort(port)
      .addService(TelemetryServiceGrpc.bindService(new TelemetryServiceImpl, ExecutionContext.global))
      .build()
      .start()

    api.start()

    println(s"Aegis Central Cluster (Brain) listening on port $port")
    println(s"Buffer store: $bufferDir")
    println(s"Briefing store: $briefingDir")
    println("Anomaly event bus + flush orchestrator active")
    println("Retrieval pipeline active (translate -> embed -> index)")
    println("Briefing pipeline active (prompt -> generate -> persist -> deliver)")

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = {
        println("Aegis Central Cluster shutting down...")
        orchestrator.close()
        api.close()
        server.shutdown()
      }
    }))

    server.awaitTermination()
  }
}
