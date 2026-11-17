"""Experimental local worker. Never auto-confirms Facebook Group publication.
Requires a user-owned browser profile and explicit opt-in. No login automation,
CAPTCHA bypass, rate-limit bypass, or stealth functionality.
"""
import os, time, sys
from urllib.parse import urlparse
import requests
from playwright.sync_api import sync_playwright

API=os.getenv('SOCIALHUB_API','http://localhost:8080').rstrip('/')
TOKEN=os.getenv('GROUP_WORKER_TOKEN','')
PROFILE=os.getenv('BROWSER_PROFILE','./browser-profile')
INTERVAL=int(os.getenv('POLL_SECONDS','30'))
if not TOKEN: sys.exit('Set GROUP_WORKER_TOKEN')
HEADERS={'Authorization':'Bearer '+TOKEN}

def result(task_id,status,note):
    r=requests.post(f'{API}/internal/group-worker/{task_id}/result',headers=HEADERS,json={'status':status,'note':note},timeout=20)
    r.raise_for_status()

def handle(task):
    url=task['groupUrl']
    parsed=urlparse(url)
    if parsed.scheme!='https' or parsed.hostname not in ('facebook.com','www.facebook.com','m.facebook.com') or not parsed.path.startswith('/groups/'):
        result(task['id'],'FAILED','Group URL is not a Facebook group');return
    with sync_playwright() as p:
        context=p.chromium.launch_persistent_context(PROFILE,headless=False)
        page=context.new_page()
        try:
            page.goto(url,wait_until='domcontentloaded',timeout=45000)
            # Experimental UI: Facebook changes controls frequently.
            # We intentionally DO NOT click Publish without verifying target and media.
            # Operator can inspect the open browser; task remains NEEDS_REVIEW.
            print('Group opened:',url)
            print('Post text for this task:',task['content'])
            page.wait_for_timeout(3000)
            result(task['id'],'NEEDS_REVIEW','Browser opened; publishing not verified. No automatic confirmation.')
        except Exception as exc:
            result(task['id'],'FAILED',str(exc)[:500])
        finally:
            context.close()

def main():
    while True:
        try:
            r=requests.post(API+'/internal/group-worker/claim',headers=HEADERS,timeout=20)
            r.raise_for_status()
            if r.status_code==200:handle(r.json())
        except KeyboardInterrupt:return
        except Exception as exc:print('Worker error:',exc,flush=True)
        time.sleep(INTERVAL)
if __name__=='__main__':main()
