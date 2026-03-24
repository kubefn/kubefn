# KubeFn Benchmarks & Resilience Harness

A suite of load tests and failure injection scripts for validating KubeFn runtime performance, zero-downtime hot-swap, resilience under failure, and head-to-head comparison with traditional Spring Boot architectures.

## Prerequisites

- [hey](https://github.com/rakyll/hey) — HTTP load generator (`brew install hey` or `go install github.com/rakyll/hey@latest`)
- `curl` — for admin API calls and metric snapshots
- `python3` — used for JSON parsing in failure-injection output
- A running KubeFn runtime (JVM, Python, and/or Node runtimes depending on the test)

## Tests

### 1. Sustained Load (`sustained-load.sh`)

**What it tests:** Runtime stability over a prolonged period (default 1 hour). Sends mixed traffic to all three runtimes (JVM checkout pipeline, Python ML pipeline, Node API gateway) at a configurable RPS. Captures latency percentiles, throughput, error rates, heap snapshots, lifecycle state, and Prometheus metrics every 60 seconds.

**Why it matters:** Short benchmarks hide GC pauses, memory leaks, and classloader pressure. A 1-hour run surfaces issues that only appear under sustained allocation.

**How to run:**
```bash
# Defaults: localhost ports, 100 RPS, 1 hour
./benchmarks/sustained-load.sh

# Custom
KUBEFN_HOST=10.0.0.5:8080 RPS=500 DURATION=1800 ./benchmarks/sustained-load.sh
```

**How to read results:** Check `benchmarks/results/<timestamp>/report.md` for a summary. Look at `heap-snapshots.jsonl` for heap growth over time — a monotonically increasing used-heap signals a leak. Compare p99 at the start vs end of the run in `prometheus-*.txt` files.

---

### 2. Hot-Swap Zero-Downtime (`hot-swap-test.sh`)

**What it tests:** Whether KubeFn can reload function JARs mid-traffic without dropping a single request. Starts sustained load at 200 RPS, waits for stabilization, triggers a hot-swap via the admin `/admin/reload` endpoint, and counts errors.

**Why it matters:** KubeFn's classloader-based hot-swap is a core differentiator. If even one request fails during a swap, the mechanism is broken.

**How to run:**
```bash
./benchmarks/hot-swap-test.sh

# Longer soak
DURATION=120 ./benchmarks/hot-swap-test.sh
```

**How to read results:** The script prints a verdict directly. `ZERO DOWNTIME VERIFIED` means no errors were observed during the swap window. Any errors are flagged. Check `during-swap.txt` for the full hey output including latency distribution — look for a latency spike around the swap time.

---

### 3. Failure Injection (`failure-injection.sh`)

**What it tests:** Four resilience scenarios:
1. **Circuit breaker trip** — Sends requests to a nonexistent route to trigger errors and inspects breaker state via the admin API.
2. **Timeout handling** — Measures actual request duration against the configured timeout ceiling.
3. **Graceful drain** — Runs load and verifies in-flight requests complete without errors during shutdown/drain.
4. **Memory pressure** — Queries the lifecycle endpoint for JVM heap pressure metrics.

**Why it matters:** Production systems fail. These tests verify that KubeFn degrades gracefully rather than cascading.

**How to run:**
```bash
./benchmarks/failure-injection.sh
```

**How to read results:** Each test prints inline results. The combined report is saved to `benchmarks/results/failure-<timestamp>/report.md`. Key signals: breaker should be OPEN after the error burst, timeout should be well below 30s, drain errors should be 0, and heap pressure should be below 0.8.

---

### 4. Spring Boot Comparison (`compare-spring-boot.sh`)

**What it tests:** Head-to-head latency and throughput comparison running the same checkout business logic on KubeFn (zero-copy heap sharing) vs Spring Boot (HTTP + JSON serialization between services).

**Why it matters:** This quantifies the "serialization tax" — the cost of JSON encode/decode and HTTP round-trips that KubeFn eliminates via HeapExchange shared-memory.

**How to run:**
```bash
# Requires both KubeFn and a Spring Boot checkout service running
SPRING_HOST=localhost:9090 ./benchmarks/compare-spring-boot.sh

# More requests for statistical significance
RUNS=10000 ./benchmarks/compare-spring-boot.sh
```

**How to read results:** Compare p50/p95/p99 latencies and Requests/sec between the two sections. The gap represents the serialization overhead that KubeFn eliminates. Expect KubeFn to show lower latency and higher throughput, especially at p99 where serialization GC pressure compounds.

---

## Results Directory

All tests write to `benchmarks/results/<test>-<timestamp>/`. Each run gets its own directory so results are never overwritten. Key files:

| File | Contents |
|------|----------|
| `report.md` | Human-readable summary |
| `*-sustained.txt` | Raw hey output per runtime |
| `heap-snapshots.jsonl` | Periodic heap state (one JSON object per line) |
| `lifecycle-snapshots.jsonl` | Periodic lifecycle/GC state |
| `prometheus-*.txt` | Prometheus scrape at each interval |

## Environment Variables

| Variable | Default | Used By |
|----------|---------|---------|
| `KUBEFN_HOST` | `localhost:8080` | All tests |
| `ADMIN_HOST` | `localhost:8081` | hot-swap, failure-injection |
| `PYTHON_HOST` | `localhost:8090` | sustained-load |
| `NODE_HOST` | `localhost:8070` | sustained-load |
| `SPRING_HOST` | `localhost:9090` | compare-spring-boot |
| `DURATION` | `3600` (sustained), `60` (hot-swap) | sustained-load, hot-swap |
| `RPS` | `100` | sustained-load |
| `RUNS` | `1000` | compare-spring-boot |
