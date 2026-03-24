# KubeFn

**KubeFn is a shared-memory function platform for the JVM.** Functions share a single heap via HeapExchange -- zero-copy, zero-serialization object sharing between independently deployed functions. Deploy functions like microservices, run them like a monolith.

## The Problem

Microservices serialize everything. A checkout flow with pricing, tax, inventory, and fraud checks means 4+ HTTP calls, 8+ JSON serializations, and 12+ milliseconds of pure overhead. The data was already in memory -- you just moved it through the network and back.

## The Solution: Memory-Continuous Architecture

KubeFn runs multiple functions in a single JVM (an "organism"). Functions share objects on the heap directly -- no serialization, no HTTP, no copying. A 4-step checkout pipeline runs in 5.7ms end-to-end.

## Install

```bash
# Homebrew
brew install kubefn/tap/kubefn

# Maven (function authoring)
implementation("com.kubefn:kubefn-api:0.4.0")

# Python runtime
pip install kubefn

# Node.js runtime
npm install @kubefn/runtime
```

## Benchmarks

Full HTTP request cycle, measured with `hey`, in-cluster:

| Runtime | Pipeline | Steps | p50 Latency |
|---------|----------|-------|-------------|
| JVM     | Checkout | 4     | 5.7ms       |
| Python  | ML       | 3     | 5.4ms       |
| Node.js | Gateway  | 3     | 3.0ms       |

Equivalent microservices architecture: 15-70ms for the same pipelines.

## Next

[Quickstart (5 minutes)](getting-started/quickstart.md) -- get a function running locally.
