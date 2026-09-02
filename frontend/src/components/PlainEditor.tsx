import { useRef, useEffect } from 'react';
import { Bold, Italic } from 'lucide-react';
import DOMPurify from 'dompurify';
import { LockOverlay } from './TrackChangesEditor';
import { cleanPastedHtml, looksLikeTsvTable, tsvToTableHtml } from '../utils/pasteHtml';
import './TrackChangesEditor.css';

interface Props {
  defaultValue: string;
  onChange: (html: string) => void;
  disabled?: boolean;
  /** Another reviewer is editing this note — their display name. See TrackChangesEditor. */
  lockedBy?: string;
}

export default function PlainEditor({ defaultValue, onChange, disabled = false, lockedBy }: Props) {
  const isReadOnly = disabled || !!lockedBy;
  const editorRef = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(onChange);
  useEffect(() => { onChangeRef.current = onChange; });

  useEffect(() => {
    if (editorRef.current) editorRef.current.innerHTML = defaultValue;
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
    if (el.innerHTML !== defaultValue) el.innerHTML = defaultValue;
  }, [defaultValue]);

  const execFormat = (cmd: string) => {
    if (isReadOnly) return;
    editorRef.current?.focus();
    document.execCommand(cmd, false);
    if (editorRef.current) onChangeRef.current(editorRef.current.innerHTML);
  };

  /**
   * Word and Excel put their own layout engine's HTML on the clipboard; pasted natively it
   * lands here verbatim, fonts and MSO metadata and all, and gets stored that way. Reduce it
   * to the same markup subset the main editor keeps. TrackChangesEditor deliberately does
   * not do this — every insertion there has to be wrapped in a tracked <ins>, so it pastes
   * as plain text.
   */
  function handlePaste(e: React.ClipboardEvent) {
    if (isReadOnly) return;
    const rawHtml = e.clipboardData.getData('text/html');
    const text = e.clipboardData.getData('text/plain');
    const html = (rawHtml ? cleanPastedHtml(rawHtml) : '')
      || (looksLikeTsvTable(text) ? tsvToTableHtml(text) : '');
    if (!html) return;

    e.preventDefault();
    editorRef.current?.focus();
    document.execCommand('insertHTML', false, DOMPurify.sanitize(html));
    if (editorRef.current) onChangeRef.current(editorRef.current.innerHTML);
  }

  function handleClick(e: React.MouseEvent) {
    let node: Node | null = e.target as Node;
    while (node) {
      if (node.nodeType === Node.ELEMENT_NODE) {
        const el = node as HTMLElement;
        if (el.tagName === 'A') {
          const href = el.getAttribute('href');
          if (href) {
            e.preventDefault();
            window.open(href, '_blank', 'noopener,noreferrer');
          }
          return;
        }
      }
      node = node.parentNode;
    }
  }

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
        onClick={handleClick}
        onPaste={handlePaste}
        onInput={() => {
          if (editorRef.current) onChangeRef.current(editorRef.current.innerHTML);
        }}
      />
      {lockedBy && <LockOverlay holder={lockedBy} />}
    </div>
  );
}
