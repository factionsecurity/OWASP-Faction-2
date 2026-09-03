import { useRef, useEffect } from 'react';
import { Bold, Italic, Lock } from 'lucide-react';
import './TrackChangesEditor.css';
import './CodeBlock.css';
import './ContentTables.css';

/** Where the caret sat, as child indices from the editor root down to the node. */
interface CaretPosition {
  path: number[];
  offset: number;
}

interface Snapshot {
  html: string;
  caret: CaretPosition | null;
}

/** A pause this long ends an undo step, so a burst of typing undoes as one. */
const TYPING_COALESCE_MS = 400;
const MAX_HISTORY = 100;

interface Props {
  defaultValue: string;
  onChange: (html: string) => void;
  userId: string;
  userName: string;
  disabled?: boolean;
  /**
   * Another reviewer is editing this region — their display name. Distinct from `disabled`:
   * the editor still looks and reads like an editor (so incoming edits stay legible) but input
   * is blocked and the holder is named.
   */
  lockedBy?: string;
}

/** Names whoever holds this editor, without hiding the content behind it. */
export function LockOverlay({ holder }: { holder: string }) {
  return (
    <div className="tce-lock-overlay">
      <span className="tce-lock-pill">
        <Lock size={11} />
        {holder} is editing
      </span>
    </div>
  );
}

export default function TrackChangesEditor({
  defaultValue,
  onChange,
  userId,
  userName,
  disabled = false,
  lockedBy,
}: Props) {
  const editorRef = useRef<HTMLDivElement>(null);
  const isReadOnly = disabled || !!lockedBy;

  const historyRef = useRef<{ stack: Snapshot[]; index: number }>({ stack: [], index: -1 });
  const checkpointTimer = useRef<ReturnType<typeof setTimeout>>();

  // Keep props fresh in the event handler closure without re-registering
  const propsRef = useRef({ onChange, userId, userName, disabled: isReadOnly });
  useEffect(() => {
    propsRef.current = { onChange, userId, userName, disabled: isReadOnly };
  });

  // Load initial HTML once on mount
  useEffect(() => {
    const el = editorRef.current;
    if (el) el.innerHTML = defaultValue;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * The editors are uncontrolled — content is written to the DOM, not re-rendered from props — so
   * a remote edit arriving as a new `defaultValue` has to be pushed in by hand. Focus is the guard:
   * the one editor that must never have content yanked out from under it is the one being typed in.
   */
  useEffect(() => {
    const el = editorRef.current;
    if (!el || document.activeElement === el) return;
    if (el.innerHTML !== defaultValue) {
      el.innerHTML = defaultValue;
      }
  }, [defaultValue]);

  // Register beforeinput handler once
  useEffect(() => {
    const el = editorRef.current;
    if (!el) return;

    // An <ins> emptied by deleting everything typed into it, or a <del> whose content was
    // taken back, is a marker for nothing. It is dropped on the way out rather than out of
    // the live DOM, where it may hold the caret the author is still typing at.
    const serialize = (): string => {
      const clone = el.cloneNode(true) as HTMLElement;
      clone.querySelectorAll('ins:empty, del:empty').forEach(marker => marker.remove());
      return clone.innerHTML;
    };

    const emit = () => {
      propsRef.current.onChange(serialize());
      scheduleCheckpoint();
    };

    // ── Undo / redo ───────────────────────────────────────────────────────────
    // Every tracked edit is made by hand on the DOM behind preventDefault, which leaves
    // the browser's own undo stack with nothing in it — Ctrl+Z did nothing at all. This
    // is that stack, kept here: the raw markup plus where the caret was, so an undo puts
    // the author back where they were rather than at the end of the document.
    const caretSnapshot = (): CaretPosition | null => {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0) return null;
      const range = selection.getRangeAt(0);
      if (!el.contains(range.startContainer)) return null;
      const path: number[] = [];
      let node: Node = range.startContainer;
      while (node !== el && node.parentNode) {
        path.unshift(Array.prototype.indexOf.call(node.parentNode.childNodes, node));
        node = node.parentNode;
      }
      return { path, offset: range.startOffset };
    };

    const restoreCaret = (caret: CaretPosition | null) => {
      if (!caret) return;
      let node: Node = el;
      for (const index of caret.path) {
        const child = node.childNodes[index];
        if (!child) break;
        node = child;
      }
      const limit = node.nodeType === Node.TEXT_NODE
        ? (node.textContent ?? '').length
        : node.childNodes.length;
      const range = document.createRange();
      range.setStart(node, Math.min(caret.offset, limit));
      range.collapse(true);
      const selection = window.getSelection();
      selection?.removeAllRanges();
      selection?.addRange(range);
    };

    const checkpoint = () => {
      if (checkpointTimer.current) {
        clearTimeout(checkpointTimer.current);
        checkpointTimer.current = undefined;
      }
      const history = historyRef.current;
      const html = el.innerHTML;
      if (history.stack[history.index]?.html === html) return;
      history.stack = history.stack.slice(0, history.index + 1);
      history.stack.push({ html, caret: caretSnapshot() });
      if (history.stack.length > MAX_HISTORY) history.stack.shift();
      history.index = history.stack.length - 1;
    };

    // A burst of typing is one undo step, not one per keystroke.
    const scheduleCheckpoint = () => {
      if (checkpointTimer.current) clearTimeout(checkpointTimer.current);
      checkpointTimer.current = setTimeout(checkpoint, TYPING_COALESCE_MS);
    };

    const applyHistory = (entry: Snapshot) => {
      el.innerHTML = entry.html;
      restoreCaret(entry.caret);
      propsRef.current.onChange(serialize());
    };

    const undo = () => {
      // The edit in flight may still be inside the coalescing window; without this it
      // would be skipped over and the undo would land a step too far back.
      checkpoint();
      const history = historyRef.current;
      if (history.index <= 0) return;
      history.index--;
      applyHistory(history.stack[history.index]);
    };

    const redo = () => {
      checkpoint();
      const history = historyRef.current;
      if (history.index >= history.stack.length - 1) return;
      history.index++;
      applyHistory(history.stack[history.index]);
    };

    const onKeyDown = (e: KeyboardEvent) => {
      if (propsRef.current.disabled) return;
      const key = e.key.toLowerCase();
      if ((e.ctrlKey || e.metaKey) && !e.altKey && key === 'z') {
        e.preventDefault();
        if (e.shiftKey) redo(); else undo();
        return;
      }
      if ((e.ctrlKey || e.metaKey) && !e.altKey && key === 'y') {
        e.preventDefault();
        redo();
      }
    };

    // The state the author started from is the bottom of the stack.
    checkpoint();

    // Walk up to find nearest INS authored by current user
    const nearestOwnIns = (node: Node | null): HTMLElement | null => {
      const { userId } = propsRef.current;
      let n: Node | null = node?.nodeType === Node.TEXT_NODE ? node.parentNode : node;
      while (n && n !== el) {
        const e = n as HTMLElement;
        if (e.nodeName === 'INS' && e.dataset.authorId === userId) return e;
        n = n.parentNode;
      }
      return null;
    };

    // Walk up to find nearest DEL ancestor
    const nearestDel = (node: Node | null): HTMLElement | null => {
      let n: Node | null = node?.nodeType === Node.TEXT_NODE ? node.parentNode : node;
      while (n && n !== el) {
        if ((n as HTMLElement).nodeName === 'DEL') return n as HTMLElement;
        n = n.parentNode;
      }
      return null;
    };

    // Place the cursor at a specific range position
    const setCursor = (container: Node, offset: number) => {
      const sel = window.getSelection();
      const r = document.createRange();
      r.setStart(container, offset);
      r.collapse(true);
      sel?.removeAllRanges();
      sel?.addRange(r);
    };

    // Resolve an element-position boundary to the actual node it refers to.
    // Range boundaries can be (element, childIndex) rather than (textNode, charOffset);
    // this gives us the real node at that boundary so nearestOwnIns can walk up from it.
    const resolveStart = (range: Range): Node => {
      const { startContainer, startOffset } = range;
      if (startContainer.nodeType !== Node.TEXT_NODE) {
        return (startContainer as Element).childNodes[startOffset] ?? startContainer;
      }
      return startContainer;
    };
    const resolveEnd = (range: Range): Node => {
      const { endContainer, endOffset } = range;
      if (endContainer.nodeType !== Node.TEXT_NODE) {
        return (endContainer as Element).childNodes[Math.max(0, endOffset - 1)] ?? endContainer;
      }
      return endContainer;
    };

    // Mark a range as deleted:
    //   - own <ins>: just remove the text (un-insert)
    //   - already in <del>: skip, move cursor before the block
    //   - otherwise: wrap in <del data-author-id>
    const markAsDeleted = (range: Range): void => {
      if (range.collapsed) return;
      const { userId, userName } = propsRef.current;
      const sel = window.getSelection();

      // Check whether the entire range falls within a single own <ins>.
      // We resolve both boundaries to their actual nodes because selections made via
      // mouse or Shift+arrows often produce element-level ranges (e.g., <p> at childIndex)
      // rather than text-level ranges, which makes commonAncestorContainer land on the
      // parent element instead of inside the <ins>.
      const startIns = nearestOwnIns(resolveStart(range));
      const endIns   = nearestOwnIns(resolveEnd(range));

      if (startIns && startIns === endIns) {
        // Undo our own insertion — just remove the content, no <del> needed
        range.deleteContents();
        sel?.removeAllRanges();
        const r = document.createRange();
        r.setStart(range.startContainer, range.startOffset);
        r.collapse(true);
        sel?.addRange(r);
        return;
      }

      const delAncestor = nearestDel(range.startContainer);
      if (delAncestor) {
        // Already deleted — move cursor before the block, do nothing else
        const r = document.createRange();
        r.setStartBefore(delAncestor);
        r.collapse(true);
        sel?.removeAllRanges();
        sel?.addRange(r);
        return;
      }

      // Whitespace-only: delete without tracking
      if (!range.toString().trim()) {
        range.deleteContents();
        return;
      }

      // Wrap selected content in <del>
      const fragment = range.extractContents();

      // Your own insertions inside the range go away outright rather than being buried
      // in a <del>. A selection often spans both: double-clicking a word you just typed
      // takes the original text it abuts along with it, and wrapping the lot left the
      // insertion struck through inside a deletion, which no further keystroke could
      // clear. Another reviewer's insertion is left to be marked deleted as normal.
      fragment.querySelectorAll(`ins[data-author-id="${CSS.escape(userId)}"]`)
        .forEach(own => own.remove());

      // Nothing but your own insertions were selected — the deletion is already done.
      if (!(fragment.textContent ?? '').trim() && !fragment.querySelector('img')) {
        const cleared = document.createRange();
        cleared.setStart(range.startContainer, range.startOffset);
        cleared.collapse(true);
        sel?.removeAllRanges();
        sel?.addRange(cleared);
        return;
      }

      const del = document.createElement('del');
      del.dataset.authorId = userId;
      del.dataset.author = userName;
      del.appendChild(fragment);
      range.insertNode(del);

      // Cursor immediately after the <del>
      const r = document.createRange();
      r.setStartAfter(del);
      r.collapse(true);
      sel?.removeAllRanges();
      sel?.addRange(r);
    };

    // Convert a StaticRange (from getTargetRanges) to a live Range
    const toRange = (sr: StaticRange): Range => {
      const r = document.createRange();
      r.setStart(sr.startContainer, sr.startOffset);
      r.setEnd(sr.endContainer, sr.endOffset);
      return r;
    };

    // Insert text at cursor position, tracked as <ins>.
    // Whitespace-only text is inserted as a plain text node (no <ins> wrapper).
    // Merges into an adjacent <ins> by the same user when possible.
    const insertText = (text: string): void => {
      const { userId, userName } = propsRef.current;
      const sel = window.getSelection();
      if (!sel || !sel.rangeCount) return;

      let range = sel.getRangeAt(0);

      // Step out of any <del> block first
      const delNode = nearestDel(range.startContainer);
      if (delNode) {
        const r = document.createRange();
        r.setStartBefore(delNode);
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        range = sel.getRangeAt(0);
      }

      // Delete any active selection, tracked
      if (!range.collapsed) {
        markAsDeleted(range);
        if (!sel.rangeCount) return;
        range = sel.getRangeAt(0);
      }

      // Whitespace-only: insert as plain text, no tracking needed
      if (!text.trim()) {
        const textNode = document.createTextNode(text);
        range.insertNode(textNode);
        setCursor(textNode, textNode.length);
        return;
      }

      // Already inside one of our own insertions: the text simply joins it. Without this
      // a caret sitting at an element position within the <ins> — which is what restoring
      // an undo gives you — opened a second <ins> nested inside the first.
      const enclosingIns = nearestOwnIns(range.startContainer);
      if (enclosingIns) {
        const inserted = document.createTextNode(text);
        range.insertNode(inserted);
        setCursor(inserted, inserted.length);
        return;
      }

      // Try to append to adjacent <ins> by same user
      const { startContainer, startOffset } = range;
      let targetIns: HTMLElement | null = null;

      if (startContainer.nodeType === Node.TEXT_NODE) {
        const parent = startContainer.parentNode as HTMLElement;
        if (
          parent.nodeName === 'INS' &&
          parent.dataset.authorId === userId &&
          startOffset === startContainer.textContent!.length
        ) {
          targetIns = parent;
        }
      } else {
        const prev = (startContainer as Element).childNodes[startOffset - 1];
        if (
          prev instanceof HTMLElement &&
          prev.nodeName === 'INS' &&
          prev.dataset.authorId === userId
        ) {
          targetIns = prev;
        }
      }

      let anchorNode: Node;
      let anchorOffset: number;

      if (targetIns) {
        targetIns.insertAdjacentText('beforeend', text);
        const last = targetIns.lastChild!;
        anchorNode = last;
        anchorOffset = last.textContent!.length;
      } else {
        const ins = document.createElement('ins');
        ins.dataset.authorId = userId;
        ins.dataset.author = userName;
        ins.textContent = text;
        range.insertNode(ins);
        const last = ins.lastChild!;
        anchorNode = last;
        anchorOffset = last.textContent!.length;
      }

      setCursor(anchorNode, anchorOffset);
    };

    const onBeforeInput = (e: InputEvent) => {
      if (propsRef.current.disabled) { e.preventDefault(); return; }

      switch (e.inputType) {
        // ── Insertions ─────────────────────────────────────────────────────
        case 'insertText': {
          e.preventDefault();
          if (e.data) insertText(e.data);
          setTimeout(emit, 0);
          break;
        }

        case 'insertFromPaste':
        case 'insertFromDrop': {
          e.preventDefault();
          // Prefer plain text from the data transfer to avoid pasting raw HTML
          const dt = (e as any).dataTransfer as DataTransfer | undefined;
          const text = dt?.getData('text/plain') ?? e.data ?? '';
          if (text) insertText(text);
          setTimeout(emit, 0);
          break;
        }

        // ── Deletions — use getTargetRanges() for the exact affected range ─
        case 'deleteContentBackward':
        case 'deleteContentForward':
        case 'deleteWordBackward':
        case 'deleteWordForward':
        case 'deleteSoftLineBackward':
        case 'deleteSoftLineForward':
        case 'deleteEntireSoftLine':
        case 'deleteHardLineBackward':
        case 'deleteHardLineForward':
        case 'deleteByCut': {
          const targets = e.getTargetRanges();
          if (targets.length > 0) {
            const range = toRange(targets[0]);
            if (!range.toString().trim()) {
              // Empty/whitespace-only range — includes paragraph-boundary backspace.
              // Let the browser handle it natively so block merging works correctly.
              setTimeout(emit, 0);
              break;
            }
            e.preventDefault();
            markAsDeleted(range);
          } else {
            e.preventDefault();
          }
          setTimeout(emit, 0);
          break;
        }

        // ── Line breaks / paragraph splits — never create <ins>/<del> ────────
        case 'insertLineBreak': {
          e.preventDefault();
          const sel2 = window.getSelection();
          if (!sel2 || !sel2.rangeCount) break;
          let r2 = sel2.getRangeAt(0);
          if (!r2.collapsed) {
            markAsDeleted(r2);
            if (!sel2.rangeCount) break;
            r2 = sel2.getRangeAt(0);
          }
          const br = document.createElement('br');
          r2.insertNode(br);
          // Trailing <br> so the new line is visible in the block
          if (!br.nextSibling) br.parentNode?.appendChild(document.createElement('br'));
          const afterBr = document.createRange();
          afterBr.setStartAfter(br);
          afterBr.collapse(true);
          sel2.removeAllRanges();
          sel2.addRange(afterBr);
          setTimeout(emit, 0);
          break;
        }

        case 'insertParagraph': {
          e.preventDefault();
          const sel2 = window.getSelection();
          if (!sel2 || !sel2.rangeCount) break;
          let r2 = sel2.getRangeAt(0);
          if (!r2.collapsed) {
            markAsDeleted(r2);
            if (!sel2.rangeCount) break;
            r2 = sel2.getRangeAt(0);
          }
          // Find nearest block ancestor within the editor
          let block: HTMLElement | null = null;
          let nb: Node | null = r2.startContainer;
          while (nb && nb !== el) {
            if (nb instanceof HTMLElement && /^(P|DIV|H[1-6]|LI|BLOCKQUOTE|PRE)$/.test(nb.nodeName)) {
              block = nb; break;
            }
            nb = nb.parentNode;
          }
          if (!block) {
            // No block ancestor — just insert a <br>
            const br = document.createElement('br');
            r2.insertNode(br);
            const after = document.createRange();
            after.setStartAfter(br);
            after.collapse(true);
            sel2.removeAllRanges();
            sel2.addRange(after);
          } else {
            // Extract everything from cursor to end of block into a new block
            const splitRange = document.createRange();
            splitRange.setStart(r2.startContainer, r2.startOffset);
            splitRange.setEnd(block, block.childNodes.length);
            const fragment = splitRange.extractContents();
            const newBlock = document.createElement(block.tagName.toLowerCase());
            newBlock.appendChild(fragment);
            if (!newBlock.textContent && !newBlock.querySelector('img,br')) {
              newBlock.innerHTML = '<br>';
            }
            if (!block.textContent && !block.querySelector('img,br')) {
              block.innerHTML = '<br>';
            }
            block.parentNode?.insertBefore(newBlock, block.nextSibling);
            // Always place cursor at the block element boundary (not inside any
            // <ins>/<del> child) so that subsequent typing opens a fresh <ins>.
            setCursor(newBlock, 0);
          }
          setTimeout(emit, 0);
          break;
        }

        // formatBold, formatItalic — browser handles natively
      }
    };

    el.addEventListener('beforeinput', onBeforeInput as EventListener);
    el.addEventListener('keydown', onKeyDown);
    return () => {
      el.removeEventListener('beforeinput', onBeforeInput as EventListener);
      el.removeEventListener('keydown', onKeyDown);
      if (checkpointTimer.current) clearTimeout(checkpointTimer.current);
    };
  }, []);

  const execFormat = (cmd: string) => {
    if (propsRef.current.disabled) return;
    editorRef.current?.focus();
    document.execCommand(cmd, false);
    if (editorRef.current) propsRef.current.onChange(editorRef.current.innerHTML);
  };

  return (
    <div className="tce-wrap">
      {!disabled && (
        <div className={`tce-toolbar${lockedBy ? ' tce-toolbar--locked' : ''}`}>
          <button
            type="button"
            className="tce-btn"
            title="Bold (Ctrl+B)"
            onMouseDown={e => { e.preventDefault(); execFormat('bold'); }}
          >
            <Bold size={14} />
          </button>
          <button
            type="button"
            className="tce-btn"
            title="Italic (Ctrl+I)"
            onMouseDown={e => { e.preventDefault(); execFormat('italic'); }}
          >
            <Italic size={14} />
          </button>
        </div>
      )}
      <div
        ref={editorRef}
        className={`tce-body${disabled ? ' tce-body--disabled' : ''}`}
        contentEditable={!isReadOnly}
        suppressContentEditableWarning
        onInput={() => {
          if (editorRef.current) propsRef.current.onChange(editorRef.current.innerHTML);
        }}
      />
      {lockedBy && <LockOverlay holder={lockedBy} />}
    </div>
  );
}
