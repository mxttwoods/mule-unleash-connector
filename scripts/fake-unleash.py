"""Minimal Unleash-compatible stub (no Docker, no GitLab).
Serves the same shape as UnleashLiveClientTest.FEATURES_JSON:
  GET  /api/client/features -> features JSON
  POST /api/client/register -> 200
  POST /api/client/metrics  -> 200
Usage: python3 scripts/fake-unleash.py [port] (default 4242)
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

FEATURES = {
    "version": 1,
    "features": [
        {
            "name": "new-checkout",
            "description": "enabled for everyone",
            "enabled": True,
            "strategies": [{"name": "default"}],
        },
        {
            "name": "retired-flag",
            "description": "switched off",
            "enabled": False,
            "strategies": [{"name": "default"}],
        },
        {
            "name": "checkout-copy",
            "description": "single variant with payload",
            "enabled": True,
            "strategies": [{"name": "default"}],
            "variants": [
                {"name": "treatment", "weight": 1000,
                 "payload": {"type": "string", "value": "blue"}}
            ],
        },
        {
            "name": "vip-flag",
            "description": "only for the vip user",
            "enabled": True,
            "strategies": [{"name": "userWithId", "parameters": {"userIds": "vip-user"}}],
        },
    ],
}

BODY = json.dumps(FEATURES).encode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        sys.stderr.write("%s %s %s\n" % (self.command, self.path, dict(self.headers)))

    def do_GET(self):
        if self.path.startswith("/api/client/features"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(BODY)))
            self.end_headers()
            self.wfile.write(BODY)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)
        if self.path.startswith(("/api/client/register", "/api/client/metrics")):
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 4242
    server = HTTPServer(("127.0.0.1", port), Handler)
    print(f"fake-unleash on http://127.0.0.1:{port}/api", flush=True)
    server.serve_forever()
