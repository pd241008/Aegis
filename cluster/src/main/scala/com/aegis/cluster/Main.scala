package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryServiceGrpc
import io.grpc.ServerBuilder

import scala.concurrent.ExecutionContext

object Main {
  def main(args: Array[String]): Unit = {
    val port = sys.env.get("AEGIS_BRAIN_PORT").flatMap(_.toIntOption).getOrElse(9090)
    val bufferDir = sys.env.getOrElse("AEGIS_BUFFER_DIR", "/tmp/aegis-buffers")

    val store = BufferStore(bufferDir)
    val orchestrator = new FlushOrchestrator(store)

    val server = ServerBuilder
      .forPort(port)
      .addService(TelemetryServiceGrpc.bindService(new TelemetryServiceImpl, ExecutionContext.global))
      .build()
      .start()

    println(s"Aegis Central Cluster (Brain) listening on port $port")
    println(s"Buffer store: $bufferDir")
    println("Anomaly event bus + flush orchestrator active")

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = {
        println("Aegis Central Cluster shutting down...")
        orchestrator.close()
        server.shutdown()
      }
    }))

    server.awaitTermination()
  }
}
