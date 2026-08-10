package com.aegis.cluster

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Persistent store for correlated multi-agent incidents (4D.2).
  *
  * Incident metadata is written as JSON; the aggregated incident briefing
  * (4D.3) is written alongside as markdown, keyed by
  * `(event_type, start_ns, end_ns)`.
  */
final class IncidentStore(rootDir: Path) {

  Files.createDirectories(rootDir)

  def save(inc: Incident): Path = {
    val name = filename(inc)
    Files.writeString(rootDir.resolve(s"$name.json"), metadataJson(inc))
    rootDir.resolve(s"$name.json")
  }

  def saveBriefing(inc: Incident, markdown: String): Path = {
    val name = filename(inc)
    Files.writeString(rootDir.resolve(s"$name.md"), markdown)
    rootDir.resolve(s"$name.md")
  }

  /** Incident metadata paths, oldest first. */
  def list: Seq[Path] =
    Files.list(rootDir).iterator().asScala.toSeq
      .map(_.getFileName.toString)
      .filter(_.endsWith(".json"))
      .sortBy(startNsOf)
      .map(rootDir.resolve)

  def latest: Option[Path] =
    list.lastOption.map(p => rootDir.resolve(p.getFileName.toString.stripSuffix(".json") + ".md"))

  private def filename(inc: Incident): String = s"${inc.eventType}_${inc.startNs}_${inc.endNs}"

  private def startNsOf(name: String): Long =
    name.stripSuffix(".json").split("_").lift(1).flatMap(_.toLongOption).getOrElse(0L)

  private def metadataJson(inc: Incident): String = {
    def e(s: String) = SearchHit.escapeJson(s)
    s"""{"id":"${e(inc.id)}","eventType":"${e(inc.eventType)}","startNs":${inc.startNs},"endNs":${inc.endNs},"agents":[${inc.agents.map(a => s""""${e(a)}"""").mkString(",")}],"maxSeverity":"${e(inc.maxSeverity)}","createdAtNs":${inc.createdAtNs}}"""
  }
}

object IncidentStore {
  def apply(dir: String): IncidentStore = new IncidentStore(Paths.get(dir))
}
