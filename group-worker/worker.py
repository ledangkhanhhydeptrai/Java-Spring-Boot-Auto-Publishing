"""One-task Facebook Group posting pilot for a group you administer.

Local, visible Playwright browser only. No login automation, CAPTCHA/2FA bypass,
stealth, rate-limit evasion, or blind retry. This is UI automation, not a Meta
Groups API integration. Facebook UI changes can require adapting selectors.

The worker only reports PUBLISHED after finding its unique task marker in the
TARGET group's feed AND extracting a matching real Facebook group permalink.
Otherwise it records NEEDS_REVIEW and does not retry the task automatically.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
import time
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from playwright.sync_api import BrowserContext, Page, TimeoutError as PWTimeoutError, sync_playwright

API = os.getenv("SOCIALHUB_API", "http://localhost:8081").rstrip("/")
TOKEN = os.getenv("GROUP_WORKER_TOKEN", "")
PROFILE = Path(os.getenv("BROWSER_PROFILE", "./browser-profile")).resolve()
TASK_ID = os.getenv("GROUP_WORKER_TARGET_TASK_ID", "").strip().lower()
GROUP_ID = os.getenv("GROUP_WORKER_EXPECTED_GROUP_ID", "").strip()
ENABLED = os.getenv("GROUP_AUTO_PUBLISH_ENABLED", "false").strip().lower() == "true"
HEADERS = {"Authorization": "Bearer " + TOKEN}
GROUP_URL = f"https://www.facebook.com/groups/{GROUP_ID}/"

# Intended for ONE small, user-owned test group, not cross-group campaigns.
MAX_IMAGES = 5
MAX_BYTES = 12 * 1024 * 1024


def require_settings(for_publish: bool) -> None:
    if not re.fullmatch(r"\d+", GROUP_ID):
        sys.exit("Set GROUP_WORKER_EXPECTED_GROUP_ID to your test Facebook group ID")
    if for_publish:
        if not ENABLED:
            sys.exit("GROUP_AUTO_PUBLISH_ENABLED is false. No tasks claimed.")
        if not TOKEN:
            sys.exit("Set GROUP_WORKER_TOKEN (same secret as Spring Boot)")
        if not re.fullmatch(r"[0-9a-f]{8}-[0-9a-f-]{27,}", TASK_ID):
            sys.exit("Set GROUP_WORKER_TARGET_TASK_ID to ONE exact task UUID")


def ensure_group_url(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in {
        "facebook.com", "www.facebook.com", "m.facebook.com"
    }:
        raise ValueError("Only a real HTTPS Facebook Group URL is allowed")
    if not re.match(rf"^/groups/{re.escape(GROUP_ID)}(?:/|$)", parsed.path):
        raise ValueError("Claimed task is for a DIFFERENT Facebook Group")


def safe_snapshot(page: Page, label: str) -> None:
    try:
        folder = PROFILE.parent / "debug"
        folder.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=str(folder / (label + ".png")), full_page=False, timeout=8000)
        print("Debug screenshot saved locally:", folder / (label + ".png"), flush=True)
    except Exception as exc:
        print("Screenshot unavailable:", exc, flush=True)


def claim() -> dict | None:
    response = requests.post(API + "/internal/group-worker/claim", headers=HEADERS, timeout=20)
    response.raise_for_status()
    if response.status_code == 204:
        return None
    data = response.json()
    if not isinstance(data, dict):
        raise RuntimeError("Unexpected claim response")
    return data


def report(task_id: str, status: str, note: str, published_url: str | None = None) -> None:
    payload = {"status": status, "publishedUrl": published_url, "note": note[:450]}
    response = requests.post(
        f"{API}/internal/group-worker/{task_id}/result",
        headers=HEADERS, json=payload, timeout=20
    )
    response.raise_for_status()
    print("RESULT", status, "task", task_id, "url", published_url or "—", flush=True)


def has_login_wall(page: Page) -> bool:
    url = page.url.lower()
    if "/login" in url or "checkpoint" in url:
        return True
    # Facebook login dialog can appear without redirect; do not dismiss/bypass.
    return page.locator('input[name="email"]').count() > 0 and page.locator('input[name="pass"]').count() > 0


def visible_first(locator):
    for item in locator.all()[:20]:
        try:
            if item.is_visible():
                return item
        except Exception:
            pass
    return None


def prepare_browser(context: BrowserContext, page: Page) -> None:
    page.goto(GROUP_URL, wait_until="domcontentloaded", timeout=60000)
    page.wait_for_timeout(2000)
    if has_login_wall(page):
        raise RuntimeError("Browser profile is not logged in or Facebook requires checkpoint. Stop safely.")
    ensure_group_url(page.url)


def download_images(urls: list[str], folder: Path) -> list[str]:
    if not isinstance(urls, list) or len(urls) > MAX_IMAGES:
        raise ValueError("Expected at most 5 IMAGE media URLs")
    saved: list[str] = []
    for idx, url in enumerate(urls):
        if not isinstance(url, str):
            raise ValueError("Invalid image URL")
        parsed = urlparse(url)
        # Fail closed: do not make arbitrary outbound requests or follow redirects.
        if parsed.scheme != "https" or parsed.hostname != "res.cloudinary.com":
            raise ValueError("IMAGE media must be an HTTPS Cloudinary public URL")
        with requests.get(url, timeout=(10, 40), stream=True, allow_redirects=False) as resp:
            resp.raise_for_status()
            if 300 <= resp.status_code < 400:
                raise ValueError("Redirected image URLs are not accepted")
            content_type = resp.headers.get("Content-Type", "").lower().split(";")[0]
            if content_type not in ("image/jpeg", "image/png", "image/webp"):
                raise ValueError("Unsupported image format from Cloudinary: " + content_type)
            suffix = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}[content_type]
            target = folder / f"media_{idx}{suffix}"
            length = 0
            with target.open("wb") as f:
                for chunk in resp.iter_content(256 * 1024):
                    length += len(chunk)
                    if length > MAX_BYTES:
                        raise ValueError("IMAGE exceeds 12MB limit")
                    f.write(chunk)
            if length == 0:
                raise ValueError("Downloaded image is empty")
            saved.append(str(target))
    return saved


def open_composer(page: Page):
    trigger_text = re.compile(r"Bạn viết gì đi|Viết gì đó|Write something|Write a post", re.I)
    trigger = visible_first(page.get_by_text(trigger_text))
    if trigger is None:
        raise RuntimeError("Cannot locate Group composer (login/permission/UI change?)")
    trigger.click(timeout=10000)
    page.wait_for_timeout(1200)
    dialogs = page.get_by_role("dialog")
    for dialog in reversed(dialogs.all()):
        try:
            if dialog.is_visible():
                return dialog
        except Exception:
            continue
    # Some Facebook layouts present the composer in the document, not a dialog.
    return page


def fill_post(page: Page, composer, content: str, images: list[str]) -> None:
    editors = composer.locator(
        '[contenteditable="true"][role="textbox"], [contenteditable="true"][data-lexical-editor="true"]')
    editor = visible_first(editors)
    if editor is None:
        editor = visible_first(composer.locator('[contenteditable="true"]'))
    if editor is None:
        raise RuntimeError("Cannot locate editor textbox")
    editor.fill(content, timeout=10000)

    if not images:
        return
    # Real IMAGE posts must include images. Never silently post text-only.
    attach = visible_first(composer.get_by_role("button", name=re.compile(r"Ảnh/video|Photo/video|Photo/Video", re.I)))
    if attach is None:
        attach = visible_first(page.get_by_text(re.compile(r"Ảnh/video|Photo/video", re.I)))
    if attach is not None:
        attach.click(timeout=10000)
        page.wait_for_timeout(1000)

    inputs = page.locator('input[type="file"]')
    target = None
    for input_el in reversed(inputs.all()):
        accept = (input_el.get_attribute("accept") or "").lower()
        if "image" in accept or ".jpg" in accept or not accept:
            target = input_el
            break
    if target is None:
        raise RuntimeError("Image file-input not found; refusing text-only publish")
    target.set_input_files(images, timeout=30000)
    page.wait_for_timeout(9000)
    # Best-effort upload progress detection; do not publish if clearly still loading.
    progress = visible_first(composer.get_by_text(re.compile(r"Đang tải lên|Uploading", re.I)))
    if progress is not None:
        raise RuntimeError("Images still uploading. Refusing to click Publish")


def group_permalink(value: str) -> str | None:
    p = urlparse(urljoin("https://www.facebook.com", value))
    if p.scheme != "https" or p.hostname not in {"facebook.com", "www.facebook.com"}:
        return None
    if not re.match(rf"^/groups/{re.escape(GROUP_ID)}/(?:posts|permalink)/(?:[0-9]+|pfbid[A-Za-z0-9]+)/?$", p.path):
        return None
    return f"https://www.facebook.com{p.path}"


def verify_published(page: Page, marker: str) -> str | None:
    """Verify a matching feed post AND recover its own permalink; never fabricate URL."""
    for attempt in range(5):
        page.goto(GROUP_URL, wait_until="domcontentloaded", timeout=60000)
        if has_login_wall(page):
            return None
        page.wait_for_timeout(3000 + attempt * 1500)
        matches = page.locator('[role="article"]').filter(has_text=marker)
        article = visible_first(matches)
        if article is None:
            # A few Facebook UI layouts use generic containers for posts.
            found = visible_first(page.get_by_text(marker, exact=False))
            if found is not None:
                candidate = found.locator('xpath=ancestor::*[@role="article"][1]')
                article = candidate if candidate.count() > 0 else None
        if article is not None:
            urls = article.locator("a[href]")
            for a in urls.all()[:70]:
                href = a.get_attribute("href")
                if href:
                    permalink = group_permalink(href)
                    if permalink:
                        return permalink
        page.mouse.wheel(0, 700)
        page.wait_for_timeout(2000)
    return None


def publish_one(page: Page, task: dict) -> None:
    task_id = str(task.get("id", "")).lower()
    if task_id != TASK_ID:
        raise RuntimeError("Claim returned a different task ID; review controller target filter")
    url = str(task.get("groupUrl", ""))
    ensure_group_url(url)
    content = str(task.get("content", "")).strip()
    if not content:
        report(task_id, "NEEDS_REVIEW", "Empty post content; publishing was not attempted")
        return
    if int(task.get("unsupportedMediaCount", 0)) != 0:
        report(task_id, "NEEDS_REVIEW", "Unsupported video/reel media. Only IMAGE pilot is supported")
        return
    images = task.get("imageUrls", [])
    if not isinstance(images, list):
        report(task_id, "NEEDS_REVIEW", "Unexpected media payload")
        return
    marker = "[SocialHub Group Pilot " + task_id[:8] + "]"
    full_content = content + "\n\n" + marker

    # Local evidence is intentionally on the group page, not a generated permalink.
    clicked = False
    try:
        prepare_browser(page.context, page)
        with tempfile.TemporaryDirectory(prefix="socialhub_group_") as work:
            files = download_images(images, Path(work))
            composer = open_composer(page)
            fill_post(page, composer, full_content, files)
            safe_snapshot(page, "before_publish_" + task_id[:8])
            buttons = composer.get_by_role("button", name=re.compile(r"^(Đăng|Post|Publish)$", re.I))
            button = visible_first(buttons)
            if button is None:
                raise RuntimeError("Publish button not uniquely identified; no click performed")
            if not button.is_enabled():
                raise RuntimeError("Publish button disabled; images may not have finished uploading")
            print("Publishing EXACT ONE task", task_id, "to group", GROUP_ID, flush=True)
            clicked = True  # Once clicked, NEVER retry automatically even if timeout occurs.
            button.click(timeout=20000)
            page.wait_for_timeout(6000)
            safe_snapshot(page, "after_publish_" + task_id[:8])

        permalink = verify_published(page, marker)
        if permalink is None:
            report(task_id, "NEEDS_REVIEW", "Publish clicked; group permalink could not be verified. DO NOT RETRY")
            return
        report(task_id, "PUBLISHED", "Matched unique task marker in correct Group feed and recovered post permalink",
               permalink)
    except Exception as exc:
        safe_snapshot(page, "error_" + task_id[:8])
        state = "After Publish click" if clicked else "Before Publish click"
        print(state, "error:", repr(exc), flush=True)
        report(task_id, "NEEDS_REVIEW", f"{state}: {str(exc)[:300]}. DO NOT AUTO-RETRY")


def run_once() -> None:
    require_settings(for_publish=True)
    PROFILE.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        context = p.chromium.launch_persistent_context(str(PROFILE), headless=False,
                                                       viewport={"width": 1300, "height": 900})
        page = context.pages[0] if context.pages else context.new_page()
        try:
            # Validate logged-in group before claiming (otherwise task gets stuck PROCESSING).
            prepare_browser(context, page)
            task = claim()
            if task is None:
                print("No pending task matching GROUP_WORKER_TARGET_TASK_ID; nothing sent", flush=True)
                return
            publish_one(page, task)
        finally:
            context.close()


def login_profile() -> None:
    require_settings(for_publish=False)
    PROFILE.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        context = p.chromium.launch_persistent_context(str(PROFILE), headless=False)
        try:
            page = context.pages[0] if context.pages else context.new_page()
            page.goto(GROUP_URL, wait_until="domcontentloaded", timeout=60000)
            print("Login to your own Facebook account in this visible browser if needed.")
            print("No task is claimed or published in --prepare-profile mode.")
            input("When the group page displays normally, press Enter to close this profile setup: ")
        finally:
            context.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Controlled SocialHub Group AUTO one-task pilot")
    parser.add_argument("--prepare-profile", action="store_true",
                        help="Open browser for manual FB login; never claim any task")
    args = parser.parse_args()
    if args.prepare_profile:
        login_profile()
    else:
        run_once()


if __name__ == "__main__":
    main()
