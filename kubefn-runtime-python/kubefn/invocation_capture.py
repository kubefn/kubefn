"""
Invocation capture — records function inputs/outputs for replay.

Compatible with JVM InvocationCapture format. Two tiers:
  REFERENCE: always-on, captures heap keys + content hashes (near-zero overhead)
  VALUE:     triggered on error/anomaly, captures full request/response bodies

Usage:
    store = InvocationCaptureStore()
    policy = CapturePolicy()

    # In request handler:
    capture = InvocationCapture(request_id, function_name, group_name)
    capture.record_heap_read(key, type_name, content_hash, size_bytes)
    ...
    if policy.should_capture_value(function_name, failed, duration_ns):
        capture.set_value(input_bytes, output_bytes)
    store.add(capture)
"""

from __future__ import annotations

import hashlib
import json
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Optional, Any


@dataclass
class HeapRef:
    """Reference to a heap key read or written during invocation."""
    key: str
    type_class: str
    content_hash: str
    size_bytes: int
    captured_at: float = field(default_factory=time.time)


@dataclass
class InvocationCapture:
    """Captured snapshot of a function invocation for replay."""

    invocation_id: str
    function_name: str
    group_name: str
    revision_id: str = ""
    timestamp: float = field(default_factory=time.time)
    http_method: str = ""
    http_path: str = ""
    http_status: int = 200
    duration_ns: int = 0
    success: bool = True
    error_message: Optional[str] = None

    # Tier 0: Causal references (always captured)
    heap_reads: list[HeapRef] = field(default_factory=list)
    heap_writes: list[HeapRef] = field(default_factory=list)

    # Tier 1: Value snapshots (captured on trigger)
    level: str = "REFERENCE"  # "REFERENCE" or "VALUE"
    input_snapshot: Optional[bytes] = None
    output_snapshot: Optional[bytes] = None
    input_format: str = "json"
    output_format: str = "json"

    def record_heap_read(self, key: str, type_class: str, value: Any):
        """Record a heap read with content hash."""
        content = json.dumps(value, default=str).encode() if value is not None else b""
        h = hashlib.sha256(content).hexdigest()[:16]
        self.heap_reads.append(HeapRef(key, type_class, h, len(content)))

    def record_heap_write(self, key: str, type_class: str, value: Any):
        """Record a heap write with content hash."""
        content = json.dumps(value, default=str).encode() if value is not None else b""
        h = hashlib.sha256(content).hexdigest()[:16]
        self.heap_writes.append(HeapRef(key, type_class, h, len(content)))

    def set_value(self, input_bytes: bytes, output_bytes: Optional[bytes] = None):
        """Escalate to VALUE capture with full input/output snapshots."""
        self.level = "VALUE"
        self.input_snapshot = input_bytes
        self.output_snapshot = output_bytes

    def estimated_bytes(self) -> int:
        base = 256
        base += len(self.heap_reads) * 128
        base += len(self.heap_writes) * 128
        if self.input_snapshot:
            base += len(self.input_snapshot)
        if self.output_snapshot:
            base += len(self.output_snapshot)
        return base

    def to_dict(self) -> dict:
        return {
            "invocationId": self.invocation_id,
            "function": self.function_name,
            "group": self.group_name,
            "revision": self.revision_id,
            "timestamp": self.timestamp,
            "method": self.http_method,
            "path": self.http_path,
            "status": self.http_status,
            "durationMs": self.duration_ns / 1_000_000,
            "success": self.success,
            "error": self.error_message,
            "captureLevel": self.level,
            "heapReads": [
                {"key": r.key, "type": r.type_class, "hash": r.content_hash, "bytes": r.size_bytes}
                for r in self.heap_reads
            ],
            "heapWrites": [
                {"key": r.key, "type": r.type_class, "hash": r.content_hash, "bytes": r.size_bytes}
                for r in self.heap_writes
            ],
            "hasInputSnapshot": self.input_snapshot is not None,
            "hasOutputSnapshot": self.output_snapshot is not None,
            "estimatedBytes": self.estimated_bytes(),
        }


class InvocationCaptureStore:
    """Ring buffer for invocation captures. Thread-safe via deque."""

    def __init__(self, max_references: int = 10_000, max_values: int = 1_000):
        self._references: deque[InvocationCapture] = deque(maxlen=max_references)
        self._values: deque[InvocationCapture] = deque(maxlen=max_values)
        self.total_captured = 0
        self.total_value_captures = 0

    def add(self, capture: InvocationCapture):
        self.total_captured += 1
        if capture.level == "VALUE":
            self._values.appendleft(capture)
            self.total_value_captures += 1
        else:
            self._references.appendleft(capture)

    def recent(self, limit: int = 20) -> list[InvocationCapture]:
        return list(self._references)[:limit]

    def recent_values(self, limit: int = 20) -> list[InvocationCapture]:
        return list(self._values)[:limit]

    def failures(self, limit: int = 20) -> list[InvocationCapture]:
        results = [c for c in self._values if not c.success]
        results += [c for c in self._references if not c.success]
        return results[:limit]

    def find_by_id(self, invocation_id: str) -> Optional[InvocationCapture]:
        for c in self._values:
            if c.invocation_id == invocation_id:
                return c
        for c in self._references:
            if c.invocation_id == invocation_id:
                return c
        return None

    def status(self) -> dict:
        ref_bytes = sum(c.estimated_bytes() for c in self._references)
        val_bytes = sum(c.estimated_bytes() for c in self._values)
        return {
            "referenceCaptures": len(self._references),
            "valueCaptures": len(self._values),
            "totalCaptured": self.total_captured,
            "totalValueCaptures": self.total_value_captures,
            "estimatedMemoryMB": (ref_bytes + val_bytes) / (1024 * 1024),
        }


class CapturePolicy:
    """Decides when to escalate from REFERENCE to VALUE capture."""

    def __init__(self):
        self.latency_threshold_ns = 100_000_000  # 100ms
        self.sampling_rate = 1000                  # 1 in 1000
        self.max_snapshot_bytes = 1024 * 1024      # 1MB
        self.watchlist: set[str] = set()
        self._counter = 0

    def should_capture_value(
        self,
        function_name: str,
        failed: bool,
        duration_ns: int,
        payload_bytes: int = 0,
    ) -> bool:
        if payload_bytes > self.max_snapshot_bytes:
            return False
        if failed:
            return True
        if duration_ns > self.latency_threshold_ns:
            return True
        if function_name in self.watchlist:
            return True
        self._counter += 1
        return self._counter % self.sampling_rate == 0

    def status(self) -> dict:
        return {
            "latencyThresholdMs": self.latency_threshold_ns / 1_000_000,
            "samplingRate": f"1:{self.sampling_rate}",
            "maxSnapshotBytes": self.max_snapshot_bytes,
            "watchlist": list(self.watchlist),
        }
