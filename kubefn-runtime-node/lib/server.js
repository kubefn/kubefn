#!/usr/bin/env node

/**
 * KubeFn Node.js Runtime — Memory-Continuous Architecture for JavaScript.
 *
 * Same concept as JVM and Python runtimes: functions share a V8 isolate,
 * exchange objects via HeapExchange with zero serialization.
 */

const http = require('http');
const { URL } = require('url');
const { HeapExchange } = require('./heap-exchange');
const { FunctionLoader } = require('./loader');

const PORT = parseInt(process.env.KUBEFN_PORT || '8080');
const FUNCTIONS_DIR = process.env.KUBEFN_FUNCTIONS_DIR || '/var/kubefn/functions';

const heap = new HeapExchange();
const loader = new FunctionLoader(FUNCTIONS_DIR, heap);
let requestCounter = 0;
const startTime = Date.now();

// Load functions
loader.loadAll();

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;
  const method = req.method;
  const queryParams = Object.fromEntries(url.searchParams);

  // Admin endpoints
  if (path === '/healthz') {
    return sendJson(res, 200, { status: 'alive', organism: 'kubefn', version: '0.3.1', runtime: 'node' });
  }
  if (path === '/readyz') {
    const fns = loader.allFunctions();
    const ready = fns.length > 0;
    return sendJson(res, ready ? 200 : 503, {
      status: ready ? 'ready' : 'no_functions_loaded',
      functionCount: fns.length, runtime: 'node',
    });
  }
  if (path === '/admin/functions') {
    const fns = loader.allFunctions();
    return sendJson(res, 200, { functions: fns, count: fns.length });
  }
  if (path === '/admin/heap') {
    return sendJson(res, 200, heap.metrics());
  }
  if (path === '/admin/status') {
    return sendJson(res, 200, {
      version: '0.3.1', runtime: 'node',
      uptime_s: Math.floor((Date.now() - startTime) / 1000),
      route_count: loader.allFunctions().length,
      heap_objects: heap.size(),
      total_requests: requestCounter,
    });
  }

  // Resolve function
  requestCounter++;
  const requestId = `node-req-${Date.now().toString(16)}-${requestCounter}`;

  const resolved = loader.resolve(method, path);
  if (!resolved) {
    return sendJson(res, 404, {
      error: `No function for ${method} ${path}`,
      requestId, status: 404,
    });
  }

  const { fn } = resolved;
  heap.setContext(fn.group, fn.name);

  // Read body
  const body = await readBody(req);

  // Build request context
  const request = { method, path, queryParams, headers: req.headers, body, bodyText: body.toString('utf-8') };
  const ctx = {
    heap,
    groupName: fn.group,
    functionName: fn.name,
    revisionId: 'node-rev-1',
    config: {},
    logger: {
      info: (msg) => console.log(`[${fn.group}.${fn.name}] ${msg}`),
      error: (msg) => console.error(`[${fn.group}.${fn.name}] ${msg}`),
    },
  };

  // Execute
  const startNs = process.hrtime.bigint();
  try {
    const result = await fn.handler(request, ctx);
    const durationMs = Number(process.hrtime.bigint() - startNs) / 1_000_000;

    const response = typeof result === 'object' ? result : { result };
    if (!response._meta) response._meta = {};
    Object.assign(response._meta, {
      requestId, durationMs: durationMs.toFixed(3),
      function: fn.name, group: fn.group,
      runtime: 'node', zeroCopy: true,
    });

    sendJson(res, 200, response, {
      'X-KubeFn-Request-Id': requestId,
      'X-KubeFn-Runtime': 'node-0.3.1',
      'X-KubeFn-Group': fn.group,
    });
  } catch (e) {
    const durationMs = Number(process.hrtime.bigint() - startNs) / 1_000_000;
    console.error(`Function error: ${fn.group}.${fn.name} [${requestId}]: ${e.message}`);
    sendJson(res, 500, { error: e.message, requestId, durationMs: durationMs.toFixed(3) });
  } finally {
    heap.clearContext();
  }
});

server.listen(PORT, () => {
  const totalRoutes = loader.allFunctions().length;
  console.log('╔════════════════════════════════════════════════════╗');
  console.log('║   KubeFn v0.3.1 — Node.js Runtime                  ║');
  console.log('║   Memory-Continuous Architecture                    ║');
  console.log('╠════════════════════════════════════════════════════╣');
  console.log(`║  HTTP:  port ${PORT}                                    ║`);
  console.log(`║  Functions: ${FUNCTIONS_DIR}`);
  console.log(`║  Routes: ${totalRoutes}                                        ║`);
  console.log('║  HeapExchange: enabled                              ║');
  console.log('║  Runtime: V8 / Node.js                              ║');
  console.log('╚════════════════════════════════════════════════════╝');
  console.log(`KubeFn Node.js organism is ALIVE. ${totalRoutes} routes registered.`);
});

function sendJson(res, status, data, extraHeaders = {}) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    ...extraHeaders,
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
  });
}
