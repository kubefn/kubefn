#!/bin/bash
# KubeFn Sustained Load Test
# ===========================
# Runs mixed traffic against all 3 runtimes for 1 hour (configurable).
#
# What it measures:
#   - Latency percentiles (p50/p95/p99) over a long window
#   - Throughput (requests/sec) sustained over time
#   - Error rates under continuous pressure
#   - Heap growth and GC behavior (via periodic snapshots)
#   - Memory leaks (monotonically increasing heap = leak)
#
# Why 1 hour: Short benchmarks hide GC pauses, classloader pressure,
# and memory leaks that only manifest under sustained allocation.
#
# Prerequisites: hey (HTTP load generator), curl
#
# Usage:
#   ./benchmarks/sustained-load.sh
#   KUBEFN_HOST=10.0.0.5:8080 RPS=500 DURATION=1800 ./benchmarks/sustained-load.sh

set -e

KUBEFN_HOST=${KUBEFN_HOST:-"localhost:8080"}
PYTHON_HOST=${PYTHON_HOST:-"localhost:8090"}
NODE_HOST=${NODE_HOST:-"localhost:8070"}
DURATION=${DURATION:-"3600"}  # 1 hour default
RPS=${RPS:-"100"}
RESULTS_DIR="benchmarks/results/$(date +%Y%m%d-%H%M%S)"

mkdir -p "$RESULTS_DIR"

echo "╔══════════════════════════════════════════╗"
echo "║  KubeFn Sustained Load Test              ║"
echo "║  Duration: ${DURATION}s | RPS: ${RPS}    ║"
echo "╚══════════════════════════════════════════╝"

# Phase 1: Warmup (2 minutes)
# JIT compilation and classloading happen on first requests.
# Warming up ensures the steady-state numbers aren't skewed by cold-start.
echo "[Phase 1] Warmup..."
hey -n 1000 -c 10 -m POST -d '{}' "http://$KUBEFN_HOST/checkout/full" > /dev/null 2>&1
hey -n 1000 -c 10 "http://$PYTHON_HOST/ml/pipeline" > /dev/null 2>&1
hey -n 1000 -c 10 "http://$NODE_HOST/gw/proxy" > /dev/null 2>&1

# Phase 2: Sustained load
# Each runtime gets its own hey process running in parallel.
# JVM gets higher concurrency (20) because it handles the multi-step
# checkout pipeline; Python and Node get 10 each.
echo "[Phase 2] Sustained load for ${DURATION}s..."

# JVM runtime — 4-step checkout pipeline (auth -> pricing -> tax -> inventory)
hey -z "${DURATION}s" -c 20 -q $RPS -m POST -d '{}' \
    "http://$KUBEFN_HOST/checkout/full" \
    > "$RESULTS_DIR/jvm-sustained.txt" 2>&1 &
JVM_PID=$!

# Python runtime — ML inference pipeline
hey -z "${DURATION}s" -c 10 -q $RPS \
    "http://$PYTHON_HOST/ml/pipeline" \
    > "$RESULTS_DIR/python-sustained.txt" 2>&1 &
PY_PID=$!

# Node runtime — API gateway proxy
hey -z "${DURATION}s" -c 10 -q $RPS \
    "http://$NODE_HOST/gw/proxy" \
    > "$RESULTS_DIR/node-sustained.txt" 2>&1 &
NODE_PID=$!

# Phase 3: Periodic metrics capture (every 60s)
# Captures heap state, lifecycle info, and Prometheus metrics at regular
# intervals so you can track memory growth and GC behavior over time.
# A growing heap-snapshots.jsonl with monotonically increasing used-heap
# indicates a memory leak.
echo "[Phase 3] Capturing metrics every 60s..."
ELAPSED=0
while [ $ELAPSED -lt $DURATION ]; do
    sleep 60
    ELAPSED=$((ELAPSED + 60))

    # Heap state — tracks used/max/committed heap, object counts
    curl -sf "http://$KUBEFN_HOST:8081/admin/heap" >> "$RESULTS_DIR/heap-snapshots.jsonl" 2>/dev/null
    echo "" >> "$RESULTS_DIR/heap-snapshots.jsonl"

    # Lifecycle state — function load counts, classloader stats, GC pressure
    curl -sf "http://$KUBEFN_HOST:8081/admin/lifecycle" >> "$RESULTS_DIR/lifecycle-snapshots.jsonl" 2>/dev/null
    echo "" >> "$RESULTS_DIR/lifecycle-snapshots.jsonl"

    # Prometheus scrape — full metrics at this point in time
    curl -sf "http://$KUBEFN_HOST:8081/admin/prometheus" >> "$RESULTS_DIR/prometheus-${ELAPSED}s.txt" 2>/dev/null

    echo "  [${ELAPSED}s] Metrics captured"
done

# Wait for all load generators to finish
wait $JVM_PID $PY_PID $NODE_PID 2>/dev/null

# Phase 4: Generate report
# Extracts key metrics from hey output into a readable markdown summary.
echo "[Phase 4] Generating report..."
cat > "$RESULTS_DIR/report.md" << REPORT
# KubeFn Sustained Load Test Results
- Date: $(date)
- Duration: ${DURATION}s
- Target RPS: ${RPS}

## JVM Runtime (4-step checkout)
$(grep -E "Requests/sec|Average|p50|p95|p99|Status" "$RESULTS_DIR/jvm-sustained.txt" 2>/dev/null || echo "No results")

## Python Runtime (ML pipeline)
$(grep -E "Requests/sec|Average|p50|p95|p99|Status" "$RESULTS_DIR/python-sustained.txt" 2>/dev/null || echo "No results")

## Node.js Runtime (API gateway)
$(grep -E "Requests/sec|Average|p50|p95|p99|Status" "$RESULTS_DIR/node-sustained.txt" 2>/dev/null || echo "No results")
REPORT

echo ""
echo "Results saved to: $RESULTS_DIR/"
echo "Report: $RESULTS_DIR/report.md"
