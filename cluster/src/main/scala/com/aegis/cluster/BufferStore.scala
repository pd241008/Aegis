package com.aegis.cluster

import com.aegis.telemetry.v1.telemetry.TelemetryRequest
import com.google.protobuf.DynamicMessage
import com.google.protobuf.util.JsonFormat

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Persistent store for flushed high-fidelity windows.
  *
  * Sub-phase 4A.3: raw windows are persisted keyed by
  * `(agent_id, window_start_ns)` for replay and audit. Each window is
  * written as a JSON array of protobuf messages (human-auditable) to a
  * flat-file directory. A production deployment would use object storage.
  */
final class BufferStore(rootDir: Path) {

  Files.createDirectories(rootDir)

  /** Writes a window and returns the file path it was saved to. */
  def save(agentId: String, startNs: Long, endNs: Long, entries: Seq[TelemetryRequest]): Path = {
    val filename = f"$agentId%s_$startNs%016d_$endNs%016d.json"
    val target = rootDir.resolve(filename)

    val printer = JsonFormat.printer()
    val body = entries
      .map(e => printer.print(DynamicMessage.parseFrom(TelemetryRequest.javaDescriptor, e.toByteArray)))
      .mkString("[", ",\n", "]")

    Files.writeString(target, body)
    target
  }

  /** Lists all persisted window files for an agent. */
  def list(agentId: String): Seq[Path] = {
    val prefix = s"${agentId}_"
    Files.list(rootDir).iterator().asScala.toSeq.filter(_.getFileName.toString.startsWith(prefix))
  }

  def count(agentId: String): Int = list(agentId).size
}

object BufferStore {
  def apply(dir: String): BufferStore = new BufferStore(Paths.get(dir))
}
