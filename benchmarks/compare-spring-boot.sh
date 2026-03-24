#!/bin/bash
# KubeFn vs Spring Boot Comparison
# ==================================
# Runs the identical checkout business logic on both KubeFn and a
# traditional Spring Boot microservices setup, then compares latency
# and throughput numbers side by side.
#
# What it measures:
#   - Requests/sec (throughput)
#   - Latency percentiles: p50, p95, p99
#   - Fastest and slowest request times
#
# Why this matters: KubeFn eliminates the "serialization tax" — the
# cost of JSON encoding/decoding and HTTP round-trips between services.
# In a traditional Spring Boot checkout:
#   - 7 HTTP calls between microservices (auth, pricing, inventory,
#     tax, fraud, shipping, order)
#   - Each call serializes request to JSON, sends over HTTP, deserializes
#     response from JSON
#   - Total overhead: ~7 serialize + 7 deserialize + 7 TCP round-trips
#
# In KubeFn:
#   - All functions share the same JVM heap via HeapExchange
#   - Zero serialization, zero network hops between functions
#   - Objects are passed by reference (zero-copy)
#
# The gap between the two numbers IS the serialization tax.
#
# Prerequisites:
#   - hey (HTTP load generator)
#   - Running KubeFn runtime with the checkout pipeline deployed
#   - Running Spring Boot checkout service (same business logic)
#
# Usage:
#   ./benchmarks/compare-spring-boot.sh
#   RUNS=10000 SPRING_HOST=10.0.0.5:9090 ./benchmarks/compare-spring-boot.sh

set -e

KUBEFN_HOST=${KUBEFN_HOST:-"localhost:8080"}
SPRING_HOST=${SPRING_HOST:-"localhost:9090"}
RUNS=${RUNS:-"1000"}

echo "╔══════════════════════════════════════════╗"
echo "║  KubeFn vs Spring Boot Comparison        ║"
echo "║  Same business logic, different arch     ║"
echo "╚══════════════════════════════════════════╝"

# Warmup both runtimes to eliminate JIT compilation noise.
# 100 requests is enough to trigger C2 compilation on hot paths.
echo ""
echo "=== Warmup ==="
for i in $(seq 1 100); do
    curl -sf -X POST "http://$KUBEFN_HOST/checkout/full" -d '{}' > /dev/null 2>&1
    curl -sf -X POST "http://$SPRING_HOST/checkout" -d '{}' > /dev/null 2>&1
done

# KubeFn: all 4 checkout steps (auth, pricing, tax, inventory) execute
# in the same JVM, sharing objects via HeapExchange. No serialization,
# no HTTP between steps.
echo ""
echo "=== KubeFn ($RUNS requests, 10 concurrent) ==="
hey -n $RUNS -c 10 -m POST -d '{}' "http://$KUBEFN_HOST/checkout/full" 2>&1 | grep -E "Requests/sec|Average|Fastest|Slowest|p50|p95|p99"

# Spring Boot: the same 4 checkout steps, but each is a separate
# microservice. The orchestrator makes 7 HTTP+JSON round-trips to
# complete a single checkout.
echo ""
echo "=== Spring Boot ($RUNS requests, 10 concurrent) ==="
hey -n $RUNS -c 10 -m POST -d '{}' "http://$SPRING_HOST/checkout" 2>&1 | grep -E "Requests/sec|Average|Fastest|Slowest|p50|p95|p99"

echo ""
echo "=== Analysis ==="
echo "KubeFn: zero-copy shared heap, no serialization between steps"
echo "Spring Boot: 7 HTTP calls + JSON serialize/deserialize per checkout"
echo ""
echo "The difference = the serialization tax."
