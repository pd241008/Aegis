package com.aegis.cluster

import java.time.Duration

/** Environment-driven selection of the [[Llm]] implementation (4C.2).
  *
  *   - `AEGIS_LLM_PROVIDER=openai` (or unset with a key present) activates
  *     the API-backed `OpenAiCompatLlm` against any OpenAI-compatible
  *     chat-completions endpoint.
  *   - `AEGIS_LLM_PROVIDER=none` (or no API key) selects the deterministic
  *     `RuleBasedLlm`, which is the CI and offline default.
  *
  * The API implementation is always wrapped in [[FaultTolerantLlm]]: on any
  * failure it logs and degrades to the rule-based briefing. A degraded
  * briefing beats no briefing.
  */
object LlmFactory {

  /** Wraps a primary [[Llm]] with deterministic fallback. */
  final class FaultTolerantLlm(primary: Llm, fallback: Llm) extends Llm {
    override def brief(prompt: BriefingPrompt): String =
      try primary.brief(prompt)
      catch
        case e: Throwable =>
          if isFatal(e) then throw e
          val reason = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          System.err.println(
            s"[LlmFactory] API LLM unavailable ($reason); falling back to rule-based briefing"
          )
          fallback.brief(prompt)

    private def isFatal(e: Throwable): Boolean =
      e.isInstanceOf[OutOfMemoryError] || e.isInstanceOf[StackOverflowError]
  }

  def fromEnv(): Llm = {
    val provider = sys.env.getOrElse("AEGIS_LLM_PROVIDER", "").trim
    val apiKey   = sys.env.getOrElse("AEGIS_LLM_API_KEY", "").trim

    val enabled =
      if provider.equalsIgnoreCase("none") then false
      else if provider.isEmpty then apiKey.nonEmpty
      else provider.equalsIgnoreCase("openai")

    if enabled && apiKey.nonEmpty then
      val model = envOr("AEGIS_LLM_MODEL", "gpt-4o-mini")
      val baseUrl = envOr("AEGIS_LLM_BASE_URL", "https://api.openai.com/v1/chat/completions")
      val timeoutMs =
        sys.env.get("AEGIS_LLM_TIMEOUT_MS").flatMap(_.toLongOption).getOrElse(30000L)
      val primary = new OpenAiCompatLlm(apiKey, model, baseUrl, Duration.ofMillis(timeoutMs))
      println(s"[LlmFactory] API-backed briefings enabled (model=$model, endpoint=$baseUrl)")
      new FaultTolerantLlm(primary, new RuleBasedLlm())
    else
      println(
        "[LlmFactory] rule-based briefings active (set AEGIS_LLM_API_KEY to enable API-backed briefings)"
      )
      new RuleBasedLlm()
  }

  /** Reads an env var, treating blank (e.g. compose passthrough `""`) as
    * unset so defaults always win.
    */
  private def envOr(key: String, default: String): String = {
    val v = sys.env.getOrElse(key, "").trim
    if v.isEmpty then default else v
  }
}
