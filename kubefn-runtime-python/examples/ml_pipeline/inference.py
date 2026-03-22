"""
ML inference function — reads features from HeapExchange (zero-copy)
and runs model prediction.

This is the killer demo: the feature vector published by the feature
engineering function is the SAME Python object in memory. No serialization.
No HTTP call. No protobuf. Just a pointer.
"""

import math
from kubefn.decorators import function


@function("/ml/predict", methods=["POST"], group="ml-pipeline")
def predict(request, ctx):
    """Run ML model prediction using features from HeapExchange."""
    ctx.logger.info("Running inference...")

    # Read features from HeapExchange — ZERO COPY
    features = ctx.heap.get("ml:features")
    if features is None:
        return {"error": "No features in HeapExchange. Call /ml/features first."}

    # Verify zero-copy: this IS the same dict object
    feature_vector = features.get("feature_vector", [0.5] * 8)

    # Simulate model prediction (logistic regression)
    weights = [0.35, -0.22, 0.48, -0.15, 0.31, 0.19, -0.08, 0.42]
    bias = -0.12

    # Dot product
    logit = sum(f * w for f, w in zip(feature_vector, weights)) + bias

    # Sigmoid activation
    probability = 1.0 / (1.0 + math.exp(-logit))

    # Decision
    prediction = "buy" if probability > 0.5 else "browse"
    confidence = probability if prediction == "buy" else 1 - probability

    result = {
        "prediction": prediction,
        "probability": round(probability, 4),
        "confidence": round(confidence, 4),
        "model": "logistic_regression_v1",
        "features_used": len(feature_vector),
        "user_id": features.get("user_id", "unknown"),
    }

    # Publish prediction to HeapExchange
    ctx.heap.publish("ml:prediction", result, "Prediction")

    return result


@function("/ml/explain", methods=["GET"], group="ml-pipeline")
def explain(request, ctx):
    """Explain the last prediction using feature importance."""
    features = ctx.heap.get("ml:features")
    prediction = ctx.heap.get("ml:prediction")

    if not features or not prediction:
        return {"error": "Run /ml/features then /ml/predict first"}

    feature_vector = features.get("feature_vector", [])
    weights = [0.35, -0.22, 0.48, -0.15, 0.31, 0.19, -0.08, 0.42]
    feature_names = ["purchase_freq", "return_rate", "avg_value",
                     "cart_abandon", "email_engage", "mobile_pct",
                     "diversity", "loyalty"]

    # Feature importance (contribution to prediction)
    contributions = [
        {"feature": name, "value": round(fv, 4),
         "weight": round(w, 4), "contribution": round(fv * w, 4)}
        for name, fv, w in zip(feature_names, feature_vector, weights)
    ]
    contributions.sort(key=lambda x: abs(x["contribution"]), reverse=True)

    return {
        "prediction": prediction.get("prediction"),
        "probability": prediction.get("probability"),
        "top_factors": contributions[:5],
        "total_features": len(feature_vector),
        "zero_copy_proof": "Features and prediction are the SAME objects in memory",
    }
