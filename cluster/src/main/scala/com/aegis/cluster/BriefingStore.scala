package com.aegis.cluster

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Persistent store for generated briefings (4C.3).
  *
  * Each briefing is written as a markdown file plus a JSON metadata
  * sidecar, keyed by `(agent_id, anomaly_timestamp_ns, version)` so
  * briefings can be re-generated and versioned against the same anomaly.
  */
final class BriefingStore(rootDir: Path) {

  Files.createDirectories(rootDir)

  /** Persists a briefing (markdown + JSON metadata) and returns its path. */
  def save(b: Briefing): Path = {
    val name = s"${b.agentId}_${b.timestampNs}_v${b.version}"
    Files.writeString(rootDir.resolve(s"$name.md"), b.withHeader)
    Files.writeString(rootDir.resolve(s"$name.json"), metadataJson(b))
    rootDir.resolve(s"$name.md")
  }

  /** Returns metadata paths for all briefings of an agent, oldest first. */
  def list(agentId: String): Seq[Path] = {
    val prefix = s"${agentId}_"
    Files.list(rootDir).iterator().asScala.toSeq
      .map(_.getFileName.toString)
      .filter(n => n.startsWith(prefix) && n.endsWith(".json"))
      .sortBy(identity)
      .map(rootDir.resolve)
  }

  /** Returns the newest briefing markdown for an agent, if any. */
  def latest(agentId: String): Option[Path] = {
    val ts = list(agentId).map { p =>
      val n = p.getFileName.toString.stripSuffix(".json")
      n.split("_").lastOption.flatMap(_.stripPrefix("v").toIntOption).getOrElse(0) -> n
    }
    ts.sortBy(_._1).lastOption.map { case (_, name) => rootDir.resolve(s"$name.md") }
  }

  /** Next version for a given anomaly (1-based, regenerations increment). */
  def nextVersion(agentId: String, timestampNs: Long): Int = {
    val prefix = s"${agentId}_${timestampNs}_v"
    Files.list(rootDir).iterator().asScala.toSeq
      .map(_.getFileName.toString)
      .filter(_.startsWith(prefix))
      .flatMap(_.stripPrefix(prefix).stripSuffix(".md").toIntOption)
      .maxOption
      .getOrElse(0) + 1
  }

  private def metadataJson(b: Briefing): String = {
    def e(s: String) = SearchHit.escapeJson(s)
    s"""{"agentId":"${e(b.agentId)}","anomalyType":"${e(b.anomalyType)}","severity":"${e(b.severity)}","score":${b.score},"timestampNs":${b.timestampNs},"windowStartNs":${b.windowStartNs},"version":${b.version},"createdAtNs":${b.createdAtNs}}"""
  }
}

object BriefingStore {
  def apply(dir: String): BriefingStore = new BriefingStore(Paths.get(dir))
}
