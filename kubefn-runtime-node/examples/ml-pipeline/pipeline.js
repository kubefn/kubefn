/**
 * ML Inference Pipeline — feature extraction, prediction, explanation
 *
 * Demonstrates HeapExchange for sharing large feature vectors and model
 * outputs between pipeline stages. Each stage publishes its result to
 * the heap; the next stage reads it zero-copy.
 *
 * Functions export with _kubefn metadata for the loader.
 */

// ── Feature extraction ─────────────────────────────────────────────

function extractFeatures(req, ctx) {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    const userId = body.userId || 'user-001';

    // Pull historical user data from heap if available (e.g., from a user-profile function)
    const userProfile = ctx.heap.get(`user:profile:${userId}`) || {};
    const purchaseHistory = ctx.heap.get(`user:purchases:${userId}`) || { count: 0, totalSpent: 0 };

    const features = {
        userId,
        // Behavioral features
        sessionDuration: body.sessionDuration || Math.random() * 300,
        pageViews: body.pageViews || Math.floor(Math.random() * 50),
        cartValue: body.cartValue || Math.random() * 500,
        cartItemCount: body.cartItemCount || Math.floor(Math.random() * 8),
        // Temporal features
        dayOfWeek: new Date().getDay(),
        hourOfDay: new Date().getHours(),
        isWeekend: [0, 6].includes(new Date().getDay()),
        // User history features
        isReturningUser: purchaseHistory.count > 0,
        lifetimeValue: purchaseHistory.totalSpent,
        previousPurchases: purchaseHistory.count,
        accountAgeDays: userProfile.createdAt
            ? Math.floor((Date.now() - userProfile.createdAt) / 86400_000)
            : 0,
        // Device features
        deviceType: body.deviceType || 'desktop',
        // Metadata
        featureVersion: 'v2',
        featureCount: 12,
        extractedAt: Date.now()
    };

    // Publish feature vector to heap — prediction reads it zero-copy
    ctx.heap.publish('ml:features', features);

    return { status: 200, body: { featureCount: features.featureCount, userId } };
}
extractFeatures._kubefn = {
    path: '/ml/features',
    methods: ['POST'],
    group: 'ml-pipeline'
};

// ── Prediction (logistic regression) ───────────────────────────────

function predict(req, ctx) {
    const features = ctx.heap.get('ml:features');
    if (!features) {
        return {
            status: 400,
            body: { error: 'Features not extracted. Call /ml/features first or use /ml/pipeline.' }
        };
    }

    // Model weights (in production, loaded from model registry on heap)
    const weights = {
        intercept: -2.1,
        sessionDuration: 0.003,
        pageViews: 0.025,
        cartValue: 0.0015,
        cartItemCount: 0.12,
        isReturningUser: 0.45,
        lifetimeValue: 0.0001,
        previousPurchases: 0.08,
        isWeekend: -0.15,
        hourOfDay: 0.0,  // non-linear, handled below
    };

    // Compute logit
    let logit = weights.intercept;
    logit += features.sessionDuration * weights.sessionDuration;
    logit += features.pageViews * weights.pageViews;
    logit += features.cartValue * weights.cartValue;
    logit += features.cartItemCount * weights.cartItemCount;
    logit += (features.isReturningUser ? 1 : 0) * weights.isReturningUser;
    logit += features.lifetimeValue * weights.lifetimeValue;
    logit += features.previousPurchases * weights.previousPurchases;
    logit += (features.isWeekend ? 1 : 0) * weights.isWeekend;

    // Hour-of-day effect: peak at 10am and 8pm
    const hourEffect = Math.sin((features.hourOfDay - 4) * Math.PI / 12) * 0.3;
    logit += hourEffect;

    const probability = 1 / (1 + Math.exp(-logit));
    const confidence = Math.abs(probability - 0.5) * 2;

    const prediction = {
        userId: features.userId,
        probability: Math.round(probability * 10000) / 10000,
        willConvert: probability > 0.5,
        confidence: Math.round(confidence * 1000) / 1000,
        riskTier: confidence > 0.7 ? 'high-confidence' : confidence > 0.3 ? 'medium' : 'low-confidence',
        model: 'logistic-v2',
        modelVersion: '2.1.0',
        predictedAt: Date.now()
    };

    // Publish prediction to heap — explanation reads it zero-copy
    ctx.heap.publish('ml:prediction', prediction);

    return { status: 200, body: prediction };
}
predict._kubefn = {
    path: '/ml/predict',
    methods: ['POST'],
    group: 'ml-pipeline'
};

// ── Explanation (feature importance) ───────────────────────────────

function explain(req, ctx) {
    const features = ctx.heap.get('ml:features');
    const prediction = ctx.heap.get('ml:prediction');

    if (!features || !prediction) {
        return {
            status: 400,
            body: { error: 'Run /ml/pipeline first to generate features and prediction.' }
        };
    }

    // Compute SHAP-like feature contributions (simplified)
    const contributions = [
        { feature: 'cartValue', value: features.cartValue, weight: 0.0015, contribution: features.cartValue * 0.0015 },
        { feature: 'cartItemCount', value: features.cartItemCount, weight: 0.12, contribution: features.cartItemCount * 0.12 },
        { feature: 'isReturningUser', value: features.isReturningUser, weight: 0.45, contribution: (features.isReturningUser ? 1 : 0) * 0.45 },
        { feature: 'pageViews', value: features.pageViews, weight: 0.025, contribution: features.pageViews * 0.025 },
        { feature: 'sessionDuration', value: features.sessionDuration, weight: 0.003, contribution: features.sessionDuration * 0.003 },
        { feature: 'previousPurchases', value: features.previousPurchases, weight: 0.08, contribution: features.previousPurchases * 0.08 },
    ].sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution));

    const explanation = {
        userId: features.userId,
        prediction: prediction.probability,
        decision: prediction.willConvert ? 'LIKELY_CONVERT' : 'UNLIKELY_CONVERT',
        topFactors: contributions.slice(0, 3).map(c => ({
            feature: c.feature,
            value: typeof c.value === 'boolean' ? c.value : Math.round(c.value * 100) / 100,
            impact: c.contribution > 0 ? 'positive' : 'negative',
            magnitude: Math.round(Math.abs(c.contribution) * 1000) / 1000
        })),
        allContributions: contributions,
        explainedAt: Date.now()
    };

    ctx.heap.publish('ml:explanation', explanation);

    return { status: 200, body: explanation };
}
explain._kubefn = {
    path: '/ml/explain',
    methods: ['POST'],
    group: 'ml-pipeline'
};

// ── Full pipeline (extract + predict + explain) ────────────────────

function mlPipeline(req, ctx) {
    const start = process.hrtime.bigint();

    // Step 1: Extract features
    const featResult = extractFeatures(req, ctx);
    if (featResult.status !== 200) return featResult;

    // Step 2: Predict (reads features from heap — zero-copy)
    const predResult = predict(req, ctx);
    if (predResult.status !== 200) return predResult;

    // Step 3: Explain (reads features + prediction from heap — zero-copy)
    const explainResult = explain(req, ctx);

    // Read final results from heap
    const prediction = ctx.heap.get('ml:prediction');
    const explanation = ctx.heap.get('ml:explanation');

    const durationNs = process.hrtime.bigint() - start;
    const durationMs = Number(durationNs) / 1_000_000;

    return {
        status: 200,
        body: {
            pipeline: 'ml-inference',
            userId: prediction.userId,
            probability: prediction.probability,
            decision: explanation.decision,
            confidence: prediction.confidence,
            riskTier: prediction.riskTier,
            topFactors: explanation.topFactors,
            _meta: {
                totalTimeMs: durationMs.toFixed(3),
                steps: ['extract', 'predict', 'explain'],
                model: prediction.model,
                featureVersion: 'v2',
                zeroCopy: true,
                heapKeys: ctx.heap.keys().length
            }
        }
    };
}
mlPipeline._kubefn = {
    path: '/ml/pipeline',
    methods: ['GET', 'POST'],
    group: 'ml-pipeline'
};

module.exports = { extractFeatures, predict, explain, mlPipeline };
