"""
Feature engineering function — extracts features from raw data
and publishes them to HeapExchange for the inference function.

In microservices, this feature matrix would be serialized to JSON/Protobuf
and sent over HTTP. In KubeFn, the inference function reads the SAME
Python dict/numpy array — zero copy, same memory address.
"""

from kubefn.decorators import function

@function("/ml/features", methods=["POST"], group="ml-pipeline")
def extract_features(request, ctx):
    """Extract features from raw input data."""
    ctx.logger.info("Extracting features...")

    # Simulate raw data parsing
    user_id = request.query_param("userId", "user-001")

    # Build feature vector (in real world: numpy array, pandas DataFrame, etc.)
    features = {
        "user_id": user_id,
        "purchase_count_30d": 12,
        "avg_order_value": 67.50,
        "days_since_last_purchase": 3,
        "category_diversity_score": 0.78,
        "return_rate": 0.05,
        "session_duration_avg_s": 340,
        "page_views_per_session": 8.2,
        "cart_abandonment_rate": 0.15,
        "email_open_rate": 0.42,
        "loyalty_tier": 3,
        "account_age_days": 547,
        "device_type_mobile_pct": 0.65,
        "feature_vector": [0.78, 0.12, 0.67, 0.05, 0.42, 0.65, 0.34, 0.91],
    }

    # Publish to HeapExchange — inference function reads this ZERO COPY
    ctx.heap.publish("ml:features", features, "FeatureVector")
    ctx.logger.info(f"Published {len(features)} features to HeapExchange")

    return {"features_extracted": len(features), "user_id": user_id}
