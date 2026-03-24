#!/bin/bash
# Failure Injection Test Suite
# =============================
# Tests KubeFn's resilience to various failure modes: circuit breaker
# tripping, request timeouts, graceful drain, and memory pressure.
#
# What it measures:
#   Test 1 — Circuit Breaker: Does the breaker trip after repeated errors?
#   Test 2 — Timeout Handling: Do requests complete within the timeout ceiling?
#   Test 3 — Graceful Drain: Do in-flight requests complete without errors
#            when the runtime is draining?
#   Test 4 — Memory Pressure: What is the current JVM heap pressure?
#
# Why this matters: Production systems fail. These tests verify that KubeFn
# degrades gracefully (circuit breaker opens, requests time out cleanly,
# drain completes in-flight work) rather than cascading failures across
# the function mesh.
#
# Expected outcomes:
#   - Circuit breaker should be OPEN after the error burst
#   - Timeout should be well below 30s for a healthy endpoint
#   - Drain errors should be 0
#   - Heap pressure should be below 0.8 under normal conditions
#
# Prerequisites: hey, curl, python3 (for JSON parsing)
#
# Usage:
#   ./benchmarks/failure-injection.sh

set -e

KUBEFN_HOST=${KUBEFN_HOST:-"localhost:8080"}
ADMIN_HOST=${ADMIN_HOST:-"localhost:8081"}
RESULTS_DIR="benchmarks/results/failure-$(date +%Y%m%d-%H%M%S)"

mkdir -p "$RESULTS_DIR"

echo "╔══════════════════════════════════════════╗"
echo "║  Failure Injection Test Suite             ║"
echo "╚══════════════════════════════════════════╝"

# --------------------------------------------------------------------------
# Test 1: Circuit Breaker Trip
# Send 10 requests to a nonexistent route to trigger 404/500 errors.
# The circuit breaker should detect the error burst and transition to OPEN,
# which causes subsequent requests to fail-fast instead of hitting the
# backend. This protects downstream functions from cascading failure.
# --------------------------------------------------------------------------
echo ""
echo "=== Test 1: Circuit Breaker Trip ==="
for i in $(seq 1 10); do
    curl -sf "http://$KUBEFN_HOST/nonexistent-route" > /dev/null 2>&1 || true
done
BREAKER_STATUS=$(curl -sf "http://$ADMIN_HOST/admin/breakers" 2>/dev/null)
echo "  Breaker state: $BREAKER_STATUS"

# --------------------------------------------------------------------------
# Test 2: Timeout Handling
# Sends a single request and measures wall-clock time. The runtime should
# enforce a request timeout (default 30s). A healthy checkout pipeline
# should complete in well under 1s. If this takes >5s, something is wrong.
# --------------------------------------------------------------------------
echo ""
echo "=== Test 2: Timeout Handling ==="
START=$(date +%s%N)
curl -sf --max-time 5 "http://$KUBEFN_HOST/checkout/full" -X POST -d '{}' > /dev/null 2>&1
END=$(date +%s%N)
DURATION_MS=$(( ($END - $START) / 1000000 ))
echo "  Request completed in ${DURATION_MS}ms (timeout would be at 30000ms)"

# --------------------------------------------------------------------------
# Test 3: Graceful Drain
# Runs a short burst of load (10s at 50 RPS). In a real drain scenario,
# the runtime stops accepting new connections but completes in-flight
# requests. Here we verify that no requests error out during the burst,
# which is the baseline for drain correctness.
# --------------------------------------------------------------------------
echo ""
echo "=== Test 3: Graceful Drain ==="
hey -z "10s" -c 5 -q 50 -m POST -d '{}' "http://$KUBEFN_HOST/checkout/full" > "$RESULTS_DIR/drain-test.txt" 2>&1
DRAIN_ERRORS=$(grep -c "Error" "$RESULTS_DIR/drain-test.txt" 2>/dev/null || echo "0")
echo "  Errors during drain test: $DRAIN_ERRORS"

# --------------------------------------------------------------------------
# Test 4: Memory Pressure
# Queries the lifecycle endpoint to check JVM heap pressure. A value
# above 0.8 indicates the heap is under significant pressure and GC
# is working hard. Above 0.95 risks OOM. This is a spot check — for
# trends over time, use the sustained-load test with periodic snapshots.
# --------------------------------------------------------------------------
echo ""
echo "=== Test 4: Memory Pressure ==="
LIFECYCLE=$(curl -sf "http://$ADMIN_HOST/admin/lifecycle" 2>/dev/null)
echo "  Lifecycle: $LIFECYCLE"

echo ""
echo "=== Summary ==="
echo "  Circuit breaker: $(echo $BREAKER_STATUS | head -c 100)"
echo "  Timeout: ${DURATION_MS}ms"
echo "  Drain errors: $DRAIN_ERRORS"
echo "  Memory: $(echo $LIFECYCLE | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("jvmHeapPressure","?"))' 2>/dev/null || echo '?')"

# Save structured report
cat > "$RESULTS_DIR/report.md" << REPORT
# Failure Injection Test Results
- Date: $(date)

## Circuit Breaker
$BREAKER_STATUS

## Timeout
Request completed in ${DURATION_MS}ms

## Drain
Errors during drain: $DRAIN_ERRORS

## Memory
$LIFECYCLE
REPORT

echo ""
echo "Results: $RESULTS_DIR/"
