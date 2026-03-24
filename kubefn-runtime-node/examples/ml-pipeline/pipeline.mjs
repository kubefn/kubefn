/**
 * ML Inference Pipeline — feature extraction, prediction, explanation
 * Demonstrates HeapExchange for sharing large objects between functions
 */

// Feature extraction
export function extractFeatures(req, ctx) {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    const userId = body.userId || 'user-001';

    const features = {
        userId,
        sessionDuration: Math.random() * 300,
        pageViews: Math.floor(Math.random() * 50),
        cartValue: Math.random() * 500,
        dayOfWeek: new Date().getDay(),
        hourOfDay: new Date().getHours(),
        isReturningUser: Math.random() > 0.3,
        deviceType: ['mobile', 'desktop', 'tablet'][Math.floor(Math.random() * 3)],
        featureCount: 8,
        extractedAt: Date.now()
    };

    ctx.heap.publish('ml:features', features);
    return { status: 200, body: features };
}
extractFeatures._kubefn = { path: '/ml/features', methods: ['POST'] };

// Prediction
export function predict(req, ctx) {
    const features = ctx.heap.get('ml:features');
    if (!features) {
        return { status: 400, body: { error: 'Features not extracted. Call /ml/features first.' } };
    }

    // Simulated logistic regression
    const weights = {
        sessionDuration: 0.002,
        pageViews: 0.03,
        cartValue: 0.001,
        isReturningUser: 0.3,
        hourOfDay: -0.01
    };

    let logit = -1.5;
    logit += features.sessionDuration * weights.sessionDuration;
    logit += features.pageViews * weights.pageViews;
    logit += features.cartValue * weights.cartValue;
    logit += (features.isReturningUser ? 1 : 0) * weights.isReturningUser;
    logit += features.hourOfDay * weights.hourOfDay;

    const probability = 1 / (1 + Math.exp(-logit));

    const prediction = {
        userId: features.userId,
        probability: Math.round(probability * 10000) / 10000,
        willConvert: probability > 0.5,
        confidence: Math.abs(probability - 0.5) * 2,
        model: 'logistic-v1',
        predictedAt: Date.now()
    };

    ctx.heap.publish('ml:prediction', prediction);
    return { status: 200, body: prediction };
}
predict._kubefn = { path: '/ml/predict', methods: ['POST'] };

// Full pipeline orchestrator
export function mlPipeline(req, ctx) {
    const start = process.hrtime.bigint();

    // Step 1: Extract features
    const featResult = extractFeatures(req, ctx);

    // Step 2: Predict (reads features from heap zero-copy)
    const predResult = predict(req, ctx);

    // Step 3: Explanation
    const features = ctx.heap.get('ml:features');
    const prediction = ctx.heap.get('ml:prediction');

    const explanation = {
        topFactors: [
            { feature: 'cartValue', value: features.cartValue, impact: 'positive' },
            { feature: 'isReturningUser', value: features.isReturningUser, impact: features.isReturningUser ? 'positive' : 'negative' },
            { feature: 'pageViews', value: features.pageViews, impact: 'positive' }
        ],
        prediction: prediction.probability,
        decision: prediction.willConvert ? 'CONVERT' : 'NO_CONVERT'
    };

    const durationMs = Number(process.hrtime.bigint() - start) / 1_000_000;

    return {
        status: 200,
        body: {
            pipeline: 'ml-inference',
            features: features.featureCount,
            prediction: prediction.probability,
            decision: explanation.decision,
            confidence: prediction.confidence,
            explanation: explanation.topFactors,
            _meta: {
                totalTimeMs: durationMs.toFixed(3),
                steps: 3,
                zeroCopy: true,
                heapObjects: ctx.heap.keys().length
            }
        }
    };
}
mlPipeline._kubefn = { path: '/ml/pipeline', methods: ['GET', 'POST'] };
