"""Minimal Unleash-compatible stub for the examples (no Docker, no GitLab).

Serves the flags from UnleashLiveClientTest.FEATURES_JSON, plus two Salesforce migration flags
that start off, under any base path, so both URL shapes work:
  http://127.0.0.1:4242/api                                    (Unleash server)
  http://127.0.0.1:4242/api/v4/feature_flags/unleash/42        (GitLab project)

  GET  <base>/client/features -> flags JSON
  POST <base>/client/register -> 200
  POST <base>/client/metrics  -> 200
  POST /admin/flags/<name>?enabled=true|false -> flips a flag, like toggling it in GitLab

Usage: python3 examples/fake-unleash.py [port]   (default 4242)
"""
import json
import sys
from urllib.parse import parse_qs, urlsplit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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
            "name": "salesforce-loyalty-tier",
            "description": "expose Loyalty_Tier__c once the Salesforce field exists",
            "enabled": False,
            "strategies": [{"name": "default"}],
        },
        {
            "name": "salesforce-is-active-checkbox",
            "description": "read Is_Active__c instead of the Status__c picklist",
            "enabled": False,
            "strategies": [{"name": "default"}],
        },
        {
            "name": "vip-flag",
            "description": "only for the vip user",
            "enabled": True,
            "strategies": [{"name": "userWithId", "parameters": {"userIds": "vip-user"}}],
        },
    ],
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("%s instance=%s\n" % (fmt % args, self.headers.get("UNLEASH-INSTANCEID")))

    def _path(self):
        return self.path.split("?", 1)[0].rstrip("/")

    def _reply(self, status, body=b"", content_type=None):
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self._path().endswith("/client/features"):
            self._reply(200, json.dumps(FEATURES).encode(), "application/json")
        else:
            self._reply(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)
        path = self._path()
        if path.endswith(("/client/register", "/client/metrics")):
            self._reply(200)
        elif path.startswith("/admin/flags/"):
            self._flip(path[len("/admin/flags/"):])
        else:
            self._reply(404)

    def _flip(self, name):
        enabled = parse_qs(urlsplit(self.path).query).get("enabled", ["true"])[0] == "true"
        for feature in FEATURES["features"]:
            if feature["name"] == name:
                feature["enabled"] = enabled
                self._reply(200, json.dumps(feature).encode(), "application/json")
                return
        self._reply(404)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 4242
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"fake-unleash on http://127.0.0.1:{port}/api", flush=True)
    server.serve_forever()
