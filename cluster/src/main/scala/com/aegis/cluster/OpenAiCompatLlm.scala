package com.aegis.cluster

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** API-backed [[Llm]] speaking the OpenAI chat-completions wire format.
  *
  * Works with any OpenAI-compatible endpoint: OpenAI, Ollama (`/v1`), vLLM,
  * Groq, Together, OpenRouter, LM Studio. Selected by [[LlmFactory]] from:
  *
  *   - `AEGIS_LLM_PROVIDER`   = `openai` (default when a key is present) | `none`
  *   - `AEGIS_LLM_API_KEY`    = bearer token
  *   - `AEGIS_LLM_MODEL`      = model id (default `gpt-4o-mini`)
  *   - `AEGIS_LLM_BASE_URL`   = chat-completions URL
  *   - `AEGIS_LLM_TIMEOUT_MS` = request timeout (default 30000)
  *
  * Generation is deliberately constrained (temperature 0, hard completion
  * cap) so briefings stay terse, factual and bounded — an SRE artifact,
  * not a chat.
  *
  * Any transport failure, non-2xx, malformed body or empty completion
  * throws [[LlmUnavailableException]], which the factory wraps into the
  * deterministic rule-based briefing: a degraded briefing beats none.
  */
final class OpenAiCompatLlm(
    apiKey: String,
    model: String,
    baseUrl: String,
    timeout: Duration
) extends Llm {

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  override def brief(prompt: BriefingPrompt): String = {
    val system = JsonObject()
    system.addProperty("role", "system")
    system.addProperty(
      "content",
      """You are an SRE diagnostic assistant for the Aegis telemetry platform.
        |Answer only from the telemetry provided; do not speculate beyond it.
        |Be concise and factual; markdown headings only, no preamble.""".stripMargin
    )
    val user = JsonObject()
    user.addProperty("role", "user")
    user.addProperty("content", prompt.text)
    val messages = JsonArray()
    messages.add(system)
    messages.add(user)

    val body = JsonObject()
    body.addProperty("model", model)
    body.addProperty("temperature", Integer.valueOf(0))
    body.addProperty("max_tokens", Integer.valueOf(700))
    body.add("messages", messages)

    val request = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl))
      .timeout(timeout)
      .header("Authorization", s"Bearer $apiKey")
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString))
      .build()

    val response =
      try http.send(request, HttpResponse.BodyHandlers.ofString())
      catch case e: Exception =>
        throw LlmUnavailableException(s"transport error: ${e.getMessage}", e)

    if response.statusCode() < 200 || response.statusCode() >= 300 then
      throw LlmUnavailableException(
        s"LLM API returned HTTP ${response.statusCode()}: ${truncate(response.body())}"
      )

    val content =
      try
        JsonParser.parseString(response.body())
          .getAsJsonObject
          .getAsJsonArray("choices")
          .get(0).getAsJsonObject
          .getAsJsonObject("message")
          .get("content").getAsString
      catch case e: Exception =>
        throw LlmUnavailableException(s"malformed LLM response: ${e.getMessage}", e)

    val text = if content == null then "" else content.trim
    if text.isEmpty then throw LlmUnavailableException("LLM API returned an empty completion")
    text
  }

  private def truncate(s: String, max: Int = 300): String =
    if s == null then "null"
    else if s.length <= max then s
    else s.take(max) + "..."
}

/** Signals the API-backed LLM could not produce a briefing; callers fall
  * back to the deterministic rule-based implementation.
  */
final case class LlmUnavailableException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
