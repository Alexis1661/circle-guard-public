"""Minimal mock server that simulates all 4 Circle Guard services."""
import json, threading
from http.server import HTTPServer, BaseHTTPRequestHandler

RESPONSES = {
    ("POST", "/api/v1/auth/login"):        (200, {"token": "mock.jwt.token"}),
    ("GET",  "/api/v1/auth/qr/generate"):  (200, {"qrToken": "mock.qr.token"}),
    ("POST", "/api/v1/gate/validate"):     (200, {"valid": True, "status": "GREEN"}),
    ("GET",  "/api/v1/buildings"):         (200, [{"id": "1", "name": "Mock Bldg"}]),
    ("POST", "/api/v1/buildings"):         (200, {"id": "abc", "name": "Perf Bldg"}),
    ("POST", "/api/v1/surveys"):           (200, {"id": "s1", "hasFever": False}),
    ("GET",  "/api/v1/questionnaires"):    (200, []),
    ("GET",  "/actuator/health"):          (200, {"status": "UP"}),
}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _respond(self, code, body):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(body).encode())
    def do_GET(self):
        code, body = RESPONSES.get(("GET", self.path.split("?")[0]), (200, {}))
        self._respond(code, body)
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        code, body = RESPONSES.get(("POST", self.path.split("?")[0]), (200, {}))
        self._respond(code, body)

def serve(port):
    HTTPServer(("localhost", port), Handler).serve_forever()

for port in [8180, 8087, 8088, 8086]:
    threading.Thread(target=serve, args=(port,), daemon=True).start()
    print(f"Mock server on :{port}", flush=True)

import time
while True: time.sleep(1)
