"""Local, one-task SocialHub Group bridge. No cookies or Facebook credentials.

Loopback only; a random per-run pairing secret protects requests from browser pages.
The only upstream operations allowed are preview, claim, and result for ONE task.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

API = os.environ.get("SOCIALHUB_API", "http://127.0.0.1:8081").rstrip("/")
TOKEN = os.environ.get("GROUP_WORKER_TOKEN", "")
GROUP_ID = os.environ.get("GROUP_WORKER_EXPECTED_GROUP_ID", "1326570106277759")
TASK_ID = os.environ.get("GROUP_WORKER_TARGET_TASK_ID", "15e8de3f-cd4b-4ffb-96d0-ae5ec42a6c49").lower()
PORT = int(os.environ.get("GROUP_EDGE_BRIDGE_PORT", "8765"))
PAIR_CODE = secrets.token_urlsafe(18)

# Never allow a bridge configuration to make requests to an arbitrary host.
if urlparse(API).scheme != "http" or urlparse(API).hostname not in ("127.0.0.1", "localhost"):
    raise SystemExit("SOCIALHUB_API must be a local HTTP URL")
if not TOKEN or not re.fullmatch(r"[0-9]+", GROUP_ID) or not re.fullmatch(r"[a-f0-9-]{36}", TASK_ID):
    raise SystemExit("GROUP_WORKER_TOKEN, numeric GROUP_WORKER_EXPECTED_GROUP_ID and target UUID required")

LOCK = threading.Lock()
STATE = {"preview": None, "digest": None, "claimed": False, "reported": False}
EXT_ORIGIN = re.compile(r"^chrome-extension://[a-p]{32}$")
GROUP_URL = re.compile(r"^https://(?:www\.)?facebook\.com/groups/" + re.escape(GROUP_ID) + r"/?(?:\?.*)?$", re.I)
PERMALINK = re.compile(r"^https://www\.facebook\.com/groups/" + re.escape(GROUP_ID) + r"/(?:posts|permalink)/(?:[0-9]+|pfbid[A-Za-z0-9]+)/?(?:\?.*)?$", re.I)


class BridgeError(Exception):
    pass


def upstream(path: str, method: str = "GET", body: dict | None = None):
    assert path in ("/preview", "/claim") or re.fullmatch(r"/[a-f0-9-]{36}/result", path)
    payload = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(API + "/internal/group-worker" + path, data=payload,
                                     headers={"Authorization": "Bearer " + TOKEN,
                                              "Content-Type": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as resp:
            if resp.status == 204:
                return None
            raw = resp.read(200000)
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        raise BridgeError(f"Backend HTTP {exc.code} at {path} (check installed Group controller and token)") from None
    except (urllib.error.URLError, TimeoutError) as exc:
        raise BridgeError("Cannot reach local Backend on port 8081") from None


def validate_preview(preview: dict) -> None:
    if not isinstance(preview, dict):
        raise BridgeError("No exact Group Task returned; check Backend settings")
    if str(preview.get("id", "")).lower() != TASK_ID:
        raise BridgeError("Wrong Task ID returned")
    if not GROUP_URL.fullmatch(str(preview.get("groupUrl") or "")):
        raise BridgeError("Wrong Facebook Group URL returned")
    if preview.get("taskStatus") != "READY":
        raise BridgeError("Task is not READY; currently: " + str(preview.get("taskStatus")))
    if preview.get("postStatus") not in ("READY", "SCHEDULED"):
        raise BridgeError("Post status must be READY or SCHEDULED")
    if preview.get("approvalStatus") != "APPROVED":
        raise BridgeError("Post is not approved")
    if preview.get("due") is not True:
        raise BridgeError("Task is not due yet")
    if int(preview.get("imageCount") or 0) != 0 or int(preview.get("unsupportedMediaCount") or 0) != 0:
        raise BridgeError("Text-only pilot: media posts not supported")
    if not str(preview.get("content") or "").strip():
        raise BridgeError("Post content empty")


def signature(preview: dict) -> str:
    parts = [str(preview.get(k)) for k in ("id", "groupUrl", "content", "taskStatus", "approvalStatus", "due")]
    return hashlib.sha256("\n".join(parts).encode()).hexdigest()


def get_preview():
    p = upstream("/preview")
    validate_preview(p)
    d = signature(p)
    with LOCK:
        if STATE["claimed"]:
            raise BridgeError("This bridge already claimed one task. Stop and review its result")
        STATE["preview"] = p
        STATE["digest"] = d
    return {"id": TASK_ID, "groupId": GROUP_ID, "content": p["content"], "digest": d,
            "marker": "[SocialHub Group Pilot " + TASK_ID[:8] + "]"}


def claim_task(body: dict):
    with LOCK:
        if STATE["claimed"]:
            raise BridgeError("Already claimed one task. Never auto retry")
        if not STATE["digest"] or not hmac.compare_digest(str(body.get("digest", "")), STATE["digest"]):
            raise BridgeError("Preview changed or missing. Preview again")
        fresh = upstream("/preview")
        validate_preview(fresh)
        if signature(fresh) != STATE["digest"]:
            STATE["digest"] = None
            raise BridgeError("Content/state changed. Preview again")
        task = upstream("/claim", method="POST", body={})
        # /claim changes READY -> PROCESSING. Fail closed if payload differs.
        STATE["claimed"] = True if task is not None else False
        if task is None:
            raise BridgeError("Task was not claimed (might be no longer READY/due). No post sent")
        if (str(task.get("id", "")).lower() != TASK_ID or
                not GROUP_URL.fullmatch(str(task.get("groupUrl") or "")) or
                task.get("content") != fresh["content"] or
                task.get("imageUrls") or int(task.get("unsupportedMediaCount") or 0) != 0):
            upstream("/" + TASK_ID + "/result", "POST", {"status": "NEEDS_REVIEW", "publishedUrl": None,
                       "note": "Claim differed from read-only preview; no Facebook post was attempted"})
            STATE["reported"] = True
            raise BridgeError("Claim mismatched preview; marked NEEDS_REVIEW. Do not retry")
        return {"id": TASK_ID, "groupId": GROUP_ID, "content": task["content"],
                "marker": "[SocialHub Group Pilot " + TASK_ID[:8] + "]"}


def report_result(body: dict):
    with LOCK:
        if not STATE["claimed"] or STATE["reported"]:
            raise BridgeError("No unreported claim")
        status = body.get("status")
        link = body.get("publishedUrl")
        if status not in ("PUBLISHED", "NEEDS_REVIEW"):
            raise BridgeError("Unsupported result status")
        if status == "PUBLISHED" and not PERMALINK.fullmatch(str(link or "")):
            raise BridgeError("Verified exact Group permalink required")
        note = str(body.get("note", ""))[:350]
        upstream("/" + TASK_ID + "/result", "POST", {"status": status,
                 "publishedUrl": link if status == "PUBLISHED" else None, "note": note})
        STATE["reported"] = True
    return {"status": status, "publishedUrl": link if status == "PUBLISHED" else None}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Avoid logging pairing secrets or post body.
        print("Bridge:", format % args, flush=True)

    def _cors(self):
        origin = self.headers.get("Origin", "")
        if not EXT_ORIGIN.fullmatch(origin):
            return False
        self.send_header("Access-Control-Allow-Origin", origin)
        self.send_header("Vary", "Origin")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-SocialHub-Pair")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Cache-Control", "no-store")
        return True

    def output(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        cors = self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        # Extension background/service-worker fetches may omit Origin when
        # host_permissions grants loopback access. Don't require it *if absent*.
        # Never trust an explicit non-extension Origin (including "null").
        origin = self.headers.get("Origin", "")
        extension_origin = bool(EXT_ORIGIN.fullmatch(origin))
        origin_allowed = not origin or extension_origin
        supplied = self.headers.get("X-SocialHub-Pair", "")
        code_matches = bool(supplied) and hmac.compare_digest(supplied, PAIR_CODE)
        if not origin_allowed or not code_matches:
            kind = "extension" if extension_origin else ("absent" if not origin else "non-extension")
            # Log *only booleans*, never pairing code, token, or post text.
            print(f"Bridge auth denied: origin={kind}, pair_match={code_matches}", flush=True)
            return False
        return True

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def handle_request(self):
        if not self.authorized():
            return self.output(403, {"error": "Extension pairing required"})
        if self.path not in ("/preview", "/claim", "/result"):
            return self.output(404, {"error": "Unknown route"})
        try:
            body = {}
            if self.command == "POST":
                length = int(self.headers.get("Content-Length") or 0)
                if length > 10000:
                    return self.output(413, {"error": "Request too large"})
                body = json.loads(self.rfile.read(length) or b"{}")
                if not isinstance(body, dict):
                    raise BridgeError("Expected JSON object")
            if self.path == "/preview" and self.command == "GET":
                result = get_preview()
            elif self.path == "/claim" and self.command == "POST":
                result = claim_task(body)
            elif self.path == "/result" and self.command == "POST":
                result = report_result(body)
            else:
                return self.output(405, {"error": "Invalid method"})
            return self.output(200, result)
        except BridgeError as exc:
            return self.output(409, {"error": str(exc)})
        except Exception as exc:
            print("Bridge internal error", type(exc).__name__, flush=True)
            return self.output(500, {"error": "Bridge internal error. Inspect local terminal; do not retry automatically"})

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()


def main():
    httpd = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("SocialHub Edge bridge: http://127.0.0.1:" + str(PORT))
    print("Group ID:", GROUP_ID)
    print("Task ID: ", TASK_ID)
    print("PAIRING CODE (enter only in Edge extension popup):", PAIR_CODE)
    print("No Facebook cookies/passwords are read; no automatic publish on startup.")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
