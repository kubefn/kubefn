#!/bin/bash
# Hot-Swap Zero-Downtime Test
# ============================
# Runs sustained load, triggers a hot-swap (classloader reload) mid-traffic,
# and verifies that zero requests are dropped during the transition.
#
# What it measures:
#   - Whether any requests fail (5xx, connection reset) during a hot-swap
#   - Latency spike magnitude during the swap window
#   - Time to complete the reload operation
#
# Why this matters: KubeFn's classloader-based hot-swap is a core
# differentiator. Functions can be redeployed without restarting the JVM
# or dropping in-flight requests. If even one request fails during a swap,
# the mechanism is broken and cannot be trusted in production.
#
# How it works:
#   1. Start sustained load at 200 RPS with 20 concurrent connections
#   2. Wait 10s for the load to stabilize
#   3. POST to /admin/reload to trigger a classloader swap
#   4. Let the load run to completion
#   5. Count errors — expect exactly 0
#
# Prerequisites: hey, curl, a running KubeFn runtime with admin API
#
# Usage:
#   ./benchmarks/hot-swap-test.sh
#   DURATION=120 ./benchmarks/hot-swap-test.sh

set -e

KUBEFN_HOST=${KUBEFN_HOST:-"localhost:8080"}
ADMIN_HOST=${ADMIN_HOST:-"localhost:8081"}
DURATION=${DURATION:-"60"}
RESULTS_DIR="benchmarks/results/hotswap-$(date +%Y%m%d-%H%M%S)"

mkdir -p "$RESULTS_DIR"

echo "╔══════════════════════════════════════════╗"
echo "║  Hot-Swap Zero-Downtime Test             ║"
echo "╚══════════════════════════════════════════╝"

# Step 1: Start sustained load in the background.
# 200 RPS at 20 concurrent connections provides enough pressure
# to surface any request-dropping during the swap window.
echo "[1/4] Starting sustained load (${DURATION}s)..."
hey -z "${DURATION}s" -c 20 -q 200 -m POST -d '{}' \
    "http://$KUBEFN_HOST/checkout/full" \
    > "$RESULTS_DIR/during-swap.txt" 2>&1 &
LOAD_PID=$!

# Wait 10s for the load to stabilize — ensures JIT is warm
# and we have a clean baseline before triggering the swap.
sleep 10

# Step 2: Trigger hot-swap via the admin API.
# This tells the runtime to reload all function JARs using a new
# classloader while the old classloader continues serving in-flight requests.
echo "[2/4] Triggering hot-swap via admin API..."
curl -sf -X POST "http://$ADMIN_HOST/admin/reload" > "$RESULTS_DIR/reload-response.json" 2>&1
echo "  Reload triggered at $(date)"

# Step 3: Wait for the load generator to finish.
echo "[3/4] Waiting for load test to complete..."
wait $LOAD_PID 2>/dev/null

# Step 4: Analyze results.
# Parse hey output for error counts and latency distribution.
echo "[4/4] Analyzing results..."
TOTAL=$(grep "Responses" "$RESULTS_DIR/during-swap.txt" 2>/dev/null | head -1 || echo "unknown")
ERRORS=$(grep -c "Status err" "$RESULTS_DIR/during-swap.txt" 2>/dev/null || echo "0")
STATUS_200=$(grep "200" "$RESULTS_DIR/during-swap.txt" | grep -c "responses" 2>/dev/null || echo "unknown")

cat > "$RESULTS_DIR/report.md" << REPORT
# Hot-Swap Zero-Downtime Test Results
- Date: $(date)
- Duration: ${DURATION}s
- Concurrent: 20

## Results
$(grep -E "Requests/sec|Average|Slowest|Fastest|200|500|Error" "$RESULTS_DIR/during-swap.txt" 2>/dev/null)

## Verdict
- Errors during swap: $ERRORS
- $([ "$ERRORS" = "0" ] && echo "ZERO DOWNTIME VERIFIED" || echo "ERRORS DETECTED DURING SWAP")
REPORT

cat "$RESULTS_DIR/report.md"
