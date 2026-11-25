'use strict';
const $ = id => document.getElementById(id);
let preview = null;
let filled = false;
let busy = false;
let keyboardAllowed = false;
let imagesAttached = false;
let imagesReviewed = false;
let videoAttached = false;
let videoReviewed = false;

// The popup is destroyed each time the operator returns to Facebook to inspect
// upload thumbnails. Keep UI progress in session storage, never localStorage.
// The content script and Bridge still perform ALL authoritative checks.
const SESSION_STATE_KEY = 'socialHubMediaPilotUiState';

async function activeGroupTab() {
    const tabs = await chrome.tabs.query({active: true, currentWindow: true});
    const tab = tabs[0];
    if (!Number.isInteger(tab?.id) ||
        !/^https:\/\/(?:www\.)?facebook\.com\/groups\/1326570106277759(?:\/|\?|$)/.test(tab.url || ''))
        return null;
    return tab;
}

async function saveUiState() {
    const tab = await activeGroupTab();
    if (!tab) return;
    await chrome.storage.session.set({
        [SESSION_STATE_KEY]: {
            tabId: tab.id, preview, filled, imagesAttached,
            imagesReviewed, videoAttached, videoReviewed
        }
    });
}

async function restoreUiState() {
    try {
        const tab = await activeGroupTab();
        const stored = (await chrome.storage.session.get(SESSION_STATE_KEY))[SESSION_STATE_KEY];
        if (!tab || !stored || stored.tabId !== tab.id ||
            !stored.preview || !/^[a-f0-9-]{36}$/.test(stored.preview.id || '') ||
            stored.preview.groupId !== '1326570106277759') return;
        preview = stored.preview;
        filled = stored.filled === true;
        imagesAttached = stored.imagesAttached === true;
        imagesReviewed = stored.imagesReviewed === true;
        videoAttached = stored.videoAttached === true;
        videoReviewed = stored.videoReviewed === true;
        $('task').style.display = 'block';
        $('task').textContent = `TASK: ${preview.id}\nGROUP: ${preview.groupId}\n\n${preview.content}\n\n${preview.marker}`;
        feedback('Đã khôi phục tiến trình popup. Vẫn phải xác minh bản nháp Facebook trước LIVE.');
    } catch (error) {
        feedback('Không khôi phục được trạng thái popup: ' + String(error.message || error), true);
    }
    controls();
}

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
    const imageCount = preview?.imageCount || 0;
    $('attachImages').disabled = !preview || !filled || imageCount === 0 || imagesAttached || busy;
    $('ackImages').disabled = !preview || !filled || !imagesAttached || imagesReviewed || busy;
    $('attachVideo').disabled = !preview || !filled || preview.mediaKind !== 'VIDEO' || videoAttached || busy;
    $('ackVideo').disabled = !preview || !filled || !videoAttached || videoReviewed || busy;
    $('publish').disabled = !preview || !filled ||
        (preview.mediaKind === 'IMAGE' && !imagesReviewed) ||
        (preview.mediaKind === 'VIDEO' && !videoReviewed) || busy;
    $('preview').disabled = busy;
    $('inspectImages').disabled = busy;
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
        try {
            await saveUiState();
        } catch (error) {
            console.warn('Cannot save popup state:', error?.message);
        }
    }
}

$('connect').addEventListener('click', () => running(async () => {
    const pairCode = $('pair').value.trim();
    if (!pairCode) throw Error('Nhập mã ghép nối');
    await chrome.storage.session.set({pairCode});
    feedback('Đã lưu mã ghép nối trong phiên Edge. Chọn Xem trước Task.');
}));
$('inspectImages').addEventListener('click', () => running(async () => {
    const report = await bridge('/inspect-media');
    $('task').style.display = 'block';
    $('task').textContent = 'Task: ' + report.id + '\nGroup: ' + report.groupId +
        '\nStatus: ' + report.taskStatus + '\nKind: ' + report.mediaKind +
        '\nDữ liệu nguồn: ' + (report.sourceMediaKind || report.mediaKind) + '\nMedia: ' + report.mediaCount +
        '\nRead-only inspection' +
        '\nCDN: ' + report.mediaHosts.join(', ') +
        '\nCDN cho phép: ' + (report.hostsAllowed ? 'Có' : 'KHÔNG');
    feedback('Chỉ kiểm tra dữ liệu. Chưa tải ảnh, chưa tạo draft, chưa đổi trạng thái hoặc đăng LIVE.');
}));
$('preview').addEventListener('click', () => running(async () => {
    filled = false;
    imagesAttached = false;
    imagesReviewed = false;
    videoAttached = false;
    videoReviewed = false;
    preview = null;
    controls();
    const result = await bridge('/preview');
    preview = result;
    $('task').style.display = 'block';
    $('task').textContent = `TASK: ${result.id}\nGROUP: ${result.groupId}\n\n${result.content}\n\n${result.marker}`;
    feedback('Task READY + APPROVED: ' + result.mediaKind + ' (' + result.mediaCount + ' file). Chưa claim và chưa đăng.');
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
    imagesAttached = false;
    imagesReviewed = false;
    videoAttached = false;
    videoReviewed = false;
    feedback(answer.message);
}));
$('attachImages').addEventListener('click', () => running(async () => {
    if (!preview || !filled || preview.imageCount < 1) throw Error('Chưa nạp bản nháp có ảnh');
    const answer = await facebook('GROUP_ATTACH_MEDIA', {preview});
    imagesAttached = true;
    feedback(answer.message);
}));
$('ackImages').addEventListener('click', () => running(async () => {
    if (!preview || !filled || !imagesAttached) throw Error('Chưa đính kèm ảnh');
    if (!confirm('Bạn đã nhìn thấy ĐÚNG ' + preview.imageCount +
        ' ảnh/thumbnail tải HOÀN TẤT trong Facebook Composer? Nếu không chắc chắn chọn Hủy.')) return;
    await facebook('GROUP_ACK_IMAGES', {preview});
    imagesReviewed = true;
    feedback('Đã ghi nhận kiểm tra thủ công ' + preview.imageCount + ' ảnh. Chỉ bấm Đăng nếu tất cả thumbnail đã tải xong.');
}));
$('attachVideo').addEventListener('click', () => running(async () => {
    if (!preview || !filled || preview.mediaKind !== 'VIDEO') throw Error('Chưa nạp video Task');
    const answer = await facebook('GROUP_ATTACH_VIDEO', {preview});
    videoAttached = true;
    feedback(answer.message);
}));
$('ackVideo').addEventListener('click', () => running(async () => {
    if (!preview || !videoAttached) throw Error('Chưa đính kèm MP4');
    if (!confirm('Facebook đã xử lý XONG video MP4, bạn THẬT SỰ nhìn thấy thumbnail/player và đúng nội dung? Nếu không chắc chắn bấm Hủy.')) return;
    await facebook('GROUP_ACK_VIDEO', {preview});
    videoReviewed = true;
    feedback('Đã xác nhận thủ công video. KHÔNG có bằng chứng Reel riêng; chỉ đăng khi Group UI hiển thị đúng đích.');
}));
$('publish').addEventListener('click', () => running(async () => {
    if (!filled || !preview) throw Error('Nạp bản nháp trước');
    const mode = preview.mediaKind === 'VIDEO'
        ? 'MP4 Group Post (video nguồn có thể được gắn nhãn Reel nhưng không bảo đảm tạo Reel riêng)'
        : preview.mediaKind;
    if (!confirm('Xác nhận đăng THẬT đúng 1 bài ' + mode + ' vào Group ' + preview.groupId +
        '? Phải xem đủ file tải xong. KHÔNG retry nếu kết quả chưa rõ.')) return;
    $('publish').disabled = true;
    // Clear UI state regardless of Facebook/Bridge outcome: one attempted LIVE
    // must never be repeatable via stale popup flags after a timeout or tab switch.
    try {
        const answer = await facebook('GROUP_PUBLISH');
        feedback(answer.message);
    } finally {
        filled = false;
        imagesAttached = false;
        imagesReviewed = false;
        videoAttached = false;
        videoReviewed = false;
        preview = null;
        $('task').style.display = 'none';
    }
}));
controls();
void restoreUiState();
