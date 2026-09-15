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

  /** Lists every persisted window file (all agents), oldest first. */
  def listAll(): Seq[Path] =
    Files.list(rootDir).iterator().asScala.toSeq
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json"))
      .sortBy(_.getFileName.toString)

  /** Reads one window file back: (agent_id, window_start_ns, entries).
    * Filename layout is `<agentId>_<startNs>_<endNs>.json` (agent ids may
    * contain underscores, hence the right-anchored split).
    */
  def readWindow(path: Path): (String, Long, Seq[TelemetryRequest]) = {
    val name = path.getFileName.toString.stripSuffix(".json")
    val parts = name.split("_")
    if (parts.length < 3)
      throw new IllegalArgumentException(s"malformed window filename: $name")
    val endNs = parts.last.toLong
    val startNs = parts.init.last.toLong
    val agentId = parts.dropRight(2).mkString("_")

    val parser = JsonFormat.parser()
    val body = Files.readString(path)
    val inner = body.stripPrefix("[").stripSuffix("]").trim
    val entries =
      if (inner.isEmpty) Seq.empty
      else
        inner.split(",\\n").toSeq.map(_.trim).filter(_.nonEmpty).map { json =>
          // Symmetric with save: JSON -> DynamicMessage (via the Java proto
          // descriptor) -> bytes -> ScalaPB case class.
          val dm = DynamicMessage.newBuilder(TelemetryRequest.javaDescriptor)
          parser.merge(json, dm)
          TelemetryRequest.parseFrom(dm.build().toByteArray)
        }

    (agentId, startNs, entries)
  }

  def count(agentId: String): Int = list(agentId).size
}

object BufferStore {
  def apply(dir: String): BufferStore = new BufferStore(Paths.get(dir))
}
