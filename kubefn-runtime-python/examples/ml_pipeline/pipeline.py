"""
ML Pipeline orchestrator — chains feature engineering + inference + explanation.

Demonstrates the full Memory-Continuous Architecture in Python:
3 functions share the same interpreter, exchange objects via HeapExchange
with zero serialization. Same concept as the JVM runtime.
"""

import time
from kubefn.decorators import function


@function("/ml/pipeline", methods=["GET", "POST"], group="ml-pipeline")
def run_pipeline(request, ctx):
    """Run the full ML pipeline: features → predict → explain."""
    start_ns = time.time_ns()
    user_id = request.query_param("userId", "user-001")

    # Find sibling functions from the registry (same interpreter, same heap)
    from kubefn.decorators import get_registered_functions
    fns = {fn.name: fn.handler for fn in get_registered_functions()
           if fn.group == ctx.group_name}

    # Step 1: Feature engineering
    t1 = time.time_ns()
    feat_result = fns["extract_features"](request, ctx)
    d1 = (time.time_ns() - t1) / 1_000_000

    # Step 2: Inference (reads features from heap — zero copy)
    t2 = time.time_ns()
    pred_result = fns["predict"](request, ctx)
    d2 = (time.time_ns() - t2) / 1_000_000

    # Step 3: Explanation (reads both features and prediction — zero copy)
    t3 = time.time_ns()
    explain_result = fns["explain"](request, ctx)
    d3 = (time.time_ns() - t3) / 1_000_000

    total_ms = (time.time_ns() - start_ns) / 1_000_000

    return {
        "pipeline": "ML Inference",
        "runtime": "Python",
        "user_id": user_id,
        "prediction": pred_result.get("prediction"),
        "probability": pred_result.get("probability"),
        "top_factors": explain_result.get("top_factors", [])[:3],
        "heap_objects": ctx.heap.size(),
        "_meta": {
            "pipelineSteps": 3,
            "totalTimeMs": f"{total_ms:.3f}",
            "stages": {
                "feature_engineering": f"{d1:.3f}ms",
                "inference": f"{d2:.3f}ms",
                "explanation": f"{d3:.3f}ms",
            },
            "zeroCopy": True,
            "runtime": "python",
            "note": "3 Python functions sharing interpreter. Zero serialization.",
        }
    }
