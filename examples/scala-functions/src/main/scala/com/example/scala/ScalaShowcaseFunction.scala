package com.example.scala

import com.kubefn.api._

import java.util.Optional
import scala.jdk.CollectionConverters._

/**
 * Orchestrator that invokes the stream processor and rule engine,
 * then assembles results from HeapExchange into a unified response.
 *
 * Demonstrates:
 *  - Function-to-function invocation via FnContext.getFunction
 *  - Zero-copy heap reads across Scala functions (and potentially
 *    across language boundaries with Kotlin/Java functions)
 *  - Timing metadata for observability
 */
@FnRoute(path = "/scala/demo", methods = Array("GET"))
@FnGroup("scala-showcase")
class ScalaShowcaseFunction extends KubeFnHandler with FnContextAware {

  private var ctx: FnContext = _

  override def setContext(context: FnContext): Unit = {
    ctx = context
  }

  // ── Timing helper ──────────────────────────────────────────────────

  case class StageResult(name: String, durationMs: Long, success: Boolean, detail: String)

  private def timed[T](name: String)(block: => T): (Option[T], StageResult) = {
    val start = System.nanoTime()
    try {
      val result = block
      val elapsed = (System.nanoTime() - start) / 1000000
      (Some(result), StageResult(name, elapsed, success = true, "ok"))
    } catch {
      case ex: Exception =>
        val elapsed = (System.nanoTime() - start) / 1000000
        (None, StageResult(name, elapsed, success = false, Option(ex.getMessage).getOrElse("unknown error")))
    }
  }

  // ── Synthetic request builder ──────────────────────────────────────

  private def syntheticRequest(body: String, httpMethod: String, httpPath: String): KubeFnRequest =
    new KubeFnRequest(httpMethod, httpPath, "",
      java.util.Map.of(), java.util.Map.of(),
      if (body != null && body.nonEmpty) body.getBytes("UTF-8") else Array.emptyByteArray)

  // ── Handler ────────────────────────────────────────────────────────

  override def handle(request: KubeFnRequest): KubeFnResponse = {
    val log = ctx.logger()
    val heap = ctx.heap()
    val orchestrationStart = System.currentTimeMillis()
    val stages = scala.collection.mutable.ListBuffer.empty[StageResult]

    log.info(s"Scala showcase orchestration starting — group=${ctx.groupName()}, revision=${ctx.revisionId()}")

    // ── Stage 1: Stream processing ───────────────────────────────────

    val sampleEvents =
      s"""[
        {"eventType":"info","source":"us-east-1","timestamp":${System.currentTimeMillis() - 4000},"payload":"metrics"},
        {"eventType":"error","source":"eu-west-1","timestamp":${System.currentTimeMillis() - 3000},"payload":"alert"},
        {"eventType":"warning","source":"ap-south-1","timestamp":${System.currentTimeMillis() - 2000},"payload":"notice"},
        {"eventType":"info","source":"us-west-2","timestamp":${System.currentTimeMillis() - 1000},"payload":"log"},
        {"eventType":"error","source":"eu-central-1","timestamp":${System.currentTimeMillis()},"payload":"critical"}
      ]"""

    val (streamResponse, streamStage) = timed("stream-processor") {
      val fn = ctx.getFunction(classOf[StreamProcessorFunction])
      fn.handle(syntheticRequest(sampleEvents, "POST", "/stream/process"))
    }
    stages += streamStage

    // ── Stage 2: Rule evaluation ─────────────────────────────────────

    val sampleRuleInputs =
      """[
        {"category":"premium","value":"8500","tags":"audit-required"},
        {"category":"restricted","value":"2000","tags":"internal-only"},
        {"category":"standard","value":"3200","tags":""},
        {"category":"standard","value":"15000","tags":"pii;high-traffic"}
      ]"""

    val (rulesResponse, rulesStage) = timed("rule-engine") {
      val fn = ctx.getFunction(classOf[RuleEngineFunction])
      fn.handle(syntheticRequest(sampleRuleInputs, "POST", "/rules/evaluate"))
    }
    stages += rulesStage

    // ── Read results from heap (zero-copy) ───────────────────────────

    val streamResult = heap.get("stream:processed", classOf[Object])
    val rulesResult = heap.get("rules:result", classOf[Object])

    val totalDurationMs = System.currentTimeMillis() - orchestrationStart

    log.info(s"Scala showcase completed in ${totalDurationMs}ms — ${stages.count(_.success)}/${stages.size} stages succeeded")

    // Build response as Map for proper JSON serialization
    val result = new java.util.LinkedHashMap[String, Object]()
    result.put("language", "Scala")
    result.put("group", ctx.groupName())
    result.put("revision", ctx.revisionId())
    result.put("streamResults", if (streamResult.isPresent) streamResult.get() else null)
    result.put("rulesResults", if (rulesResult.isPresent) rulesResult.get() else null)
    result.put("_meta", java.util.Map.of(
      "pipelineSteps", Integer.valueOf(stages.size),
      "totalTimeMs", s"${totalDurationMs}ms",
      "stages", stages.map(s => java.util.Map.of(
        "name", s.name, "durationMs", java.lang.Long.valueOf(s.durationMs),
        "success", java.lang.Boolean.valueOf(s.success)
      )).asJava,
      "zeroCopy", java.lang.Boolean.TRUE
    ))

    KubeFnResponse.ok(result)
  }
}
