/**
 * API Gateway functions — rate limiting, auth, routing
 * Each function reads/writes to HeapExchange (zero-copy)
 */

// Rate limiter — token bucket per client
export function rateLimiter(req, ctx) {
    const clientId = req.headers['x-client-id'] || 'anonymous';
    const key = `ratelimit:${clientId}`;
    const existing = ctx.heap.get(key);
    const now = Date.now();

    let bucket = existing || { tokens: 100, lastRefill: now };

    // Refill tokens (10 per second)
    const elapsed = (now - bucket.lastRefill) / 1000;
    bucket.tokens = Math.min(100, bucket.tokens + elapsed * 10);
    bucket.lastRefill = now;

    if (bucket.tokens < 1) {
        ctx.heap.publish(key, bucket);
        return { status: 429, body: { error: 'Rate limit exceeded', retryAfterMs: 100 } };
    }

    bucket.tokens -= 1;
    ctx.heap.publish(key, bucket);
    return { status: 200, body: { allowed: true, remaining: Math.floor(bucket.tokens), clientId } };
}
rateLimiter._kubefn = { path: '/gw/ratelimit', methods: ['POST'] };

// Auth verifier
export function authVerify(req, ctx) {
    const token = (req.headers['authorization'] || '').replace('Bearer ', '');
    if (!token) {
        return { status: 401, body: { error: 'No token provided' } };
    }

    // Simulated JWT decode
    const userId = `user-${Math.abs(hashCode(token)) % 10000}`;
    const authContext = {
        userId,
        authenticated: true,
        roles: ['user'],
        tokenHash: hashCode(token),
        verifiedAt: Date.now()
    };

    // Publish to heap — all downstream functions read this zero-copy
    ctx.heap.publish('auth:current', authContext);

    return { status: 200, body: authContext };
}
authVerify._kubefn = { path: '/gw/auth', methods: ['POST'] };

// Request router
export function routeRequest(req, ctx) {
    const auth = ctx.heap.get('auth:current');
    const path = req.url || '/';

    const routes = {
        '/api/users': { service: 'user-service', port: 8080 },
        '/api/orders': { service: 'order-service', port: 8080 },
        '/api/products': { service: 'product-service', port: 8080 }
    };

    const route = routes[path] || { service: 'default', port: 8080 };

    return {
        status: 200,
        body: {
            routed: true,
            target: route,
            authenticatedUser: auth?.userId || 'anonymous',
            path,
            timestamp: Date.now()
        }
    };
}
routeRequest._kubefn = { path: '/gw/route', methods: ['GET', 'POST'] };

// Gateway proxy — orchestrates rate limit + auth + route
export function gatewayProxy(req, ctx) {
    const start = process.hrtime.bigint();

    // Step 1: Rate limit
    const rlResult = rateLimiter(req, ctx);
    if (rlResult.status !== 200) return rlResult;

    // Step 2: Auth
    const authResult = authVerify(req, ctx);

    // Step 3: Route
    const routeResult = routeRequest(req, ctx);

    const durationMs = Number(process.hrtime.bigint() - start) / 1_000_000;

    return {
        status: 200,
        body: {
            gateway: true,
            steps: {
                rateLimit: { allowed: true, remaining: rlResult.body.remaining },
                auth: { userId: authResult.body.userId, authenticated: authResult.body.authenticated },
                route: routeResult.body.target
            },
            _meta: {
                totalTimeMs: durationMs.toFixed(3),
                zeroCopy: true,
                heapObjects: ctx.heap.keys().length
            }
        }
    };
}
gatewayProxy._kubefn = { path: '/gw/proxy', methods: ['GET', 'POST'] };

function hashCode(str) {
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
        hash = ((hash << 5) - hash) + str.charCodeAt(i);
        hash |= 0;
    }
    return hash;
}
