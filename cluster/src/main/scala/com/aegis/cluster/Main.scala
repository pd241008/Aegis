package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryServiceGrpc
import io.grpc.ServerBuilder

import java.nio.file.Paths
import scala.concurrent.ExecutionContext

object Main {
  def main(args: Array[String]): Unit = {
    val port = sys.env.get("AEGIS_BRAIN_PORT").flatMap(_.toIntOption).getOrElse(9090)
    val bufferDir = sys.env.getOrElse("AEGIS_BUFFER_DIR", "/tmp/aegis-buffers")
    val briefingDir = sys.env.getOrElse("AEGIS_BRIEFING_DIR", "/tmp/aegis-briefings")
    val incidentDir = sys.env.getOrElse("AEGIS_INCIDENT_DIR", "/tmp/aegis-incidents")
    val httpPort = sys.env.get("AEGIS_HTTP_PORT").flatMap(_.toIntOption).getOrElse(9091)
    val webRoot = sys.env.getOrElse("AEGIS_WEBROOT", "frontend")
    val corrWindowMs = sys.env.get("AEGIS_CORR_WINDOW_MS").flatMap(_.toLongOption).getOrElse(10000L)
    val corrMinAgents = sys.env.get("AEGIS_CORR_MIN_AGENTS").flatMap(_.toIntOption).getOrElse(2)

    val embedder: Embedder = new HashEmbedder()
    val vectorStore = new VectorStore()
    val store = BufferStore(bufferDir)
    val indexer = new RetrievalIndexer(embedder, vectorStore)

    val llm: Llm = LlmFactory.fromEnv()
    val notifier: Notifier = new LogNotifier()
    val briefingStore = BriefingStore(briefingDir)
    val briefingService = new BriefingService(embedder, vectorStore, llm, briefingStore, notifier)
    val orchestrator = new FlushOrchestrator(store, indexer, briefingService)

    val incidentStore = IncidentStore(incidentDir)
    val incidentBriefing = new IncidentBriefingService(llm, incidentStore, notifier)
    val correlation = new CorrelationEngine(corrMinAgents, corrWindowMs * 1_000_000L, 2000L)

    // Rebuild the in-memory retrieval index from persisted windows. The
    // vector store is in-memory by design (ADR-008); without this pass a
    // restart would silently empty retrieval.
    val reindexer = new StartupReindexer(store, indexer)
    val reindex: () => Int = () => reindexer.reindexAll()
    val reindexed = reindexer.reindexAll()
    println(s"Startup reindex complete: $reindexed telemetry entries searchable")

    val api = new HttpApi(embedder, vectorStore, briefingStore, incidentStore, httpPort, Paths.get(webRoot), reindex)

    val server = ServerBuilder
      .forPort(port)
      .addService(TelemetryServiceGrpc.bindService(new TelemetryServiceImpl, ExecutionContext.global))
      .build()
      .start()

    api.start()

    println(s"Aegis Central Cluster (Brain) listening on port $port")
    println(s"Buffer store: $bufferDir")
    println(s"Briefing store: $briefingDir")
    println(s"Incident store: $incidentDir")
    println("Anomaly event bus + flush orchestrator active")
    println("Brain-side anomaly detection active (sliding-window z-score)")
    println("Retrieval pipeline active (translate -> embed -> index)")
    println("Briefing pipeline active (prompt -> generate -> persist -> deliver)")
    println(s"Incident correlation active ($corrMinAgents+ agents, ${corrWindowMs}ms window)")
    println(s"Dashboard webroot: $webRoot")

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = {
        println("Aegis Central Cluster shutting down...")
        correlation.close()
        incidentBriefing.close()
        orchestrator.close()
        api.close()
        server.shutdown()
      }
    }))

    server.awaitTermination()
  }
}
