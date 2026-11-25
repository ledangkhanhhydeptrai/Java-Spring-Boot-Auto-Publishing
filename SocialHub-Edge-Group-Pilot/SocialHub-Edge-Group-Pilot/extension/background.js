'use strict';
// One approved task, one known administrator-owned test group.
// No Facebook API, cookies, passwords, or tokens are accessed.
// Required debugger permission: user explicitly confirms before each browser interaction.
const ENDPOINT = 'http://127.0.0.1:8765';
const ROUTES = new Set(['/preview', '/claim', '/result', '/stage-images', '/stage-video', '/inspect-images', '/inspect-media']);
const EXPECTED_GROUP_ID = '1326570106277759';
const MAX_TEXT_LENGTH = 6000;
const DEBUGGED_TABS = new Set();
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function bridge(path, body) {
    const {pairCode} = await chrome.storage.session.get('pairCode');
    if (!pairCode) throw Error('Chưa kết nối mã ghép nối của Edge Bridge');
    const method = path === '/preview' || path === '/inspect-images' || path === '/inspect-media' ? 'GET' : 'POST';
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), path === '/stage-video' ? 240000 : path === '/stage-images' ? 120000 : 20000);
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
    if (!(await chrome.permissions.contains({permissions: ['debugger']})))
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
            try {
                await chrome.debugger.detach(target);
            } catch (error) {
                console.warn('SocialHub debugger detach failed:', error?.message);
            }
        }
        DEBUGGED_TABS.delete(tabId);
    }
}

async function attachImagesByBrowser(message, sender) {
    const tabId = sender.tab?.id;
    const url = sender.tab?.url || '';
    if (!Number.isInteger(tabId) ||
        !new RegExp('^https://(?:www\\.)?facebook\\.com/groups/' + EXPECTED_GROUP_ID + '(?:/|\\?|$)', 'i').test(url))
        throw Error('Chỉ đính kèm ảnh vào đúng tab Group thử nghiệm');
    if (message.groupId !== EXPECTED_GROUP_ID ||
        !/^[a-f0-9-]{36}$/.test(message.inputMarker || '') ||
        !/^[a-f0-9-]{36}$/.test(message.taskId || ''))
        throw Error('Image target/Task/Group invalid');
    if (DEBUGGED_TABS.has(tabId)) throw Error('Tab đang có thao tác CDP khác');
    const preview = await bridge('/preview');
    if (preview.id !== message.taskId || preview.digest !== message.digest ||
        preview.groupId !== EXPECTED_GROUP_ID || preview.imageCount !== message.count ||
        !Number.isInteger(message.count) || message.count < 1 || message.count > 10)
        throw Error('Image count/Task/digest changed. Do not attach');
    // Stage ONE time, from server-validated image URLs; the untrusted page
    // never supplies local paths or image URLs to the bridge.
    const staged = await bridge('/stage-images', {digest: preview.digest});
    if (staged.digest !== preview.digest || staged.id !== preview.id ||
        staged.count !== preview.imageCount ||
        !Array.isArray(staged.files) || staged.files.length !== staged.count)
        throw Error('Staged image manifest mismatched');
    const paths = staged.files.map(file => file.path);
    if (!paths.every(path => typeof path === 'string' && path.length > 5))
        throw Error('Missing staged images');

    const target = {tabId};
    let attached = false;
    DEBUGGED_TABS.add(tabId);
    try {
        await chrome.debugger.attach(target, '1.3');
        attached = true;
        const documentResult = await chrome.debugger.sendCommand(target, 'DOM.getDocument', {depth: 1});
        const rootId = documentResult?.root?.nodeId;
        if (!rootId) throw Error('No DOM root for image picker');
        const selector = 'input[type="file"][data-socialhub-media-target="' + message.inputMarker + '"]';
        const nodes = await chrome.debugger.sendCommand(target, 'DOM.querySelectorAll',
            {nodeId: rootId, selector});
        if (!Array.isArray(nodes.nodeIds) || nodes.nodeIds.length !== 1)
            throw Error('Không xác định duy nhất file input trong Group Composer');
        await chrome.debugger.sendCommand(target, 'DOM.setFileInputFiles',
            {files: paths, nodeId: nodes.nodeIds[0]});
        await delay(200);
        const inspected = await chrome.debugger.sendCommand(target, 'Runtime.evaluate', {
            expression: 'document.querySelector(' + JSON.stringify(selector) + ')?.files?.length ?? -1',
            returnByValue: true
        });
        const observed = inspected?.result?.value;
        // React may clear/replace file inputs after starting an upload.
        // The CDP command succeeded; the operator MUST visually verify each
        // thumbnail because input.files may already be empty or detached.
        return {
            selectedCount: observed, expectedCount: staged.count,
            commandAccepted: true, inputWasReset: observed !== staged.count,
            message: 'Đã gửi ảnh vào Facebook. Chờ đủ thumbnail tải xong rồi xác minh bằng mắt; input có thể reset.'
        };
    } catch (error) {
        throw Error('CDP-IMAGE-PILOT: ' + (error?.message || String(error)) +
            '. Đóng/bỏ bản nháp, không bấm Đăng và không retry trong phiên này.');
    } finally {
        if (attached) {
            try {
                await chrome.debugger.detach(target);
            } catch (error) {
                console.warn('debugger detach:', error?.message);
            }
        }
        DEBUGGED_TABS.delete(tabId);
    }
}

// V12: exact one-video MP4, one CDP file input. No retries, no Facebook API.
async function attachVideoByBrowser(message, sender) {
    const tabId = sender.tab?.id;
    const url = sender.tab?.url || '';
    if (!Number.isInteger(tabId) ||
        !new RegExp('^https://(?:www\\.)?facebook\\.com/groups/' + EXPECTED_GROUP_ID + '(?:/|\\?|$)', 'i').test(url))
        throw Error('Chỉ đính kèm video vào Group thử nghiệm');
    if (message.groupId !== EXPECTED_GROUP_ID ||
        !/^[a-f0-9-]{36}$/.test(message.inputMarker || '') ||
        !/^[a-f0-9-]{36}$/.test(message.taskId || '') || message.count !== 1)
        throw Error('Video target/Task/Group không hợp lệ');
    if (DEBUGGED_TABS.has(tabId)) throw Error('Tab đang xử lý thao tác CDP khác');
    const preview = await bridge('/preview');
    if (preview.id !== message.taskId || preview.digest !== message.digest ||
        preview.groupId !== EXPECTED_GROUP_ID || preview.mediaKind !== 'VIDEO' ||
        preview.mediaCount !== 1)
        throw Error('Task/video/digest changed; KHÔNG đính kèm');
    const staged = await bridge('/stage-video', {digest: preview.digest});
    if (staged.digest !== preview.digest || staged.id !== preview.id ||
        staged.mediaKind !== 'VIDEO' || staged.count !== 1 ||
        !Array.isArray(staged.files) || staged.files.length !== 1 ||
        typeof staged.files[0]?.path !== 'string')
        throw Error('Staged video mismatched');
    const target = {tabId};
    let attached = false;
    DEBUGGED_TABS.add(tabId);
    try {
        await chrome.debugger.attach(target, '1.3');
        attached = true;
        const doc = await chrome.debugger.sendCommand(target, 'DOM.getDocument', {depth: 1});
        if (!doc?.root?.nodeId) throw Error('DOM root missing');
        const selector = 'input[type="file"][data-socialhub-video-target="' + message.inputMarker + '"]';
        const nodes = await chrome.debugger.sendCommand(target, 'DOM.querySelectorAll',
            {nodeId: doc.root.nodeId, selector});
        if (!Array.isArray(nodes.nodeIds) || nodes.nodeIds.length !== 1)
            throw Error('Không có đúng một ô chọn video trong Group Composer');
        await chrome.debugger.sendCommand(target, 'DOM.setFileInputFiles',
            {files: [staged.files[0].path], nodeId: nodes.nodeIds[0]});
        await delay(300);
        const inspected = await chrome.debugger.sendCommand(target, 'Runtime.evaluate', {
            expression: 'document.querySelector(' + JSON.stringify(selector) + ')?.files?.length ?? -1',
            returnByValue: true
        });
        const observed = inspected?.result?.value;
        return {
            selectedCount: observed, expectedCount: 1,
            commandAccepted: true, inputWasReset: observed !== 1,
            message: 'Đã gửi MP4 vào Facebook. Chờ video xử lý hoàn tất và xem player thực tế; chưa chứng minh đăng Reel.'
        };
    } catch (error) {
        throw Error('CDP-VIDEO-PILOT: ' + (error?.message || String(error)) +
            '. Đóng bản nháp, KHÔNG đăng hoặc tự retry.');
    } finally {
        if (attached) {
            try {
                await chrome.debugger.detach(target);
            } catch (error) {
                console.warn('CDP detach:', error?.message);
            }
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
    } else if (message?.kind === 'GROUP_ATTACH_IMAGES') {
        operation = attachImagesByBrowser(message, sender);
    } else if (message?.kind === 'GROUP_ATTACH_VIDEO') {
        operation = attachVideoByBrowser(message, sender);
    } else {
        return false;
    }
    operation.then(value => sendResponse({ok: true, value}))
        .catch(error => sendResponse({ok: false, error: String(error?.message || error)}));
    return true;
});
