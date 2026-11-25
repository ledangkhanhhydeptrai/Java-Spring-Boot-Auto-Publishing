'use strict';
const $ = id => document.getElementById(id);
let preview = null;
let filled = false;
let busy = false;
let keyboardAllowed = false;

function feedback(message, error = false) {
    $('message').style.display = 'block';
    $('message').className = error ? 'danger' : '';
    $('message').textContent = message;
}

function controls() {
    $('fill').disabled = !preview || !keyboardAllowed || busy;
    $('grantKeyboard').disabled = keyboardAllowed || busy;
    $('grantKeyboard').textContent = keyboardAllowed
        ? 'Đã cấp quyền nhập bằng bàn phím'
        : 'Tôi xác nhận dùng bàn phím Edge (CDP)';
    $('publish').disabled = !preview || !filled || busy;
    $('preview').disabled = busy;
}

function bridge(path, body) {
    return new Promise((resolve, reject) => {
        chrome.runtime.sendMessage({kind: 'BRIDGE', path, body}, response => {
            if (chrome.runtime.lastError) return reject(Error(chrome.runtime.lastError.message));
            if (!response?.ok) return reject(Error(response?.error || 'Bridge unavailable'));
            resolve(response.value);
        });
    });
}

async function facebook(kind, payload = {}) {
    const tabs = await chrome.tabs.query({active: true, currentWindow: true});
    const tab = tabs[0];
    if (!tab?.id || !/^https:\/\/(?:www\.)?facebook\.com\/groups\/[0-9]+(?:\/|\?|$)/.test(tab.url || ''))
        throw Error('Bạn cần đang mở tab Facebook Group tương ứng trên Edge');
    return new Promise((resolve, reject) => {
        chrome.tabs.sendMessage(tab.id, {kind, ...payload}, response => {
            if (chrome.runtime.lastError) return reject(Error('Hãy F5 trang Facebook Group rồi thử lại: ' + chrome.runtime.lastError.message));
            if (!response?.ok) return reject(Error(response?.error || 'Facebook UI error'));
            resolve(response);
        });
    });
}

async function running(fn) {
    if (busy) return;
    busy = true;
    controls();
    try {
        await fn();
    } catch (e) {
        feedback(String(e.message || e), true);
    } finally {
        busy = false;
        controls();
    }
}

$('connect').addEventListener('click', () => running(async () => {
    const pairCode = $('pair').value.trim();
    if (!pairCode) throw Error('Nhập mã ghép nối');
    await chrome.storage.session.set({pairCode});
    feedback('Đã lưu mã ghép nối trong phiên Edge. Chọn Xem trước Task.');
}));
$('preview').addEventListener('click', () => running(async () => {
    filled = false;
    preview = null;
    controls();
    const result = await bridge('/preview');
    preview = result;
    $('task').style.display = 'block';
    $('task').textContent = `TASK: ${result.id}\nGROUP: ${result.groupId}\n\n${result.content}\n\n${result.marker}`;
    feedback('Task READY + APPROVED + đến hạn, đúng nhóm, nội dung chỉ văn bản. Chưa claim và chưa đăng.');
}));
$('grantKeyboard').addEventListener('click', async () => {
    try {
        // The debugger permission must be REQUIRED in manifest.json.
        // Chrome does not permit requesting it as an optional permission.
        // This button is an explicit per-popup acknowledgement, not a grant.
        const granted = await chrome.permissions.contains({permissions: ['debugger']});
        keyboardAllowed = Boolean(granted);
        feedback(granted
            ? 'Đã xác nhận. Chỉ nhập đúng Task đã Preview, không tự đăng.'
            : 'Extension chưa được cấp quyền debugger. Hãy xác nhận quyền trong edge://extensions rồi Reload.', !granted);
    } catch (error) {
        keyboardAllowed = false;
        feedback('Không kiểm tra được quyền debugger: ' + String(error.message || error), true);
    }
    controls();
});
$('fill').addEventListener('click', () => running(async () => {
    if (!keyboardAllowed) throw Error('Chưa cấp quyền nhập bằng bàn phím');
    if (!preview) throw Error('Xem trước Task trước');
    const answer = await facebook('GROUP_FILL', {preview});
    filled = true;
    feedback(answer.message);
}));
$('publish').addEventListener('click', () => running(async () => {
    if (!filled) throw Error('Nạp bản nháp trước');
    if (!confirm('Bạn đã kiểm tra nội dung bản nháp trên Facebook và chắc chắn muốn ĐĂNG THẬT 1 bài vào Group ' + preview.groupId + '? Không tự thử lại nếu kết quả không xác định.')) return;
    $('publish').disabled = true;
    // This message starts a one-time claim and post in the Facebook content script;
    // processing continues even if this popup is closed.
    const answer = await facebook('GROUP_PUBLISH');
    filled = false;
    preview = null;
    $('task').style.display = 'none';
    feedback(answer.message);
}));
controls();
// Require the operator to acknowledge CDP use on each popup opening.
