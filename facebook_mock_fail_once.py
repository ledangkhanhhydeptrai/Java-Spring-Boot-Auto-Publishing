"""One-shot localhost Facebook mock. Simulates an accepted /feed request with lost HTTP response.
Run only for SocialHub E2E; never connects to Meta. Stops accepting further /feed requests.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from datetime import datetime, timezone
import json, re, threading
HOST, PORT = '127.0.0.1', 9090
PAGE = '999000111222'
TOKEN = 'mock_test_token'
REQUESTS = []
LOCK = threading.Lock()
FEED = re.compile(r'^/v\d+\.\d+/(\d+)/feed$')

class Handler(BaseHTTPRequestHandler):
    def reply(self, code, value):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/health':
            return self.reply(200, {'status': 'UP', 'mode': 'MOCK', 'fault': 'FAIL_AFTER_ACCEPT_ONCE', 'host': HOST, 'port': PORT})
        if path == '/requests':
            with LOCK:
                entries = list(REQUESTS)
            return self.reply(200, {'count': len(entries), 'requests': entries})
        return self.reply(404, {'error': 'not found'})

    def do_POST(self):
        found = FEED.fullmatch(urlparse(self.path).path)
        if not found or found.group(1) != PAGE:
            return self.reply(404, {'error': 'only test Page /feed allowed'})
        try:
            size = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            return self.reply(400, {'error': 'invalid length'})
        if size <= 0 or size > 65536:
            return self.reply(413, {'error': 'invalid size'})
        params = parse_qs(self.rfile.read(size).decode('utf-8', 'replace'), keep_blank_values=True)
        if params.get('access_token') != [TOKEN]:
            return self.reply(403, {'error': 'mock token required'})
        msg = params.get('message', [''])[0]
        if not msg.strip():
            return self.reply(400, {'error': 'empty message'})
        with LOCK:
            if REQUESTS:
                return self.reply(409, {'error': 'one-shot fault mock: second request prohibited'})
            REQUESTS.append({'sequence': 1, 'page_id': PAGE, 'message': msg,
                             'time': datetime.now(timezone.utc).isoformat(),
                             'fault': 'response_lost', 'published': False})
        print('[MOCK] accepted one request, intentionally returns HTTP 503; NO actual post was published', flush=True)
        return self.reply(503, {'error': 'simulated ambiguous outcome; manual review required'})

    def log_message(self, fmt, *args):
        print('[HTTP] ' + fmt % args, flush=True)

if __name__ == '__main__':
    print(f'[MOCK FAILURE] listening on http://{HOST}:{PORT}; NO Meta traffic', flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
