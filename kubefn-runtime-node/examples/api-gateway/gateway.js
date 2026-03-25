/**
 * API Gateway — rate limiting, auth verification, request routing
 *
 * A 3-step pipeline that demonstrates HeapExchange zero-copy sharing
 * between gateway functions. Each step publishes state to the heap;
 * downstream steps read it without serialization.
 *
 * Functions export with _kubefn metadata so the loader can register routes.
 */

// ── Rate limiter (token-bucket per client) ─────────────────────────

function rateLimiter(req, ctx) {
    const clientId = req.headers['x-client-id'] || req.headers['x-forwarded-for'] || 'anonymous';
    const key = `ratelimit:${clientId}`;
    const now = Date.now();

    // Read existing bucket from heap (zero-copy)
    let bucket = ctx.heap.get(key) || { tokens: 100, lastRefill: now, totalRequests: 0 };

    // Refill tokens — 10 per second, max 100
    const elapsed = (now - bucket.lastRefill) / 1000;
    bucket.tokens = Math.min(100, bucket.tokens + elapsed * 10);
    bucket.lastRefill = now;
    bucket.totalRequests++;

    if (bucket.tokens < 1) {
        ctx.heap.publish(key, bucket);
        const retryAfterMs = Math.ceil((1 - bucket.tokens) / 10 * 1000);
        return {
            status: 429,
            body: {
                error: 'Rate limit exceeded',
                clientId,
                retryAfterMs,
                totalRequests: bucket.totalRequests
            }
        };
    }

    bucket.tokens -= 1;
    ctx.heap.publish(key, bucket);

    // Publish rate-limit result so downstream steps can read it
    ctx.heap.publish('gw:ratelimit-result', {
        allowed: true,
        remaining: Math.floor(bucket.tokens),
        clientId,
        checkedAt: now
    });

    return {
        status: 200,
        body: { allowed: true, remaining: Math.floor(bucket.tokens), clientId }
    };
}
rateLimiter._kubefn = {
    path: '/gw/ratelimit',
    methods: ['POST'],
    group: 'api-gateway'
};

// ── Auth verifier ──────────────────────────────────────────────────

function authVerify(req, ctx) {
    const header = req.headers['authorization'] || '';
    const token = header.replace(/^Bearer\s+/i, '');

    if (!token) {
        return { status: 401, body: { error: 'Missing authorization token' } };
    }

    // Validate token structure (simplified — real impl would verify JWT signature)
    if (token.length < 8) {
        return { status: 401, body: { error: 'Malformed token' } };
    }

    const userId = `user-${Math.abs(hashCode(token)) % 100000}`;
    const roles = token.startsWith('admin-') ? ['admin', 'user'] : ['user'];

    const authContext = {
        userId,
        authenticated: true,
        roles,
        permissions: roles.includes('admin')
            ? ['read', 'write', 'delete', 'admin']
            : ['read', 'write'],
        tokenHash: hashCode(token),
        verifiedAt: Date.now(),
        expiresAt: Date.now() + 3600_000
    };

    // Publish to heap — all downstream functions read this zero-copy
    ctx.heap.publish('auth:current', authContext);

    return { status: 200, body: authContext };
}
authVerify._kubefn = {
    path: '/gw/auth',
    methods: ['POST'],
    group: 'api-gateway'
};

// ── Request router ─────────────────────────────────────────────────

function routeRequest(req, ctx) {
    const auth = ctx.heap.get('auth:current');
    const rlResult = ctx.heap.get('gw:ratelimit-result');
    const path = req.url || req.path || '/';

    // Route table — in production this would be loaded from config
    const routes = {
        '/api/users':    { service: 'user-service',    port: 8080, requiresAuth: true },
        '/api/orders':   { service: 'order-service',   port: 8080, requiresAuth: true },
        '/api/products': { service: 'product-service', port: 8080, requiresAuth: false },
        '/api/health':   { service: 'health-check',    port: 8081, requiresAuth: false }
    };

    // Longest-prefix match
    let matchedPath = null;
    let matchedRoute = null;
    for (const [routePath, route] of Object.entries(routes)) {
        if (path.startsWith(routePath)) {
            if (!matchedPath || routePath.length > matchedPath.length) {
                matchedPath = routePath;
                matchedRoute = route;
            }
        }
    }

    if (!matchedRoute) {
        return { status: 404, body: { error: 'No route matched', path } };
    }

    if (matchedRoute.requiresAuth && (!auth || !auth.authenticated)) {
        return { status: 403, body: { error: 'Authentication required for this route', path } };
    }

    return {
        status: 200,
        body: {
            routed: true,
            target: matchedRoute,
            matchedPath,
            authenticatedUser: auth?.userId || 'anonymous',
            rateLimit: rlResult ? { remaining: rlResult.remaining } : null,
            path,
            timestamp: Date.now()
        }
    };
}
routeRequest._kubefn = {
    path: '/gw/route',
    methods: ['GET', 'POST'],
    group: 'api-gateway'
};

// ── Gateway proxy (full 3-step pipeline) ───────────────────────────

function gatewayProxy(req, ctx) {
    const start = process.hrtime.bigint();

    // Step 1: Rate limit check
    const rlResult = rateLimiter(req, ctx);
    if (rlResult.status !== 200) return rlResult;

    // Step 2: Auth verification
    const authResult = authVerify(req, ctx);
    if (authResult.status === 401) {
        // Continue as anonymous for non-protected routes
    }

    // Step 3: Route the request
    const routeResult = routeRequest(req, ctx);

    const durationNs = process.hrtime.bigint() - start;
    const durationMs = Number(durationNs) / 1_000_000;

    // Publish gateway telemetry to heap for observability functions
    ctx.heap.publish('gw:telemetry', {
        durationMs,
        steps: ['ratelimit', 'auth', 'route'],
        finalStatus: routeResult.status,
        timestamp: Date.now()
    });

    return {
        status: routeResult.status,
        body: {
            gateway: true,
            steps: {
                rateLimit: { allowed: true, remaining: rlResult.body.remaining },
                auth: {
                    userId: authResult.body.userId || 'anonymous',
                    authenticated: authResult.body.authenticated || false
                },
                route: routeResult.body.target || null
            },
            _meta: {
                totalTimeMs: durationMs.toFixed(3),
                zeroCopy: true,
                heapKeys: ctx.heap.keys().length
            }
        }
    };
}
gatewayProxy._kubefn = {
    path: '/gw/proxy',
    methods: ['GET', 'POST'],
    group: 'api-gateway'
};

// ── Utility ────────────────────────────────────────────────────────

function hashCode(str) {
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
        hash = ((hash << 5) - hash) + str.charCodeAt(i);
        hash |= 0;
    }
    return hash;
}

module.exports = { rateLimiter, authVerify, routeRequest, gatewayProxy };
