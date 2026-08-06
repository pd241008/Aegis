package com.aegis.cluster

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Global registry of connected sentinels, keyed by agent ID. */
object StateManager {
  private val sentinels = new ConcurrentHashMap[String, SentinelState]()

  /** Returns the state for an agent, creating it on first contact. */
  def sentinelFor(agentId: String): SentinelState =
    sentinels.computeIfAbsent(agentId, _ => new SentinelState(agentId))

  /** Returns the state for an agent if it has been seen. */
  def get(agentId: String): Option[SentinelState] = Option(sentinels.get(agentId))

  def all: Seq[SentinelState] = sentinels.values().asScala.toSeq

  def size: Int = sentinels.size()
}
