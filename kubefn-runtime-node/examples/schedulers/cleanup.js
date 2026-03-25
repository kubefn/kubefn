/**
 * Scheduled Session Cleanup — purges expired sessions, stale heap entries,
 * and orphaned rate-limit buckets.
 *
 * Runs on a cron schedule (every 5 minutes). Publishes cleanup stats to
 * the heap so monitoring functions can read them zero-copy.
 *
 * Functions export with _kubefn metadata + schedule for the loader.
 */

// ── Configuration ──────────────────────────────────────────────────

const SESSION_TTL_MS = 30 * 60 * 1000;       // 30 minutes
const RATELIMIT_TTL_MS = 10 * 60 * 1000;     // 10 minutes
const ML_RESULT_TTL_MS = 5 * 60 * 1000;      // 5 minutes
const TELEMETRY_TTL_MS = 15 * 60 * 1000;     // 15 minutes

// ── Session cleanup ────────────────────────────────────────────────

function cleanupSessions(req, ctx) {
    const now = Date.now();
    const keys = ctx.heap.keys();
    let expired = 0;
    let active = 0;
    const expiredSessions = [];

    for (const key of keys) {
        if (!key.startsWith('session:')) continue;

        const session = ctx.heap.get(key);
        if (!session) continue;

        const lastActivity = session.lastActivityAt || session.createdAt || 0;
        if (now - lastActivity > SESSION_TTL_MS) {
            ctx.heap.remove(key);
            expired++;
            expiredSessions.push({
                sessionId: key,
                userId: session.userId || 'unknown',
                idleMs: now - lastActivity
            });
        } else {
            active++;
        }
    }

    const result = {
        type: 'session-cleanup',
        expired,
        active,
        expiredSessions: expiredSessions.slice(0, 10), // cap detail list
        cleanedAt: now
    };

    ctx.heap.publish('cleanup:sessions', result);
    return { status: 200, body: result };
}
cleanupSessions._kubefn = {
    path: '/scheduler/cleanup/sessions',
    methods: ['POST'],
    group: 'schedulers',
    schedule: { cron: '*/5 * * * *' }
};

// ── Rate-limit bucket cleanup ──────────────────────────────────────

function cleanupRateLimits(req, ctx) {
    const now = Date.now();
    const keys = ctx.heap.keys();
    let purged = 0;
    let kept = 0;

    for (const key of keys) {
        if (!key.startsWith('ratelimit:')) continue;

        const bucket = ctx.heap.get(key);
        if (!bucket) continue;

        // Purge buckets that haven't been refilled recently
        if (now - (bucket.lastRefill || 0) > RATELIMIT_TTL_MS) {
            ctx.heap.remove(key);
            purged++;
        } else {
            kept++;
        }
    }

    const result = {
        type: 'ratelimit-cleanup',
        purged,
        kept,
        cleanedAt: now
    };

    ctx.heap.publish('cleanup:ratelimits', result);
    return { status: 200, body: result };
}
cleanupRateLimits._kubefn = {
    path: '/scheduler/cleanup/ratelimits',
    methods: ['POST'],
    group: 'schedulers',
    schedule: { cron: '*/10 * * * *' }
};

// ── Stale ML results cleanup ───────────────────────────────────────

function cleanupMLResults(req, ctx) {
    const now = Date.now();
    const prefixes = ['ml:features', 'ml:prediction', 'ml:explanation'];
    const keys = ctx.heap.keys();
    let purged = 0;

    for (const key of keys) {
        const isMLKey = prefixes.some(p => key.startsWith(p));
        if (!isMLKey) continue;

        const value = ctx.heap.get(key);
        if (!value) continue;

        const createdAt = value.extractedAt || value.predictedAt || value.explainedAt || 0;
        if (now - createdAt > ML_RESULT_TTL_MS) {
            ctx.heap.remove(key);
            purged++;
        }
    }

    const result = {
        type: 'ml-cleanup',
        purged,
        cleanedAt: now
    };

    ctx.heap.publish('cleanup:ml', result);
    return { status: 200, body: result };
}
cleanupMLResults._kubefn = {
    path: '/scheduler/cleanup/ml',
    methods: ['POST'],
    group: 'schedulers',
    schedule: { cron: '*/5 * * * *' }
};

// ── Full cleanup sweep (orchestrates all sub-cleanups) ─────────────

function cleanupSweep(req, ctx) {
    const start = process.hrtime.bigint();
    const heapSizeBefore = ctx.heap.keys().length;

    // Run all cleanups
    const sessionResult = cleanupSessions(req, ctx);
    const rlResult = cleanupRateLimits(req, ctx);
    const mlResult = cleanupMLResults(req, ctx);

    // Also clean up old telemetry entries
    const now = Date.now();
    let telemetryPurged = 0;
    for (const key of ctx.heap.keys()) {
        if (!key.startsWith('gw:telemetry') && !key.startsWith('cleanup:')) continue;
        const value = ctx.heap.get(key);
        if (value && value.timestamp && now - value.timestamp > TELEMETRY_TTL_MS) {
            ctx.heap.remove(key);
            telemetryPurged++;
        }
    }

    const heapSizeAfter = ctx.heap.keys().length;
    const durationNs = process.hrtime.bigint() - start;
    const durationMs = Number(durationNs) / 1_000_000;

    const totalPurged =
        sessionResult.body.expired +
        rlResult.body.purged +
        mlResult.body.purged +
        telemetryPurged;

    const sweepResult = {
        type: 'full-sweep',
        totalPurged,
        heapSizeBefore,
        heapSizeAfter,
        breakdown: {
            sessions: { expired: sessionResult.body.expired, active: sessionResult.body.active },
            rateLimits: { purged: rlResult.body.purged, kept: rlResult.body.kept },
            mlResults: { purged: mlResult.body.purged },
            telemetry: { purged: telemetryPurged }
        },
        durationMs: parseFloat(durationMs.toFixed(3)),
        completedAt: now
    };

    ctx.heap.publish('cleanup:last-sweep', sweepResult);

    return { status: 200, body: sweepResult };
}
cleanupSweep._kubefn = {
    path: '/scheduler/cleanup/sweep',
    methods: ['POST'],
    group: 'schedulers',
    schedule: { cron: '*/5 * * * *' }
};

module.exports = { cleanupSessions, cleanupRateLimits, cleanupMLResults, cleanupSweep };
