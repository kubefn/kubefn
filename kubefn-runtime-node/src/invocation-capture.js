/**
 * Invocation capture — records function inputs/outputs for replay.
 *
 * Compatible with JVM InvocationCapture format. Two tiers:
 *   REFERENCE: always-on, captures heap keys + content hashes
 *   VALUE:     triggered on error/anomaly, captures full request/response bodies
 */

import { createHash } from 'node:crypto';

export class InvocationCapture {
  constructor(invocationId, functionName, groupName) {
    this.invocationId = invocationId;
    this.functionName = functionName;
    this.groupName = groupName;
    this.revisionId = '';
    this.timestamp = Date.now();
    this.httpMethod = '';
    this.httpPath = '';
    this.httpStatus = 200;
    this.durationNs = 0;
    this.success = true;
    this.errorMessage = null;
    this.heapReads = [];
    this.heapWrites = [];
    this.level = 'REFERENCE';
    this.inputSnapshot = null;
    this.outputSnapshot = null;
  }

  recordHeapRead(key, typeClass, value) {
    const content = value != null ? JSON.stringify(value) : '';
    const hash = createHash('sha256').update(content).digest('hex').slice(0, 16);
    this.heapReads.push({ key, type: typeClass, hash, bytes: Buffer.byteLength(content) });
  }

  recordHeapWrite(key, typeClass, value) {
    const content = value != null ? JSON.stringify(value) : '';
    const hash = createHash('sha256').update(content).digest('hex').slice(0, 16);
    this.heapWrites.push({ key, type: typeClass, hash, bytes: Buffer.byteLength(content) });
  }

  setValue(inputBytes, outputBytes = null) {
    this.level = 'VALUE';
    this.inputSnapshot = inputBytes;
    this.outputSnapshot = outputBytes;
  }

  estimatedBytes() {
    let base = 256;
    base += this.heapReads.length * 128;
    base += this.heapWrites.length * 128;
    if (this.inputSnapshot) base += this.inputSnapshot.length;
    if (this.outputSnapshot) base += this.outputSnapshot.length;
    return base;
  }

  toJSON() {
    return {
      invocationId: this.invocationId,
      function: this.functionName,
      group: this.groupName,
      revision: this.revisionId,
      timestamp: this.timestamp,
      method: this.httpMethod,
      path: this.httpPath,
      status: this.httpStatus,
      durationMs: this.durationNs / 1_000_000,
      success: this.success,
      error: this.errorMessage,
      captureLevel: this.level,
      heapReads: this.heapReads,
      heapWrites: this.heapWrites,
      hasInputSnapshot: this.inputSnapshot !== null,
      hasOutputSnapshot: this.outputSnapshot !== null,
      estimatedBytes: this.estimatedBytes(),
    };
  }
}

export class InvocationCaptureStore {
  constructor(maxReferences = 10_000, maxValues = 1_000) {
    this._references = [];
    this._values = [];
    this._maxReferences = maxReferences;
    this._maxValues = maxValues;
    this.totalCaptured = 0;
    this.totalValueCaptures = 0;
  }

  add(capture) {
    this.totalCaptured++;
    if (capture.level === 'VALUE') {
      this._values.unshift(capture);
      this.totalValueCaptures++;
      if (this._values.length > this._maxValues) this._values.pop();
    } else {
      this._references.unshift(capture);
      if (this._references.length > this._maxReferences) this._references.pop();
    }
  }

  recent(limit = 20) { return this._references.slice(0, limit); }
  recentValues(limit = 20) { return this._values.slice(0, limit); }

  failures(limit = 20) {
    const all = [...this._values, ...this._references].filter(c => !c.success);
    return all.slice(0, limit);
  }

  findById(invocationId) {
    return this._values.find(c => c.invocationId === invocationId)
        || this._references.find(c => c.invocationId === invocationId)
        || null;
  }

  status() {
    const refBytes = this._references.reduce((s, c) => s + c.estimatedBytes(), 0);
    const valBytes = this._values.reduce((s, c) => s + c.estimatedBytes(), 0);
    return {
      referenceCaptures: this._references.length,
      valueCaptures: this._values.length,
      totalCaptured: this.totalCaptured,
      totalValueCaptures: this.totalValueCaptures,
      estimatedMemoryMB: (refBytes + valBytes) / (1024 * 1024),
    };
  }
}

export class CapturePolicy {
  constructor() {
    this.latencyThresholdNs = 100_000_000; // 100ms
    this.samplingRate = 1000;
    this.maxSnapshotBytes = 1024 * 1024;
    this.watchlist = new Set();
    this._counter = 0;
  }

  shouldCaptureValue(functionName, failed, durationNs, payloadBytes = 0) {
    if (payloadBytes > this.maxSnapshotBytes) return false;
    if (failed) return true;
    if (durationNs > this.latencyThresholdNs) return true;
    if (this.watchlist.has(functionName)) return true;
    this._counter++;
    return this._counter % this.samplingRate === 0;
  }

  status() {
    return {
      latencyThresholdMs: this.latencyThresholdNs / 1_000_000,
      samplingRate: `1:${this.samplingRate}`,
      maxSnapshotBytes: this.maxSnapshotBytes,
      watchlist: [...this.watchlist],
    };
  }
}
