"""
Replay engine + structural diff + promotion gate for Python runtime.

Replays captured invocations against current (or new) function code,
diffs outputs structurally, and gates hot-swap promotion.

Same semantics as JVM ReplayExecutor + PromotionGate.
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Optional, Any

from .invocation_capture import InvocationCapture, InvocationCaptureStore
from .decorators import get_registered_functions
from .context import FnContext, FnRequest
from .heap_exchange import HeapExchange

logger = logging.getLogger("kubefn.replay")


# ── Structural Diff ──────────────────────────────────────────────

def structural_diff(original: Any, replayed: Any, path: str = "$") -> list[dict]:
    """Compare two values structurally. Returns list of differences."""
    diffs = []

    if type(original) != type(replayed):
        diffs.append({
            "path": path,
            "type": "TYPE_MISMATCH",
            "original": type(original).__name__,
            "replayed": type(replayed).__name__,
        })
        return diffs

    if isinstance(original, dict):
        all_keys = set(original.keys()) | set(replayed.keys())
        for key in sorted(all_keys):
            child_path = f"{path}.{key}"
            if key not in original:
                diffs.append({"path": child_path, "type": "ADDED", "value": replayed[key]})
            elif key not in replayed:
                diffs.append({"path": child_path, "type": "REMOVED", "value": original[key]})
            else:
                diffs.extend(structural_diff(original[key], replayed[key], child_path))
    elif isinstance(original, (list, tuple)):
        if len(original) != len(replayed):
            diffs.append({
                "path": path,
                "type": "LENGTH_MISMATCH",
                "original": len(original),
                "replayed": len(replayed),
            })
        for i in range(min(len(original), len(replayed))):
            diffs.extend(structural_diff(original[i], replayed[i], f"{path}[{i}]"))
    elif original != replayed:
        diffs.append({
            "path": path,
            "type": "VALUE_CHANGED",
            "original": original,
            "replayed": replayed,
        })

    return diffs


# ── Replay Result ────────────────────────────────────────────────

@dataclass
class ReplayResult:
    invocation_id: str
    function_name: str
    original_duration_ns: int = 0
    replay_duration_ns: int = 0
    output_match: bool = False
    diffs: list[dict] = field(default_factory=list)
    error: Optional[str] = None
    original_output: Any = None
    replayed_output: Any = None

    def to_dict(self) -> dict:
        return {
            "invocationId": self.invocation_id,
            "function": self.function_name,
            "originalDurationMs": self.original_duration_ns / 1_000_000,
            "replayDurationMs": self.replay_duration_ns / 1_000_000,
            "outputMatch": self.output_match,
            "diffCount": len(self.diffs),
            "diffs": self.diffs[:20],  # cap at 20
            "error": self.error,
        }


@dataclass
class BatchReplayResult:
    total: int = 0
    passed: int = 0
    failed: int = 0
    diverged: int = 0
    errors: int = 0
    results: list[ReplayResult] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "total": self.total,
            "passed": self.passed,
            "failed": self.failed,
            "diverged": self.diverged,
            "errors": self.errors,
            "results": [r.to_dict() for r in self.results[:50]],
        }


# ── Replay Executor ──────────────────────────────────────────────

class ReplayExecutor:
    """Re-executes captured invocations against current function code."""

    def __init__(self, heap: HeapExchange):
        self.heap = heap

    def replay_single(self, capture: InvocationCapture) -> ReplayResult:
        """Replay a single captured invocation."""
        result = ReplayResult(
            invocation_id=capture.invocation_id,
            function_name=capture.function_name,
            original_duration_ns=capture.duration_ns,
        )

        if capture.input_snapshot is None:
            result.error = "No input snapshot available (REFERENCE-level capture)"
            return result

        # Find the function handler
        handler = None
        for fn in get_registered_functions():
            if fn.name == capture.function_name:
                handler = fn.handler
                break

        if handler is None:
            result.error = f"Function '{capture.function_name}' not found in registry"
            return result

        # Reconstruct request from captured input
        try:
            input_text = capture.input_snapshot.decode("utf-8") if capture.input_snapshot else ""
            request = FnRequest(
                method=capture.http_method,
                path=capture.http_path,
                headers={},
                query_params={},
                body=capture.input_snapshot or b"",
                body_text=input_text,
            )
            ctx = FnContext(
                heap=self.heap,
                group_name=capture.group_name,
                function_name=capture.function_name,
                revision_id="replay",
                config={},
            )
        except Exception as e:
            result.error = f"Failed to reconstruct request: {e}"
            return result

        # Execute
        try:
            start = time.time_ns()
            replayed_output = handler(request, ctx)
            result.replay_duration_ns = time.time_ns() - start
        except Exception as e:
            result.replay_duration_ns = time.time_ns() - start
            result.error = f"Replay execution failed: {e}"
            return result

        # Compare outputs
        if capture.output_snapshot is not None:
            try:
                original = json.loads(capture.output_snapshot)
                replayed = replayed_output if isinstance(replayed_output, dict) else {"result": replayed_output}
                # Strip _meta from both for comparison
                original.pop("_meta", None)
                replayed.pop("_meta", None)

                result.diffs = structural_diff(original, replayed)
                result.output_match = len(result.diffs) == 0
                result.original_output = original
                result.replayed_output = replayed
            except Exception as e:
                result.error = f"Output comparison failed: {e}"
        else:
            # No original output to compare — just check it didn't crash
            result.output_match = True

        return result

    def replay_batch(self, captures: list[InvocationCapture]) -> BatchReplayResult:
        """Replay a batch of captured invocations."""
        batch = BatchReplayResult()

        for capture in captures:
            r = self.replay_single(capture)
            batch.results.append(r)
            batch.total += 1

            if r.error:
                batch.errors += 1
            elif r.output_match:
                batch.passed += 1
            else:
                batch.diverged += 1

            if not capture.success:
                batch.failed += 1

        return batch


# ── Promotion Gate ───────────────────────────────────────────────

class PromotionGate:
    """Evidence-based hot-swap validation for Python functions."""

    def __init__(self, capture_store: InvocationCaptureStore, heap: HeapExchange):
        self.capture_store = capture_store
        self.heap = heap
        self.mode = "ADVISORY"  # "ADVISORY" or "ENFORCING"
        self.min_captures_required = 5
        self.max_failure_rate = 0.0
        self.max_divergence_rate = 0.05
        self.max_latency_increase = 0.20

    def validate(self, function_name: str, new_revision_id: str) -> dict:
        """Validate a function before promotion."""
        captures = [
            c for c in self.capture_store._values
            if c.function_name == function_name
            and c.level == "VALUE"
            and c.input_snapshot is not None
        ][:200]

        if len(captures) < self.min_captures_required:
            return {
                "decision": "PROMOTE",
                "summary": f"Insufficient captures ({len(captures)} < {self.min_captures_required}). Allowing.",
                "replay": None,
            }

        executor = ReplayExecutor(self.heap)
        batch = executor.replay_batch(captures)

        violations = []

        failure_rate = batch.failed / batch.total if batch.total > 0 else 0
        if failure_rate > self.max_failure_rate:
            violations.append(f"Failure rate {failure_rate:.1%} exceeds {self.max_failure_rate:.1%}")

        divergence_rate = batch.diverged / batch.total if batch.total > 0 else 0
        if divergence_rate > self.max_divergence_rate:
            violations.append(f"Divergence {divergence_rate:.1%} exceeds {self.max_divergence_rate:.1%}")

        if batch.errors > 0:
            violations.append(f"{batch.errors} replay errors")

        if not violations:
            decision = "PROMOTE"
            summary = f"SAFE: {batch.passed}/{batch.total} passed"
        elif self.mode == "ADVISORY":
            decision = "WARN"
            summary = f"WARNING: {'; '.join(violations)}"
        else:
            decision = "BLOCK"
            summary = f"BLOCKED: {'; '.join(violations)}"

        logger.info(f"PromotionGate: {decision} for {function_name} — {summary}")

        return {
            "decision": decision,
            "summary": summary,
            "replay": batch.to_dict(),
        }

    def status(self) -> dict:
        return {
            "mode": self.mode,
            "minCapturesRequired": self.min_captures_required,
            "maxFailureRate": f"{self.max_failure_rate:.1%}",
            "maxDivergenceRate": f"{self.max_divergence_rate:.1%}",
            "maxLatencyIncrease": f"{self.max_latency_increase:.1%}",
        }
