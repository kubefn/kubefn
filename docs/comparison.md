# KubeFn vs Monolith vs Microservices — Honest Comparison

## The question everyone asks

> "If functions share a JVM, how is this different from a modular monolith?"

This is a fair question. Here's the honest answer.

## What a Spring Boot monolith already gives you

- In-process method calls (fast)
- Shared memory (same heap)
- Simple deployment (one JAR)
- One debugger, one heap dump
- Shared connection pools

## What KubeFn adds that a monolith cannot do

### 1. Independent hot-swap per function
**Monolith:** Change one function → redeploy the entire application. Typical: 30-60s downtime or rolling restart.

**KubeFn:** Replace one function's classloader while others keep serving. Zero-downtime. The organism lives — only the organ is replaced.

**Why this matters:** In a 20-function checkout system, fixing a tax calculation bug shouldn't require redeploying the fraud engine.

### 2. Revision coexistence (canary deploys inside one process)
**Monolith:** Canary requires two separate deployments with traffic splitting at the load balancer level. Double the pods, double the memory.

**KubeFn:** Run PricingFunction v1 and v2 simultaneously in the same JVM. Split traffic 90/10 via RevisionManager. One pod, one heap. Compare outputs zero-copy.

**Why this matters:** Canary at the function level, not the application level. Test a new fraud model on 5% of traffic without deploying a separate cluster.

### 3. Per-function operational controls
**Monolith:** Circuit breakers are typically per-external-dependency, not per-internal-module. One slow function slows everything.

**KubeFn:** Each function has its own:
- Circuit breaker (trips independently)
- Concurrency limit (semaphore)
- Timeout (enforced)
- Metrics (p50/p95/p99 per function)
- Tracing (OpenTelemetry span per function)
- Fallback (executes on breaker trip)

**Why this matters:** When FraudCheckFunction starts timing out, it trips its own breaker — PricingFunction keeps serving normally.

### 4. Typed shared data plane (HeapExchange)
**Monolith:** Functions call each other via method calls. Data sharing is ad-hoc — static singletons, service injection, or passing objects through call chains.

**KubeFn:** HeapExchange is a first-class, typed, audited, versioned shared object graph. Every publish/get is tracked with producer attribution, timestamps, versions, and causal lineage.

**Why this matters:** In a monolith, you can't answer "who last modified this object?" or "what was the state of this cache entry when this request failed?" KubeFn can.

### 5. Independent development lifecycle
**Monolith:** All functions share one build, one CI pipeline, one release. Merge conflicts. Coordinated releases. "Don't merge until Friday."

**KubeFn:** Each function group is a separate JAR with its own build. Deploy independently. Contracts module defines the interface. Developer A and Developer B never touch each other's code.

**Why this matters:** Team velocity. Microservice-style independence without microservice overhead.

## When to use what

| Use case | Best choice | Why |
|---|---|---|
| Small team, < 10 endpoints | **Monolith** | Simplest. No overhead. |
| Independent teams, separate concerns | **Microservices** | Strong isolation, separate databases |
| High-chatter pipeline, same data | **KubeFn** | Zero-copy sharing, no serialization tax |
| Latency-critical composition | **KubeFn** | 4-18x faster than microservices |
| Multi-tenant, untrusted code | **Microservices** | Process isolation required |
| Hot-swap business rules in production | **KubeFn** | Replace functions without restart |
| Canary at function level | **KubeFn** | Revision coexistence in same JVM |

## What KubeFn is NOT better at

- **Strong isolation:** A monolith or microservices provide better blast radius control. KubeFn's shared JVM means one OOM kills everything.
- **Language diversity:** Microservices let each team pick their language. KubeFn functions within a group must share a runtime.
- **Simple CRUD:** If your functions just read/write a database and don't compose, the overhead of HeapExchange isn't worth it.

## The honest summary

**KubeFn is a monolith that deploys like microservices.**

It gives you the speed of in-process calls and the operational flexibility of independent deployments. The trade-off is shared failure domain and the requirement that functions trust each other.

If your system is a high-chatter pipeline where 5+ functions compose for every request, and you need per-function operational controls with zero-downtime upgrades — KubeFn is genuinely better than both alternatives.

If your system is simple CRUD or needs strong multi-tenant isolation — stick with what you have.
