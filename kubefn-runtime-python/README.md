# KubeFn Python Runtime

> **Memory-Continuous Architecture for Python** — Functions share a CPython interpreter heap. Zero serialization between functions.

KubeFn is a Live Application Fabric where independently deployable functions share memory. The Python runtime brings this to ML/data science teams.

## Install

```bash
pip install kubefn
```

## Quick Start

```python
from kubefn import function, HeapExchange

@function(path="/ml/features", methods=["POST"])
def extract_features(request, ctx):
    """Extract ML features and publish to shared heap."""
    user_id = request.get("userId", "user-001")

    features = {
        "recency": 0.8,
        "frequency": 12,
        "monetary": 450.0,
        "category_affinity": [0.3, 0.7, 0.1],
    }

    # Publish to heap — other functions read this zero-copy
    ctx.heap.publish(f"features:{user_id}", features)
    return {"features": len(features), "userId": user_id}


@function(path="/ml/predict", methods=["POST"])
def predict(request, ctx):
    """Read features from heap (zero-copy) and run inference."""
    features = ctx.heap.require("features:user-001")  # Same Python object, not deserialized

    score = sum(features.values()) / len(features) if isinstance(features, dict) else 0.5

    ctx.heap.publish("prediction:latest", {"score": score, "model": "v2"})
    return {"prediction": score}
```

## Run

```bash
# Start the runtime
kubefn-python --port 8080 --functions-dir ./my-functions

# Or with Python module
python -m kubefn --port 8080 --functions-dir ./my-functions
```

## Production Features

| Feature | Description |
|---|---|
| HeapExchange | Zero-copy shared Python objects between functions |
| Circuit Breakers | Per-function failure isolation (CLOSED/OPEN/HALF_OPEN) |
| Drain Manager | Graceful hot-swap with in-flight request tracking |
| Request Timeout | Configurable per-request deadline enforcement |
| Causal Introspection | Event ring buffer with trace assembly |
| Prometheus Metrics | Per-function latency histograms in exposition format |
| Heap Guard | Size limits, TTL, memory pressure detection |
| Scheduler Engine | Cron-based function scheduling (`@schedule`) |
| Admin API | 12 endpoints: health, ready, functions, heap, breakers, metrics, traces, scheduler |

## Benchmarks

In-cluster (service-to-service on k3s):
- **3-step ML pipeline: 5.4ms** (vs 6-30ms equivalent microservices)
- **Speedup: 1.1-5.5x** (full HTTP cycle, honestly measured)

## Links

- **Website**: https://kubefn.com
- **GitHub**: https://github.com/kubefn/kubefn
- **Paper**: [DOI: 10.5281/zenodo.19161471](https://doi.org/10.5281/zenodo.19161471)
- **JVM Runtime**: `com.kubefn:kubefn-api` on Maven Central
- **CLI**: `brew tap kubefn/tap && brew install kubefn`
