"""Local-only Facebook Graph API mock for SocialHub (no Meta network calls).

Python 3.10+. Run: py -3 facebook_mock_server.py

Endpoints:
  GET  /health
  GET  /requests
  POST /vX.Y/{pageId}/photos (published=false for multi-photo upload)
  POST /vX.Y/{pageId}/feed   (text or attached_media[n])

Binds exclusively to 127.0.0.1. Does not fetch image URLs or contact Meta.
"""

from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
import json
import re
import threading

HOST = "127.0.0.1"
PORT = 9090
MOCK_TOKEN = "mock_test_token"
MAX_BODY_BYTES = 65536
MAX_IMAGES_PER_POST = 10

EVENTS = []
PHOTOS = {}  # mock_photo_id -> metadata (in-memory only)
LOCK = threading.Lock()
GRAPH_PATTERN = re.compile(r"^/v\d+\.\d+/(\d+)/(feed|photos)$")
ATTACHED_PATTERN = re.compile(r"^attached_media\[(\d+)\]$")


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health":
            return self.reply(200, {"status": "UP", "mode": "MOCK", "host": HOST, "port": PORT})
        if path == "/requests":
            with LOCK:
                snapshot = list(EVENTS)
            return self.reply(200, {"count": len(snapshot), "requests": snapshot})
        return self.reply(404, {"error": "Not found"})

    def do_POST(self):
        match = GRAPH_PATTERN.fullmatch(urlparse(self.path).path)
        if not match:
            return self.reply(404, {"error": "Only mock /photos and /feed endpoints are supported"})

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400, {"error": "Invalid Content-Length"})
        if length < 1 or length > MAX_BODY_BYTES:
            return self.reply(413, {"error": "Invalid request size"})
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/x-www-form-urlencoded":
            return self.reply(415, {"error": "Form URL encoded request required"})

        try:
            body = self.rfile.read(length).decode("utf-8", errors="strict")
        except UnicodeDecodeError:
            return self.reply(400, {"error": "Invalid UTF-8"})
        params = parse_qs(body, keep_blank_values=True)

        # Never store, print or return supplied access tokens.
        if params.get("access_token") != [MOCK_TOKEN]:
            return self.reply(403, {"error": "Mock token required"})

        page_id, endpoint = match.groups()
        if endpoint == "photos":
            return self.handle_photo(page_id, params)
        return self.handle_feed(page_id, params)

    def handle_photo(self, page_id, params):
        image_url = params.get("url", [""])[0].strip()
        if not image_url.startswith("https://") or not urlparse(image_url).hostname:
            return self.reply(400, {"error": "HTTPS photo URL required"})
        published = params.get("published", ["true"])[0].strip().lower()
        if published not in ("false", "true"):
            return self.reply(400, {"error": "Invalid published flag"})
        if published != "false":
            return self.reply(400, {"error": "This mock only supports unpublished photo uploads"})

        # Do NOT download, probe, or inspect image URLs.
        with LOCK:
            sequence = len(EVENTS) + 1
            photo_id = str(2000000000 + sequence)
            PHOTOS[photo_id] = {"page_id": page_id, "image_url": image_url}
            EVENTS.append({
                "sequence": sequence,
                "time": datetime.now(timezone.utc).isoformat(),
                "type": "PHOTO_UPLOAD",
                "page_id": page_id,
                "mock_photo_id": photo_id,
                "published": False,
                "image_url": image_url,
            })
        print(f"[MOCK] accepted unpublished photo #{sequence}, page={page_id}, id={photo_id}", flush=True)
        return self.reply(200, {"id": photo_id})

    def handle_feed(self, page_id, params):
        message = params.get("message", [""])[0]
        if not message.strip():
            return self.reply(400, {"error": "Empty message"})

        attached = []
        for key, values in params.items():
            item = ATTACHED_PATTERN.fullmatch(key)
            if not item:
                if key.startswith("attached_media"):
                    return self.reply(400, {"error": "Malformed attached_media"})
                continue
            if len(values) != 1:
                return self.reply(400, {"error": "Duplicate attached_media entry"})
            index = int(item.group(1))
            if index >= MAX_IMAGES_PER_POST:
                return self.reply(400, {"error": "Too many photos"})
            try:
                parsed = json.loads(values[0])
            except (ValueError, TypeError):
                return self.reply(400, {"error": "Invalid attached_media JSON"})
            if not isinstance(parsed, dict) or not isinstance(parsed.get("media_fbid"), str):
                return self.reply(400, {"error": "Missing media_fbid"})
            attached.append((index, parsed["media_fbid"]))

        attached.sort(key=lambda x: x[0])
        if attached and [index for index, _ in attached] != list(range(len(attached))):
            return self.reply(400, {"error": "Attached media indexes must start at 0 and be continuous"})
        ids = [photo_id for _, photo_id in attached]
        if len(ids) != len(set(ids)):
            return self.reply(400, {"error": "Duplicate attached photo"})

        with LOCK:
            if any(photo_id not in PHOTOS or PHOTOS[photo_id]["page_id"] != page_id for photo_id in ids):
                return self.reply(400, {"error": "Unknown photo or mismatched Page ID"})
            sequence = len(EVENTS) + 1
            external_id = f"{page_id}_{1000000000 + sequence}"
            EVENTS.append({
                "sequence": sequence,
                "time": datetime.now(timezone.utc).isoformat(),
                "type": "FEED_PUBLISH",
                "page_id": page_id,
                "message": message,
                "attached_photo_ids": ids,
                "mock_post_id": external_id,
            })
        print(f"[MOCK] accepted feed #{sequence}, page={page_id}, images={len(ids)}, id={external_id}", flush=True)
        return self.reply(200, {"id": external_id})

    def log_message(self, fmt, *args):
        print("[HTTP] " + fmt % args, flush=True)


if __name__ == "__main__":
    print(f"Facebook MOCK listening on http://{HOST}:{PORT}", flush=True)
    print("Text /feed and unpublished /photos + attached_media supported.", flush=True)
    print("Local-only. NEVER calls Meta or fetches image URLs.", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
