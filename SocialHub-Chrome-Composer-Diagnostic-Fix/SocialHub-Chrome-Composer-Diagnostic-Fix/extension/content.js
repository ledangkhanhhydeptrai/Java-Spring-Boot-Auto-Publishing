'use strict';
// Visible UI automation for ONE administrator-owned test group. No stealth,
// CAPTCHA/2FA bypass, session export, background posting or blind retries.
(() => {
  let prepared = null;
  let inFlight = false;
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

  function composerScope() {
    // Prefer actual accessible dialogs containing the create-post heading.
    // A missing heading is permitted ONLY if the dialog contains both the
    // composer placeholder and a distinct Post button.
    const dialogs = all(document, '[role="dialog"],[aria-modal="true"]')
      .filter(isVisible);
    const approvedDialogs = dialogs.filter(el => containsComposerHeading(el) ||
      (hasPostButton(el) && Boolean(visibleWhere(el,
        'span,div,p,[data-placeholder],[aria-placeholder]', exactPrompt))));
    if (approvedDialogs.length === 1) return approvedDialogs[0];
    if (approvedDialogs.length > 1) return null;

    // Modal markup can be role-less. Climb from either the exact heading OR
    // the exact placeholder and select the tightest modal containing Post.
    // Never choose an editor from unrelated comments or the group feed.
    const anchors = [
      ...headingNodes(document),
      ...all(document, 'span,div,p,[data-placeholder],[aria-placeholder]')
        .filter(exactPrompt)
    ];
    const candidates = new Set();
    for (const anchor of anchors) {
      let parent = anchor.parentElement;
      for (let depth = 0; parent && depth < 12; depth++, parent = parent.parentElement) {
        if (!sensiblePanel(parent)) continue;
        if (hasPostButton(parent)) candidates.add(parent);
      }
    }
    if (!candidates.size) return null;
    const sorted = [...candidates].sort((a,b) => {
      const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
      return ar.width * ar.height - br.width * br.height;
    });
    if (sorted.length > 1 &&
        Math.abs(sorted[0].getBoundingClientRect().width * sorted[0].getBoundingClientRect().height -
          sorted[1].getBoundingClientRect().width * sorted[1].getBoundingClientRect().height) < 100)
      return null;
    return sorted[0];
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
    throw new Error('COMPOSER-DIAG-v2: không tìm được duy nhất ô soạn thảo. ' +
      'scope=' + Boolean(scope) + ', fieldsInScope=' + count +
      ', visibleFields=' + visibleEditors.length + ', dialogs=' + dialogs.length +
      ', createPostTitles=' + titles.length + '. KHÔNG đăng');
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

  function setEditor(editor, content) {
    const previous = txt(editor);
    if (previous && previous !== content) {
      throw new Error('Ô soạn thảo đã có nội dung khác (ví dụ Hello). Đóng/xóa bản nháp đó rồi thử nạp bài');
    }
    if (previous === content) return;
    editor.focus();
    const selection = window.getSelection();
    const range = document.createRange();
    range.selectNodeContents(editor);
    selection.removeAllRanges();
    selection.addRange(range);
    if (!document.execCommand('insertText', false, content))
      throw new Error('Facebook từ chối chèn nội dung vào ô soạn thảo');
    editor.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: content}));
    if (txt(editor) !== content.trim())
      throw new Error('Nội dung hiển thị không trùng nội dung Task; KHÔNG đăng');
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
      try { u = new URL(anchor.href, location.href); } catch { continue; }
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
    const {id, groupId, content, marker, digest} = request.preview || {};
    if (!taskRegex.test(id || '') || !/^\d+$/.test(groupId || '') || !digest ||
        marker !== '[SocialHub Group Pilot ' + id.slice(0, 8) + ']')
      throw new Error('Preview không hợp lệ');
    assertGroup(groupId);
    const expected = content.trim() + '\n\n' + marker;
    const {editor} = await openComposer();
    setEditor(editor, expected);
    prepared = {id, groupId, expected, digest, marker};
    return {message: 'Đã nạp bản nháp. Kiểm tra nội dung và nhóm trước khi bấm Đăng 1 bài'};
  }

  async function publish() {
    if (inFlight) throw new Error('Đang xử lý; không bấm lại');
    if (!prepared) throw new Error('Chưa nạp bài trong tab này');
    const current = prepared;
    assertGroup(current.groupId);
    const {scope, editor} = editorForComposer();
    if (!editor || txt(editor) !== current.expected)
      throw new Error('Nội dung đã thay đổi. Không đăng để tránh nhầm bài');
    const button = publishButton(scope);
    inFlight = true;
    let claimed = false;
    let clicked = false;
    try {
      // Atomic READY -> PROCESSING is performed ONLY after the user explicitly
      // presses the extension's publish button, never on fill/preview.
      const task = await callBridge('/claim', {digest: current.digest});
      claimed = true;
      if (task.id !== current.id || task.groupId !== current.groupId ||
          (task.content.trim() + '\n\n' + task.marker) !== current.expected)
        throw new Error('Claim payload differs from preview; DO NOT publish');
      // One click only, no catch-based retry even on timeout or UI change.
      clicked = true;
      button.click();
      const permalink = await verify(current.marker, current.groupId);
      const result = permalink ?
        {status: 'PUBLISHED', publishedUrl: permalink,
         note: 'Matched one Task marker and actual permalink in target Group feed'} :
        {status: 'NEEDS_REVIEW', publishedUrl: null,
         note: 'Facebook Publish was clicked; could not verify matching permalink. DO NOT RETRY'};
      await callBridge('/result', result);
      return {message: permalink ? 'Đã xác minh bài đăng: ' + permalink :
        'Đã bấm Đăng nhưng chưa xác minh được permalink; Task NEEDS_REVIEW, KHÔNG đăng lại'};
    } catch (error) {
      if (claimed) {
        try {
          await callBridge('/result', {status: 'NEEDS_REVIEW', publishedUrl: null,
            note: (clicked ? 'After' : 'Before') + ' Facebook Publish click: ' + String(error.message).slice(0, 250) +
                  '. Review group manually, DO NOT AUTO RETRY'});
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
    if (msg?.kind !== 'GROUP_FILL' && msg?.kind !== 'GROUP_PUBLISH') return;
    const op = msg.kind === 'GROUP_FILL' ? fill(msg) : publish();
    op.then(result => respond({ok: true, ...result}))
      .catch(error => respond({ok: false, error: String(error.message || error)}));
    return true;
  });
})();
