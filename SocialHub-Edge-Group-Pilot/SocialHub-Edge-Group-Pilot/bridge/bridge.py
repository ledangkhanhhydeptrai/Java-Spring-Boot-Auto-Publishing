"""Local, one-task SocialHub Group bridge. No cookies or Facebook credentials.

Loopback only; a random per-run pairing secret protects requests from browser pages.
Only one exact configured task can be previewed, staged, claimed and reported.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import pathlib
import tempfile
import atexit
import shutil
import subprocess
import ipaddress
import urllib.parse
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
STATE = {"preview": None, "manifest": None, "digest": None, "claimed": False,
         "reported": False, "staged": None, "mediaKind": None}
MAX_IMAGES = 10
MAX_VIDEO_BYTES = 256 * 1024 * 1024  # conservative one-task MP4 pilot
MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_TOTAL_BYTES = 70 * 1024 * 1024
# Exact HTTPS host allowlist; operator must explicitly authorize their image CDN.
ALLOWED_MEDIA_HOSTS = set(h.strip().lower() for h in os.environ.get(
    "GROUP_MEDIA_ALLOWED_HOSTS", "res.cloudinary.com").split(",") if h.strip())
STAGING_DIR = pathlib.Path(tempfile.mkdtemp(prefix="socialhub_group_media_pilot_"))
atexit.register(lambda: shutil.rmtree(STAGING_DIR, ignore_errors=True))
EXT_ORIGIN = re.compile(r"^chrome-extension://[a-p]{32}$")
GROUP_URL = re.compile(r"^https://(?:www\.)?facebook\.com/groups/" + re.escape(GROUP_ID) + r"/?(?:\?.*)?$", re.I)
PERMALINK = re.compile(r"^https://www\.facebook\.com/groups/" + re.escape(
    GROUP_ID) + r"/(?:posts|permalink)/(?:[0-9]+|pfbid[A-Za-z0-9]+)/?(?:\?.*)?$", re.I)


class BridgeError(Exception):
    pass


def upstream(path: str, method: str = "GET", body: dict | None = None):
    assert path in ("/preview", "/claim", "/image-manifest", "/image-claim", "/video-claim",
                    "/inspect-exact") or re.fullmatch(r"/[a-f0-9-]{36}/result", path)
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
    if preview.get("postStatus") not in ("READY", "SCHEDULED", "PUBLISHED"):
        raise BridgeError("Post status must be READY, SCHEDULED, or PUBLISHED")
    if preview.get("approvalStatus") != "APPROVED":
        raise BridgeError("Post is not approved")
    if preview.get("due") is not True:
        raise BridgeError("Task is not due yet")
    image_count = preview.get("imageCount")
    if not isinstance(image_count, int) or not 0 <= image_count <= MAX_IMAGES:
        raise BridgeError("Unsupported image count (0-10 only)")
    unsupported = preview.get("unsupportedMediaCount")
    if not isinstance(unsupported, int) or unsupported < 0 or unsupported > 1:
        raise BridgeError("Only text, images or one MP4 video are supported")
    if not str(preview.get("content") or "").strip():
        raise BridgeError("Post content empty")


def media_manifest(preview: dict):
    """Read the only Task's immutable media references, classify strictly.

    Group Page video posts and Group Reels are not guaranteed to use the same
    Facebook UI. Classification never claims that a video is already a Reel.
    """
    manifest = upstream("/image-manifest")  # v11 read-only endpoint, handles ALL types
    if not isinstance(manifest, dict) or str(manifest.get("id", "")).lower() != TASK_ID \
            or str(manifest.get("groupId", "")) != GROUP_ID:
        raise BridgeError("Media manifest task/group mismatch")
    rows = manifest.get("media")
    if not isinstance(rows, list):
        raise BridgeError("Invalid media manifest")
    media = []
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("url"), str) or not row["url"].strip():
            raise BridgeError("Media URL missing")
        media.append({"type": row.get("type"), "url": row["url"]})
    images = [m for m in media if m["type"] == "IMAGE"]
    videos = [m for m in media if m["type"] in ("VIDEO", "REEL")]
    if len(images) != preview["imageCount"] or len(media) - len(images) != preview["unsupportedMediaCount"]:
        raise BridgeError("Media count changed since read-only preview")
    if not media:
        return "TEXT", []
    if len(images) == len(media) and 1 <= len(images) <= MAX_IMAGES:
        if len(set(m["url"] for m in media)) != len(media):
            raise BridgeError("Duplicate image URLs")
        return "IMAGE", media
    if len(videos) == 1 and len(media) == 1 and preview["imageCount"] == 0:
        return "VIDEO", media
    raise BridgeError("Mixed/unsupported media; one video OR 1-10 images only")


def signature(preview: dict, media: list[dict]) -> str:
    parts = [str(preview.get(k)) for k in ("id", "groupUrl", "content", "taskStatus",
                                           "approvalStatus", "due", "imageCount", "unsupportedMediaCount")]
    parts.extend(m["type"] + ":" + m["url"] for m in media)
    return hashlib.sha256("\n".join(parts).encode()).hexdigest()


def get_preview():
    raw = upstream("/preview")
    validate_preview(raw)
    kind, media = media_manifest(raw)
    digest = signature(raw, media)
    with LOCK:
        if STATE["claimed"]:
            raise BridgeError("Already claimed this exact Task; do not retry")
        if STATE["staged"] is not None:
            if digest != STATE["digest"]:
                raise BridgeError("Staged media or Post changed; discard draft")
        else:
            STATE.update(preview=raw, manifest=media, digest=digest, mediaKind=kind)
    return {"id": TASK_ID, "groupId": GROUP_ID, "content": raw["content"],
            "digest": digest, "imageCount": raw["imageCount"],
            "mediaKind": kind, "sourceMediaKind": (media[0]["type"] if kind == "VIDEO" else kind),
            "mediaCount": len(media),
            "marker": "[SocialHub Group Pilot " + TASK_ID[:8] + "]"}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise BridgeError("Media CDN redirected request; review host instead")


def valid_media_url(url: str) -> None:
    try:
        parsed = urllib.parse.urlsplit(url)
    except ValueError:
        raise BridgeError("Invalid media URL") from None
    host = (parsed.hostname or "").lower()
    try:
        ipaddress.ip_address(host)
        raise BridgeError("IP literal media host is not allowed")
    except ValueError:
        pass
    if (parsed.scheme != "https" or host in ("localhost", "localhost.localdomain") or
            host.endswith(".local") or host not in ALLOWED_MEDIA_HOSTS or
            parsed.username is not None or parsed.password is not None or
            parsed.port not in (None, 443) or len(url) > 3000 or not parsed.path):
        raise BridgeError("Media URL requires explicitly authorized HTTPS CDN host")


def file_type(data: bytes) -> str:
    if data.startswith(b"\xff\xd8\xff") and data.endswith(b"\xff\xd9"):
        return ".jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return ".webp"
    raise BridgeError("Image is not JPEG/PNG/WebP; no upload attempted")


def stage_images(body: dict):
    with LOCK:
        if STATE["claimed"]:
            raise BridgeError("Task already claimed; do not upload again")
        if not STATE["digest"] or not hmac.compare_digest(str(body.get("digest", "")), STATE["digest"]):
            raise BridgeError("Preview token mismatch; preview again")
        fresh = upstream("/preview")
        validate_preview(fresh)
        kind, media = media_manifest(fresh)
        if kind != "IMAGE" or signature(fresh, media) != STATE["digest"]:
            STATE["digest"] = None
            raise BridgeError("Media/Post state changed; preview again")
        urls = [item["url"] for item in media]
        if not urls:
            raise BridgeError("This task has no images")
        if STATE["staged"] is not None:
            raise BridgeError("Images already staged; do not retry attachment")
        for url in urls:
            valid_media_url(url)
        opener = urllib.request.build_opener(NoRedirect())
        files = []
        total_bytes = 0
        try:
            for idx, url in enumerate(urls):
                request = urllib.request.Request(url, headers={"User-Agent": "SocialHubGroupPilot/1.0",
                                                               "Accept": "image/jpeg,image/png,image/webp"})
                try:
                    with opener.open(request, timeout=25) as response:
                        length = response.headers.get("Content-Length")
                        if length and int(length) > MAX_IMAGE_BYTES:
                            raise BridgeError("Image larger than 20 MiB")
                        raw = response.read(MAX_IMAGE_BYTES + 1)
                except (urllib.error.URLError, TimeoutError) as exc:
                    raise BridgeError("Image CDN unavailable. Check allowed HTTPS host and public URL") from None
                if len(raw) > MAX_IMAGE_BYTES or len(raw) < 16:
                    raise BridgeError("Image size not supported")
                total_bytes += len(raw)
                if total_bytes > MAX_TOTAL_BYTES:
                    raise BridgeError("Combined images exceed 70 MiB")
                ext = file_type(raw)
                path = STAGING_DIR / ("group-pilot-%02d" % (idx + 1) + ext)
                path.write_bytes(raw)
                files.append(
                    {"path": str(path.absolute()), "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
        except Exception:
            for entry in files:
                pathlib.Path(entry["path"]).unlink(missing_ok=True)
            raise
        STATE["staged"] = files
        return {"id": TASK_ID, "groupId": GROUP_ID, "digest": STATE["digest"],
                "count": len(files), "files": files}


def video_type(header: bytes) -> str:
    # ISO BMFF/MP4 signature; reject MOV-only, AVI, HTML, HLS and executable data.
    if len(header) >= 12 and header[4:8] == b"ftyp" and header[8:12] in (
            b"isom", b"iso2", b"mp41", b"mp42", b"avc1", b"dash", b"MSNV", b"iso6"):
        return ".mp4"
    raise BridgeError("Only MP4 video is supported; cannot stage MOV/other files")


def validate_mp4_streams(path: pathlib.Path) -> dict:
    """Reject corrupt/incompatible MP4 before CDP. Requires local FFmpeg ffprobe.

    This does NOT prove Facebook has uploaded/processed the video, nor does it
    make a Group post into a Reel. A human must verify the Facebook UI.
    """
    binary = shutil.which(os.environ.get("GROUP_MEDIA_FFPROBE", "ffprobe"))
    if not binary:
        raise BridgeError("ffprobe not installed / on PATH. Video staging blocked; no live posting")
    try:
        result = subprocess.run([
            binary, "-v", "error", "-show_entries",
            "format=duration,format_name:stream=codec_name,codec_type,width,height",
            "-of", "json", str(path)
        ], capture_output=True, text=True, timeout=35, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise BridgeError("ffprobe failed or timed out; video is not verified") from None
    if result.returncode != 0:
        raise BridgeError("MP4 container/stream invalid; ffprobe rejected video")
    try:
        info = json.loads(result.stdout)
        duration = float(info.get("format", {}).get("duration", 0))
        streams = info.get("streams", [])
        videos = [st for st in streams if st.get("codec_type") == "video"]
        audios = [st for st in streams if st.get("codec_type") == "audio"]
    except (TypeError, ValueError, KeyError, AttributeError):
        raise BridgeError("ffprobe video metadata invalid") from None
    if not 0.5 <= duration <= 7200:
        raise BridgeError("Video duration outside pilot range (0.5s–120min)")
    if len(videos) != 1 or videos[0].get("codec_name") != "h264":
        raise BridgeError("One H.264 video stream required for MP4 pilot")
    width, height = int(videos[0].get("width") or 0), int(videos[0].get("height") or 0)
    if not 16 <= width <= 4096 or not 16 <= height <= 4096:
        raise BridgeError("Video dimensions invalid / too large")
    if len(audios) > 1 or any(st.get("codec_name") != "aac" for st in audios):
        raise BridgeError("Audio must be absent or a single AAC stream")
    return {"durationSeconds": round(duration, 2), "width": width,
            "height": height, "videoCodec": "h264", "audio": bool(audios)}


def digest_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def stage_video(body: dict):
    # This stages ONE MP4 file but never claims/publishes the Task.
    with LOCK:
        if STATE["claimed"] or STATE["staged"] is not None:
            raise BridgeError("Video already staged/claimed; do not retry in this session")
        if not STATE["digest"] or not hmac.compare_digest(str(body.get("digest", "")), STATE["digest"]):
            raise BridgeError("Preview token mismatch")
        fresh = upstream("/preview")
        validate_preview(fresh)
        kind, media = media_manifest(fresh)
        if kind != "VIDEO" or signature(fresh, media) != STATE["digest"]:
            raise BridgeError("Video metadata changed, do not stage")
        url = media[0]["url"]
        valid_media_url(url)
        path = STAGING_DIR / "group-pilot-one-video.mp4"
        path.unlink(missing_ok=True)
        request = urllib.request.Request(url, headers={
            "User-Agent": "SocialHubGroupPilot/1.0", "Accept": "video/mp4"})
        opener = urllib.request.build_opener(NoRedirect())
        try:
            with opener.open(request, timeout=45) as response:
                length = response.headers.get("Content-Length")
                if length and int(length) > MAX_VIDEO_BYTES:
                    raise BridgeError("Video exceeds 256 MiB one-task limit")
                total, head = 0, b""
                with path.open("wb") as output:
                    while block := response.read(1024 * 1024):
                        total += len(block)
                        if total > MAX_VIDEO_BYTES:
                            raise BridgeError("Video exceeds 256 MiB one-task limit")
                        if len(head) < 16:
                            head = (head + block)[:16]
                        output.write(block)
                if total < 1024:
                    raise BridgeError("Video file too small/invalid")
                if length and total != int(length):
                    raise BridgeError("Video download incomplete; file size mismatched")
                video_type(head)
                # Validate full container and streams; a correct ftyp header alone
                # can be spoofed by corrupt input or an HTML error page.
                technical = validate_mp4_streams(path)
        except (urllib.error.URLError, TimeoutError):
            path.unlink(missing_ok=True)
            raise BridgeError("Video CDN unavailable; do not retry automatically") from None
        except Exception:
            path.unlink(missing_ok=True)
            raise
        item = {"path": str(path.absolute()), "size": total, "sha256": digest_file(path)}
        STATE["staged"] = [item]
        return {"id": TASK_ID, "groupId": GROUP_ID, "digest": STATE["digest"],
                "count": 1, "mediaKind": "VIDEO", "videoInfo": technical, "files": [item]}


def inspect_media():
    # Supports PENDING tasks for read-only inspection, never requires due/approved.
    raw = upstream("/inspect-exact")
    if not isinstance(raw, dict) or str(raw.get("id", "")).lower() != TASK_ID or \
            not GROUP_URL.fullmatch(str(raw.get("groupUrl") or "")):
        raise BridgeError("Wrong/missing exact Group Task")
    if not isinstance(raw.get("imageCount"), int) or not 0 <= raw["imageCount"] <= MAX_IMAGES:
        raise BridgeError("Invalid image count")
    kind, media = media_manifest(raw)
    hosts, allowed = [], True
    for item in media:
        host = urllib.parse.urlsplit(item["url"]).hostname or ""
        hosts.append(host)
        try:
            valid_media_url(item["url"])
        except BridgeError:
            allowed = False
    return {"id": TASK_ID, "groupId": GROUP_ID, "taskStatus": raw.get("taskStatus"),
            "imageCount": raw["imageCount"], "mediaKind": kind,
            "mediaCount": len(media), "sourceMediaKind": (media[0]["type"] if kind == "VIDEO" else kind),
            "mediaHosts": sorted(set(hosts)),
            "hostsAllowed": allowed, "message": "Read only; not claimed/downloaded/published"}


def inspect_images():
    # Keep backward-compatible endpoint name for the already-installed v11 popup.
    return inspect_media()


def claim_task(body: dict):
    with LOCK:
        if STATE["claimed"]:
            raise BridgeError("Already claimed one task. Never auto retry")
        if not STATE["digest"] or not hmac.compare_digest(str(body.get("digest", "")), STATE["digest"]):
            raise BridgeError("Preview changed or missing. Preview again")
        fresh = upstream("/preview")
        validate_preview(fresh)
        kind, media = media_manifest(fresh)
        if signature(fresh, media) != STATE["digest"] or kind != STATE["mediaKind"]:
            STATE["digest"] = None
            raise BridgeError("Content/media changed; preview again")
        if media:
            staged = STATE["staged"]
            if not isinstance(staged, list) or len(staged) != len(media):
                raise BridgeError("Media has not been staged; no claim made")
            for item in staged:
                path = pathlib.Path(item["path"])
                if not path.is_file() or digest_file(path) != item["sha256"]:
                    raise BridgeError("Staged media changed/missing; no claim made")
        endpoint = "/video-claim" if kind == "VIDEO" else "/image-claim" if kind == "IMAGE" else "/claim"
        task = upstream(endpoint, method="POST", body={})
        STATE["claimed"] = task is not None
        if task is None:
            raise BridgeError("Task not claimable: must be READY/APPROVED/due. No post sent")
        expected_images = [m["url"] for m in media if m["type"] == "IMAGE"]
        expected_videos = [m["url"] for m in media if m["type"] in ("VIDEO", "REEL")]
        if (str(task.get("id", "")).lower() != TASK_ID or
                not GROUP_URL.fullmatch(str(task.get("groupUrl") or "")) or
                task.get("content") != fresh["content"] or
                task.get("imageUrls") != expected_images or
                task.get("videoUrls", []) != expected_videos or
                int(task.get("unsupportedMediaCount") or 0) != 0):
            upstream("/" + TASK_ID + "/result", "POST", {"status": "NEEDS_REVIEW",
                                                         "publishedUrl": None,
                                                         "note": "Claim mismatch; no Facebook post attempted"})
            STATE["reported"] = True
            raise BridgeError("Claim differs from preview; NEEDS_REVIEW, do not retry")
        return {"id": TASK_ID, "groupId": GROUP_ID, "content": task["content"],
                "imageCount": len(expected_images), "mediaKind": kind, "mediaCount": len(media),
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
                                                     "publishedUrl": link if status == "PUBLISHED" else None,
                                                     "note": note})
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
        if self.path not in ("/preview", "/claim", "/result", "/stage-images", "/stage-video", "/inspect-images",
                             "/inspect-media"):
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
            if self.path in ("/inspect-images", "/inspect-media") and self.command == "GET":
                result = inspect_media()
            elif self.path == "/preview" and self.command == "GET":
                result = get_preview()
            elif self.path == "/stage-images" and self.command == "POST":
                result = stage_images(body)
            elif self.path == "/stage-video" and self.command == "POST":
                result = stage_video(body)
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
            return self.output(500,
                               {"error": "Bridge internal error. Inspect local terminal; do not retry automatically"})

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
    print("Media CDN hosts allowed:", ", ".join(sorted(ALLOWED_MEDIA_HOSTS)))
    httpd.serve_forever()


if __name__ == "__main__":
    main()
