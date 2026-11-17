from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
import time
import uuid
from urllib.parse import parse_qs

MODE = os.getenv("MOCK_META_MODE", "fail_once").lower()
TIMEOUT_SECONDS = int(os.getenv("MOCK_TIMEOUT_SECONDS", "75"))

request_count = 0
accepted_posts = []


class MockMetaHandler(BaseHTTPRequestHandler):

    def send_json(self, status_code, data):
        payload = json.dumps(data).encode("utf-8")

        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()

        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/mock/status":
            self.send_json(200, {
                "mode": MODE,
                "request_count": request_count,
                "accepted_count": len(accepted_posts),
                "accepted_posts": accepted_posts
            })
            return

        self.send_json(404, {
            "error": {"message": "Endpoint not found"}
        })

    def do_POST(self):
        global request_count

        if not self.path.endswith("/feed"):
            self.send_json(404, {
                "error": {"message": "Endpoint not found"}
            })
            return

        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")

        params = parse_qs(body)
        message = params.get("message", [""])[0]

        request_count += 1

        print(
            f"Request #{request_count} | Mode: {MODE}",
            flush=True
        )

        should_fail = (
                MODE == "fail"
                or (MODE == "fail_once" and request_count == 1)
        )

        if should_fail:
            self.send_json(500, {
                "error": {
                    "message": "Mock Meta API temporary failure",
                    "code": 500
                }
            })

            print("Response: HTTP 500", flush=True)
            return

        external_id = f"mock_page_{uuid.uuid4().hex}"

        accepted_posts.append({
            "id": external_id,
            "message": message
        })

        if MODE == "timeout_after_accept":
            print(
                f"ACCEPTED: {external_id} | Response withheld",
                flush=True
            )

            # Longer than the Spring HTTP read timeout.
            time.sleep(TIMEOUT_SECONDS)

            # Close without returning a successful response.
            self.close_connection = True
            return

        self.send_json(200, {
            "id": external_id
        })

        print(
            f"Response: HTTP 200 | Post ID: {external_id}",
            flush=True
        )


if __name__ == "__main__":
    server = HTTPServer(
        ("127.0.0.1", 9090),
        MockMetaHandler
    )

    print(
        f"Mock Meta API running at http://127.0.0.1:9090 | Mode: {MODE}",
        flush=True
    )

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Mock Meta API stopped")
    finally:
        server.server_close()
