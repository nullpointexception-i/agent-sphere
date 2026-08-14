/**
 * Content-script editor/input helpers (ISOLATED world).
 * Loaded before content.js; attaches to the shared window.__asContent namespace.
 */
window.__asContent = window.__asContent || {};

(function () {
  const AS = window.__asContent;

  // --- Editor framework detection for the type action ---
  AS.detectEditorType = function (el) {
    if (!el) return 'unknown';
    if (el.closest('.DraftEditor-root') || el.classList.contains('public-DraftEditor-content') || el.classList.contains('DraftEditor-editorContainer')) {
      return 'draft-js';
    }
    if (el.closest('.ql-container') || el.classList.contains('ql-editor')) {
      return 'quill';
    }
    if (el.classList.contains('ProseMirror')) {
      return 'prosemirror';
    }
    if (el.closest('[data-slate-editor]') || el.hasAttribute('data-slate-editor')) {
      return 'slate';
    }
    if (el.closest('[data-lexical-editor]') || el.classList.contains('lexical-editor') || document.querySelector('[data-lexical-editor]')) {
      return 'lexical';
    }
    if (el.closest('.fr-box') || el.classList.contains('fr-element')) {
      return 'froala';
    }
    if (el.closest('.mce-content-body') || el.id?.startsWith('tiny') || document.querySelector('.mce-tinymce')) {
      return 'tinymce';
    }
    if (el.hasAttribute('contenteditable') || el.isContentEditable) {
      return 'contenteditable';
    }
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      return 'input';
    }
    return 'unknown';
  };

  // --- Draft.js paste simulation (triggers handlePastedText → React state update) ---
  AS.typeInDraftJS = async function (el, text, isAppend) {
    el.focus();
    const currentText = el.textContent || '';
    const finalText = isAppend ? currentText + text : text;

    const dt = new DataTransfer();
    dt.setData('text/plain', finalText);
    dt.setData('text/html', `<p>${finalText.replace(/\n/g, '</p><p>')}</p>`);
    el.dispatchEvent(new ClipboardEvent('paste', {
      clipboardData: dt, bubbles: true, cancelable: true,
    }));
    dt.clearData();

    await new Promise((r) => setTimeout(r, 50));
    if ((el.textContent || '').includes(finalText.slice(0, 20))) {
      return { success: true, method: 'draft-paste' };
    }

    const sel = window.getSelection();
    sel.collapse(el, el.childNodes.length);
    document.execCommand('insertText', false, finalText);
    await new Promise((r) => setTimeout(r, 50));
    if ((el.textContent || '').includes(finalText.slice(0, 20))) {
      return { success: true, method: 'draft-exec' };
    }

    return { success: false, error: 'Draft.js insert failed after paste and execCommand strategies' };
  };

  // --- Quill API insertion (accesses __quill on parent container) ---
  AS.typeInQuill = function (el, text, isAppend) {
    el.focus();
    const container = el.closest('.ql-container');
    const quill = container?.__quill || container?.parentElement?.__quill;
    if (quill) {
      const index = isAppend ? quill.getLength() : (quill.getSelection()?.index ?? quill.getLength());
      quill.insertText(index, text, 'user');
      return { success: true, method: 'quill-api' };
    }
    if (container) {
      const qlEditor = container.querySelector('.ql-editor');
      if (qlEditor) {
        qlEditor.innerHTML = isAppend ? qlEditor.innerHTML + text : text;
        qlEditor.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
        return { success: true, method: 'quill-innerhtml' };
      }
    }
    return { success: false, error: 'Quill instance not found' };
  };

  // --- ProseMirror / TipTap insertion ---
  AS.typeInProseMirror = function (el, text, isAppend) {
    el.focus();
    if (isAppend) {
      const sel = window.getSelection();
      sel.collapse(el, el.childNodes.length);
    }
    document.execCommand('insertText', false, text);
    el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
    return { success: true, method: 'prosemirror-exec' };
  };

  // --- Lexical editor insertion ---
  AS.typeInLexical = function (el, text, isAppend) {
    el.focus();
    if (isAppend) {
      const sel = window.getSelection();
      sel.collapse(el, el.childNodes.length);
    }
    document.execCommand('insertText', false, text);
    el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
    return { success: true, method: 'lexical-exec' };
  };

  // --- Plain contenteditable insertion ---
  AS.typeInContentEditable = function (el, text, isAppend) {
    el.focus();
    if (isAppend) {
      const sel = window.getSelection();
      sel.collapse(el, el.childNodes.length);
      document.execCommand('insertText', false, text);
    } else {
      el.textContent = text;
    }
    el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
    return { success: true, method: 'contenteditable' };
  };

  // --- React-compatible input/textarea setter (native setter + _valueTracker cleanup) ---
  AS.typeInInput = function (el, text, isAppend) {
    el.focus();
    const currentValue = el.value;
    const newValue = isAppend ? currentValue + text : text;
    const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
    if (!nativeSetter) {
      el.value = newValue;
    } else {
      nativeSetter.call(el, newValue);
      const tracker = el._valueTracker;
      if (tracker) {
        try { tracker.setValue(currentValue); } catch (e) { /* ignore */ }
      }
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return { success: true, method: 'input-native' };
  };

  // --- Dispatch text into an element according to its editor type ---
  AS.typeInElement = function (el, text, isAppend) {
    if (el.tagName === 'DIV' && !el.isContentEditable) {
      const inner = el.querySelector('[contenteditable="true"]');
      if (inner) el = inner;
    }
    const editorType = AS.detectEditorType(el);
    switch (editorType) {
      case 'draft-js':
        return AS.typeInDraftJS(el, text, isAppend);
      case 'quill':
        return AS.typeInQuill(el, text, isAppend);
      case 'prosemirror':
        return AS.typeInProseMirror(el, text, isAppend);
      case 'lexical':
        return AS.typeInLexical(el, text, isAppend);
      case 'contenteditable':
        return AS.typeInContentEditable(el, text, isAppend);
      case 'input':
        return AS.typeInInput(el, text, isAppend);
      default:
        if (el.isContentEditable) return AS.typeInContentEditable(el, text, isAppend);
        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') return AS.typeInInput(el, text, isAppend);
        return { success: false, error: 'Unsupported element: ' + el.tagName };
    }
  };
})();
