'use strict';
// No Facebook cookies, passwords or tokens are extracted. This worker only talks
// to a single loopback bridge that owns the server-to-server SocialHub token.
const ENDPOINT = 'http://127.0.0.1:8765';
const ROUTES = new Set(['/preview', '/claim', '/result']);

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.kind !== 'BRIDGE' || !ROUTES.has(message.path)) return;
  (async () => {
    const values = await chrome.storage.session.get('pairCode');
    const pairCode = values.pairCode || '';
    if (!pairCode) throw new Error('Nhập mã ghép nối từ PowerShell trước');
    const method = message.path === '/preview' ? 'GET' : 'POST';
    const controller = new AbortController();
    const id = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(ENDPOINT + message.path, {
        method, cache: 'no-store', signal: controller.signal,
        headers: {'X-SocialHub-Pair': pairCode, 'Content-Type': 'application/json'},
        body: method === 'POST' ? JSON.stringify(message.body || {}) : undefined
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.error || `Bridge HTTP ${response.status}`);
      return body;
    } finally {
      clearTimeout(id);
    }
  })().then(value => sendResponse({ok: true, value}))
    .catch(error => sendResponse({ok: false, error: String(error.message || error)}));
  return true;
});
