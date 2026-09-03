import { useRef, useEffect, useState } from 'react';
import { Check, X, CheckCheck, Bold, Italic, List, ListOrdered, RotateCcw, Link, Unlink, Code } from 'lucide-react';
import ConfirmDialog from './ConfirmDialog';
import './TrackChangesResolver.css';
import './CodeBlock.css';
import './ContentTables.css';

interface Props {
  initialValue: string;
  onChange: (html: string) => void;
  disabled?: boolean;
}

interface ContextMenu {
  x: number;
  y: number;
  el: Element;
}

type EditorMode = 'track' | 'edit';

const FONT_COLORS = [
  '#000000', '#374151', '#6b7280',
  '#dc2626', '#ea580c', '#d97706',
  '#16a34a', '#2563eb', '#7c3aed',
  '#db2777', '#ffffff',
];

export default function TrackChangesResolver({ initialValue, onChange, disabled = false }: Props) {
  const bodyRef = useRef<HTMLDivElement>(null);
  const colorWrapRef = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const [mode, setMode] = useState<EditorMode>('track');
  const [selectedChange, setSelectedChange] = useState<Element | null>(null);
  const [contextMenu, setContextMenu] = useState<ContextMenu | null>(null);
  const [showColorPalette, setShowColorPalette] = useState(false);
  const [showRevertConfirm, setShowRevertConfirm] = useState(false);
  const [showLinkInput, setShowLinkInput] = useState(false);
  const [linkUrl, setLinkUrl] = useState('');
  const savedRangeRef = useRef<Range | null>(null);

  useEffect(() => {
    if (bodyRef.current) bodyRef.current.innerHTML = initialValue;
  }, []); // only on mount

  // Highlight/unhighlight selected change
  useEffect(() => {
    bodyRef.current?.querySelectorAll('.tcr-selected').forEach(el =>
      el.classList.remove('tcr-selected')
    );
    if (selectedChange && bodyRef.current?.contains(selectedChange)) {
      selectedChange.classList.add('tcr-selected');
    }
  }, [selectedChange]);

  // Close context menu on outside click or Escape
  useEffect(() => {
    if (!contextMenu) return;
    const close = () => setContextMenu(null);
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', onKey);
    };
  }, [contextMenu]);

  // Close color palette when clicking outside the color wrap
  useEffect(() => {
    if (!showColorPalette) return;
    const close = (e: MouseEvent) => {
      if (!colorWrapRef.current?.contains(e.target as Node)) {
        setShowColorPalette(false);
      }
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [showColorPalette]);

  function emit() {
    if (bodyRef.current) {
      // Strip zero-width spaces used to break cursor out of inline elements
      onChangeRef.current(bodyRef.current.innerHTML.replace(/\u200B/g, ''));
    }
  }

  function unwrap(el: Element) {
    const parent = el.parentNode;
    if (!parent) return;
    while (el.firstChild) parent.insertBefore(el.firstChild, el);
    parent.removeChild(el);
  }

  function acceptAll() {
    if (!bodyRef.current) return;
    Array.from(bodyRef.current.querySelectorAll('ins[data-author-id]')).forEach(el => unwrap(el));
    Array.from(bodyRef.current.querySelectorAll('del[data-author-id]')).forEach(el => el.remove());
    emit();
    setSelectedChange(null);
  }

  function rejectAll() {
    if (!bodyRef.current) return;
    Array.from(bodyRef.current.querySelectorAll('ins[data-author-id]')).forEach(el => el.remove());
    Array.from(bodyRef.current.querySelectorAll('del[data-author-id]')).forEach(el => unwrap(el));
    emit();
    setSelectedChange(null);
  }

  function acceptOne(el: Element) {
    if (el.tagName === 'INS') unwrap(el);
    else el.remove();
    emit();
    setSelectedChange(null);
    setContextMenu(null);
  }

  function rejectOne(el: Element) {
    if (el.tagName === 'DEL') unwrap(el);
    else el.remove();
    emit();
    setSelectedChange(null);
    setContextMenu(null);
  }

  function stopTracking() {
    acceptAll();
    setMode('edit');
    setContextMenu(null);
  }

  function handleRevertConfirm() {
    if (bodyRef.current) bodyRef.current.innerHTML = initialValue;
    emit();
    setMode('track');
    setSelectedChange(null);
    setContextMenu(null);
    setShowRevertConfirm(false);
  }

  // ── Rich text edit commands ───────────────────────────────────────────────

  function execFormat(e: React.MouseEvent, cmd: string, value?: string) {
    e.preventDefault();
    bodyRef.current?.focus();
    document.execCommand(cmd, false, value);
    emit();
  }

  function saveSelection() {
    const sel = window.getSelection();
    if (sel && sel.rangeCount > 0) {
      savedRangeRef.current = sel.getRangeAt(0).cloneRange();
    }
  }

  function restoreSelection() {
    const sel = window.getSelection();
    if (sel && savedRangeRef.current) {
      sel.removeAllRanges();
      sel.addRange(savedRangeRef.current);
    }
  }

  function openLinkInput(e: React.MouseEvent) {
    e.preventDefault();
    saveSelection();
    // Pre-fill with existing href if cursor is inside a link
    const sel = window.getSelection();
    let existing = '';
    if (sel && sel.anchorNode) {
      let node: Node | null = sel.anchorNode;
      while (node && node !== bodyRef.current) {
        if (node.nodeType === Node.ELEMENT_NODE && (node as HTMLElement).tagName === 'A') {
          existing = (node as HTMLAnchorElement).href;
          break;
        }
        node = node.parentNode;
      }
    }
    setLinkUrl(existing);
    setShowLinkInput(true);
  }

  function applyLink() {
    const url = linkUrl.trim();
    if (!url) return;
    const href = url.startsWith('http') ? url : `https://${url}`;
    bodyRef.current?.focus();
    restoreSelection();
    document.execCommand('createLink', false, href);
    // Make links open in new tab
    bodyRef.current?.querySelectorAll('a').forEach(a => {
      a.setAttribute('target', '_blank');
      a.setAttribute('rel', 'noopener noreferrer');
    });
    emit();
    setShowLinkInput(false);
    setLinkUrl('');
  }

  function applyInlineCode(e: React.MouseEvent) {
    e.preventDefault();
    bodyRef.current?.focus();
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return;
    const range = sel.getRangeAt(0);
    if (range.collapsed) return;

    // Check if the selection is already inside an inline <code> (toggle off)
    let codeEl: HTMLElement | null = null;
    let node: Node | null = range.commonAncestorContainer;
    while (node && node !== bodyRef.current) {
      if (node.nodeType === Node.ELEMENT_NODE) {
        const tag = (node as HTMLElement).tagName;
        if (tag === 'PRE') break;
        if (tag === 'CODE' && (node as HTMLElement).parentElement?.tagName !== 'PRE') {
          codeEl = node as HTMLElement;
          break;
        }
      }
      node = node.parentNode;
    }

    if (codeEl) {
      // Toggle off: split the <code> element around the selection.
      // Build three pieces using cloneContents so the DOM isn't touched until
      // we're ready for the atomic replaceChild.
      const beforeRange = document.createRange();
      beforeRange.setStart(codeEl, 0);
      beforeRange.setEnd(range.startContainer, range.startOffset);

      const afterRange = document.createRange();
      afterRange.setStart(range.endContainer, range.endOffset);
      afterRange.setEnd(codeEl, codeEl.childNodes.length);

      const replacement = document.createDocumentFragment();

      if (!beforeRange.collapsed) {
        const beforeCode = document.createElement('code');
        beforeCode.appendChild(beforeRange.cloneContents());
        replacement.appendChild(beforeCode);
      }

      replacement.appendChild(range.cloneContents()); // selected text, unwrapped

      if (!afterRange.collapsed) {
        const afterCode = document.createElement('code');
        afterCode.appendChild(afterRange.cloneContents());
        replacement.appendChild(afterCode);
      }

      codeEl.parentNode!.replaceChild(replacement, codeEl);
      emit();
      return;
    }

    // Toggle on: wrap selection in <code>
    const code = document.createElement('code');
    try {
      range.surroundContents(code);
    } catch {
      // selection spans multiple elements — extract, wrap, re-insert
      const fragment = range.extractContents();
      code.appendChild(fragment);
      range.insertNode(code);
    }
    emit();
  }

  function applyBlock(e: React.MouseEvent, tag: string) {
    e.preventDefault();
    bodyRef.current?.focus();
    document.execCommand('formatBlock', false, tag);
    emit();
  }

  function applyColor(e: React.MouseEvent, color: string) {
    e.preventDefault();
    bodyRef.current?.focus();
    document.execCommand('foreColor', false, color);
    emit();
    setShowColorPalette(false);
  }

  // ── DOM helpers ───────────────────────────────────────────────────────────

  function findTrackedChange(target: Node): Element | null {
    let node: Node | null = target;
    while (node && node !== bodyRef.current) {
      if (node.nodeType === Node.ELEMENT_NODE) {
        const el = node as Element;
        if ((el.tagName === 'INS' || el.tagName === 'DEL') && el.hasAttribute('data-author-id')) {
          return el;
        }
      }
      node = node.parentNode;
    }
    return null;
  }

  function handleClick(e: React.MouseEvent) {
    // Always open anchor links
    let node: Node | null = e.target as Node;
    while (node && node !== bodyRef.current) {
      if (node.nodeType === Node.ELEMENT_NODE) {
        const el = node as HTMLElement;
        if (el.tagName === 'A') {
          const href = el.getAttribute('href');
          if (href) { e.preventDefault(); window.open(href, '_blank', 'noopener,noreferrer'); }
          return;
        }
      }
      node = node.parentNode;
    }

    // In edit mode: fix browser placing cursor inside <code> when clicking
    // adjacent text to the right of the element.
    if (mode === 'edit') {
      const sel = window.getSelection();
      if (sel && sel.rangeCount > 0) {
        let n: Node | null = sel.anchorNode;
        let codeEl: Element | null = null;
        while (n && n !== bodyRef.current) {
          if (n.nodeType === Node.ELEMENT_NODE) {
            const tag = (n as Element).tagName;
            if (tag === 'PRE') break;
            if (tag === 'CODE' && (n as Element).parentElement?.tagName !== 'PRE') {
              codeEl = n as Element; break;
            }
          }
          n = n.parentNode;
        }
        if (codeEl && e.clientX > (codeEl as HTMLElement).getBoundingClientRect().right) {
          const afterRange = document.createRange();
          afterRange.setStartAfter(codeEl);
          afterRange.collapse(true);
          sel.removeAllRanges();
          sel.addRange(afterRange);
        }
      }
    }

    if (disabled || mode !== 'track') return;

    const tracked = findTrackedChange(e.target as Node);
    setSelectedChange(tracked ? (prev => prev === tracked ? null : tracked) : null);
  }

  function handleContextMenu(e: React.MouseEvent) {
    if (disabled || mode !== 'track') return;
    const tracked = findTrackedChange(e.target as Node);
    if (!tracked) return;
    e.preventDefault();
    setSelectedChange(tracked);
    setContextMenu({
      x: Math.min(e.clientX, window.innerWidth - 180),
      y: Math.min(e.clientY, window.innerHeight - 80),
      el: tracked,
    });
  }

  // ── Markdown shortcut helpers ─────────────────────────────────────────────

  /** Nearest block-level ancestor of startNode within bodyRef (bodyRef itself as fallback). */
  function blockAncestor(startNode: Node | null): Element | null {
    const blockTags = new Set(['P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'DIV', 'LI', 'BLOCKQUOTE']);
    let n: Node | null = startNode;
    while (n && n !== bodyRef.current) {
      if (n.nodeType === Node.ELEMENT_NODE && blockTags.has((n as Element).tagName)) return n as Element;
      n = n.parentNode;
    }
    return bodyRef.current; // fallback: cursor directly in editor div (e.g. Chrome first line)
  }

  /** Map a character index in a block's concatenated text to the owning Text node + local offset. */
  function charPosInBlock(blockEl: Element, charIdx: number): { node: Text; offset: number } | null {
    const walker = document.createTreeWalker(blockEl, NodeFilter.SHOW_TEXT);
    let count = 0;
    while (walker.nextNode()) {
      const t = walker.currentNode as Text;
      if (count + t.length > charIdx) return { node: t, offset: charIdx - count };
      count += t.length;
    }
    return null;
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (mode !== 'edit') return;
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return;

    const inCodeOrPre = (): boolean => {
      let n: Node | null = sel.anchorNode;
      while (n && n !== bodyRef.current) {
        if (n.nodeType === Node.ELEMENT_NODE) {
          const tag = (n as Element).tagName;
          if (tag === 'CODE' || tag === 'PRE') return true;
        }
        n = n.parentNode;
      }
      return false;
    };

    // ── Enter: heading shortcut, <pre> exit, inline <code> exit ──────────
    if (e.key === 'Enter') {
      const range = sel.getRangeAt(0);

      // Heading shortcut: line starting with "# "/"## "/etc. → heading + new paragraph
      if (range.collapsed && !inCodeOrPre()) {
        const blk = blockAncestor(sel.anchorNode);
        if (blk) {
          const blkText = blk.textContent || '';
          const headingMatch = /^(#{1,4}) /.exec(blkText);
          if (headingMatch) {
            e.preventDefault();
            const level = headingMatch[1].length;
            const newTag = ['h1', 'h2', 'h3', 'h4'][level - 1];
            const prefixLen = headingMatch[0].length; // e.g. 3 for "## "
            // Delete the "## " prefix from the first text node
            const firstPos = charPosInBlock(blk, 0);
            if (firstPos && firstPos.node.length >= firstPos.offset + prefixLen) {
              firstPos.node.deleteData(firstPos.offset, prefixLen);
            }
            document.execCommand('formatBlock', false, newTag);
            // Insert new paragraph after the heading
            const freshSel = window.getSelection();
            if (freshSel && freshSel.rangeCount > 0) {
              const headingEl = blockAncestor(freshSel.anchorNode);
              if (headingEl && headingEl !== bodyRef.current) {
                const p = document.createElement('p');
                p.innerHTML = '<br>';
                headingEl.parentNode?.insertBefore(p, headingEl.nextSibling);
                const newRange = document.createRange();
                newRange.setStart(p, 0);
                newRange.collapse(true);
                freshSel.removeAllRanges();
                freshSel.addRange(newRange);
              }
            }
            emit();
            return;
          }
        }
      }

      // <pre> and inline <code> handling
      let node: Node | null = sel.anchorNode;
      let preEl: Element | null = null;
      let inlineCodeEl: Element | null = null;
      while (node && node !== bodyRef.current) {
        if (node.nodeType === Node.ELEMENT_NODE) {
          const tag = (node as Element).tagName;
          if (tag === 'PRE') { preEl = node as Element; break; }
          if (tag === 'CODE' && (node as Element).parentElement?.tagName !== 'PRE') {
            inlineCodeEl = node as Element; break;
          }
        }
        node = node.parentNode;
      }
      if (preEl) {
        e.preventDefault();
        if (e.shiftKey) {
          range.deleteContents();
          const nl = document.createTextNode('\n');
          range.insertNode(nl);
          range.setStartAfter(nl);
          range.collapse(true);
          sel.removeAllRanges();
          sel.addRange(range);
        } else {
          const p = document.createElement('p');
          p.innerHTML = '<br>';
          preEl.parentNode?.insertBefore(p, preEl.nextSibling);
          const newRange = document.createRange();
          newRange.setStart(p, 0);
          newRange.collapse(true);
          sel.removeAllRanges();
          sel.addRange(newRange);
        }
        emit();
      } else if (inlineCodeEl) {
        const afterRange = document.createRange();
        afterRange.setStartAfter(inlineCodeEl);
        afterRange.collapse(true);
        sel.removeAllRanges();
        sel.addRange(afterRange);
      }
      return;
    }

    // ── Bold/Italic: **text** → <strong>, *text* → <em> (also __ and _) ──
    if (e.key === '*' || e.key === '_') {
      const range = sel.getRangeAt(0);
      if (!range.collapsed || inCodeOrPre()) return;
      // Cursor must be in a text node — that's always the case during typing
      if (range.startContainer.nodeType !== Node.TEXT_NODE) return;
      const tn = range.startContainer as Text;
      // textBefore: text in THIS text node up to the cursor (indices map 1-to-1 to tn offsets)
      const textBefore = (tn.nodeValue ?? '').slice(0, range.startOffset);
      const ch = e.key;

      // Helper: place cursor just after `el`, past a zero-width space that breaks Chrome's
      // sticky-bold/italic/code caret association with the preceding inline element.
      const placeAfterEl = (el: Element) => {
        const zwsp = document.createTextNode('\u200B');
        el.parentNode?.insertBefore(zwsp, el.nextSibling);
        const r = document.createRange();
        r.setStart(zwsp, 1); // cursor AFTER the zero-width space, clearly outside el
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
      };

      // Bold: textBefore ends with `ch` → check for opening `chch` pair
      if (textBefore.endsWith(ch)) {
        const withoutLast = textBefore.slice(0, -1);
        const doubleIdx = withoutLast.lastIndexOf(ch + ch);
        if (doubleIdx !== -1) {
          const charBefore = withoutLast[doubleIdx - 1];
          const charAfter = withoutLast[doubleIdx + 2];
          if (charBefore !== ch && charAfter !== ch) {
            const content = withoutLast.slice(doubleIdx + 2);
            if (content.length > 0 && !content.startsWith(' ') && !content.endsWith(' ')) {
              e.preventDefault();
              const boldRange = document.createRange();
              boldRange.setStart(tn, doubleIdx);
              boldRange.setEnd(tn, range.startOffset);
              boldRange.deleteContents();
              const strong = document.createElement('strong');
              strong.textContent = content;
              boldRange.insertNode(strong);
              placeAfterEl(strong);
              emit();
              return;
            }
          }
        }
      }

      // Italic: find the last unpaired single `ch` in textBefore
      let lastSingleIdx = -1;
      for (let i = textBefore.length - 1; i >= 0; i--) {
        if (textBefore[i] === ch) {
          const prevIsCh = i > 0 && textBefore[i - 1] === ch;
          const nextIsCh = i < textBefore.length - 1 && textBefore[i + 1] === ch;
          if (!prevIsCh && !nextIsCh) {
            lastSingleIdx = i;
            break;
          }
          if (prevIsCh) i--; // skip both chars of the `chch` pair
        }
      }
      if (lastSingleIdx !== -1) {
        const content = textBefore.slice(lastSingleIdx + 1);
        if (content.length > 0 && !content.startsWith(' ') && !content.endsWith(' ')) {
          e.preventDefault();
          const italicRange = document.createRange();
          italicRange.setStart(tn, lastSingleIdx);
          italicRange.setEnd(tn, range.startOffset);
          italicRange.deleteContents();
          const em = document.createElement('em');
          em.textContent = content;
          italicRange.insertNode(em);
          placeAfterEl(em);
          emit();
          return;
        }
      }
    }

    // ── Backtick: ``` → code block; `text` → inline code ──────────────────
    if (e.key === '`') {
      const range = sel.getRangeAt(0);
      if (!range.collapsed || inCodeOrPre()) return;
      if (range.startContainer.nodeType !== Node.TEXT_NODE) return;
      const tn = range.startContainer as Text;
      const textBefore = (tn.nodeValue ?? '').slice(0, range.startOffset);

      // Three backticks at the start of a line → fenced code block
      if (textBefore === '``') {
        e.preventDefault();
        const delRange = document.createRange();
        delRange.setStart(tn, 0);
        delRange.setEnd(tn, 2);
        delRange.deleteContents();
        delRange.collapse(true);
        sel.removeAllRanges();
        sel.addRange(delRange);
        document.execCommand('formatBlock', false, 'pre');
        emit();
        return;
      }

      // Closing backtick: find the most-recent opening backtick in the line
      const openIdx = textBefore.lastIndexOf('`');
      if (openIdx !== -1) {
        const codeText = textBefore.slice(openIdx + 1);
        if (codeText.length > 0) {
          e.preventDefault();
          const codeRange = document.createRange();
          codeRange.setStart(tn, openIdx);
          codeRange.setEnd(tn, range.startOffset);
          codeRange.deleteContents();
          const code = document.createElement('code');
          code.textContent = codeText;
          codeRange.insertNode(code);
          // Place cursor after <code> using a zero-width space to break Chrome's sticky-code
          // caret association (same technique as bold/italic above)
          const zwsp = document.createTextNode('\u200B');
          code.parentNode?.insertBefore(zwsp, code.nextSibling);
          const after = document.createRange();
          after.setStart(zwsp, 1);
          after.collapse(true);
          sel.removeAllRanges();
          sel.addRange(after);
          emit();
          return;
        }
      }
      return; // no pattern matched — let browser insert the backtick
    }

    // ── Space: inline code triple-space exit ───────────────────────────────
    if (e.key === ' ') {
      const range = sel.getRangeAt(0);

      // Triple-space inside inline <code> → exit the element
      let node: Node | null = sel.anchorNode;
      let codeEl: Element | null = null;
      while (node && node !== bodyRef.current) {
        if (node.nodeType === Node.ELEMENT_NODE) {
          const tag = (node as Element).tagName;
          if (tag === 'PRE') break;
          if (tag === 'CODE' && (node as Element).parentElement?.tagName !== 'PRE') {
            codeEl = node as Element; break;
          }
        }
        node = node.parentNode;
      }
      if (codeEl) {
        const preRange = document.createRange();
        preRange.setStart(codeEl, 0);
        preRange.setEnd(range.startContainer, range.startOffset);
        if (preRange.toString().endsWith('  ')) {
          e.preventDefault();
          const textNode = range.startContainer;
          const offset = range.startOffset;
          if (textNode.nodeType === Node.TEXT_NODE && offset >= 2) {
            (textNode as Text).deleteData(offset - 2, 2);
          }
          const exitZwsp = document.createTextNode('\u200B');
          codeEl.parentNode?.insertBefore(exitZwsp, codeEl.nextSibling);
          const afterRange = document.createRange();
          afterRange.setStart(exitZwsp, 1);
          afterRange.collapse(true);
          sel.removeAllRanges();
          sel.addRange(afterRange);
          emit();
        }
      }
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="tcr-wrap">
      <div className="tcr-toolbar">
        {mode === 'track' ? (
          <>
            <button className="tcr-btn" onMouseDown={e => e.preventDefault()} onClick={acceptAll} disabled={disabled} title="Accept all changes">
              <CheckCheck size={13} /> Accept All
            </button>
            <button className="tcr-btn" onMouseDown={e => e.preventDefault()} onClick={rejectAll} disabled={disabled} title="Reject all changes">
              <X size={13} /> Reject All
            </button>

            {selectedChange && !disabled && (
              <>
                <div className="tcr-sep" />
                <button className="tcr-btn tcr-btn--accept-one" onMouseDown={e => e.preventDefault()} onClick={() => acceptOne(selectedChange)} title="Accept this change">
                  <Check size={13} /> Accept
                </button>
                <button className="tcr-btn tcr-btn--reject-one" onMouseDown={e => e.preventDefault()} onClick={() => rejectOne(selectedChange)} title="Reject this change">
                  <X size={13} /> Reject
                </button>
              </>
            )}

            {!disabled && (
              <>
                <div className="tcr-sep" />
                <button className="tcr-btn tcr-btn--stop" onMouseDown={e => e.preventDefault()} onClick={stopTracking} title="Accept all changes and switch to free editing">
                  Stop Tracking
                </button>
              </>
            )}
          </>
        ) : (
          <>
            {/* Bold / Italic */}
            <button className="tcr-btn" onMouseDown={e => execFormat(e, 'bold')} title="Bold">
              <Bold size={13} />
            </button>
            <button className="tcr-btn" onMouseDown={e => execFormat(e, 'italic')} title="Italic">
              <Italic size={13} />
            </button>

            <div className="tcr-sep" />

            {/* Font color */}
            <div className="tcr-color-wrap" ref={colorWrapRef}>
              <button
                className="tcr-btn tcr-btn--color"
                onMouseDown={e => e.preventDefault()}
                onClick={() => setShowColorPalette(v => !v)}
                title="Font color"
              >
                <span className="tcr-color-a">A</span>
              </button>
              {showColorPalette && (
                <div className="tcr-color-palette">
                  {FONT_COLORS.map(color => (
                    <button
                      key={color}
                      className="tcr-color-swatch"
                      style={{ background: color }}
                      onMouseDown={e => applyColor(e, color)}
                      title={color}
                    />
                  ))}
                </div>
              )}
            </div>

            <div className="tcr-sep" />

            {/* Block format */}
            <button className="tcr-btn" onMouseDown={e => applyBlock(e, 'p')} title="Normal paragraph">¶</button>
            <button className="tcr-btn tcr-btn--heading" onMouseDown={e => applyBlock(e, 'h1')} title="Heading 1">H1</button>
            <button className="tcr-btn tcr-btn--heading" onMouseDown={e => applyBlock(e, 'h2')} title="Heading 2">H2</button>
            <button className="tcr-btn tcr-btn--heading" onMouseDown={e => applyBlock(e, 'h3')} title="Heading 3">H3</button>

            <div className="tcr-sep" />

            {/* Lists */}
            <button className="tcr-btn" onMouseDown={e => execFormat(e, 'insertUnorderedList')} title="Bulleted list">
              <List size={13} />
            </button>
            <button className="tcr-btn" onMouseDown={e => execFormat(e, 'insertOrderedList')} title="Numbered list">
              <ListOrdered size={13} />
            </button>

            <div className="tcr-sep" />

            {/* Links */}
            <button className="tcr-btn" onMouseDown={openLinkInput} title="Insert link">
              <Link size={13} />
            </button>
            <button className="tcr-btn" onMouseDown={e => execFormat(e, 'unlink')} title="Remove link">
              <Unlink size={13} />
            </button>
            {showLinkInput && (
              <div className="tcr-link-input-wrap" onMouseDown={e => e.stopPropagation()}>
                <input
                  className="tcr-link-input"
                  type="url"
                  placeholder="https://..."
                  value={linkUrl}
                  onChange={e => setLinkUrl(e.target.value)}
                  onKeyDown={e => {
                    if (e.key === 'Enter') { e.preventDefault(); applyLink(); }
                    if (e.key === 'Escape') { setShowLinkInput(false); setLinkUrl(''); }
                  }}
                  autoFocus
                />
                <button className="tcr-btn" onMouseDown={e => { e.preventDefault(); applyLink(); }} title="Apply link">
                  <Check size={13} />
                </button>
                <button className="tcr-btn" onMouseDown={e => { e.preventDefault(); setShowLinkInput(false); setLinkUrl(''); }} title="Cancel">
                  <X size={13} />
                </button>
              </div>
            )}

            <div className="tcr-sep" />

            {/* Code */}
            <button className="tcr-btn" onMouseDown={e => applyInlineCode(e)} title="Inline code">
              <Code size={13} />
            </button>
            <button className="tcr-btn tcr-btn--heading" onMouseDown={e => applyBlock(e, 'pre')} title="Code block">
              {'</>'}
            </button>

            <div className="tcr-spacer" />

            {/* Revert */}
            <button className="tcr-btn tcr-btn--revert" onMouseDown={e => e.preventDefault()} onClick={() => setShowRevertConfirm(true)} title="Revert to original track changes">
              <RotateCcw size={13} /> Revert to Track Changes
            </button>
          </>
        )}
      </div>

      <div
        ref={bodyRef}
        className="tcr-body"
        contentEditable={mode === 'edit'}
        suppressContentEditableWarning
        onClick={handleClick}
        onContextMenu={handleContextMenu}
        onKeyDown={mode === 'edit' ? handleKeyDown : undefined}
        onInput={mode === 'edit' ? emit : undefined}
      />

      {/* Right-click context menu */}
      {contextMenu && (
        <div
          className="tcr-context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onMouseDown={e => e.stopPropagation()}
        >
          <button className="tcr-context-item tcr-context-item--accept" onClick={() => acceptOne(contextMenu.el)}>
            <Check size={13} /> Accept Change
          </button>
          <button className="tcr-context-item tcr-context-item--reject" onClick={() => rejectOne(contextMenu.el)}>
            <X size={13} /> Reject Change
          </button>
        </div>
      )}

      {/* Revert confirmation */}
      <ConfirmDialog
        isOpen={showRevertConfirm}
        onClose={() => setShowRevertConfirm(false)}
        onConfirm={handleRevertConfirm}
        title="Revert to Track Changes"
        message="This will discard all your manual edits and restore the original tracked changes. This cannot be undone."
        confirmText="Revert"
        variant="warning"
      />
    </div>
  );
}
