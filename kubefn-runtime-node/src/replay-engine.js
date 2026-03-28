/**
 * Replay engine + structural diff + promotion gate for Node.js runtime.
 *
 * Same semantics as JVM ReplayExecutor + PromotionGate.
 */

// ── Structural Diff ──────────────────────────────────────────────

export function structuralDiff(original, replayed, path = '$') {
  const diffs = [];

  if (typeof original !== typeof replayed) {
    diffs.push({ path, type: 'TYPE_MISMATCH', original: typeof original, replayed: typeof replayed });
    return diffs;
  }

  if (original === null || replayed === null) {
    if (original !== replayed) {
      diffs.push({ path, type: 'VALUE_CHANGED', original, replayed });
    }
    return diffs;
  }

  if (Array.isArray(original) && Array.isArray(replayed)) {
    if (original.length !== replayed.length) {
      diffs.push({ path, type: 'LENGTH_MISMATCH', original: original.length, replayed: replayed.length });
    }
    const len = Math.min(original.length, replayed.length);
    for (let i = 0; i < len; i++) {
      diffs.push(...structuralDiff(original[i], replayed[i], `${path}[${i}]`));
    }
    return diffs;
  }

  if (typeof original === 'object') {
    const allKeys = new Set([...Object.keys(original), ...Object.keys(replayed)]);
    for (const key of [...allKeys].sort()) {
      const childPath = `${path}.${key}`;
      if (!(key in original)) {
        diffs.push({ path: childPath, type: 'ADDED', value: replayed[key] });
      } else if (!(key in replayed)) {
        diffs.push({ path: childPath, type: 'REMOVED', value: original[key] });
      } else {
        diffs.push(...structuralDiff(original[key], replayed[key], childPath));
      }
    }
    return diffs;
  }

  if (original !== replayed) {
    diffs.push({ path, type: 'VALUE_CHANGED', original, replayed });
  }

  return diffs;
}

// ── Replay Executor ──────────────────────────────────────────────

export class ReplayExecutor {
  constructor(loader, heap) {
    this.loader = loader;
    this.heap = heap;
  }

  async replaySingle(capture) {
    const result = {
      invocationId: capture.invocationId,
      function: capture.functionName,
      originalDurationMs: capture.durationNs / 1_000_000,
      replayDurationMs: 0,
      outputMatch: false,
      diffCount: 0,
      diffs: [],
      error: null,
    };

    if (!capture.inputSnapshot) {
      result.error = 'No input snapshot (REFERENCE-level capture)';
      return result;
    }

    // Find function handler
    const resolved = this.loader.resolve('POST', capture.httpPath);
    if (!resolved) {
      result.error = `Function '${capture.functionName}' not found`;
      return result;
    }

    const { fn } = resolved;
    const inputText = capture.inputSnapshot.toString('utf-8');

    const request = {
      method: capture.httpMethod,
      path: capture.httpPath,
      queryParams: {},
      headers: {},
      body: capture.inputSnapshot,
      bodyText: inputText,
      requestId: `replay-${capture.invocationId}`,
    };

    const ctx = {
      heap: this.heap,
      groupName: capture.groupName,
      functionName: capture.functionName,
      revisionId: 'replay',
      requestId: request.requestId,
      config: {},
      logger: { info: () => {}, warn: () => {}, error: () => {} },
    };

    // Execute
    const startNs = process.hrtime.bigint();
    let replayedOutput;
    try {
      replayedOutput = await fn.handler(request, ctx);
      result.replayDurationMs = Number(process.hrtime.bigint() - startNs) / 1_000_000;
    } catch (e) {
      result.replayDurationMs = Number(process.hrtime.bigint() - startNs) / 1_000_000;
      result.error = `Replay failed: ${e.message}`;
      return result;
    }

    // Compare outputs
    if (capture.outputSnapshot) {
      try {
        const original = JSON.parse(capture.outputSnapshot.toString('utf-8'));
        const replayed = typeof replayedOutput === 'object' ? replayedOutput : { result: replayedOutput };
        delete original._meta;
        delete replayed._meta;

        result.diffs = structuralDiff(original, replayed);
        result.diffCount = result.diffs.length;
        result.outputMatch = result.diffs.length === 0;
      } catch (e) {
        result.error = `Comparison failed: ${e.message}`;
      }
    } else {
      result.outputMatch = true;
    }

    return result;
  }

  async replayBatch(captures) {
    const batch = { total: 0, passed: 0, failed: 0, diverged: 0, errors: 0, results: [] };

    for (const capture of captures) {
      const r = await this.replaySingle(capture);
      batch.results.push(r);
      batch.total++;

      if (r.error) batch.errors++;
      else if (r.outputMatch) batch.passed++;
      else batch.diverged++;

      if (!capture.success) batch.failed++;
    }

    return batch;
  }
}

// ── Promotion Gate ───────────────────────────────────────────────

export class PromotionGate {
  constructor(captureStore, loader, heap) {
    this.captureStore = captureStore;
    this.loader = loader;
    this.heap = heap;
    this.mode = 'ADVISORY';
    this.minCapturesRequired = 5;
    this.maxFailureRate = 0.0;
    this.maxDivergenceRate = 0.05;
    this.maxLatencyIncrease = 0.20;
  }

  async validate(functionName, newRevisionId) {
    const captures = this.captureStore._values
      .filter(c => c.functionName === functionName && c.level === 'VALUE' && c.inputSnapshot)
      .slice(0, 200);

    if (captures.length < this.minCapturesRequired) {
      return {
        decision: 'PROMOTE',
        summary: `Insufficient captures (${captures.length} < ${this.minCapturesRequired}). Allowing.`,
        replay: null,
      };
    }

    const executor = new ReplayExecutor(this.loader, this.heap);
    const batch = await executor.replayBatch(captures);

    const violations = [];

    const failureRate = batch.total > 0 ? batch.failed / batch.total : 0;
    if (failureRate > this.maxFailureRate) {
      violations.push(`Failure rate ${(failureRate * 100).toFixed(1)}% exceeds ${(this.maxFailureRate * 100).toFixed(1)}%`);
    }

    const divergenceRate = batch.total > 0 ? batch.diverged / batch.total : 0;
    if (divergenceRate > this.maxDivergenceRate) {
      violations.push(`Divergence ${(divergenceRate * 100).toFixed(1)}% exceeds ${(this.maxDivergenceRate * 100).toFixed(1)}%`);
    }

    if (batch.errors > 0) {
      violations.push(`${batch.errors} replay errors`);
    }

    let decision, summary;
    if (violations.length === 0) {
      decision = 'PROMOTE';
      summary = `SAFE: ${batch.passed}/${batch.total} passed`;
    } else if (this.mode === 'ADVISORY') {
      decision = 'WARN';
      summary = `WARNING: ${violations.join('; ')}`;
    } else {
      decision = 'BLOCK';
      summary = `BLOCKED: ${violations.join('; ')}`;
    }

    console.log(`PromotionGate: ${decision} for ${functionName} — ${summary}`);

    return { decision, summary, replay: batch };
  }

  status() {
    return {
      mode: this.mode,
      minCapturesRequired: this.minCapturesRequired,
      maxFailureRate: `${(this.maxFailureRate * 100).toFixed(1)}%`,
      maxDivergenceRate: `${(this.maxDivergenceRate * 100).toFixed(1)}%`,
      maxLatencyIncrease: `${(this.maxLatencyIncrease * 100).toFixed(1)}%`,
    };
  }
}
