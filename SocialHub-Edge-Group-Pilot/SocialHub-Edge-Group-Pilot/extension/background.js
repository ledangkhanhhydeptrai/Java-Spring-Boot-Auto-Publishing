'use strict';
// One approved task, one known administrator-owned test group.
// No Facebook API, cookies, passwords, or tokens are accessed.
// An explicit optional debugger permission is requested in popup.js.
const ENDPOINT = 'http://127.0.0.1:8765';
const ROUTES = new Set(['/preview', '/claim', '/result']);
const EXPECTED_GROUP_ID = '1326570106277759';
const MAX_TEXT_LENGTH = 6000;
const DEBUGGED_TABS = new Set();
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function bridge(path, body) {
  const {pairCode} = await chrome.storage.session.get('pairCode');
  if (!pairCode) throw Error('Chưa kết nối mã ghép nối của Edge Bridge');
  const method = path === '/preview' ? 'GET' : 'POST';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(ENDPOINT + path, {
      method, cache: 'no-store', signal: controller.signal,
      headers: {'X-SocialHub-Pair': pairCode, 'Content-Type': 'application/json'},
      body: method === 'POST' ? JSON.stringify(body || {}) : undefined
    });
    const result = await response.json();
    if (!response.ok) throw Error(result.error || `Bridge HTTP ${response.status}`);
    return result;
  } finally {
    clearTimeout(timer);
  }
}

async function typeByBrowser(message, sender) {
  const tabId = sender.tab?.id;
  const url = sender.tab?.url || '';
  if (!Number.isInteger(tabId) ||
      !new RegExp('^https://(?:www\\.)?facebook\\.com/groups/' + EXPECTED_GROUP_ID + '(?:/|\\?|$)', 'i').test(url))
    throw Error('Chỉ nhập vào tab Facebook Group thử nghiệm đúng ID');
  if (message.groupId !== EXPECTED_GROUP_ID || typeof message.text !== 'string' ||
      message.text.length < 10 || message.text.length > MAX_TEXT_LENGTH)
    throw Error('Nội dung / Group ID không hợp lệ');
  if (DEBUGGED_TABS.has(tabId)) throw Error('Tab đang nhập nội dung; không được gửi lệnh trùng');
  if (!(await chrome.permissions.contains({permissions:['debugger']})))
    throw Error('Hãy bấm Cấp quyền nhập bằng bàn phím ở extension trước');

  // Re-read the same read-only Bridge preview. This binds the typed text to
  // the exact approved/due task; the content script cannot supply arbitrary text.
  const preview = await bridge('/preview');
  const expected = (preview.content || '').trim() + '\n\n' + preview.marker;
  if (preview.groupId !== EXPECTED_GROUP_ID ||
      preview.id !== message.taskId || preview.digest !== message.digest ||
      message.text !== expected)
    throw Error('Task/Group/digest/content thay đổi; KHÔNG nhập');

  const target = {tabId};
  let attached = false;
  DEBUGGED_TABS.add(tabId);
  try {
    // DevTools must be closed for this tab: the debugger is exclusive.
    await chrome.debugger.attach(target, '1.3');
    attached = true;
    // CDP Input events come from the browser, unlike document.execCommand or
    // untrusted synthetic DOM events. They are sent ONLY to this tab.
    const lines = message.text.replace(/\r\n?/g, '\n').split('\n');
    for (let index = 0; index < lines.length; index++) {
      if (index > 0) {
        await chrome.debugger.sendCommand(target, 'Input.dispatchKeyEvent', {
          type: 'keyDown', key: 'Enter', code: 'Enter',
          windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13,
          text: '\r', unmodifiedText: '\r'
        });
        await chrome.debugger.sendCommand(target, 'Input.dispatchKeyEvent', {
          type: 'keyUp', key: 'Enter', code: 'Enter',
          windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13
        });
        await delay(95);
      }
      if (lines[index]) {
        await chrome.debugger.sendCommand(target, 'Input.insertText', {text: lines[index]});
        await delay(125);
      }
    }
    return {inserted: true};
  } catch (error) {
    throw Error('CDP-KEYBOARD-v10: ' + (error?.message || String(error)) +
      '. Không thử lại trong bản nháp này; KHÔNG đăng');
  } finally {
    if (attached) {
      try { await chrome.debugger.detach(target); }
      catch (error) { console.warn('SocialHub debugger detach failed:', error?.message); }
    }
    DEBUGGED_TABS.delete(tabId);
  }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  let operation;
  if (message?.kind === 'BRIDGE' && ROUTES.has(message.path)) {
    operation = bridge(message.path, message.body);
  } else if (message?.kind === 'GROUP_TYPE_TEXT') {
    operation = typeByBrowser(message, sender);
  } else {
    return false;
  }
  operation.then(value => sendResponse({ok: true, value}))
    .catch(error => sendResponse({ok: false, error: String(error?.message || error)}));
  return true;
});
