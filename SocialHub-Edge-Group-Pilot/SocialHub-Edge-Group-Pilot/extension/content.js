'use strict';
// Visible UI automation for ONE administrator-owned test group. No stealth,
// CAPTCHA/2FA bypass, session export, background posting or blind retries.
(() => {
    // Prevent two copies of the content script from registering competing
    // GROUP_FILL listeners in the same tab. Reload Facebook after updating.
    const INSTALL_KEY = '__socialHubGroupPilotContentListener';
    if (window[INSTALL_KEY]) return;
    window[INSTALL_KEY] = true;

    let prepared = null;
    let inFlight = false;
    let filling = false; // Prevent overlapping Fill calls on the same tab.
    let mediaAttempted = false; // Never upload files twice to one draft.
    const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
    const taskRegex = /^[a-f0-9-]{36}$/;
    const isVisible = el => Boolean(el && el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden');
    const all = (root, selector) => [...root.querySelectorAll(selector)];
    const txt = el => String(el?.innerText || el?.textContent || '').trim();
    const withinTarget = groupId =>
        location.hostname === 'www.facebook.com' || location.hostname === 'facebook.com' ?
            new RegExp('^/groups/' + groupId + '(?:/|$)').test(location.pathname) : false;

    function assertGroup(groupId) {
        if (!/^\d+$/.test(groupId) || !withinTarget(groupId))
            throw new Error('Tab hiện tại không phải đúng Facebook Group được chỉ định');
        if (document.querySelector('input[name="email"]') && document.querySelector('input[name="pass"]'))
            throw new Error('Facebook chưa đăng nhập; không điều khiển hoặc vượt qua màn hình xác minh');
        if (document.body.innerText.includes("This content isn't available at the moment"))
            throw new Error('Facebook Group không truy cập được bằng phiên này');
    }

    function visibleWhere(root, selector, fn) {
        return all(root, selector).find(el => isVisible(el) && fn(el));
    }

    // Facebook changes its composer markup frequently. Some layouts omit
    // role="dialog", lazily attach the editable field, or require clicking its
    // placeholder before the contenteditable node exists. Never fall back to an
    // editor elsewhere in the feed (e.g. a comment box).
    const composerHeading = /^(?:Tạo bài viết|Create post)$/i;
    const composerPrompt = /^(?:Bạn viết gì đi(?:\.\.\.)?|Viết gì đó(?:\.\.\.)?|Write something(?:\.\.\.)?|Write a post)$/i;

    // Some Facebook builds put the exact heading text directly in a DIV,
    // instead of a SPAN/heading element. Inspect only the element's OWN text
    // nodes so we do not mistake the whole feed for a composer title.
    function ownText(el) {
        return [...el.childNodes].filter(n => n.nodeType === 3)
            .map(n => n.textContent || '').join(' ').trim();
    }

    function exactHeading(el) {
        return isVisible(el) && (composerHeading.test(ownText(el)) ||
            composerHeading.test(txt(el)) && el.children.length <= 1);
    }

    function exactPrompt(el) {
        if (!isVisible(el)) return false;
        return composerPrompt.test(el.getAttribute('aria-label') || '') ||
            composerPrompt.test(el.getAttribute('data-placeholder') || '') ||
            composerPrompt.test(el.getAttribute('aria-placeholder') || '') ||
            composerPrompt.test(ownText(el));
    }

    function headingNodes(root) {
        return all(root, 'h1,h2,h3,[role="heading"],span,div').filter(exactHeading);
    }

    function containsComposerHeading(root) {
        return headingNodes(root).length > 0;
    }

    function hasPostButton(root) {
        return Boolean(visibleWhere(root, 'button,[role="button"]', el =>
            /^(?:Đăng|Post|Publish)$/.test(txt(el))));
    }

    function sensiblePanel(el) {
        if (!isVisible(el)) return false;
        const r = el.getBoundingClientRect();
        return r.width >= 280 && r.width <= 1050 &&
            r.height >= 240 && r.height <= 1050;
    }

    // Facebook can render nested accessible dialogs for the SAME composer.
    // Earlier code treated two nested dialogs as two different composers and
    // rejected the request (scope=false, dialogs=2). Keep only the *innermost*
    // eligible panel; reject genuinely separate/ambiguous composers.
    function uniqueInnermost(nodes) {
        const unique = [...new Set(nodes)];
        const leaves = unique.filter(node => !unique.some(other =>
            other !== node && node.contains(other)));
        return leaves.length === 1 ? leaves[0] : null;
    }

    function hasComposerPrompt(root) {
        return Boolean(visibleWhere(root,
            '[role="textbox"],[aria-placeholder],[data-placeholder],p,span,div', exactPrompt));
    }

    function isComposerPanel(el) {
        return hasPostButton(el) &&
            (containsComposerHeading(el) || hasComposerPrompt(el)) &&
            (Boolean(findEditor(el)) || hasComposerPrompt(el));
    }

    function composerScope() {
        const dialogs = all(document, '[role="dialog"],[aria-modal="true"]')
            .filter(isVisible);
        const eligible = dialogs.filter(isComposerPanel);

        // When the outer and inner modal are nested, prefer the innermost one
        // that actually owns an editable field. This never chooses between
        // two distinct modal windows.
        const withEditor = eligible.filter(el => Boolean(findEditor(el)));
        let chosen = uniqueInnermost(withEditor);
        if (chosen) return chosen;
        chosen = uniqueInnermost(eligible);
        if (chosen) return chosen;
        if (eligible.length > 1) return null;

        // Some Facebook versions omit role="dialog". Walk from the exact
        // composer heading / placeholder to a compact panel with the Post button.
        const anchors = [
            ...headingNodes(document),
            ...all(document, 'span,div,p,[data-placeholder],[aria-placeholder]')
                .filter(exactPrompt)
        ];
        const candidates = new Set();
        for (const anchor of anchors) {
            let parent = anchor.parentElement;
            for (let depth = 0; parent && depth < 18; depth++, parent = parent.parentElement) {
                if (!sensiblePanel(parent)) continue;
                if (isComposerPanel(parent)) candidates.add(parent);
            }
        }
        const withField = [...candidates].filter(el => Boolean(findEditor(el)));
        return uniqueInnermost(withField.length ? withField : [...candidates]);
    }


    function isEditable(el) {
        if (!isVisible(el)) return false;
        if (el.tagName === 'TEXTAREA') return !el.disabled && !el.readOnly;
        // Accept contenteditable="", "true", or inherited editable state.
        const attribute = el.getAttribute('contenteditable');
        return el.isContentEditable ||
            (attribute !== null && attribute.toLowerCase() !== 'false' &&
                attribute.toLowerCase() !== 'inherit');
    }

    function findEditor(scope) {
        if (!scope) return null;
        const fields = all(scope,
            '[contenteditable]:not([contenteditable="false"]),[data-lexical-editor],textarea,[role="textbox"]')
            .filter(isEditable)
            .filter(el => el.getBoundingClientRect().width >= 80);
        const topLevel = fields.filter(el => !fields.some(other =>
            other !== el && other.contains(el) && isEditable(other)));
        const preferred = topLevel.filter(el =>
            el.getAttribute('role') === 'textbox' ||
            el.hasAttribute('data-lexical-editor') ||
            composerPrompt.test(el.getAttribute('aria-label') || '') ||
            composerPrompt.test(el.getAttribute('aria-placeholder') || '') ||
            composerPrompt.test(el.getAttribute('data-placeholder') || ''));
        if (preferred.length === 1) return preferred[0];
        if (preferred.length > 1) return null; // Never choose ambiguously.
        return topLevel.length === 1 ? topLevel[0] : null;
    }

    function editorForComposer() {
        const scope = composerScope();
        return {scope, editor: findEditor(scope)};
    }

    async function waitForEditor(timeoutMs = 9000) {
        const deadline = Date.now() + timeoutMs;
        let activated = false;
        while (Date.now() < deadline) {
            const found = editorForComposer();
            if (found.editor) return found;
            if (found.scope && !activated) {
                // Some Facebook layouts create the actual editable field only after
                // clicking the placeholder in the already open create-post modal.
                const placeholder = visibleWhere(found.scope,
                    '[role="textbox"],[aria-placeholder],[data-placeholder],p,span,div', exactPrompt);
                if (placeholder) {
                    placeholder.click();
                    activated = true;
                }
            }
            await delay(300);
        }
        const scope = composerScope();
        const count = scope ? all(scope, '[contenteditable],textarea,[role="textbox"]').length : 0;
        const visibleEditors = all(document, '[contenteditable],[role="textbox"],textarea')
            .filter(isVisible);
        const dialogs = all(document, '[role="dialog"],[aria-modal="true"]')
            .filter(isVisible);
        const titles = headingNodes(document);
        const eligibleDialogs = dialogs.filter(isComposerPanel);
        const eligibleWithField = eligibleDialogs.filter(el => Boolean(findEditor(el)));
        throw new Error('COMPOSER-DIAG-v4: không tìm được duy nhất ô soạn thảo. ' +
            'scope=' + Boolean(scope) + ', fieldsInScope=' + count +
            ', visibleFields=' + visibleEditors.length + ', dialogs=' + dialogs.length +
            ', createPostTitles=' + titles.length +
            ', eligibleDialogs=' + eligibleDialogs.length +
            ', dialogsWithEditor=' + eligibleWithField.length + '. KHÔNG đăng');
    }

    async function openComposer() {
        const found = editorForComposer();
        if (found.editor) return found;
        if (found.scope) return waitForEditor(); // Already open: do not click behind modal.

        const trigger = visibleWhere(document, '[role="button"],button,[role="textbox"]', el =>
                /Bạn viết gì đi|Viết gì đó|Write something|Write a post/i.test(
                    txt(el) || el.getAttribute('aria-label') || '')) ||
            visibleWhere(document, 'span,div,p', exactPrompt);
        if (!trigger) throw new Error('Không tìm được ô tạo bài trên Facebook Group');
        trigger.click();
        return waitForEditor();
    }

    // COMPOSER-INSERT-v10: browser-originated typing via chrome.debugger.
    // NEVER mutate Lexical DOM, execCommand, synthetic events or blind retry.
    const normalizeText = value => String(value ?? '')
        .replace(/\r\n?/g, '\n').replace(/\u00a0/g, ' ')
        .replace(/\u200b/g, '').trim();

    function paragraphText(paragraph) {
        const parts = [];

        function visit(node) {
            if (node.nodeType === Node.TEXT_NODE) {
                parts.push(node.nodeValue || '');
                return;
            }
            if (node.nodeType !== Node.ELEMENT_NODE ||
                node.getAttribute('aria-hidden') === 'true' || node.hidden) return;
            if (node.tagName === 'BR') {
                parts.push('\n');
                return;
            }
            for (const child of node.childNodes) visit(child);
        }

        const hasContent = [...paragraph.childNodes].some(node =>
            node.nodeType === Node.TEXT_NODE ? Boolean(node.nodeValue) :
                node.nodeType === Node.ELEMENT_NODE && node.tagName !== 'BR');
        if (!hasContent) return '';
        for (const node of paragraph.childNodes) visit(node);
        return parts.join('').replace(/\n$/, '');
    }

    function readEditor(editor) {
        if (!editor) return {text: '', source: 'missing'};
        if (editor.tagName === 'TEXTAREA') {
            return {text: normalizeText(editor.value), source: 'textarea'};
        }
        const paragraphs = all(editor, 'p').filter(p => !p.parentElement?.closest('p'));
        if (paragraphs.length) return {
            text: normalizeText(paragraphs.map(paragraphText).join('\n')),
            source: 'paragraphs'
        };
        return {text: normalizeText(editor.innerText || ''), source: 'innerText'};
    }

    const editorText = editor => readEditor(editor).text;
    const exactEditorContent = (editor, expected) =>
        Boolean(editor) && editorText(editor) === normalizeText(expected);

    function mismatch(expected, editor, stage) {
        const rendered = readEditor(editor);
        const marker = expected.match(/\[SocialHub Group Pilot [a-f0-9]{8}\]$/)?.[0] || '';
        const markerCount = marker ? rendered.text.split(marker).length - 1 : 0;
        throw new Error('COMPOSER-INSERT-v10: Facebook hiển thị nội dung khác Task (' + stage + '). ' +
            'expectedLength=' + normalizeText(expected).length +
            ', visibleLength=' + rendered.text.length +
            ', domBlocks=' + (editor?.querySelectorAll('p,div,li,blockquote').length || 0) +
            ', brCount=' + (editor?.querySelectorAll('br').length || 0) +
            ', source=' + rendered.source + ', markerCount=' + markerCount +
            '. BỎ BẢN NHÁP; KHÔNG đăng');
    }

    async function checkRendered(expected, stage) {
        for (const ms of [350, 750, 1100]) {
            await delay(ms);
            const {scope, editor} = editorForComposer();
            if (!scope || !editor || !exactEditorContent(editor, expected))
                mismatch(expected, editor, stage);
        }
    }

    let typedOnceInTab = false;

    function callTyping(request) {
        return new Promise((resolve, reject) => {
            chrome.runtime.sendMessage({kind: 'GROUP_TYPE_TEXT', ...request}, response => {
                if (chrome.runtime.lastError) return reject(Error(chrome.runtime.lastError.message));
                if (!response?.ok) return reject(Error(response?.error || 'CDP typing unavailable'));
                resolve(response.value);
            });
        });
    }

    async function setEditor(editor, content, task) {
        const expected = normalizeText(content);
        const previous = editorText(editor);
        if (previous && previous !== expected)
            throw Error('Facebook Composer đang có bản nháp khác; đóng và BỎ bản nháp; KHÔNG đăng');
        if (previous === expected) {
            await checkRendered(expected, 'existing draft');
            return;
        }
        if (typedOnceInTab)
            throw Error('Đã thử nhập một lần trong tab này. Đóng bản nháp, refresh Facebook; KHÔNG tự Retry');
        const {scope, editor: current} = editorForComposer();
        // The actual Facebook modal editor is in a dialog. No fallback to the
        // public Group feed textbox or a comment editor.
        if (!scope || !current || current !== editor ||
            !current.closest('[role="dialog"],[aria-modal="true"]'))
            throw Error('Không xác định duy nhất editor trong cửa sổ Tạo bài viết; KHÔNG nhập');
        if (current.tagName !== 'DIV' || !current.isContentEditable)
            throw Error('Editor không phải Facebook Lexical DIV; KHÔNG nhập');

        current.focus();
        const selection = window.getSelection();
        if (!selection) throw Error('Không có con trỏ soạn thảo; KHÔNG nhập');
        const range = document.createRange();
        range.selectNodeContents(current);
        range.collapse(true); // caret inside the empty editor, NOT a select-all mutation
        selection.removeAllRanges();
        selection.addRange(range);
        if (!current.contains(selection.anchorNode))
            throw Error('Con trỏ không nằm trong Facebook Composer; KHÔNG nhập');
        typedOnceInTab = true; // no second attempt even if CDP fails halfway
        await callTyping({
            groupId: task.groupId, taskId: task.id,
            digest: task.digest, text: content
        });
        await checkRendered(expected, 'browser-keyboard insertion');
    }

    // IMAGE-PILOT-v11: explicit file chooser selection with browser CDP.
    // No DOM replacement of Lexical, no fake file input events, no automatic retries.
    const imageControl = /(?:Ảnh\s*\/?\s*video|Thêm ảnh|Photo\s*\/?\s*video|Add photo|Add photos)/i;

    function fileInputs() {
        return all(document, 'input[type="file"]').filter(el => el.isConnected && !el.disabled);
    }

    function imageAccepts(input) {
        const accept = (input.getAttribute('accept') || '').toLowerCase();
        return !accept || /image|\.jpe?g|\.png|\.webp/.test(accept);
    }

    async function findImageInput(scope, expectedCount) {
        const initial = fileInputs();
        let eligible = initial.filter(input => imageAccepts(input) &&
            (expectedCount === 1 || input.multiple) && scope.contains(input));
        if (eligible.length === 1) return eligible[0];
        if (eligible.length > 1) throw new Error('Có nhiều ô tải ảnh trong Composer; KHÔNG chọn bừa');

        // Some Facebook revisions create the hidden input on an accessible
        // Photo/video button click. Require an unambiguous, visible button.
        const controls = all(scope, 'button,[role="button"]').filter(el => isVisible(el) &&
            imageControl.test([el.getAttribute('aria-label'), el.getAttribute('title'), txt(el)]
                .filter(Boolean).join(' ')));
        if (controls.length !== 1)
            throw new Error('Không xác định duy nhất nút Ảnh/video trong Composer. ' +
                'Nếu không có, mở nút Ảnh/video trên Facebook rồi kiểm tra lại, KHÔNG đăng');
        controls[0].click();
        await delay(800);
        const after = fileInputs().filter(input => imageAccepts(input) &&
            (expectedCount === 1 || input.multiple));
        eligible = after.filter(input => scope.contains(input) || !initial.includes(input));
        if (eligible.length !== 1)
            throw new Error('Không tìm được một input ảnh duy nhất sau khi mở Ảnh/video; KHÔNG đăng');
        return eligible[0];
    }

    function videoAccepts(input) {
        const accept = (input.getAttribute('accept') || '').toLowerCase();
        return !accept || /video|\.mp4/.test(accept);
    }

    async function findVideoInput(scope) {
        const before = fileInputs();
        let matching = before.filter(input => scope.contains(input) && videoAccepts(input));
        if (matching.length === 1) return matching[0];
        if (matching.length > 1) throw Error('Nhiều ô chọn video trong Composer; KHÔNG chọn bừa');
        const controls = all(scope, 'button,[role="button"]').filter(el => isVisible(el) &&
            imageControl.test([el.getAttribute('aria-label'), el.getAttribute('title'), txt(el)]
                .filter(Boolean).join(' ')));
        if (controls.length !== 1)
            throw Error('Không thấy một nút Ảnh/video duy nhất; mở thủ công đúng Composer rồi kiểm tra lại');
        controls[0].click();
        await delay(800);
        matching = fileInputs().filter(input => videoAccepts(input) &&
            (scope.contains(input) || !before.includes(input)));
        if (matching.length !== 1)
            throw Error('Không xác định được một input VIDEO MP4 sau khi mở Ảnh/video');
        return matching[0];
    }

    function callVideoAttach(request) {
        return new Promise((resolve, reject) => {
            chrome.runtime.sendMessage({kind: 'GROUP_ATTACH_VIDEO', ...request}, response => {
                if (chrome.runtime.lastError) return reject(Error(chrome.runtime.lastError.message));
                if (!response?.ok) return reject(Error(response?.error || 'Edge không chọn được video'));
                resolve(response.value);
            });
        });
    }

    async function attachVideo(request) {
        if (filling || inFlight || !prepared) throw Error('Chưa có bản nháp hợp lệ');
        const current = prepared;
        if (!request.preview || request.preview.id !== current.id ||
            request.preview.digest !== current.digest || current.mediaKind !== 'VIDEO' ||
            current.mediaCount !== 1)
            throw Error('Không phải một Task VIDEO hợp lệ');
        if (mediaAttempted) throw Error('Đã thử chọn media trong tab này; không Retry');
        assertGroup(current.groupId);
        const {scope, editor} = editorForComposer();
        if (!scope || !editor || !exactEditorContent(editor, current.expected))
            throw Error('Nội dung bản nháp thay đổi; không chọn video');
        const input = await findVideoInput(scope);
        const marker = crypto.randomUUID();
        input.setAttribute('data-socialhub-video-target', marker);
        mediaAttempted = true;
        const result = await callVideoAttach({
            taskId: current.id, groupId: current.groupId,
            digest: current.digest, inputMarker: marker, count: 1
        });
        // FB may clear its file input after accepting the upload. Require
        // explicit operator check of the completed video/player before LIVE.
        if (result.commandAccepted !== true || result.expectedCount !== 1)
            throw Error('CDP chưa xác nhận lệnh chọn MP4; KHÔNG đăng');
        await delay(900);
        const check = editorForComposer();
        if (!check.editor || !exactEditorContent(check.editor, current.expected))
            throw Error('Facebook thay đổi nội dung sau khi chọn video; KHÔNG đăng');
        current.mediaAttached = true;
        return {message: 'Đã chọn MP4 vào Composer. Đợi Facebook xử lý XONG và kiểm tra video/thumbnail bằng mắt trước LIVE.'};
    }

    async function acknowledgeVideo(request) {
        if (!prepared || prepared.mediaKind !== 'VIDEO' || !prepared.mediaAttached ||
            request.preview?.id !== prepared.id || request.preview?.digest !== prepared.digest)
            throw Error('Task/video không khớp hoặc chưa đính kèm');
        prepared.mediaReviewed = true; // Operator confirmation, NOT automatic upload proof.
        return {message: 'Đã xác nhận video được Facebook hiển thị và xử lý xong (thủ công)'};
    }

    function callImageAttach(request) {
        return new Promise((resolve, reject) => {
            chrome.runtime.sendMessage({kind: 'GROUP_ATTACH_IMAGES', ...request}, response => {
                if (chrome.runtime.lastError) return reject(Error(chrome.runtime.lastError.message));
                if (!response?.ok) return reject(Error(response?.error || 'Không thể chọn ảnh qua Edge CDP'));
                resolve(response.value);
            });
        });
    }

    async function attachImages(request) {
        if (filling || inFlight || !prepared) throw Error('Chưa có bản nháp hợp lệ hoặc đang xử lý');
        const current = prepared;
        const {preview} = request;
        if (!preview || preview.id !== current.id || preview.digest !== current.digest ||
            preview.groupId !== current.groupId || current.mediaKind !== 'IMAGE' ||
            preview.imageCount !== current.imageCount ||
            !Number.isInteger(current.imageCount) || current.imageCount < 1 || current.imageCount > 10)
            throw Error('Preview/ảnh/Task không khớp; KHÔNG đính kèm');
        if (mediaAttempted) throw Error('Đã thử chọn ảnh trong tab này; KHÔNG tự Retry');
        assertGroup(current.groupId);
        const {scope, editor} = editorForComposer();
        if (!scope || !editor || !exactEditorContent(editor, current.expected))
            throw Error('Nội dung Composer thay đổi; KHÔNG tải ảnh');
        const input = await findImageInput(scope, current.imageCount);
        const inputMarker = crypto.randomUUID();
        input.setAttribute('data-socialhub-media-target', inputMarker);
        mediaAttempted = true; // one attempt per draft even after an unknown CDP failure
        const result = await callImageAttach({
            taskId: current.id, groupId: current.groupId,
            digest: current.digest, inputMarker, count: current.imageCount
        });
        // Facebook may unmount/reset its <input type="file"> once upload starts.
        // Successful CDP selection is necessary but NOT proof of completed upload.
        // Manual verification in GROUP_ACK_IMAGES remains mandatory before LIVE.
        if (result.commandAccepted !== true || result.expectedCount !== current.imageCount)
            throw Error('CDP không xác nhận đã chuyển đủ ảnh vào Composer; KHÔNG đăng');
        await delay(1300);
        const recheck = editorForComposer();
        if (!recheck.editor || !exactEditorContent(recheck.editor, current.expected))
            throw Error('Nội dung thay đổi sau khi chọn ảnh; KHÔNG đăng');
        current.imageAttached = true;
        current.mediaAttached = true;
        return {
            message: 'Đã chọn ' + current.imageCount + ' ảnh qua Edge. ' +
                'Đợi Facebook tải xong; phải NHÌN THẤY đủ ' + current.imageCount +
                ' thumbnail trước khi xác nhận thủ công. Chưa đăng.'
        };
    }

    async function acknowledgeImages(request) {
        if (!prepared || !prepared.imageAttached || !request.preview ||
            request.preview.id !== prepared.id ||
            request.preview.digest !== prepared.digest ||
            request.preview.imageCount !== prepared.imageCount)
            throw Error('Không đủ bằng chứng đã đính kèm ảnh; KHÔNG đăng');
        // Operator has visually confirmed all thumbnails. This is NOT an
        // automated proof of successful Facebook upload.
        prepared.imagesReviewed = true;
        prepared.mediaReviewed = true;
        return {message: 'Đã ghi nhận kiểm tra thủ công số ảnh'};
    }

    function publishButton(scope) {
        const candidates = all(scope, '[role="button"],button').filter(isVisible);
        const matching = candidates.filter(el => /^(Đăng|Post|Publish)$/.test(txt(el)) &&
            el.getAttribute('aria-disabled') !== 'true' && !el.disabled);
        if (matching.length !== 1) throw new Error('Không xác định duy nhất nút Đăng trong hộp thoại');
        return matching[0];
    }

    function postLink(el, groupId) {
        const regex = new RegExp('^https://www\\.facebook\\.com/groups/' + groupId +
            '/(?:posts|permalink)/(?:[0-9]+|pfbid[A-Za-z0-9]+)/?(?:\\?.*)?$', 'i');
        for (const anchor of el.querySelectorAll('a[href]')) {
            let u;
            try {
                u = new URL(anchor.href, location.href);
            } catch {
                continue;
            }
            if (regex.test(u.href)) return u.origin + u.pathname;
        }
        return null;
    }

    async function verify(marker, groupId) {
        // Never fabricate permalinks. A visible post containing the marker AND a
        // real matching link must be found before reporting PUBLISHED to SocialHub.
        for (let i = 0; i < 14; i++) {
            await delay(2200);
            const articles = all(document, '[role="article"]');
            for (const article of articles) {
                if (txt(article).includes(marker)) {
                    const url = postLink(article, groupId);
                    if (url) return url;
                }
            }
        }
        return null;
    }

    function callBridge(path, body) {
        return new Promise((resolve, reject) => {
            chrome.runtime.sendMessage({kind: 'BRIDGE', path, body}, response => {
                if (chrome.runtime.lastError) return reject(new Error(chrome.runtime.lastError.message));
                if (!response?.ok) return reject(new Error(response?.error || 'Bridge unavailable'));
                resolve(response.value);
            });
        });
    }

    async function fill(request) {
        if (filling) throw new Error('Đang nạp bản nháp; không gửi yêu cầu trùng');
        if (inFlight) throw new Error('Đang xử lý đăng; không được nạp bản nháp mới');
        filling = true;
        prepared = null; // No publish if any subsequent validation fails.
        try {
            const {id, groupId, content, marker, digest, imageCount, mediaKind, mediaCount} = request.preview || {};
            if (!taskRegex.test(id || '') || !/^\d+$/.test(groupId || '') || !digest ||
                typeof content !== 'string' || !Number.isInteger(imageCount) || imageCount < 0 || imageCount > 10 ||
                !['TEXT', 'IMAGE', 'VIDEO'].includes(mediaKind) || !Number.isInteger(mediaCount) || mediaCount < 0 || mediaCount > 10 ||
                (mediaKind === 'IMAGE' && (mediaCount !== imageCount || imageCount < 1)) ||
                (mediaKind === 'VIDEO' && (imageCount !== 0 || mediaCount !== 1)) ||
                (mediaKind === 'TEXT' && mediaCount !== 0) ||
                marker !== '[SocialHub Group Pilot ' + id.slice(0, 8) + ']')
                throw new Error('Preview không hợp lệ');
            assertGroup(groupId);
            const expected = content.trim() + '\n\n' + marker;
            const {editor} = await openComposer();
            await setEditor(editor, expected, {id, groupId, digest});
            prepared = {
                id,
                groupId,
                expected,
                digest,
                marker,
                imageCount,
                imageAttached: false,
                imagesReviewed: false,
                mediaKind,
                mediaCount,
                mediaAttached: false,
                mediaReviewed: false
            };
            return {message: 'Đã nạp và kiểm tra bản nháp. Hãy xem lại nội dung trước khi đăng'};
        } finally {
            filling = false;
        }
    }

    async function publish() {
        if (inFlight || filling) throw new Error('Đang xử lý; không bấm lại');
        if (!prepared) throw new Error('Chưa nạp bài trong tab này');
        const current = prepared;
        assertGroup(current.groupId);
        if (current.mediaCount > 0 && (!current.mediaAttached || !current.mediaReviewed))
            throw new Error('Media chưa được đính kèm và kiểm tra; KHÔNG đăng');
        const {scope, editor} = editorForComposer();
        if (!editor || !exactEditorContent(editor, current.expected))
            throw new Error('Nội dung đã thay đổi. Không đăng để tránh nhầm bài');
        // Revalidate after React/Lexical has had a chance to reconcile editor state.
        await delay(450);
        const stable = editorForComposer();
        if (!stable.editor || !exactEditorContent(stable.editor, current.expected))
            throw new Error('Nội dung thay đổi sau khi Facebook cập nhật editor; KHÔNG đăng');
        const button = publishButton(stable.scope);
        inFlight = true;
        let claimed = false;
        let clicked = false;
        try {
            // Atomic READY -> PROCESSING is performed ONLY after the user explicitly
            // presses the extension's publish button, never on fill/preview.
            const task = await callBridge('/claim', {digest: current.digest});
            claimed = true;
            if (task.id !== current.id || task.groupId !== current.groupId ||
                (task.content.trim() + '\n\n' + task.marker) !== current.expected ||
                task.imageCount !== current.imageCount ||
                task.mediaKind !== current.mediaKind || task.mediaCount !== current.mediaCount)
                throw new Error('Claim payload differs from preview; DO NOT publish');
            // One click only, no catch-based retry even on timeout or UI change.
            clicked = true;
            button.click();
            const permalink = await verify(current.marker, current.groupId);
            const result = permalink ?
                {
                    status: 'PUBLISHED', publishedUrl: permalink,
                    note: 'Matched one Task marker and actual permalink in target Group feed'
                } :
                {
                    status: 'NEEDS_REVIEW', publishedUrl: null,
                    note: 'Facebook Publish was clicked; could not verify matching permalink. DO NOT RETRY'
                };
            await callBridge('/result', result);
            return {
                message: permalink ? 'Đã xác minh bài đăng: ' + permalink :
                    'Đã bấm Đăng nhưng chưa xác minh được permalink; Task NEEDS_REVIEW, KHÔNG đăng lại'
            };
        } catch (error) {
            if (claimed) {
                try {
                    await callBridge('/result', {
                        status: 'NEEDS_REVIEW', publishedUrl: null,
                        note: (clicked ? 'After' : 'Before') + ' Facebook Publish click: ' + String(error.message).slice(0, 250) +
                            '. Review group manually, DO NOT AUTO RETRY'
                    });
                } catch (reportError) {
                    throw new Error('Đã claim nhưng không ghi được kết quả: ' + reportError.message +
                        ' — kiểm tra Backend, KHÔNG đăng lại');
                }
            }
            throw error;
        } finally {
            inFlight = false;
            prepared = null; // One task / one attempt per page session.
        }
    }

    chrome.runtime.onMessage.addListener((msg, sender, respond) => {
        if (!['GROUP_FILL', 'GROUP_ATTACH_MEDIA', 'GROUP_ACK_IMAGES', 'GROUP_ATTACH_VIDEO', 'GROUP_ACK_VIDEO', 'GROUP_PUBLISH'].includes(msg?.kind)) return;
        const op = msg.kind === 'GROUP_FILL' ? fill(msg) :
            msg.kind === 'GROUP_ATTACH_MEDIA' ? attachImages(msg) :
                msg.kind === 'GROUP_ACK_IMAGES' ? acknowledgeImages(msg) :
                    msg.kind === 'GROUP_ATTACH_VIDEO' ? attachVideo(msg) :
                        msg.kind === 'GROUP_ACK_VIDEO' ? acknowledgeVideo(msg) : publish();
        op.then(result => respond({ok: true, ...result}))
            .catch(error => respond({ok: false, error: String(error.message || error)}));
        return true;
    });
})();
