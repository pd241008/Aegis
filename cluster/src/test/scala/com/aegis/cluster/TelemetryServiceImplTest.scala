package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.*
import io.grpc.{ManagedChannel, ManagedChannelBuilder, Server, ServerBuilder}
import io.grpc.stub.StreamObserver

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.ExecutionContext

/** End-to-end gRPC test: real TelemetryServiceImpl behind an in-process
  * server, exercised through the generated client stubs over TCP.
  */
class TelemetryServiceImplTest extends munit.FunSuite {

  private var server: Server = _
  private var channel: ManagedChannel = _

  override def beforeAll(): Unit = {
    server = ServerBuilder
      .forPort(0)
      .addService(TelemetryServiceGrpc.bindService(new TelemetryServiceImpl, ExecutionContext.global))
      .build()
      .start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort).usePlaintext().build()
  }

  override def afterAll(): Unit = {
    channel.shutdownNow()
    server.shutdownNow()
  }

  private def metricRequest(agentId: String, tsNs: Long): TelemetryRequest =
    TelemetryRequest(
      agent = Some(AgentMetadata(agentId = agentId, timestampNs = tsNs)),
      payload = TelemetryRequest.Payload.Metric(
        MetricPayload(cpuUsagePercent = 10.0, memoryUsagePercent = 5.0)
      )
    )

  test("ingests a metric stream and registers sentinel state") {
    val agentId = s"sentinel-${System.nanoTime()}"
    val stream = TelemetryServiceGrpc.stub(channel).streamTelemetry(collecting[TelemetryResponse]())
    var ts = System.currentTimeMillis() * 1_000_000L
    (1 to 10).foreach { _ =>
      ts += 1_000_000L
      stream.onNext(metricRequest(agentId, ts))
    }
    stream.onCompleted()

    // onCompleted() is async; poll until the server has processed the stream.
    val deadline = System.currentTimeMillis() + 5000
    while (StateManager.get(agentId).isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assert(StateManager.get(agentId).isDefined, "sentinel state should be registered")
  }

  test("sends SLOW_DOWN when the ingress rate exceeds the threshold") {
    val agentId = s"sentinel-${System.nanoTime()}"
    val responses = new ConcurrentLinkedQueue[TelemetryResponse]()
    val stream = TelemetryServiceGrpc.stub(channel).streamTelemetry(collecting(responses))
    var ts = System.currentTimeMillis() * 1_000_000L
    (1 to 600).foreach { _ =>
      ts += 1_000L
      stream.onNext(metricRequest(agentId, ts))
    }
    stream.onCompleted()

    val deadline = System.currentTimeMillis() + 5000
    while (responses.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assert(
      responses.stream().anyMatch(_.action == TelemetryResponse.Action.SLOW_DOWN),
      "expected a SLOW_DOWN action under sustained high rate"
    )
  }

  test("flush returns the recorded window for a known agent") {
    val agentId = s"sentinel-${System.nanoTime()}"
    val ingest = TelemetryServiceGrpc.stub(channel).streamTelemetry(collecting[TelemetryResponse]())
    var ts = System.currentTimeMillis() * 1_000_000L
    (1 to 5).foreach { _ =>
      ts += 1_000_000L
      ingest.onNext(metricRequest(agentId, ts))
    }
    ingest.onCompleted()

    // onCompleted() is async; wait until the server has ingested all 5
    // messages before flushing so the window is non-empty.
    val ingestDeadline = System.currentTimeMillis() + 5000
    while (
      !StateManager.get(agentId).exists(_.size >= 5) &&
      System.currentTimeMillis() < ingestDeadline
    ) Thread.sleep(10)
    assert(
      StateManager.get(agentId).exists(_.size >= 5),
      "ingested messages should be recorded before flush"
    )

    val chunks = new ConcurrentLinkedQueue[FlushChunk]()
    TelemetryServiceGrpc
      .stub(channel)
      .flushBuffer(FlushRequest(agentId = agentId, startTimeNs = 0L, endTimeNs = 0L), collecting(chunks))

    val deadline = System.currentTimeMillis() + 5000
    while (chunks.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assert(chunks.size() >= 1, "expected at least one flush chunk")
    val last = chunks.stream().filter(_.isLast).findFirst()
    assert(last.isPresent, "flush must terminate with a final chunk")
    assert(last.get().totalSize > 0L, "flush should contain the recorded entries")
  }

  test("flush returns an empty chunk for an unknown agent") {
    val chunks = new ConcurrentLinkedQueue[FlushChunk]()
    TelemetryServiceGrpc
      .stub(channel)
      .flushBuffer(FlushRequest(agentId = "ghost-agent", startTimeNs = 0L, endTimeNs = 0L), collecting(chunks))

    val deadline = System.currentTimeMillis() + 5000
    while (chunks.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assert(chunks.size() >= 1)
    assertEquals(chunks.stream().filter(_.isLast).findFirst().get().totalSize, 0L)
  }

  private def collecting[A](queue: ConcurrentLinkedQueue[A]): StreamObserver[A] = new StreamObserver[A] {
    override def onNext(value: A): Unit = queue.add(value)
    override def onError(t: Throwable): Unit = ()
    override def onCompleted(): Unit = ()
  }

  private def collecting[A](): StreamObserver[A] = collecting[A](new ConcurrentLinkedQueue[A]())
}
