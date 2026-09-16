package com.aegis.cluster

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

import scala.collection.mutable

class LlmTest extends munit.FunSuite {

  private val samplePrompt = BriefingPrompt(
    anomaly = AnomalyEventBus.AnomalyEvent(
      agentId = "sentinel-001",
      eventType = "CPU_SPIKE",
      description = "cpu utilisation 97% (z=4.1)",
      severity = "CRITICAL",
      score = 4.1,
      timestampNs = 1_000_000_000L
    ),
    windowText = Seq("12:00:01 sentinel-001 cpu=97%"),
    hits = Seq.empty,
    text = "Assembled diagnostic prompt for testing."
  )

  /** Builds a chat-completions response body with proper JSON escaping. */
  private def completionJson(content: String): String = {
    val message = JsonObject()
    message.addProperty("role", "assistant")
    message.addProperty("content", content)
    val choice = JsonObject()
    choice.addProperty("index", 0)
    choice.add("message", message)
    val choices = JsonArray()
    choices.add(choice)
    val root = JsonObject()
    root.add("choices", choices)
    root.toString
  }

  /** Boots a throwaway OpenAI-compatible endpoint for live round-trips. */
  private def withServer(status: Int, body: String)(f: String => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/chat/completions",
      (exchange: HttpExchange) => {
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if bytes.isEmpty then -1 else bytes.length.toLong)
        if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}/v1/chat/completions")
    finally server.stop(0)
  }

  test("parses a valid chat-completions response") {
    withServer(200, completionJson("## Root-Cause Hypothesis\nCPU spike.")) { url =>
      val llm = new OpenAiCompatLlm("test-key", "gpt-4o-mini", url, Duration.ofSeconds(5))
      assertEquals(llm.brief(samplePrompt), "## Root-Cause Hypothesis\nCPU spike.")
    }
  }

  test("sends model, temperature and prompt in the request body") {
    val seen = mutable.StringBuilder.newBuilder
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/chat/completions",
      (exchange: HttpExchange) => {
        seen.clear()
        seen.append(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val bytes = completionJson("ok").getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try {
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/v1/chat/completions"
      val llm = new OpenAiCompatLlm("test-key", "gpt-4o-mini", url, Duration.ofSeconds(5))
      llm.brief(samplePrompt)
      val body = seen.result()
      assert(body.contains("\"model\":\"gpt-4o-mini\""), s"body was: $body")
      assert(body.contains("\"temperature\":0"), s"body was: $body")
      assert(body.contains("Assembled diagnostic prompt"), s"body was: $body")
    } finally server.stop(0)
  }

  test("raises LlmUnavailableException on HTTP 500") {
    withServer(500, """{"error":{"message":"overloaded"}}""") { url =>
      val llm = new OpenAiCompatLlm("test-key", "gpt-4o-mini", url, Duration.ofSeconds(5))
      intercept[LlmUnavailableException](llm.brief(samplePrompt))
    }
  }

  test("raises LlmUnavailableException on malformed JSON") {
    withServer(200, "not json at all") { url =>
      val llm = new OpenAiCompatLlm("test-key", "gpt-4o-mini", url, Duration.ofSeconds(5))
      intercept[LlmUnavailableException](llm.brief(samplePrompt))
    }
  }

  test("raises LlmUnavailableException on empty choices") {
    withServer(200, """{"choices":[]}""") { url =>
      val llm = new OpenAiCompatLlm("test-key", "gpt-4o-mini", url, Duration.ofSeconds(5))
      intercept[LlmUnavailableException](llm.brief(samplePrompt))
    }
  }

  test("FaultTolerantLlm returns the primary result when healthy") {
    val stub = new Llm {
      override def brief(prompt: BriefingPrompt): String = "primary briefing"
    }
    val llm = new LlmFactory.FaultTolerantLlm(stub, new RuleBasedLlm())
    assertEquals(llm.brief(samplePrompt), "primary briefing")
  }

  test("FaultTolerantLlm degrades to the rule-based briefing on API failure") {
    val boom = new Llm {
      override def brief(prompt: BriefingPrompt): String =
        throw LlmUnavailableException("simulated outage")
    }
    val llm = new LlmFactory.FaultTolerantLlm(boom, new RuleBasedLlm())
    val briefing = llm.brief(samplePrompt)
    assert(briefing.contains("Root-Cause Hypothesis"), "expected deterministic fallback briefing")
  }

  test("FaultTolerantLlm degrades on any non-fatal primary fault") {
    val flaky = new Llm {
      override def brief(prompt: BriefingPrompt): String =
        throw new IllegalStateException("transient bug in primary")
    }
    val llm = new LlmFactory.FaultTolerantLlm(flaky, new RuleBasedLlm())
    val briefing = llm.brief(samplePrompt)
    assert(briefing.contains("Root-Cause Hypothesis"), "expected deterministic fallback briefing")
  }

  // Note: the isFatal guard (OOM/StackOverflowError propagate, bypassing
  // fallback) is intentionally not unit-tested — munit aborts any test that
  // throws a VirtualMachineError, even inside intercept.
}
