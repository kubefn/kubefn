"""
KubeFn Python HTTP Server — async HTTP server using uvicorn/starlette.
Falls back to built-in http.server if dependencies unavailable.

Dispatches requests to registered function handlers on the shared interpreter.
"""

import json
import logging
import time
import os
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from typing import Optional

from .heap_exchange import HeapExchange
from .context import FnContext, FnRequest
from .decorators import FunctionMetadata, get_registered_functions
from .loader import FunctionLoader

logger = logging.getLogger("kubefn.server")


class KubeFnHandler(BaseHTTPRequestHandler):
    """HTTP request handler that dispatches to KubeFn functions."""

    heap: HeapExchange = None
    loader: FunctionLoader = None
    request_counter: int = 0
    start_time: float = 0

    def do_GET(self):
        self._handle_request("GET")

    def do_POST(self):
        self._handle_request("POST")

    def _handle_request(self, method: str):
        parsed = urlparse(self.path)
        path = parsed.path
        query_params = {k: v[0] for k, v in parse_qs(parsed.query).items()}

        # Admin endpoints
        if path.startswith("/admin") or path in ("/healthz", "/readyz"):
            self._handle_admin(method, path, query_params)
            return

        # Find matching function
        KubeFnHandler.request_counter += 1
        request_id = f"py-req-{int(time.time()*1000):x}-{KubeFnHandler.request_counter}"

        matched = self._resolve_function(method, path)
        if not matched:
            self._send_json(404, {
                "error": f"No function for {method} {path}",
                "status": 404,
                "requestId": request_id,
            })
            return

        fn_meta = matched

        # Read body
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length) if content_length > 0 else b""

        # Build request + context
        headers = {k.lower(): v for k, v in self.headers.items()}
        request = FnRequest(
            method=method, path=path, headers=headers,
            query_params=query_params, body=body,
            body_text=body.decode("utf-8") if body else "",
        )

        ctx = FnContext(
            heap=KubeFnHandler.heap,
            group_name=fn_meta.group,
            function_name=fn_meta.name,
            revision_id="py-rev-1",
            config={},
        )

        # Set heap context for attribution
        KubeFnHandler.heap.set_context(fn_meta.group, fn_meta.name)

        # Execute
        start_ns = time.time_ns()
        try:
            result = fn_meta.handler(request, ctx)
            duration_ms = (time.time_ns() - start_ns) / 1_000_000

            response = result if isinstance(result, dict) else {"result": result}
            response.setdefault("_meta", {}).update({
                "requestId": request_id,
                "durationMs": f"{duration_ms:.3f}",
                "function": fn_meta.name,
                "group": fn_meta.group,
                "runtime": "python",
                "zeroCopy": True,
            })

            self._send_json(200, response, extra_headers={
                "X-KubeFn-Request-Id": request_id,
                "X-KubeFn-Runtime": "python-0.3.1",
                "X-KubeFn-Group": fn_meta.group,
            })

        except Exception as e:
            duration_ms = (time.time_ns() - start_ns) / 1_000_000
            logger.error(f"Function error: {fn_meta.group}.{fn_meta.name} [{request_id}]: {e}")
            self._send_json(500, {
                "error": str(e),
                "function": f"{fn_meta.group}.{fn_meta.name}",
                "requestId": request_id,
                "durationMs": f"{duration_ms:.3f}",
            })
        finally:
            KubeFnHandler.heap.clear_context()

    def _resolve_function(self, method: str, path: str) -> Optional[FunctionMetadata]:
        """Find the function that matches this method + path."""
        best_match = None
        best_len = 0

        for fn in get_registered_functions():
            if method.upper() in [m.upper() for m in fn.methods]:
                if path == fn.path or path.startswith(fn.path + "/"):
                    if len(fn.path) > best_len:
                        best_match = fn
                        best_len = len(fn.path)

        return best_match

    def _handle_admin(self, method: str, path: str, query_params: dict):
        """Handle admin/health endpoints."""
        functions = get_registered_functions()

        if path == "/healthz":
            self._send_json(200, {
                "status": "alive",
                "organism": "kubefn",
                "version": "0.3.1",
                "runtime": "python",
            })
        elif path == "/readyz":
            ready = len(functions) > 0
            self._send_json(200 if ready else 503, {
                "status": "ready" if ready else "no_functions_loaded",
                "functionCount": len(functions),
                "runtime": "python",
            })
        elif path == "/admin/functions":
            fn_list = []
            for fn in functions:
                for m in fn.methods:
                    fn_list.append({
                        "method": m,
                        "path": fn.path,
                        "group": fn.group,
                        "function": fn.name,
                        "runtime": "python",
                    })
            self._send_json(200, {"functions": fn_list, "count": len(fn_list)})
        elif path == "/admin/heap":
            self._send_json(200, KubeFnHandler.heap.metrics())
        elif path == "/admin/status":
            uptime = time.time() - KubeFnHandler.start_time
            self._send_json(200, {
                "version": "0.3.1",
                "runtime": "python",
                "uptime_s": int(uptime),
                "route_count": len(functions),
                "heap_objects": KubeFnHandler.heap.size(),
                "total_requests": KubeFnHandler.request_counter,
            })
        else:
            self._send_json(404, {"error": f"Unknown admin endpoint: {path}"})

    def _send_json(self, status: int, data: dict, extra_headers: dict = None):
        body = json.dumps(data, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        """Suppress default HTTP server logs — we use our own."""
        pass


def run_server(host: str = "0.0.0.0", port: int = 8080,
               functions_dir: str = "/var/kubefn/functions"):
    """Start the KubeFn Python runtime."""

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
    )

    logger.info("Booting KubeFn Python organism v0.3.1...")

    # Create shared HeapExchange
    heap = HeapExchange()
    KubeFnHandler.heap = heap
    KubeFnHandler.start_time = time.time()

    # Load functions
    loader = FunctionLoader(functions_dir, heap)
    KubeFnHandler.loader = loader
    loaded = loader.load_all()

    total_routes = sum(len(fns) for fns in loaded.values())

    logger.info("╔════════════════════════════════════════════════════╗")
    logger.info("║   KubeFn v0.3.1 — Python Runtime                   ║")
    logger.info("║   Memory-Continuous Architecture                    ║")
    logger.info("╠════════════════════════════════════════════════════╣")
    logger.info(f"║  HTTP:  port {port}                                    ║")
    logger.info(f"║  Functions: {functions_dir}")
    logger.info(f"║  Routes: {total_routes}                                        ║")
    logger.info("║  HeapExchange: enabled                              ║")
    logger.info("║  Runtime: CPython                                    ║")
    logger.info("╚════════════════════════════════════════════════════╝")
    logger.info(f"KubeFn Python organism is ALIVE. {total_routes} routes registered.")

    server = HTTPServer((host, port), KubeFnHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Shutting down Python organism...")
        server.shutdown()
