import { useEffect, useRef } from 'react';
import { EditorView, keymap, lineNumbers, highlightActiveLine, placeholder as cmPlaceholder } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { HighlightStyle, syntaxHighlighting, bracketMatching, indentOnInput } from '@codemirror/language';
import { css as cssLanguage } from '@codemirror/lang-css';
import { tags as t } from '@lezer/highlight';

export interface CssEditorProps {
  /** Initial document. Later changes are pushed in only when they differ from what's on screen,
   *  so a parent re-rendering on its own onChange never fights the cursor. */
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /** Minimum editor height; the editor grows with the content past this. */
  minHeight?: string;
  readOnly?: boolean;
}

// The CSS field is a fixed dark surface regardless of the app theme (it predates this component —
// see .css-editor / .css-preview in ReportDesigner.css), so these track that VS Code-ish palette
// rather than the theme variables.
const BACKGROUND = '#1e1e1e';
const FOREGROUND = '#d4d4d4';

const cssHighlightStyle = HighlightStyle.define([
  { tag: t.comment, color: '#6a9955', fontStyle: 'italic' },
  { tag: t.propertyName, color: '#9cdcfe' },
  { tag: [t.tagName, t.typeName, t.className, t.labelName], color: '#d7ba7d' },
  { tag: [t.string, t.special(t.string)], color: '#ce9178' },
  { tag: [t.number, t.unit, t.color], color: '#b5cea8' },
  { tag: [t.keyword, t.atom, t.definitionKeyword, t.modifier], color: '#c586c0' },
  { tag: t.variableName, color: '#9cdcfe' },
  { tag: t.punctuation, color: FOREGROUND },
  { tag: t.invalid, color: '#f48771' },
]);

/**
 * CodeMirror 6 editor for the report template's custom CSS: syntax highlighting, line numbers,
 * bracket matching, auto-indent, and its own undo history.
 *
 * <p>The view is created once on mount and never torn down by a re-render — the parent keeps the
 * text in its own state and saves on every keystroke, so recreating it would drop focus on each
 * character. Remount it with a `key` to load a different document.
 */
export default function CssEditor({
  value, onChange, placeholder, minHeight = '300px', readOnly = false,
}: CssEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);

  // Held in a ref so the updateListener closure stays current without rebuilding the view.
  const onChangeRef = useRef(onChange);
  useEffect(() => { onChangeRef.current = onChange; }, [onChange]);

  // Mount-only: `value` seeds the document, and the effect below syncs later external edits.
  const initialValueRef = useRef(value);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const view = new EditorView({
      doc: initialValueRef.current,
      parent: container,
      extensions: [
        cssLanguage(),
        syntaxHighlighting(cssHighlightStyle),
        lineNumbers(),
        highlightActiveLine(),
        bracketMatching(),
        indentOnInput(),
        history(),
        EditorView.lineWrapping,
        // indentWithTab first so Tab indents instead of moving focus out of the editor.
        keymap.of([indentWithTab, ...historyKeymap, ...defaultKeymap]),
        cmPlaceholder(placeholder ?? ''),
        EditorState.readOnly.of(readOnly),
        EditorView.editable.of(!readOnly),
        EditorView.theme({
          '&': { backgroundColor: BACKGROUND, color: FOREGROUND, fontSize: '0.875rem' },
          '&.cm-focused': { outline: 'none' },
          '.cm-scroller': {
            fontFamily: "'Monaco', 'Menlo', 'Ubuntu Mono', monospace",
            lineHeight: '1.6',
            minHeight,
            overflow: 'auto',
          },
          '.cm-content': { padding: '0.75rem 0' },
          '.cm-gutters': { backgroundColor: BACKGROUND, color: '#5a5a5a', border: 'none' },
          '.cm-activeLine': { backgroundColor: 'rgba(255, 255, 255, 0.04)' },
          '.cm-activeLineGutter': { backgroundColor: 'transparent', color: '#a0a0a0' },
          '.cm-cursor': { borderLeftColor: FOREGROUND },
          '.cm-placeholder': { color: '#6a6a6a' },
          '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#264f78' },
          '.cm-matchingBracket, &.cm-focused .cm-matchingBracket': {
            backgroundColor: 'rgba(255, 255, 255, 0.12)',
            outline: 'none',
          },
        }, { dark: true }),
        EditorView.updateListener.of(update => {
          if (update.docChanged) onChangeRef.current(update.state.doc.toString());
        }),
      ],
    });
    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // Mount-only: see the class doc. Remount with a `key` to swap documents.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Adopt a `value` that changed outside the editor (e.g. the template reloaded after a failed
  // save). Comparing against the live document first means our own keystrokes are a no-op here.
  useEffect(() => {
    const view = viewRef.current;
    if (!view || value === view.state.doc.toString()) return;
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
  }, [value]);

  return <div className="css-codemirror" ref={containerRef} />;
}
