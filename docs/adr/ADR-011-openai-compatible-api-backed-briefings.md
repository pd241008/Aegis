# 📜 ADR-011: OpenAI-Compatible API-Backed Briefings with Deterministic Fallback

> **Pattern:** adapter over an open wire standard + graceful degradation to
> the zero-dependency default. Implemented September 2026 (this ADR is
> *not* a retroactive backfill — the code and the decision land together).

> **Status:** `Decided`
> **Date:** `September 2026`

---

## 🌎 Context

[ADR-008](./ADR-008-rag-briefing-deterministic-defaults.md) shipped the full
RAG briefing pipeline (anomaly → window snapshot → retrieval → prompt →
generate → persist → notify) with `RuleBasedLlm` as a deterministic
placeholder, explicitly deferring the "real model" upgrade. The deferral
was right for pipeline validation, but briefings remained template prose —
the weakest link in the product's headline feature. Constraints for the
upgrade:

- The brain is plain Scala 3 + JDK — no HTTP framework on the classpath,
  and none wanted for one synchronous, low-QPS call.
- CI and offline dev must stay hermetic: no keys, no network, no mocks.
- A briefing pipeline outage must never take anomaly processing down.
- Vendor lock-in is unacceptable for an observability tool.

## 🛤️ Options Considered

1. **Vendor SDK** (official OpenAI or Anthropic Java SDK) — batteries
   included, but pins one vendor's shapes, drags transitive dependencies
   into an assembly jar that currently stays lean, and still can't talk to
   a local Ollama without a second adapter.
2. **OpenAI-compatible adapter over the JDK `HttpClient`** — the
   `/v1/chat/completions` wire format is a de-facto standard spoken by
   OpenAI, Ollama, vLLM, Groq, Together, OpenRouter and LM Studio; ~120
   lines of gson + `java.net.http` with zero new transitive weight (gson
   is already a protobuf-java-util neighbour in the dependency graph).
3. **Stay rule-based** — honest, but leaves the roadmap gap open.

---

## 🎯 Decision

> [!IMPORTANT]
> **Implement `OpenAiCompatLlm` against the OpenAI chat-completions wire
> format using the JDK `HttpClient` + gson, selected by `LlmFactory` from
> env vars (`AEGIS_LLM_*`), always wrapped in `FaultTolerantLlm` which
> degrades to `RuleBasedLlm` on any failure. Blank/unset env ⇒ rule-based,
> which keeps CI and offline dev hermetic by default.**

## 🧠 Reasoning

The wire format is the interface, not the vendor: one adapter covers every
current and future OpenAI-compatible endpoint, including a local Ollama —
which matters because telemetry is exactly the data you don't want to send
to a hosted API by default. Constraining generation (temperature 0, hard
completion cap, a system prompt that forbids speculation) keeps briefings
terse and reviewable. The fallback wrapper encodes the product stance that
*a degraded briefing beats no briefing*: transport errors, non-2xx,
malformed JSON and empty completions all collapse into the deterministic
path with a log line, while fatal JVM errors still propagate.

> [!NOTE]
> **Embeddings are untouched.** Retrieval still runs on `HashEmbedder`
> (ADR-008). Upgrading the embedder is a separate decision — this ADR
> deliberately changes one stage of the pipeline.

## ⚖️ Consequences

- **Good:** 🟢 Briefings are model-generated analysis, not templates; works
  with local models (no data egress) or hosted ones; one env var set
  upgrades, one env var (`AEGIS_LLM_PROVIDER=none`) downgrades; LLM outage
  degrades to rule-based briefings instead of erroring the pipeline.
- **Bad:** 🔴 One synchronous HTTP call now sits in the briefing path
  (bounded by `AEGIS_LLM_TIMEOUT_MS`, default 30s); prompt quality is
  prompt-engineering-dependent; cost/quota tracking is the operator's
  problem; the wrapper can mask a permanently-broken API key as a stream
  of fallback log lines rather than a hard failure.

## 🔄 Revisit When

Streaming (SSE) becomes necessary for long briefings, structured output
(tools/JSON mode) beats free-text markdown for downstream automation, or
the embedding stage gets its own upgrade (which would likely deserve a
companion `OpenAiCompatEmbedder` under the same factory pattern).
