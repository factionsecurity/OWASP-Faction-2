import { useRef, useEffect, useState, forwardRef, useImperativeHandle } from 'react';
import { createPortal } from 'react-dom';
import {
  AlignCenter,
  AlignLeft,
  Bold,
  Bot,
  Check,
  ChevronDown,
  Code,
  ImagePlus,
  Italic,
  Link,
  List,
  ListOrdered,
  Loader2,
  Lock,
  MessageCircleQuestion,
  RemoveFormatting,
  Sparkles,
  Table2,
  Underline,
  Unlink,
  X,
} from 'lucide-react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import TurndownService from 'turndown';
import { gfm } from 'turndown-plugin-gfm';
import { EditorView, keymap, placeholder as cmPlaceholder, Decoration, ViewPlugin, WidgetType } from '@codemirror/view';
import type { DecorationSet, ViewUpdate } from '@codemirror/view';
import { Prec, RangeSetBuilder, StateField } from '@codemirror/state';
import type { EditorState } from '@codemirror/state';
import { defaultKeymap, indentWithTab } from '@codemirror/commands';
import { HighlightStyle, syntaxHighlighting, syntaxTree } from '@codemirror/language';
import { markdown } from '@codemirror/lang-markdown';
import { GFM as lezerGfm } from '@lezer/markdown';
import { tags } from '@lezer/highlight';
import { mentionsApi, aiApi } from '../api';
import type { AiPromptScope, AiPromptSummary } from '../types';
import './RichTextEditor.css';

// "> text" markdown is repurposed as CENTERED text, not blockquote. Reports are the
// product here — blockquotes have no meaning in them, and the previous representation
// (<div align="center">) didn't survive the markdown round-trip and broke the docx
// converter. <center> round-trips cleanly and converts.
marked.use({
  // Single newlines become <br> instead of collapsing into the previous line —
  // otherwise line breaks typed in the markdown view silently disappear.
  breaks: true,
  renderer: {
    blockquote(token) {
      return `<center>${this.parser.parse(token.tokens)}</center>\n`;
    },
  },
});

// Block-level tags that stand on their own at the editor root. Anything else
// (text nodes, inline elements, <br> runs) gets wrapped in a <p> on save.
const ROOT_BLOCK_TAGS = new Set([
  'P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
  'UL', 'OL', 'TABLE', 'PRE', 'BLOCKQUOTE', 'CENTER', 'FIGURE', 'HR',
]);

function containsBlockElement(el: Element): boolean {
  return Array.from(el.children).some(
    c => ROOT_BLOCK_TAGS.has(c.tagName) || containsBlockElement(c)
  );
}

// Normalizes serialized editor HTML so every continuous run of text lives in a
// <p>: loose text/inline nodes at the root are wrapped, and plain single-line
// <div>s (what contenteditable historically produced on Enter) become <p>s.
// Operates on the serialized string only — the live DOM (and the caret) is
// never touched.
function normalizeBlocks(html: string): string {
  if (!html || !html.trim() || html === '<br>') return html;
  const tpl = document.createElement('template');
  tpl.innerHTML = html;
  const out = document.createElement('div');
  let run: Node[] = [];

  const flushRun = () => {
    if (run.length === 0) return;
    const text = run.map(n => n.textContent ?? '').join('');
    const hasContent = text.trim() !== ''
      || run.some(n => n.nodeType === Node.ELEMENT_NODE && (n as Element).querySelector?.('img') !== null)
      // A lone <br> at the root is an intentional blank line — keep it as an
      // empty paragraph so it survives markdown round-trips and the DOCX.
      || run.some(n => n.nodeType === Node.ELEMENT_NODE
            && ((n as Element).tagName === 'IMG' || (n as Element).tagName === 'BR'));
    if (hasContent) {
      const p = document.createElement('p');
      run.forEach(n => p.appendChild(n));
      out.appendChild(p);
    }
    run = [];
  };

  Array.from(tpl.content.childNodes).forEach(node => {
    const el = node.nodeType === Node.ELEMENT_NODE ? (node as Element) : null;
    if (el && ROOT_BLOCK_TAGS.has(el.tagName)) {
      flushRun();
      if (el.tagName === 'DIV' && !containsBlockElement(el)) {
        // Single-line div → paragraph, keeping alignment/style attributes
        const p = document.createElement('p');
        for (const attr of Array.from(el.attributes)) p.setAttribute(attr.name, attr.value);
        p.append(...Array.from(el.childNodes));
        out.appendChild(p);
      } else {
        out.appendChild(el);
      }
    } else {
      run.push(node);
    }
  });
  flushRun();
  return out.innerHTML;
}

const turndownService = new TurndownService({ headingStyle: 'atx', bulletListMarker: '-', codeBlockStyle: 'fenced' });
turndownService.use(gfm);

/**
 * The serialised form of an @mention. Markdown has no mention syntax, so a mention
 * travels through the markdown view as raw inline HTML — the same escape hatch
 * underline and coloured text already use.
 *
 * MentionQueueService on the backend greps for `data-username="…"` and nothing else,
 * so this string is the entire contract between the two halves. Both the rich-text
 * and markdown insert paths build it here so they cannot drift apart.
 */
function mentionSpanHtml(username: string): string {
  // Escaped because the rich-text path parses this string as HTML, where a username
  // containing quotes or angle brackets would otherwise break out of the attribute.
  const safe = username
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
  return `<span class="mention" data-username="${safe}" contenteditable="false">@${safe}</span>`;
}

// Formatting with no markdown equivalent — underline and colored text — must pass
// through to the markdown view as raw inline HTML (turndown's default is to unwrap
// the tag and keep only its text, silently losing the formatting on every
// rich→markdown conversion). marked parses the inline HTML straight back, so the
// round-trip is lossless. Spans are kept only when they carry the formatting styles
// we care about — Chrome litters contenteditable output with incidental spans
// (font-size wrappers etc.) that would otherwise turn the markdown into tag soup.
//
// @mention spans are kept for the same reason but keyed on data-username rather than
// style: without this, turndown unwrapped them to bare "@alice" text, so merely
// opening the markdown view stripped every mention's data-username and the backend
// silently stopped notifying anyone who had been mentioned.
turndownService.keep(node =>
  node.nodeName === 'U' ||
  (node.nodeName === 'SPAN' && (
    node.hasAttribute('data-username') ||
    /(?:^|;)\s*(?:color|background-color|text-decoration)\s*:/i.test(node.getAttribute('style') ?? '')
  ))
);

// The reverse mapping: anything centered — a <center> tag, legacy align="center",
// or an inline text-align:center (what execCommand('justifyCenter') produces in the
// rich view) — becomes "> " prefixed lines. LI is excluded: list items are centered
// via their parent UL/OL, and a "> " inside a "- " marker would corrupt the list.
turndownService.addRule('centeredBlock', {
  filter: node => {
    if (node.nodeName === 'CENTER') return true;
    if (!['P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'FIGURE', 'UL', 'OL'].includes(node.nodeName)) return false;
    const el = node as HTMLElement;
    return el.getAttribute('align') === 'center' || el.style.textAlign === 'center';
  },
  replacement: content => {
    const inner = content.replace(/^\n+|\n+$/g, '');
    if (!inner) return '';
    return '\n\n' + inner.split('\n').map(line => (line ? `> ${line}` : '>')).join('\n') + '\n\n';
  },
});

// Line breaks map to plain newlines in the markdown source (marked runs with
// breaks:true, so a single newline parses straight back to <br> — lossless
// without turndown's invisible trailing-two-spaces convention). A <br> that
// IS the whole paragraph (an empty <p><br></p> = intentional blank line)
// becomes a <br/> placeholder block; htmlToMarkdown then rewrites those
// placeholders into real blank lines so the markdown stays clean — see
// preserveBlankLines / restoreBlankLines.
// (Registered before tableCellLineBreak — turndown gives later rules
// precedence, and table-cell <br> placeholders must stay suppressed.)
turndownService.addRule('lineBreak', {
  filter: 'br',
  replacement: (_content, node) => {
    const parent = node.parentNode as HTMLElement | null;
    // Standalone blank line: an empty <p><br></p>, or a <br> sitting directly
    // at the document root (what marked emits for a placeholder block).
    const isBlankLine = (parent?.nodeName === 'P' && !(parent.textContent ?? '').trim())
      || parent?.nodeName === 'X-TURNDOWN';
    return isBlankLine ? '<br/>' : '\n';
  },
});

// A GFM table row must stay on a single line — but turndown's default <br> rule emits
// a real line break ("  \n"). Our own empty table cells use <br> as a placeholder so
// the contenteditable cell stays focusable/visible, and that placeholder was leaking
// through as a literal newline, splitting one table row across several broken lines.
turndownService.addRule('tableCellLineBreak', {
  filter: node => node.nodeName === 'BR' && ['TD', 'TH'].includes(node.parentNode?.nodeName ?? ''),
  replacement: () => '',
});

// ── Markdown source view: CodeMirror syntax highlighting (Toast UI-style) ──────

const markdownHighlightStyle = HighlightStyle.define([
  { tag: tags.heading1, fontSize: '1.6em', fontWeight: '700', color: '#111827' },
  { tag: tags.heading2, fontSize: '1.4em', fontWeight: '700', color: '#111827' },
  { tag: tags.heading3, fontSize: '1.25em', fontWeight: '700', color: '#111827' },
  { tag: tags.heading4, fontSize: '1.1em', fontWeight: '700', color: '#111827' },
  { tag: tags.heading5, fontSize: '1.05em', fontWeight: '700', color: '#111827' },
  { tag: tags.heading6, fontSize: '1em', fontWeight: '700', color: '#374151' },
  { tag: tags.heading, fontWeight: '700' }, // GFM table header row
  { tag: tags.strong, fontWeight: '700', color: '#111827' },
  { tag: tags.emphasis, fontStyle: 'italic', color: '#111827' },
  { tag: tags.strikethrough, textDecoration: 'line-through', color: '#6b7280' },
  { tag: tags.monospace, color: '#be185d', backgroundColor: '#f3f4f6' },
  { tag: tags.link, color: '#4f46e5', textDecoration: 'underline' },
  { tag: tags.url, color: '#6366f1' },
  // tags.quote deliberately unstyled: "> " is repurposed as centered text (see the
  // marked blockquote renderer), so quote-ish gray italics would be misleading.
  // Markup syntax characters themselves (#, *, `, |, >, [ ], etc.) — dimmed so the
  // actual content stands out, same convention Typora/Obsidian/Toast UI use.
  { tag: tags.processingInstruction, color: '#9ca3af' },
  { tag: tags.contentSeparator, color: '#9ca3af' },
]);

// GFM tables and fenced code blocks aren't tagged with a dedicated "block" tag by
// @lezer/markdown, so a plain HighlightStyle can't give code blocks a full-width grey
// background (tag-based highlighting only colors the text it spans, not the line box).
// This ViewPlugin instead walks the syntax tree for FencedCode/CodeBlock ranges and
// applies a line decoration, matching the surrounding contenteditable's `pre` styling.
const codeBlockLineDecoration = Decoration.line({ attributes: { class: 'cm-code-block-line' } });

function computeCodeBlockDecorations(view: EditorView): DecorationSet {
  const lineNumbers = new Set<number>();
  syntaxTree(view.state).iterate({
    enter: node => {
      if (node.name === 'FencedCode' || node.name === 'CodeBlock') {
        const startLine = view.state.doc.lineAt(node.from).number;
        const endLine = view.state.doc.lineAt(node.to).number;
        for (let ln = startLine; ln <= endLine; ln++) lineNumbers.add(ln);
      }
    },
  });
  const builder = new RangeSetBuilder<Decoration>();
  Array.from(lineNumbers).sort((a, b) => a - b).forEach(ln => {
    const line = view.state.doc.line(ln);
    builder.add(line.from, line.from, codeBlockLineDecoration);
  });
  return builder.finish();
}

const codeBlockBackgroundPlugin = ViewPlugin.fromClass(class {
  decorations: DecorationSet;
  constructor(view: EditorView) {
    this.decorations = computeCodeBlockDecorations(view);
  }
  update(update: ViewUpdate) {
    if (update.docChanged || update.viewportChanged) {
      this.decorations = computeCodeBlockDecorations(update.view);
    }
  }
}, {
  decorations: v => v.decorations,
});

// "> " (center) and "-"/"1." (list) markers render blue. They can't be singled out
// in the HighlightStyle above — @lezer/markdown lumps every marker character under
// the same processingInstruction tag — so this plugin decorates the QuoteMark and
// ListMark syntax nodes directly.
const mdMarkerDecoration = Decoration.mark({ class: 'cm-md-marker' });

function computeMarkerDecorations(view: EditorView): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>();
  for (const { from, to } of view.visibleRanges) {
    syntaxTree(view.state).iterate({
      from, to,
      enter: node => {
        if (node.name === 'QuoteMark' || node.name === 'ListMark') {
          builder.add(node.from, node.to, mdMarkerDecoration);
        }
      },
    });
  }
  return builder.finish();
}

const markerHighlightPlugin = ViewPlugin.fromClass(class {
  decorations: DecorationSet;
  constructor(view: EditorView) {
    this.decorations = computeMarkerDecorations(view);
  }
  update(update: ViewUpdate) {
    if (update.docChanged || update.viewportChanged) {
      this.decorations = computeMarkerDecorations(update.view);
    }
  }
}, {
  decorations: v => v.decorations,
});

// ── Markdown source view: inline image previews ────────────────────────────────
// Renders the actual image as a block widget below every ![alt](url) line, so a
// pasted image is visible in the source view instead of just its markdown syntax.

class ImagePreviewWidget extends WidgetType {
  constructor(readonly src: string) { super(); }
  eq(other: ImagePreviewWidget) { return other.src === this.src; }
  get estimatedHeight() { return 120; }
  toDOM() {
    const wrap = document.createElement('div');
    wrap.className = 'cm-image-preview';
    const img = document.createElement('img');
    img.src = this.src;
    img.alt = '';
    wrap.appendChild(img);
    return wrap;
  }
}

const IMAGE_MARKDOWN_RE = /!\[[^\]]*\]\(([^)\s]+)(?:\s[^)]*)?\)/g;

function buildImagePreviews(state: EditorState): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>();
  const text = state.doc.toString();
  for (const m of text.matchAll(IMAGE_MARKDOWN_RE)) {
    const line = state.doc.lineAt(m.index + m[0].length);
    builder.add(line.to, line.to, Decoration.widget({
      widget: new ImagePreviewWidget(m[1]),
      block: true,
      side: 1,
    }));
  }
  return builder.finish();
}

// A StateField, not a ViewPlugin — CodeMirror forbids block decorations from
// plugins because they change vertical layout.
const imagePreviewField = StateField.define<DecorationSet>({
  create: buildImagePreviews,
  update(deco, tr) {
    return tr.docChanged ? buildImagePreviews(tr.state) : deco.map(tr.changes);
  },
  provide: f => EditorView.decorations.from(f),
});

// ── Markdown source view: @mention chips ───────────────────────────────────────
// A mention has to travel through markdown as a raw <span data-username="…">, which
// is a lot of noise to read around. These decorations replace each span with the
// same chip the rich view shows. The document still contains the full span — only
// its rendering is collapsed — so nothing about the round-trip or the backend's
// parsing changes.

/** Reverses the escaping mentionSpanHtml applies, for display in the chip. */
function unescapeMentionAttr(value: string): string {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&gt;/g, '>')
    .replace(/&lt;/g, '<')
    .replace(/&amp;/g, '&');
}

class MentionChipWidget extends WidgetType {
  constructor(readonly username: string) { super(); }
  eq(other: MentionChipWidget) { return other.username === this.username; }
  toDOM() {
    const el = document.createElement('span');
    el.className = 'cm-mention';
    el.textContent = `@${this.username}`;
    return el;
  }
  // Let CodeMirror handle clicks so the caret can be placed either side of the chip.
  ignoreEvent() { return false; }
}

// Attribute order is not guaranteed — content that has round-tripped through
// marked/DOMPurify can come back with the attributes reordered — so match on
// data-username wherever it appears rather than on a fixed attribute sequence.
const MENTION_SPAN_RE = /<span\b[^>]*\bdata-username="([^"]*)"[^>]*>[^<]*<\/span>/g;

function buildMentionChips(state: EditorState): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>();
  for (const m of state.doc.toString().matchAll(MENTION_SPAN_RE)) {
    const from = m.index;
    builder.add(from, from + m[0].length, Decoration.replace({
      widget: new MentionChipWidget(unescapeMentionAttr(m[1])),
    }));
  }
  return builder.finish();
}

const mentionChipField = StateField.define<DecorationSet>({
  create: buildMentionChips,
  update(deco, tr) {
    return tr.docChanged ? buildMentionChips(tr.state) : deco.map(tr.changes);
  },
  provide: f => [
    EditorView.decorations.from(f),
    // Without this the caret can sit *inside* the hidden markup, so one Backspace
    // would silently shave a character off the span and corrupt the mention while
    // the chip still looked intact. Atomic ranges make arrow keys step over the
    // whole chip and Backspace delete it in one go.
    EditorView.atomicRanges.of(view => view.state.field(f, false) ?? Decoration.none),
  ],
});

const markdownEditorTheme = EditorView.theme({
  '&': {
    fontSize: '0.8rem',
    color: '#374151',
    backgroundColor: '#ffffff',
    // Fill the pane in split view — otherwise a WYSIWYG side taller than the
    // markdown content leaves the page background showing below the editor.
    height: '100%',
  },
  '.cm-content': {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
    padding: '0.5rem 0.75rem',
    caretColor: '#374151',
    minHeight: '100px',
  },
  '.cm-line': {
    lineHeight: '1.6',
  },
  '.cm-scroller': {
    overflow: 'visible',
  },
  '&.cm-focused': {
    outline: 'none',
  },
  '.cm-code-block-line': {
    backgroundColor: '#f3f4f6',
  },
  '.cm-md-marker': {
    color: '#2563eb',
  },
  '.cm-image-preview': {
    padding: '4px 0',
  },
  '.cm-image-preview img': {
    maxWidth: '100%',
    display: 'block',
    margin: '0 auto',
  },
  // Deliberately the same tokens as the rich view's .mention chip — in split view both
  // panes are on screen at once, so the mention has to read as the same object in both.
  '.cm-mention': {
    background: 'color-mix(in srgb, var(--primary-color) 15%, transparent)',
    color: 'var(--primary-color)',
    borderRadius: '3px',
    padding: '0 3px',
    fontWeight: '500',
    whiteSpace: 'nowrap',
    // The pane is monospace; the chip reads as prose, like it does in the rich view.
    fontFamily: 'inherit',
  },
  '.cm-placeholder': {
    color: '#9ca3af',
  },
});

// Pre-processing turndown needs to correctly recognize our own DOM structures as GFM
// tables / fenced code blocks before handing off to turndown itself.
function normalizeForMarkdown(html: string): string {
  const container = document.createElement('div');
  container.innerHTML = html;

  // turndown-plugin-gfm only recognizes a <table> as a GFM table if its first row is a
  // genuine header (<thead>, or a first row made entirely of <th>). Tables pasted from
  // outside sources (Excel, Sheets, Confluence, web pages, ...) are almost always plain
  // <table><tbody><tr><td>, so without this they'd fall through to raw, unconverted HTML
  // in the markdown output. Markdown tables always have a header, so promote the first
  // row when there isn't one already.
  container.querySelectorAll('table').forEach(table => {
    if (table.querySelector('thead')) return;
    const firstRow = table.rows[0];
    if (!firstRow || firstRow.cells.length === 0) return;
    if (Array.from(firstRow.cells).every(cell => cell.tagName === 'TH')) return;

    const thead = document.createElement('thead');
    const headerRow = document.createElement('tr');
    Array.from(firstRow.cells).forEach(cell => {
      const th = document.createElement('th');
      th.innerHTML = cell.innerHTML;
      headerRow.appendChild(th);
    });
    thead.appendChild(headerRow);
    // `firstRow` usually lives inside a <tbody>, not directly under <table>, so it
    // can't be used as an insertBefore reference on `table` itself — prepend instead.
    table.prepend(thead);
    firstRow.remove();
  });

  // turndown only converts a <pre> into a fenced/indented code block when its first
  // child is a <code> element. Our own code blocks (from the toolbar button or the
  // ``` keyboard shortcut) are created via `execCommand('formatBlock', 'pre')`, which
  // produces a bare <pre> with no nested <code> — so without this they'd fall through
  // to plain-text conversion with no code-block markers at all.
  container.querySelectorAll('pre').forEach(pre => {
    if (pre.firstChild && pre.firstChild.nodeName === 'CODE') return;
    const code = document.createElement('code');
    while (pre.firstChild) code.appendChild(pre.firstChild);
    pre.appendChild(code);
  });

  return container.innerHTML;
}

// ── Blank-line preservation ────────────────────────────────────────────────
// Markdown has no representation for consecutive blank lines — they collapse
// into a single paragraph break, so blank lines typed in either view were
// silently lost on every round-trip. The pair below keeps them: on the way
// INTO marked, each extra blank line becomes a standalone <br/> block (which
// renders as an empty paragraph in the rich view); on the way OUT of
// turndown, those placeholder blocks are rewritten back into blank lines so
// the markdown source stays clean.

function preserveBlankLines(markdown: string): string {
  // Never rewrite inside fenced code blocks, where blank lines are content.
  return markdown
    .split(/(```[\s\S]*?(?:```|$))/)
    .map((part, i) => i % 2 === 1
      ? part
      : part.replace(/\n{3,}/g, m => '\n\n' + '<br/>\n\n'.repeat(m.length - 2)))
    .join('');
}

function restoreBlankLines(markdown: string): string {
  return markdown
    .replace(/^<br\/>\n\n/gm, '\n')
    .replace(/\n\n<br\/>\s*$/, '\n\n\n');
}

function htmlToMarkdown(html: string): string {
  return html ? restoreBlankLines(turndownService.turndown(normalizeForMarkdown(html))) : '';
}

// HTML that wasn't produced by this editor (markdown round-trips, API/MCP-uploaded
// content, AI output) arrives as plain, unclassed elements, so re-apply the editor's
// presentation: tables get the .rte-table class all table styling is scoped to,
// images get the same centering + sizing inline styles uploadAndInsert puts on
// them, and empty table cells get the <br> placeholder that keeps them focusable.
function applyEditorPresentation(html: string): string {
  if (!html.includes('<table') && !html.includes('<img')) return html;
  const container = document.createElement('div');
  container.innerHTML = html;
  container.querySelectorAll('table').forEach(table => table.classList.add('rte-table'));
  container.querySelectorAll('td, th').forEach(cell => {
    if (!cell.firstChild) cell.innerHTML = '<br>';
  });
  container.querySelectorAll('img').forEach(img => {
    img.style.maxWidth = '100%';
    img.style.display = 'block';
    img.style.marginLeft = 'auto';
    img.style.marginRight = 'auto';
  });
  return container.innerHTML;
}

function markdownToHtml(markdown: string): string {
  return applyEditorPresentation(DOMPurify.sanitize(String(marked.parse(preserveBlankLines(markdown)))));
}

/**
 * Enables the AI toolbar actions (admin-defined prompts + Ask AI). The backend
 * limits the AI's data access to this assessment; scope picks which admin
 * prompts are offered (ASSESSMENT fields vs VULNERABILITY editors).
 */
export interface RichTextEditorAiContext {
  assessmentId: string;
  vulnerabilityId?: string;
  scope: AiPromptScope;
}

export interface RichTextEditorProps {
  value?: string;
  onChange?: (html: string) => void;
  onImageUpload?: (file: File) => Promise<string>;
  placeholder?: string;
  disabled?: boolean;
  /**
   * Display name of another user currently editing this field.
   *
   * Distinct from `disabled`: that strips the toolbar and renders a flat read-only
   * view, which hides the very edits the viewer is watching arrive. This keeps the
   * editor looking like an editor, washes it with a "locked" tint, and blocks input.
   */
  lockedBy?: string;
  aiContext?: RichTextEditorAiContext;
  /**
   * Enables the `@` user-autocomplete. Opt-in, because inserting a mention makes the
   * backend notify (and email) that user — which is only meaningful where the content is
   * addressed to a person. Reusable content (vulnerability templates, report layouts,
   * organization descriptions, engagement scope) must not notify, so it stays off there.
   *
   * Enabled on: application comments, vulnerability comments, assessment notes.
   */
  mentions?: boolean;
  /**
   * The conversation being written to, passed to the mention lookup.
   *
   * External (portal) users can only mention their own organization plus the people already on
   * this thread — the remediation contact and its subscribers — so without the context their
   * list is just their own organization. Internal users are unaffected: their candidates come
   * from the user directory their permissions already allow.
   */
  mentionContext?: { vulnerabilityId?: string; applicationId?: string };
}

export interface RichTextEditorRef {
  getHTML: () => string;
  setHTML: (html: string) => void;
  focus: () => void;
}

const MAX_HISTORY = 100;
const TYPING_COALESCE_MS = 500;

// Baked into the uploaded image's pixels (not CSS) so the stored file carries the
// border into reports and anywhere else it's embedded, exactly as shown in the editor.
const IMAGE_BORDER_PX = 1;
const IMAGE_BORDER_COLOR = '#374151';

async function bakeImageBorder(file: File): Promise<File> {
  // Only raster formats a canvas can round-trip — gif would lose animation and
  // svg its scalability, so those upload untouched.
  if (!/^image\/(png|jpe?g|webp)$/.test(file.type)) return file;
  try {
    const bitmap = await createImageBitmap(file);
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width + IMAGE_BORDER_PX * 2;
    canvas.height = bitmap.height + IMAGE_BORDER_PX * 2;
    const ctx = canvas.getContext('2d');
    if (!ctx) return file;
    ctx.fillStyle = IMAGE_BORDER_COLOR;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, IMAGE_BORDER_PX, IMAGE_BORDER_PX);
    bitmap.close();
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, file.type, 0.92));
    if (!blob) return file;
    return new File([blob], file.name, { type: file.type });
  } catch {
    // Decode failed (corrupt/unsupported data) — upload the original untouched.
    return file;
  }
}

const FONT_COLORS = [
  '#000000', '#374151', '#6b7280',
  '#dc2626', '#ea580c', '#d97706',
  '#16a34a', '#2563eb', '#7c3aed',
  '#db2777', '#ffffff',
];

const RichTextEditor = forwardRef<RichTextEditorRef, RichTextEditorProps>(
  ({ value = '', onChange, onImageUpload, placeholder, disabled = false, lockedBy, aiContext, mentions = false, mentionContext }, ref) => {
    /** Content cannot be modified — either permanently (`disabled`) or while another user holds the lock. */
    const isReadOnly = disabled || !!lockedBy;
    // The CodeMirror extensions below are built once, so they read the flag through a ref.
    const isReadOnlyRef = useRef(isReadOnly);
    isReadOnlyRef.current = isReadOnly;
    const editorRef = useRef<HTMLDivElement>(null);
    const onChangeRef = useRef(onChange);
    onChangeRef.current = onChange;
    const onImageUploadRef = useRef(onImageUpload);
    onImageUploadRef.current = onImageUpload;
    const colorWrapRef = useRef<HTMLDivElement>(null);
    const fileInputRef = useRef<HTMLInputElement>(null);
    const replaceTargetRef = useRef<HTMLImageElement | null>(null);
    const tablePickerWrapRef = useRef<HTMLDivElement>(null);

    // Undo/redo history — a custom stack, since structural edits (table row/col
    // add/delete/merge, image ops) mutate the DOM directly and are invisible to
    // the browser's native contenteditable undo manager.
    const historyRef = useRef<{ stack: string[]; index: number }>({ stack: [value], index: 0 });
    const typingDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const isApplyingHistoryRef = useRef(false);

    const [showColorPalette, setShowColorPalette] = useState(false);
    const [recentColors, setRecentColors] = useState<string[]>(() => {
      try { return JSON.parse(localStorage.getItem('rte-recent-colors') ?? '[]'); }
      catch { return []; }
    });
    const [customColor, setCustomColor] = useState('#000000');
    const [showLinkInput, setShowLinkInput] = useState(false);
    const [linkUrl, setLinkUrl] = useState('');
    const savedRangeRef = useRef<Range | null>(null);
    const [imgMenu, setImgMenu] = useState<{ img: HTMLImageElement; x: number; y: number } | null>(null);
    const [tableMenu, setTableMenu] = useState<{ x: number; y: number; cell: HTMLTableCellElement; table: HTMLTableElement } | null>(null);
    const [showTablePicker, setShowTablePicker] = useState(false);
    const [pickerHover, setPickerHover] = useState<{ r: number; c: number }>({ r: 0, c: 0 });

    // Rich text / markdown source toggle. Starts on the user's preferred view
    // (set via the account dropdown, stored in localStorage like the app theme).
    // Read-only editors always render the rich view — the other modes are editing
    // surfaces.
    const [viewMode, setViewMode] = useState<'rich' | 'markdown' | 'split'>(() => {
      if (disabled) return 'rich';
      const saved = localStorage.getItem('rte-default-view');
      return saved === 'markdown' || saved === 'split' ? saved : 'rich';
    });
    const cmContainerRef = useRef<HTMLDivElement>(null);
    const cmViewRef = useRef<EditorView | null>(null);
    // Set by switchToRichView so the focus-restoring effect below only fires on an
    // explicit switch back — not on the component's initial mount (which also starts
    // in 'rich' mode, but shouldn't steal focus just because it rendered).
    const pendingRichFocusRef = useRef(false);
    // True while the FIRST CodeMirror mount is still pending and came from the saved
    // view preference rather than a user click — that mount must not steal page focus.
    const initialCmMountRef = useRef(viewMode !== 'rich');

    // Load initial value on mount. Declared BEFORE the CodeMirror mount effect —
    // effects run in declaration order, and when the preferred default view is
    // markdown/split, CodeMirror derives its initial doc from this innerHTML.
    useEffect(() => {
      if (editorRef.current) editorRef.current.innerHTML = applyEditorPresentation(value);
      // Enter should create <p> blocks, not the browser default <div> — every
      // continuous block of text is stored inside <p></p> so the generated
      // DOCX gets real paragraphs (with spacing) instead of margin-less divs.
      try {
        document.execCommand('defaultParagraphSeparator', false, 'p');
      } catch { /* not supported — normalizeBlocks covers serialization */ }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Mount/tear down the CodeMirror markdown editor whenever the view is toggled.
    // Its own document is the source of truth while mounted — every edit (typed or
    // toolbar-driven) fires the updateListener below, which keeps the hidden rich-text
    // editor and onChange in sync (see markdownDocChanged).
    useEffect(() => {
      if (viewMode === 'rich') return;
      const container = cmContainerRef.current;
      const editor = editorRef.current;
      if (!container || !editor) return;

      const initialMarkdown = htmlToMarkdown(editor.innerHTML.replace(/\u200B/g, ''));

      const view = new EditorView({
        doc: initialMarkdown,
        parent: container,
        extensions: [
          markdown({ extensions: [lezerGfm] }),
          syntaxHighlighting(markdownHighlightStyle),
          codeBlockBackgroundPlugin,
          markerHighlightPlugin,
          imagePreviewField,
          mentionChipField,
          markdownEditorTheme,
          EditorView.lineWrapping,
          // Deliberately no history()/historyKeymap — Ctrl+Z/Ctrl+Y are handled by our
          // own cross-mode undo stack (see handleMarkdownKeyDown / tryHandleUndoRedoKey).
          // indentWithTab: Tab indents the selected lines (or inserts a tab at a
          // bare cursor), Shift+Tab outdents — mirrors the rich text side.
          // Must outrank defaultKeymap: while the @mention picker is open it owns
          // Arrow/Enter/Tab/Escape, which would otherwise move the cursor or insert a
          // newline before the picker ever saw the key. Each handler returns false when
          // the picker is closed, so normal editing behaviour is untouched.
          Prec.highest(keymap.of([
            { key: 'ArrowDown', run: () => cmMentionMove(1) },
            { key: 'ArrowUp', run: () => cmMentionMove(-1) },
            { key: 'Enter', run: () => cmMentionAccept() },
            { key: 'Tab', run: () => cmMentionAccept() },
            { key: 'Escape', run: () => cmMentionDismiss() },
          ])),
          keymap.of([indentWithTab, ...defaultKeymap]),
          cmPlaceholder(placeholder ?? ''),
          EditorView.updateListener.of(update => {
            if (update.docChanged) markdownDocChanged(update.state.doc.toString());
            // Selection changes matter too: clicking or arrowing to sit just after an
            // existing "@word" should offer the picker exactly as typing it does.
            if (update.docChanged || update.selectionSet) detectCmMention(update.view);
          }),
          // CodeMirror has no built-in image handling — without this, pasting an image
          // while focused here silently does nothing (unlike the rich-text side's
          // handlePaste). Reuses uploadAndInsert, which already inserts markdown image
          // syntax (not an <img> tag) whenever viewMode is 'markdown'.
          EditorView.domEventHandlers({
            paste: event => {
              if (isReadOnlyRef.current || !onImageUploadRef.current) return false;
              const items = Array.from(event.clipboardData?.items ?? []);
              const imageItem = items.find(item => item.type.startsWith('image/'));
              if (!imageItem) return false;
              event.preventDefault();
              const file = imageItem.getAsFile();
              if (file) uploadAndInsert(file);
              return true;
            },
          }),
        ],
      });
      cmViewRef.current = view;
      if (initialCmMountRef.current) {
        initialCmMountRef.current = false;
      } else {
        view.focus();
      }

      return () => {
        view.destroy();
        cmViewRef.current = null;
      };
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [viewMode]);

    // The rich-text body stays mounted (just CSS-hidden) while in markdown mode, so
    // switching back doesn't remount it — nothing would otherwise refocus it, which is
    // why Alt+W / the "Rich Text" tab used to leave focus stranded on the old editor.
    useEffect(() => {
      if (viewMode !== 'rich' || !pendingRichFocusRef.current) return;
      pendingRichFocusRef.current = false;
      const editor = editorRef.current;
      if (!editor) return;
      editor.focus();
      const range = document.createRange();
      range.selectNodeContents(editor);
      range.collapse(false);
      const sel = window.getSelection();
      sel?.removeAllRanges();
      sel?.addRange(range);
    }, [viewMode]);

    // ── AI actions (admin prompts + Ask AI) ──
    const aiMenuWrapRef = useRef<HTMLDivElement>(null);
    const askAiWrapRef = useRef<HTMLDivElement>(null);
    const [showAiMenu, setShowAiMenu] = useState(false);
    const [showAskAi, setShowAskAi] = useState(false);
    const [aiPrompts, setAiPrompts] = useState<AiPromptSummary[] | null>(null); // null = not fetched yet
    const [aiPromptsLoading, setAiPromptsLoading] = useState(false);
    const [askAiText, setAskAiText] = useState('');
    const [aiBusy, setAiBusy] = useState(false);
    const [aiError, setAiError] = useState<string | null>(null);

    // @mention state
    const [mentionQuery, setMentionQuery] = useState<string | null>(null);
    const [mentionUsers, setMentionUsers] = useState<Array<{ username: string; display: string }>>([]);
    const [mentionHighlight, setMentionHighlight] = useState(0);
    const [mentionPos, setMentionPos] = useState<{ x: number; y: number }>({ x: 0, y: 0 });
    const mentionDropdownRef = useRef<HTMLDivElement>(null);
    const mentionFetchRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    // The CodeMirror extensions are built once per viewMode mount, so any handler
    // inside them would close over the mention state as it was at mount time. Mirror
    // it into refs — the same trick the file already uses for onChange/onImageUpload.
    const mentionsRef = useRef(mentions);
    mentionsRef.current = mentions;
    const mentionQueryRef = useRef(mentionQuery);
    mentionQueryRef.current = mentionQuery;
    const mentionUsersRef = useRef(mentionUsers);
    mentionUsersRef.current = mentionUsers;
    const mentionHighlightRef = useRef(mentionHighlight);
    mentionHighlightRef.current = mentionHighlight;

    useImperativeHandle(ref, () => ({
      getHTML: () => editorRef.current?.innerHTML ?? '',
      setHTML: (html: string) => {
        if (!editorRef.current) return;
        editorRef.current.innerHTML = applyEditorPresentation(html);
        historyRef.current = { stack: [html], index: 0 };
      },
      focus: () => editorRef.current?.focus(),
    }));


    // Sync controlled value changes without disrupting an active cursor
    const lastValueRef = useRef(value);
    useEffect(() => {
      if (value === lastValueRef.current) return;
      lastValueRef.current = value;
      const el = editorRef.current;
      if (!el) return;
      const current = el.innerHTML.replace(/\u200B/g, '');
      // `value` may just be the normalized HTML this editor itself emitted \u2014 emit()
      // runs normalizeBlocks() (wrapping loose text in <p>) before calling onChange,
      // so the value fed back legitimately differs char-for-char from the live,
      // un-normalized innerHTML while representing the same content. Rewriting
      // innerHTML here in that case resets the caret to the start of the editor, so
      // every keystroke in an initially-empty editor prepends the next character in
      // its own paragraph (typing "broken" comes out reversed, one char per line).
      // Only re-apply when the DOM genuinely differs from an external value change.
      if (current === value || normalizeBlocks(current) === value) return;
      el.innerHTML = applyEditorPresentation(value);
      historyRef.current = { stack: [value], index: 0 };

      // Push the same change into the markdown pane. CodeMirror derives its document once, at
      // mount, from this element's innerHTML — so an editor that mounted in markdown/split view
      // before its value arrived (a saved view preference plus an async load) kept the empty
      // document it snapshotted, and split view showed content in the rich pane only. Toggling
      // views remounted CodeMirror against the populated innerHTML, which is why it looked
      // correct ever after.
      const view = cmViewRef.current;
      if (!view) return;
      const markdown = htmlToMarkdown(el.innerHTML.replace(/\u200B/g, ''));
      // Never dispatch a no-op: while the user types here the change flows the other way
      // (markdownDocChanged → onChange → back as `value`), and rewriting the document would
      // drop their cursor to the start.
      if (markdown === view.state.doc.toString()) return;
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: markdown } });
    }, [value]);

    // Close color palette on outside click
    useEffect(() => {
      if (!showColorPalette) return;
      const close = (e: MouseEvent) => {
        if (!colorWrapRef.current?.contains(e.target as Node)) setShowColorPalette(false);
      };
      document.addEventListener('mousedown', close);
      return () => document.removeEventListener('mousedown', close);
    }, [showColorPalette]);

    // Close image context menu on outside click or Escape
    useEffect(() => {
      if (!imgMenu) return;
      const close = () => setImgMenu(null);
      const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
      document.addEventListener('mousedown', close);
      document.addEventListener('keydown', onKey);
      return () => {
        document.removeEventListener('mousedown', close);
        document.removeEventListener('keydown', onKey);
      };
    }, [imgMenu]);

    // Close table context menu on outside click or Escape
    useEffect(() => {
      if (!tableMenu) return;
      const close = () => setTableMenu(null);
      const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
      document.addEventListener('mousedown', close);
      document.addEventListener('keydown', onKey);
      return () => {
        document.removeEventListener('mousedown', close);
        document.removeEventListener('keydown', onKey);
      };
    }, [tableMenu]);

    // Close table picker on outside click
    useEffect(() => {
      if (!showTablePicker) return;
      const close = (e: MouseEvent) => {
        if (!tablePickerWrapRef.current?.contains(e.target as Node)) setShowTablePicker(false);
      };
      document.addEventListener('mousedown', close);
      return () => document.removeEventListener('mousedown', close);
    }, [showTablePicker]);

    // Close AI prompt menu on outside click
    useEffect(() => {
      if (!showAiMenu) return;
      const close = (e: MouseEvent) => {
        if (!aiMenuWrapRef.current?.contains(e.target as Node)) setShowAiMenu(false);
      };
      document.addEventListener('mousedown', close);
      return () => document.removeEventListener('mousedown', close);
    }, [showAiMenu]);

    // Close Ask AI panel on outside click or Escape
    useEffect(() => {
      if (!showAskAi) return;
      const close = (e: MouseEvent) => {
        if (!askAiWrapRef.current?.contains(e.target as Node)) setShowAskAi(false);
      };
      const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setShowAskAi(false); };
      document.addEventListener('mousedown', close);
      document.addEventListener('keydown', onKey);
      return () => {
        document.removeEventListener('mousedown', close);
        document.removeEventListener('keydown', onKey);
      };
    }, [showAskAi]);

    // Fetch candidates when the mention query changes. The endpoint — not this component —
    // decides who is addressable: a portal user gets their own organization plus whoever is
    // already on this thread, never another organization's users.
    const mentionVulnerabilityId = mentionContext?.vulnerabilityId;
    const mentionApplicationId = mentionContext?.applicationId;
    useEffect(() => {
      if (mentionQuery === null) { setMentionUsers([]); return; }
      if (mentionFetchRef.current) clearTimeout(mentionFetchRef.current);
      mentionFetchRef.current = setTimeout(async () => {
        try {
          const res = await mentionsApi.getCandidates(mentionQuery, {
            vulnerabilityId: mentionVulnerabilityId,
            applicationId: mentionApplicationId,
          });
          setMentionUsers((res.data ?? []).map(u => ({ username: u.username, display: u.displayName })));
          setMentionHighlight(0);
        } catch { setMentionUsers([]); }
      }, 150);
    }, [mentionQuery, mentionVulnerabilityId, mentionApplicationId]);

    // Close mention dropdown on outside click
    useEffect(() => {
      if (mentionQuery === null) return;
      const close = (e: MouseEvent) => {
        const target = e.target as Node;
        // Clicks inside the markdown editor are left to detectCmMention, which runs on
        // the resulting selection change — closing here would fight it.
        if (cmContainerRef.current?.contains(target)) return;
        if (!mentionDropdownRef.current?.contains(target) && target !== editorRef.current) {
          setMentionQuery(null);
        }
      };
      document.addEventListener('mousedown', close);
      return () => document.removeEventListener('mousedown', close);
    }, [mentionQuery]);

    // \u2500\u2500 Undo / redo history \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    function commitHistory(html: string) {
      const h = historyRef.current;
      if (h.stack[h.index] === html) return;
      h.stack = h.stack.slice(0, h.index + 1);
      h.stack.push(html);
      if (h.stack.length > MAX_HISTORY) h.stack.shift();
      h.index = h.stack.length - 1;
    }

    function restoreFromHistory(html: string) {
      // Markdown mode: dispatch the derived markdown text into CodeMirror. Its
      // updateListener (markdownDocChanged) then syncs the hidden rich-text editor and
      // fires onChange for us — guarded by isApplyingHistoryRef so it doesn't also
      // push a duplicate entry onto the undo stack.
      if (viewMode !== 'rich') {
        const view = cmViewRef.current;
        if (!view) return;
        const markdown = htmlToMarkdown(html);
        isApplyingHistoryRef.current = true;
        view.dispatch({
          changes: { from: 0, to: view.state.doc.length, insert: markdown },
          selection: { anchor: markdown.length },
        });
        isApplyingHistoryRef.current = false;
        view.focus();
        return;
      }

      const editor = editorRef.current;
      if (!editor) return;
      isApplyingHistoryRef.current = true;
      editor.innerHTML = html;
      isApplyingHistoryRef.current = false;
      onChangeRef.current?.(html);

      // Best-effort cursor placement: end of content.
      const range = document.createRange();
      range.selectNodeContents(editor);
      range.collapse(false);
      const sel = window.getSelection();
      sel?.removeAllRanges();
      sel?.addRange(range);
    }

    function undo() {
      if (typingDebounceRef.current) { clearTimeout(typingDebounceRef.current); typingDebounceRef.current = null; }
      const h = historyRef.current;
      if (h.index <= 0) return;
      h.index--;
      restoreFromHistory(h.stack[h.index]);
    }

    function redo() {
      if (typingDebounceRef.current) { clearTimeout(typingDebounceRef.current); typingDebounceRef.current = null; }
      const h = historyRef.current;
      if (h.index >= h.stack.length - 1) return;
      h.index++;
      restoreFromHistory(h.stack[h.index]);
    }

    // `coalesce: true` (used for regular typing) delays the history checkpoint until
    // typing pauses, so a burst of keystrokes undoes as one step instead of one per
    // character. Every other caller (toolbar buttons, table ops, image ops, paste,
    // markdown shortcuts, etc.) is a single discrete action and checkpoints immediately.
    function emit(opts?: { coalesce?: boolean }) {
      const editor = editorRef.current;
      if (!editor) return;
      const html = normalizeBlocks(editor.innerHTML.replace(/\u200B/g, ''));

      if (!isApplyingHistoryRef.current) {
        if (opts?.coalesce) {
          if (typingDebounceRef.current) clearTimeout(typingDebounceRef.current);
          typingDebounceRef.current = setTimeout(() => {
            typingDebounceRef.current = null;
            if (editorRef.current) commitHistory(normalizeBlocks(editorRef.current.innerHTML.replace(/\u200B/g, '')));
          }, TYPING_COALESCE_MS);
        } else {
          if (typingDebounceRef.current) { clearTimeout(typingDebounceRef.current); typingDebounceRef.current = null; }
          commitHistory(html);
        }
      }

      onChangeRef.current?.(html);
    }

    // ── Markdown source view ─────────────────────────────────────────────────

    function closeAllPopups() {
      setShowColorPalette(false);
      setShowLinkInput(false);
      setImgMenu(null);
      setTableMenu(null);
      setShowTablePicker(false);
      setMentionQuery(null);
    }

    // ── AI actions ───────────────────────────────────────────────────────────

    function toggleAiMenu() {
      setShowAskAi(false);
      setAiError(null);
      setShowAiMenu(v => !v);
      // Lazy-load the prompt list on first open
      if (aiPrompts === null && aiContext && !aiPromptsLoading) {
        setAiPromptsLoading(true);
        aiApi.getPrompts(aiContext.scope)
          .then(res => setAiPrompts(res.data || []))
          .catch(() => setAiPrompts([]))
          .finally(() => setAiPromptsLoading(false));
      }
    }

    function toggleAskAi() {
      setShowAiMenu(false);
      setAiError(null);
      setShowAskAi(v => !v);
    }

    /**
     * Replaces the editor content with sanitized AI output. In markdown/split view
     * the change goes through CodeMirror so both panes stay in sync; either path
     * commits to the undo history, so Ctrl+Z restores the previous text.
     */
    function applyAiResult(rawHtml: string) {
      const html = DOMPurify.sanitize(rawHtml);
      if (viewMode !== 'rich') {
        const view = cmViewRef.current;
        if (!view) return;
        const md = htmlToMarkdown(html);
        view.dispatch({
          changes: { from: 0, to: view.state.doc.length, insert: md },
          selection: { anchor: md.length },
        });
        return;
      }
      const editor = editorRef.current;
      if (!editor) return;
      editor.innerHTML = applyEditorPresentation(html);
      emit();
    }

    async function runAiPrompt(prompt: AiPromptSummary) {
      if (!aiContext || aiBusy) return;
      setShowAiMenu(false);
      setAiBusy(true);
      setAiError(null);
      try {
        const res = await aiApi.executePrompt({
          promptId: prompt.id,
          assessmentId: aiContext.assessmentId,
          vulnerabilityId: aiContext.vulnerabilityId,
          currentText: editorRef.current?.innerHTML || '',
        });
        if (res.data?.success && res.data.content) {
          applyAiResult(res.data.content);
        } else {
          setAiError(res.data?.message || 'AI generation failed.');
        }
      } catch {
        setAiError('AI request failed. Check your connection and try again.');
      } finally {
        setAiBusy(false);
      }
    }

    async function runAskAi() {
      if (!aiContext || aiBusy || !askAiText.trim()) return;
      setShowAskAi(false);
      setAiBusy(true);
      setAiError(null);
      try {
        const res = await aiApi.ask({
          assessmentId: aiContext.assessmentId,
          vulnerabilityId: aiContext.vulnerabilityId,
          question: askAiText.trim(),
          currentText: editorRef.current?.innerHTML || '',
        });
        if (res.data?.success && res.data.content) {
          applyAiResult(res.data.content);
          setAskAiText('');
        } else {
          setAiError(res.data?.message || 'AI generation failed.');
        }
      } catch {
        setAiError('AI request failed. Check your connection and try again.');
      } finally {
        setAiBusy(false);
      }
    }

    function switchToMarkdownView() {
      closeAllPopups();
      setViewMode('markdown');
    }

    function switchToSplitView() {
      closeAllPopups();
      setViewMode('split');
    }

    function switchToRichView() {
      pendingRichFocusRef.current = true;
      setViewMode('rich');
    }

    // Fired by the CodeMirror instance's updateListener on every doc change (typed or
    // toolbar-driven alike), keeping the hidden rich-text editor and onChange in sync —
    // mirrors handleInput's coalesced-typing history behavior for the rich text side.
    function markdownDocChanged(markdown: string) {
      if (editorRef.current) editorRef.current.innerHTML = markdownToHtml(markdown);
      emit({ coalesce: true });
    }

    // ── Markdown toolbar (CodeMirror-selection based equivalents of the rich text commands) ──

    const MD_LINE_MARKER_RE = /^(#{1,6}\s+|[-*+]\s+|\d+\.\s+|>\s+)/;

    function cmLineRange(view: EditorView): { from: number; to: number } {
      const sel = view.state.selection.main;
      return { from: view.state.doc.lineAt(sel.from).from, to: view.state.doc.lineAt(sel.to).to };
    }

    function mdWrapSelection(before: string, after: string, placeholder: string) {
      const view = cmViewRef.current;
      if (!view) return;
      const { from, to } = view.state.selection.main;
      const selected = from === to ? placeholder : view.state.sliceDoc(from, to);
      view.dispatch({
        changes: { from, to, insert: before + selected + after },
        selection: { anchor: from + before.length, head: from + before.length + selected.length },
      });
      view.focus();
    }

    function mdSetLinePrefix(prefix: string) {
      const view = cmViewRef.current;
      if (!view) return;
      const { from, to } = cmLineRange(view);
      const newBlock = view.state.sliceDoc(from, to)
        .split('\n')
        .map(line => prefix + line.replace(MD_LINE_MARKER_RE, ''))
        .join('\n');
      view.dispatch({ changes: { from, to, insert: newBlock }, selection: { anchor: from, head: from + newBlock.length } });
      view.focus();
    }

    function mdClearLinePrefix() {
      const view = cmViewRef.current;
      if (!view) return;
      const { from, to } = cmLineRange(view);
      const newBlock = view.state.sliceDoc(from, to)
        .split('\n')
        .map(line => line.replace(MD_LINE_MARKER_RE, ''))
        .join('\n');
      view.dispatch({ changes: { from, to, insert: newBlock }, selection: { anchor: from, head: from + newBlock.length } });
      view.focus();
    }

    function mdWrapBlock(before: string, after: string) {
      const view = cmViewRef.current;
      if (!view) return;
      const { from, to } = cmLineRange(view);
      const newBlock = before + view.state.sliceDoc(from, to) + after;
      view.dispatch({ changes: { from, to, insert: newBlock }, selection: { anchor: from, head: from + newBlock.length } });
      view.focus();
    }

    // Unlike mdSetLinePrefix, centering PREPENDS "> " (keeping list/heading markers
    // intact — a centered list stays a list); uncentering strips only the "> ".
    // Legacy <div align="center"> wrappers from the old representation are also
    // removed on uncenter.
    function mdCenterLines() {
      const view = cmViewRef.current;
      if (!view) return;
      const { from, to } = cmLineRange(view);
      const newBlock = view.state.sliceDoc(from, to)
        .split('\n')
        .map(line => (line === '' || /^>(\s|$)/.test(line) ? line : '> ' + line))
        .join('\n');
      view.dispatch({ changes: { from, to, insert: newBlock }, selection: { anchor: from, head: from + newBlock.length } });
      view.focus();
    }

    function mdUncenterLines() {
      const view = cmViewRef.current;
      if (!view) return;
      const { from, to } = cmLineRange(view);
      const newBlock = view.state.sliceDoc(from, to)
        .replace(/<div align="center">\n*/g, '')
        .replace(/\n*<\/div>/g, '')
        .split('\n')
        .map(line => line.replace(/^>\s?/, ''))
        .join('\n');
      view.dispatch({ changes: { from, to, insert: newBlock }, selection: { anchor: from, head: from + newBlock.length } });
      view.focus();
    }

    function mdRemoveLink() {
      const view = cmViewRef.current;
      if (!view) return;
      const { from, to } = view.state.selection.main;
      const selected = view.state.sliceDoc(from, to);
      const match = /^\[([^\]]*)\]\([^)]*\)$/.exec(selected);
      const replacement = match ? match[1] : selected;
      view.dispatch({ changes: { from, to, insert: replacement }, selection: { anchor: from, head: from + replacement.length } });
      view.focus();
    }

    function mdClearFormatting() {
      const view = cmViewRef.current;
      if (!view) return;
      const { from, to } = view.state.selection.main;
      if (from === to) {
        mdClearLinePrefix();
        return;
      }
      const stripped = view.state.sliceDoc(from, to)
        .replace(/\*\*(.*?)\*\*/gs, '$1')
        .replace(/__(.*?)__/gs, '$1')
        .replace(/\*(.*?)\*/gs, '$1')
        .replace(/_(.*?)_/gs, '$1')
        .replace(/`(.*?)`/gs, '$1')
        .replace(/<\/?u>/gi, '')
        .replace(/<span[^>]*>/gi, '')
        .replace(/<\/span>/gi, '')
        .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');
      view.dispatch({ changes: { from, to, insert: stripped }, selection: { anchor: from, head: from + stripped.length } });
      view.focus();
    }

    function execMarkdownFormat(cmd: string) {
      switch (cmd) {
        case 'bold': mdWrapSelection('**', '**', 'bold text'); break;
        case 'italic': mdWrapSelection('*', '*', 'italic text'); break;
        case 'underline': mdWrapSelection('<u>', '</u>', 'underlined text'); break;
        case 'insertUnorderedList': mdSetLinePrefix('- '); break;
        case 'insertOrderedList': mdSetLinePrefix('1. '); break;
        case 'justifyCenter': mdCenterLines(); break;
        case 'justifyLeft': mdUncenterLines(); break;
        case 'unlink': mdRemoveLink(); break;
      }
    }

    function applyMarkdownBlock(tag: string) {
      switch (tag) {
        case 'p': mdClearLinePrefix(); break;
        case 'h1': mdSetLinePrefix('# '); break;
        case 'h2': mdSetLinePrefix('## '); break;
        case 'h3': mdSetLinePrefix('### '); break;
        // Line-boundary wrap (not mdWrapSelection): a fence glued mid-line onto other
        // text (e.g. from a collapsed cursor or a partial-line selection) isn't
        // recognized as a code block by CommonMark and shows up as literal ``` text.
        case 'pre': mdWrapBlock('```\n', '\n```'); break;
      }
    }

    function insertMarkdownTable(rows: number, cols: number) {
      const view = cmViewRef.current;
      if (!view) return;
      const header = '| ' + Array.from({ length: cols }, (_, i) => `Header ${i + 1}`).join(' | ') + ' |';
      const sep = '| ' + Array.from({ length: cols }, () => '---').join(' | ') + ' |';
      const bodyRows = Array.from({ length: Math.max(rows - 1, 1) }, () =>
        '| ' + Array.from({ length: cols }, () => ' ').join(' | ') + ' |'
      );
      const table = [header, sep, ...bodyRows].join('\n');
      const pos = view.state.selection.main.from;
      const docText = view.state.doc.toString();
      const needsLeadingNewline = pos > 0 && docText[pos - 1] !== '\n';
      const insertion = (needsLeadingNewline ? '\n\n' : '') + table + '\n\n';
      view.dispatch({ changes: { from: pos, to: pos, insert: insertion }, selection: { anchor: pos + insertion.length } });
      view.focus();
      setShowTablePicker(false);
    }

    function insertMarkdownImage(url: string, alt: string) {
      const view = cmViewRef.current;
      if (!view) return;
      const pos = view.state.selection.main.from;
      const insertion = `![${alt}](${url})`;
      view.dispatch({ changes: { from: pos, to: pos, insert: insertion }, selection: { anchor: pos + insertion.length } });
      view.focus();
    }

    // ── @mention helpers ──────────────────────────────────────────────────────

    function detectMentionQuery(): string | null {
      // Gate for the rich-text view; the markdown view gates in cmMentionRange.
      if (!mentions) return null;
      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0 || !sel.isCollapsed) return null;
      const range = sel.getRangeAt(0);
      if (range.startContainer.nodeType !== Node.TEXT_NODE) return null;
      const text = (range.startContainer as Text).nodeValue ?? '';
      const before = text.slice(0, range.startOffset);
      const match = /@(\w*)$/.exec(before);
      if (!match) return null;
      // Only trigger if @ is at the start or preceded by whitespace (not mid-word like emails)
      const atIndex = match.index;
      if (atIndex > 0 && !/\s/.test(before[atIndex - 1])) return null;
      return match[1];
    }

    function insertMention(username: string) {
      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0) return;
      const range = sel.getRangeAt(0);
      if (range.startContainer.nodeType !== Node.TEXT_NODE) return;

      const tn = range.startContainer as Text;
      const before = (tn.nodeValue ?? '').slice(0, range.startOffset);
      const atIdx = before.lastIndexOf('@');
      if (atIdx === -1) return;

      // Delete the @query text
      const deleteRange = document.createRange();
      deleteRange.setStart(tn, atIdx);
      deleteRange.setEnd(tn, range.startOffset);
      deleteRange.deleteContents();

      // Insert mention span — built from the shared serialiser so the rich-text and
      // markdown paths always emit byte-identical markup.
      const tpl = document.createElement('template');
      tpl.innerHTML = mentionSpanHtml(username);
      const span = tpl.content.firstElementChild;
      if (!span) return;
      deleteRange.insertNode(span);

      // Place cursor after the span with a trailing space
      const space = document.createTextNode('\u00A0');
      span.parentNode?.insertBefore(space, span.nextSibling);
      const newRange = document.createRange();
      newRange.setStart(space, 1);
      newRange.collapse(true);
      sel.removeAllRanges();
      sel.addRange(newRange);

      setMentionQuery(null);
      emit();
    }

    // ── @mention helpers, markdown/split view ─────────────────────────────────
    // The markdown view is a CodeMirror instance, not the contenteditable, so it needs
    // its own detect/insert/navigate trio. Detection rules are deliberately identical
    // to detectMentionQuery so the two views trigger on exactly the same input.

    /** Locates the `@query` immediately before the cursor in the CodeMirror doc. */
    function cmMentionRange(view: EditorView): { from: number; to: number; query: string } | null {
      if (!mentionsRef.current) return null;
      const sel = view.state.selection.main;
      if (!sel.empty) return null;
      const line = view.state.doc.lineAt(sel.head);
      const before = view.state.sliceDoc(line.from, sel.head);
      const match = /@(\w*)$/.exec(before);
      if (!match) return null;
      // Same guard as the rich view: only at line start or after whitespace, so
      // email addresses and mid-word @ don't open the picker.
      if (match.index > 0 && !/\s/.test(before[match.index - 1])) return null;
      return { from: line.from + match.index, to: sel.head, query: match[1] };
    }

    function detectCmMention(view: EditorView) {
      const found = cmMentionRange(view);
      if (!found) {
        if (mentionQueryRef.current !== null) setMentionQuery(null);
        return;
      }
      setMentionQuery(found.query);
      const coords = view.coordsAtPos(found.to);
      if (coords) setMentionPos({ x: coords.left, y: coords.bottom + 4 });
    }

    function insertMentionInMarkdown(username: string) {
      const view = cmViewRef.current;
      if (!view) return;
      const found = cmMentionRange(view);
      if (!found) return;

      // A plain space, not the rich view's nbsp — an &nbsp; would render literally in
      // the markdown source. The dispatch triggers markdownDocChanged, which re-syncs
      // the hidden rich body and fires onChange.
      const insertion = `${mentionSpanHtml(username)} `;
      view.dispatch({
        changes: { from: found.from, to: found.to, insert: insertion },
        selection: { anchor: found.from + insertion.length },
      });
      setMentionQuery(null);
      view.focus();
    }

    /** True when the picker is open with results, i.e. it owns the arrow/enter keys. */
    function cmMentionIsOpen(): boolean {
      return mentionQueryRef.current !== null && mentionUsersRef.current.length > 0;
    }

    function cmMentionMove(delta: number): boolean {
      if (!cmMentionIsOpen()) return false;
      const last = mentionUsersRef.current.length - 1;
      setMentionHighlight(h => Math.min(Math.max(h + delta, 0), last));
      return true;
    }

    function cmMentionAccept(): boolean {
      if (!cmMentionIsOpen()) return false;
      const user = mentionUsersRef.current[mentionHighlightRef.current];
      if (!user) return false;
      insertMentionInMarkdown(user.username);
      return true;
    }

    function cmMentionDismiss(): boolean {
      if (mentionQueryRef.current === null) return false;
      setMentionQuery(null);
      return true;
    }

    /**
     * Routes a picked user to whichever editor is actually taking input. In split view
     * the rich body is not contenteditable, so markdown/split both go to CodeMirror.
     */
    function acceptMention(username: string) {
      if (viewMode === 'rich') insertMention(username);
      else insertMentionInMarkdown(username);
    }

    function getCursorClientPos(): { x: number; y: number } | null {
      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0) return null;
      const range = sel.getRangeAt(0);
      // Collapsed range getBoundingClientRect() returns zeros — select the preceding char instead
      if (range.collapsed && range.startContainer.nodeType === Node.TEXT_NODE) {
        const tn = range.startContainer as Text;
        if (range.startOffset > 0) {
          const charRange = document.createRange();
          charRange.setStart(tn, range.startOffset - 1);
          charRange.setEnd(tn, range.startOffset);
          const r = charRange.getBoundingClientRect();
          if (r.height > 0) return { x: r.right, y: r.bottom };
        }
      }
      const r = range.getBoundingClientRect();
      if (r.height > 0) return { x: r.left, y: r.bottom };
      return null;
    }

    function handleInput() {
      emit({ coalesce: true });
      const query = detectMentionQuery();
      if (query !== null) {
        setMentionQuery(query);
        const pos = getCursorClientPos();
        if (pos) setMentionPos({ x: pos.x, y: pos.y + 4 });
      } else {
        if (mentionQuery !== null) setMentionQuery(null);
      }
    }

    // ── Table grid helpers ────────────────────────────────────────────────────

    function buildGrid(table: HTMLTableElement): {
      grid: HTMLTableCellElement[][];
      numRows: number;
      numCols: number;
    } {
      const rows = Array.from(table.rows);
      const numRows = rows.length;
      if (numRows === 0) return { grid: [], numRows: 0, numCols: 0 };
      let numCols = 0;
      for (const row of rows) {
        let c = 0;
        for (const cell of Array.from(row.cells)) c += cell.colSpan;
        numCols = Math.max(numCols, c);
      }
      const grid: HTMLTableCellElement[][] = Array.from({ length: numRows }, () =>
        new Array(numCols).fill(null)
      );
      for (let r = 0; r < numRows; r++) {
        let col = 0;
        for (const cell of Array.from(rows[r].cells)) {
          while (col < numCols && grid[r][col] !== null) col++;
          for (let dr = 0; dr < cell.rowSpan; dr++)
            for (let dc = 0; dc < cell.colSpan; dc++)
              if (r + dr < numRows && col + dc < numCols) grid[r + dr][col + dc] = cell;
          col += cell.colSpan;
        }
      }
      return { grid, numRows, numCols };
    }

    function getCellOrigin(
      grid: HTMLTableCellElement[][],
      cell: HTMLTableCellElement,
      numRows: number,
      numCols: number
    ): { r: number; c: number } | null {
      for (let r = 0; r < numRows; r++)
        for (let c = 0; c < numCols; c++)
          if (
            grid[r][c] === cell &&
            (c === 0 || grid[r][c - 1] !== cell) &&
            (r === 0 || grid[r - 1][c] !== cell)
          )
            return { r, c };
      return null;
    }

    // ── Table insert ──────────────────────────────────────────────────────────

    function insertTable(rows: number, cols: number) {
      if (viewMode !== 'rich') {
        insertMarkdownTable(rows, cols);
        return;
      }
      const editor = editorRef.current;
      if (!editor) return;
      editor.focus();

      const table = document.createElement('table');
      table.className = 'rte-table';

      const thead = document.createElement('thead');
      const headerRow = document.createElement('tr');
      for (let c = 0; c < cols; c++) {
        const th = document.createElement('th');
        th.innerHTML = '<br>';
        th.contentEditable = 'true';
        headerRow.appendChild(th);
      }
      thead.appendChild(headerRow);
      table.appendChild(thead);

      const tbody = document.createElement('tbody');
      for (let r = 1; r < rows; r++) {
        const tr = document.createElement('tr');
        for (let c = 0; c < cols; c++) {
          const td = document.createElement('td');
          td.innerHTML = '<br>';
          td.contentEditable = 'true';
          tr.appendChild(td);
        }
        tbody.appendChild(tr);
      }
      table.appendChild(tbody);

      // Find insertion point: after the current block-level element
      const sel = window.getSelection();
      let insertAfter: Element | null = null;
      if (sel && sel.rangeCount > 0) {
        let node: Node | null = sel.getRangeAt(0).startContainer;
        const blockTags = new Set(['P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'DIV', 'LI', 'BLOCKQUOTE', 'PRE', 'UL', 'OL', 'FIGURE']);
        while (node && node !== editor) {
          if (node.nodeType === Node.ELEMENT_NODE && blockTags.has((node as Element).tagName)) {
            insertAfter = node as Element;
            break;
          }
          node = node.parentNode;
        }
      }

      const trailing = document.createElement('p');
      trailing.innerHTML = '<br>';

      if (insertAfter && insertAfter.parentNode === editor) {
        editor.insertBefore(trailing, insertAfter.nextSibling);
        editor.insertBefore(table, trailing);
      } else {
        editor.appendChild(table);
        editor.appendChild(trailing);
      }

      // Focus first cell
      const firstCell = table.querySelector('th, td') as HTMLElement | null;
      if (firstCell) {
        firstCell.focus();
        const range = document.createRange();
        range.setStart(firstCell, 0);
        range.collapse(true);
        const s = window.getSelection();
        s?.removeAllRanges();
        s?.addRange(range);
      }

      emit();
      setShowTablePicker(false);
    }

    // ── Table operations ──────────────────────────────────────────────────────

    function tableAddRowAbove() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const { r } = origin;

      const newRow = document.createElement('tr');
      for (let c = 0; c < numCols; ) {
        const existing = grid[r][c];
        const existingOrigin = getCellOrigin(grid, existing, numRows, numCols);
        if (existingOrigin && existingOrigin.r < r) {
          // Cell spans from above — increment its rowSpan
          existing.rowSpan++;
          c += existing.colSpan;
        } else {
          const td = document.createElement('td');
          td.innerHTML = '<br>';
          td.contentEditable = 'true';
          newRow.appendChild(td);
          c++;
        }
      }

      // Insert into tbody if possible, else thead
      const tbody = table.querySelector('tbody');
      const targetRow = table.rows[r];
      if (targetRow) {
        targetRow.parentNode?.insertBefore(newRow, targetRow);
      } else if (tbody) {
        tbody.appendChild(newRow);
      }

      emit();
      setTableMenu(null);
    }

    function tableAddRowBelow() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const { r } = origin;
      const insertRow = r + cell.rowSpan;

      const newRow = document.createElement('tr');
      for (let c = 0; c < numCols; ) {
        if (insertRow < numRows) {
          const existing = grid[insertRow][c];
          const existingOrigin = getCellOrigin(grid, existing, numRows, numCols);
          if (existingOrigin && existingOrigin.r < insertRow) {
            // Cell spans through insertion point — increment rowSpan
            existing.rowSpan++;
            c += existing.colSpan;
            continue;
          }
        }
        const td = document.createElement('td');
        td.innerHTML = '<br>';
        td.contentEditable = 'true';
        newRow.appendChild(td);
        c++;
      }

      const tbody = table.querySelector('tbody');
      if (insertRow < table.rows.length) {
        table.rows[insertRow].parentNode?.insertBefore(newRow, table.rows[insertRow]);
      } else if (tbody) {
        tbody.appendChild(newRow);
      } else {
        table.appendChild(newRow);
      }

      emit();
      setTableMenu(null);
    }

    function tableDeleteRow() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const { r } = origin;

      // For cells that span down from this row, decrease their rowSpan
      for (let c = 0; c < numCols; c++) {
        const gc = grid[r][c];
        if (!gc) continue;
        const go = getCellOrigin(grid, gc, numRows, numCols);
        if (go && go.r === r && gc.rowSpan > 1) {
          gc.rowSpan--;
          // Move cell to next row
          const nextRow = table.rows[r + 1];
          if (nextRow) {
            // Find insertion position in next row
            let insertBefore: HTMLTableCellElement | null = null;
            for (let nc = go.c + gc.colSpan; nc < numCols; nc++) {
              const neighbor = grid[r + 1][nc];
              const ngo = neighbor ? getCellOrigin(grid, neighbor, numRows, numCols) : null;
              if (ngo && ngo.r === r + 1) {
                insertBefore = neighbor;
                break;
              }
            }
            if (insertBefore) {
              nextRow.insertBefore(gc, insertBefore);
            } else {
              nextRow.appendChild(gc);
            }
          }
        }
      }

      table.rows[r].remove();

      // If table is empty, remove it
      if (table.rows.length === 0) {
        const p = document.createElement('p');
        p.innerHTML = '<br>';
        table.parentNode?.insertBefore(p, table);
        table.remove();
        const range = document.createRange();
        range.setStart(p, 0);
        range.collapse(true);
        const s = window.getSelection();
        s?.removeAllRanges();
        s?.addRange(range);
      }

      emit();
      setTableMenu(null);
    }

    function tableAddColLeft() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const insertCol = origin.c;

      for (let r = 0; r < numRows; r++) {
        const gc = grid[r][insertCol];
        if (!gc) {
          // Add new cell
          const newCell = document.createElement(table.rows[r].closest('thead') ? 'th' : 'td');
          newCell.innerHTML = '<br>';
          newCell.contentEditable = 'true';
          table.rows[r].insertBefore(newCell, table.rows[r].cells[0]);
          continue;
        }
        const go = getCellOrigin(grid, gc, numRows, numCols);
        if (go && go.c < insertCol) {
          // Cell spans from left — increment colSpan (but only once per cell)
          if (go.r === r) gc.colSpan++;
        } else if (go && go.r === r) {
          // Insert new cell before this one
          const isHeader = gc.tagName === 'TH';
          const newCell = document.createElement(isHeader ? 'th' : 'td');
          newCell.innerHTML = '<br>';
          newCell.contentEditable = 'true';
          gc.parentNode?.insertBefore(newCell, gc);
        }
      }

      emit();
      setTableMenu(null);
    }

    function tableAddColRight() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const insertCol = origin.c + cell.colSpan; // insert after the cell's right edge

      for (let r = 0; r < numRows; r++) {
        if (insertCol >= numCols) {
          // Append new cell at end of row
          const isHeader = !!table.rows[r].closest('thead');
          const newCell = document.createElement(isHeader ? 'th' : 'td');
          newCell.innerHTML = '<br>';
          newCell.contentEditable = 'true';
          table.rows[r].appendChild(newCell);
          continue;
        }
        const gc = grid[r][insertCol];
        if (!gc) {
          const isHeader = !!table.rows[r].closest('thead');
          const newCell = document.createElement(isHeader ? 'th' : 'td');
          newCell.innerHTML = '<br>';
          newCell.contentEditable = 'true';
          table.rows[r].appendChild(newCell);
          continue;
        }
        const go = getCellOrigin(grid, gc, numRows, numCols);
        if (go && go.c < insertCol) {
          // Cell spans through insertion point — increment colSpan (once per cell)
          if (go.r === r) gc.colSpan++;
        } else if (go && go.r === r) {
          // Insert new cell before this cell
          const isHeader = gc.tagName === 'TH';
          const newCell = document.createElement(isHeader ? 'th' : 'td');
          newCell.innerHTML = '<br>';
          newCell.contentEditable = 'true';
          gc.parentNode?.insertBefore(newCell, gc);
        }
      }

      emit();
      setTableMenu(null);
    }

    function tableDeleteCol() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const { c } = origin;

      for (let r = 0; r < numRows; r++) {
        for (let dc = 0; dc < cell.colSpan; dc++) {
          const col = c + dc;
          if (col >= numCols) continue;
          const gc = grid[r][col];
          if (!gc) continue;
          const go = getCellOrigin(grid, gc, numRows, numCols);
          if (!go) continue;
          if (go.r !== r) continue; // already processed via rowSpan
          if (gc.colSpan > 1) {
            gc.colSpan--;
          } else {
            gc.remove();
          }
        }
      }

      // Check if table has no columns left
      if (table.rows.length > 0 && table.rows[0].cells.length === 0) {
        const p = document.createElement('p');
        p.innerHTML = '<br>';
        table.parentNode?.insertBefore(p, table);
        table.remove();
        const range = document.createRange();
        range.setStart(p, 0);
        range.collapse(true);
        const s = window.getSelection();
        s?.removeAllRanges();
        s?.addRange(range);
      }

      emit();
      setTableMenu(null);
    }

    function tableMergeRight() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const { r, c } = origin;
      const rightCol = c + cell.colSpan;
      if (rightCol >= numCols) return;
      const neighbor = grid[r][rightCol];
      if (!neighbor) return;
      if (neighbor.rowSpan !== cell.rowSpan) return;
      // Append neighbor content
      const neighborContent = neighbor.innerHTML.replace(/<br\s*\/?>/gi, '').trim();
      if (neighborContent) {
        const currentContent = cell.innerHTML.replace(/<br\s*\/?>/gi, '').trim();
        cell.innerHTML = (currentContent ? currentContent + ' ' : '') + neighborContent;
      }
      cell.colSpan += neighbor.colSpan;
      neighbor.remove();
      emit();
      setTableMenu(null);
    }

    function tableMergeDown() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const { r, c } = origin;
      const belowRow = r + cell.rowSpan;
      if (belowRow >= numRows) return;
      const below = grid[belowRow][c];
      if (!below) return;
      if (below.colSpan !== cell.colSpan) return;
      // Append below content
      const belowContent = below.innerHTML.replace(/<br\s*\/?>/gi, '').trim();
      if (belowContent) {
        const currentContent = cell.innerHTML.replace(/<br\s*\/?>/gi, '').trim();
        cell.innerHTML = (currentContent ? currentContent + ' ' : '') + belowContent;
      }
      cell.rowSpan += below.rowSpan;
      below.remove();
      emit();
      setTableMenu(null);
    }

    function tableSplitHorizontal() {
      if (!tableMenu) return;
      const { cell } = tableMenu;
      if (cell.colSpan <= 1) return;
      cell.colSpan--;
      const newCell = document.createElement(cell.tagName.toLowerCase() as 'td' | 'th');
      newCell.innerHTML = '<br>';
      newCell.contentEditable = 'true';
      cell.parentNode?.insertBefore(newCell, cell.nextSibling);
      emit();
      setTableMenu(null);
    }

    function tableSplitVertical() {
      if (!tableMenu) return;
      const { cell, table } = tableMenu;
      if (cell.rowSpan <= 1) return;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return;
      const { r, c } = origin;
      cell.rowSpan--;
      const newRowIndex = r + cell.rowSpan;
      const targetRow = table.rows[newRowIndex];
      if (!targetRow) return;
      const newCell = document.createElement(cell.tagName.toLowerCase() as 'td' | 'th');
      newCell.innerHTML = '<br>';
      newCell.contentEditable = 'true';
      // Find insertion position
      let insertBefore: HTMLTableCellElement | null = null;
      for (let nc = c + cell.colSpan; nc < numCols; nc++) {
        const neighbor = grid[newRowIndex][nc];
        if (!neighbor) continue;
        const ngo = getCellOrigin(grid, neighbor, numRows, numCols);
        if (ngo && ngo.r === newRowIndex) {
          insertBefore = neighbor;
          break;
        }
      }
      if (insertBefore) {
        targetRow.insertBefore(newCell, insertBefore);
      } else {
        targetRow.appendChild(newCell);
      }
      emit();
      setTableMenu(null);
    }

    // ── Image upload ──────────────────────────────────────────────────────────

    async function uploadAndInsert(file: File) {
      const upload = onImageUploadRef.current;
      if (!upload) return;
      try {
        const url = await upload(await bakeImageBorder(file));
        if (viewMode !== 'rich') {
          insertMarkdownImage(url, file.name);
          return;
        }
        editorRef.current?.focus();
        const img = document.createElement('img');
        img.src = url;
        img.alt = file.name;
        img.style.maxWidth = '100%';
        // Inline (not editor CSS) so the centering carries into saved HTML and reports
        img.style.display = 'block';
        img.style.marginLeft = 'auto';
        img.style.marginRight = 'auto';
        const sel = window.getSelection();
        if (sel && sel.rangeCount > 0) {
          const range = sel.getRangeAt(0);
          range.deleteContents();
          range.insertNode(img);
          range.setStartAfter(img);
          range.collapse(true);
          sel.removeAllRanges();
          sel.addRange(range);
        } else {
          editorRef.current?.appendChild(img);
        }
        emit();
      } catch {
        // Upload failed — do not insert anything
      }
    }

    // Heuristics for "this plain-text paste is actually markdown source"
    const MARKDOWN_PATTERNS = [
      /^#{1,6}\s+\S/m,            // # Heading
      /\*\*[^*\n]+\*\*/,          // **bold**
      /(^|\s)_[^_\n]+_(\s|$)/,    // _italic_
      /^\s{0,3}[-*+]\s+\S/m,      // - bullet / * bullet
      /^\s{0,3}\d+\.\s+\S/m,      // 1. numbered
      /^\s{0,3}>\s?\S/m,          // > blockquote
      /```/,                      // fenced code block
      /\[[^\]]+\]\([^)\s]+\)/,    // [text](url)
      /^\s*\|.+\|\s*$/m,          // | table | row |
    ];

    function looksLikeMarkdown(text: string): boolean {
      return MARKDOWN_PATTERNS.some(pattern => pattern.test(text));
    }

    function insertHtmlAtCursor(html: string) {
      const editor = editorRef.current;
      if (!editor) return;
      editor.focus();
      const sel = window.getSelection();
      const template = document.createElement('template');
      template.innerHTML = html;
      const frag = template.content;
      const lastNode = frag.lastChild;
      if (sel && sel.rangeCount > 0 && editor.contains(sel.getRangeAt(0).startContainer)) {
        const range = sel.getRangeAt(0);
        range.deleteContents();
        range.insertNode(frag);
      } else {
        editor.appendChild(frag);
      }
      if (lastNode) {
        const newRange = document.createRange();
        newRange.setStartAfter(lastNode);
        newRange.collapse(true);
        sel?.removeAllRanges();
        sel?.addRange(newRange);
      }
    }

    function handlePaste(e: React.ClipboardEvent) {
      if (isReadOnly) return;

      const items = Array.from(e.clipboardData.items);
      const imageItem = onImageUploadRef.current ? items.find(item => item.type.startsWith('image/')) : undefined;
      if (imageItem) {
        e.preventDefault();
        const file = imageItem.getAsFile();
        if (file) uploadAndInsert(file);
        return;
      }

      // Real HTML on the clipboard (copied from another rich-text app/page) —
      // let the browser's native paste insert it as-is.
      if (e.clipboardData.types.includes('text/html')) return;

      // Plain text that reads as markdown source — render it to rich text instead
      // of dropping the raw "**bold**" / "# Heading" syntax into the document.
      const text = e.clipboardData.getData('text/plain');
      if (!text || !looksLikeMarkdown(text)) return;

      e.preventDefault();
      // markdownToHtml, not a bare marked.parse: it re-applies the editor's
      // presentation (.rte-table class, image centering) that raw markdown can't carry.
      insertHtmlAtCursor(markdownToHtml(text));
      emit();
    }

    function handleDrop(e: React.DragEvent) {
      if (isReadOnly || !onImageUploadRef.current) return;
      const files = Array.from(e.dataTransfer.files).filter(f => f.type.startsWith('image/'));
      if (files.length === 0) return;
      e.preventDefault();
      files.forEach(uploadAndInsert);
    }

    function handleContextMenu(e: React.MouseEvent) {
      // Split view: the rich pane is a read-only preview — its table/image context
      // menus mutate the rich DOM directly, which would desync from the CodeMirror
      // source of truth.
      if (isReadOnly || viewMode === 'split') return;
      let node: Node | null = e.target as Node;

      // Check for image first
      while (node && node !== editorRef.current) {
        if (node instanceof HTMLImageElement) {
          e.preventDefault();
          setImgMenu({
            img: node,
            x: Math.min(e.clientX, window.innerWidth - 170),
            y: Math.min(e.clientY, window.innerHeight - 160),
          });
          return;
        }
        node = node.parentNode;
      }

      // Check for table cell
      node = e.target as Node;
      while (node && node !== editorRef.current) {
        if (node instanceof HTMLTableCellElement) {
          e.preventDefault();
          const cellEl = node as HTMLTableCellElement;
          const tableEl = cellEl.closest('table') as HTMLTableElement | null;
          if (tableEl) {
            setTableMenu({
              x: Math.min(e.clientX, window.innerWidth - 210),
              y: Math.min(e.clientY, window.innerHeight - 320),
              cell: cellEl,
              table: tableEl,
            });
          }
          return;
        }
        node = node.parentNode;
      }
    }

    function applyImageSize(width: string) {
      if (!imgMenu) return;
      imgMenu.img.style.width = width;
      emit();
      setImgMenu(null);
    }

    function applyCaption() {
      if (!imgMenu) return;
      const img = imgMenu.img;
      let figure = img.closest('figure');
      if (!figure) {
        figure = document.createElement('figure');
        img.parentNode?.insertBefore(figure, img);
        figure.appendChild(img);
        const cap = document.createElement('figcaption');
        figure.appendChild(cap);
      }
      // Place cursor inside the figcaption
      const cap = figure.querySelector('figcaption');
      if (cap) {
        const range = document.createRange();
        range.selectNodeContents(cap);
        range.collapse(false);
        const sel = window.getSelection();
        sel?.removeAllRanges();
        sel?.addRange(range);
        (cap as HTMLElement).focus();
      }
      emit();
      setImgMenu(null);
    }

    function deleteImage() {
      if (!imgMenu) return;
      const img = imgMenu.img;
      // Remove the whole <figure> if the image is the only meaningful child
      const figure = img.closest('figure');
      if (figure) {
        figure.remove();
      } else {
        img.remove();
      }
      emit();
      setImgMenu(null);
    }

    function replaceImage() {
      if (!imgMenu) return;
      replaceTargetRef.current = imgMenu.img;
      setImgMenu(null);
      fileInputRef.current?.click();
    }

    // ── Format helpers ────────────────────────────────────────────────────────

    function execFormat(e: React.MouseEvent, cmd: string, value?: string) {
      e.preventDefault();
      if (viewMode !== 'rich') {
        execMarkdownFormat(cmd);
        return;
      }
      editorRef.current?.focus();
      document.execCommand(cmd, false, value);
      emit();
    }

    function clearFormatting(e: React.MouseEvent) {
      e.preventDefault();
      if (viewMode !== 'rich') {
        mdClearFormatting();
        return;
      }
      editorRef.current?.focus();

      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0 || !editorRef.current) return;
      const range = sel.getRangeAt(0);
      const editor = editorRef.current;

      const BLOCK_TAGS = new Set(['H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'PRE', 'BLOCKQUOTE']);

      // Replace a block element with a plain <p>, keeping its text
      function convertToP(el: Element): HTMLParagraphElement {
        const p = document.createElement('p');
        const text = el.textContent ?? '';
        if (text.trim()) { p.textContent = text; } else { p.innerHTML = '<br>'; }
        el.parentNode?.replaceChild(p, el);
        return p;
      }

      // Lift a <li> out of its list into a plain <p>
      function liftListItem(li: Element): HTMLParagraphElement {
        const p = document.createElement('p');
        const text = li.textContent ?? '';
        if (text.trim()) { p.textContent = text; } else { p.innerHTML = '<br>'; }
        const list = li.closest('ul, ol');
        if (list) {
          list.parentNode?.insertBefore(p, list.nextSibling);
          li.remove();
          if (!list.querySelector('li')) list.remove();
        } else {
          li.parentNode?.replaceChild(p, li);
        }
        return p;
      }

      function placeCursor(el: Element) {
        const r = document.createRange();
        r.setStart(el, 0);
        r.collapse(true);
        const s = window.getSelection();
        s?.removeAllRanges();
        s?.addRange(r);
      }

      // ── Collapsed cursor: clear the block the cursor is in ───────────────
      if (range.collapsed) {
        let n: Node | null = range.startContainer;
        while (n && n !== editor) {
          if (n.nodeType === Node.ELEMENT_NODE) {
            const el = n as Element;
            if (BLOCK_TAGS.has(el.tagName)) {
              placeCursor(convertToP(el));
              emit();
              return;
            }
            if (el.tagName === 'LI') {
              placeCursor(liftListItem(el));
              emit();
              return;
            }
          }
          n = n.parentNode;
        }
        // Plain block — just strip inline formatting
        document.execCommand('removeFormat', false);
        emit();
        return;
      }

      // ── Range selection: collect all special blocks that intersect ───────
      const toConvert: Element[] = [];

      function walk(node: Node) {
        if (node.nodeType !== Node.ELEMENT_NODE) return;
        const el = node as Element;
        const tag = el.tagName;
        if (BLOCK_TAGS.has(tag) || tag === 'LI') {
          try {
            const elRange = document.createRange();
            elRange.selectNode(el);
            const startBeforeEnd = range.compareBoundaryPoints(Range.END_TO_START, elRange) < 0;
            const endAfterStart = range.compareBoundaryPoints(Range.START_TO_END, elRange) > 0;
            if (startBeforeEnd && endAfterStart) { toConvert.push(el); return; }
          } catch { /* skip */ }
        }
        el.childNodes.forEach(walk);
      }
      walk(editor);

      // Strip inline formatting on selection first
      document.execCommand('removeFormat', false);

      // Convert block elements (references still valid — removeFormat only touches inline nodes)
      let lastP: HTMLParagraphElement | null = null;
      for (const el of toConvert) {
        // element may have been removed by a previous iteration (nested LI / duplicate)
        if (!editor.contains(el)) continue;
        lastP = el.tagName === 'LI' ? liftListItem(el) : convertToP(el);
      }

      if (lastP) placeCursor(lastP);
      emit();
    }

    function applyBlock(e: React.MouseEvent, tag: string) {
      e.preventDefault();
      if (viewMode !== 'rich') {
        applyMarkdownBlock(tag);
        return;
      }
      editorRef.current?.focus();
      document.execCommand('formatBlock', false, tag);
      emit();
    }

    function recordRecentColor(color: string) {
      setRecentColors(prev => {
        const next = [color, ...prev.filter(c => c !== color)].slice(0, 4);
        try { localStorage.setItem('rte-recent-colors', JSON.stringify(next)); } catch { /* ignore */ }
        return next;
      });
    }

    function applyColor(e: React.MouseEvent, color: string) {
      e.preventDefault();
      if (viewMode !== 'rich') {
        mdWrapSelection(`<span style="color: ${color}">`, '</span>', 'colored text');
        recordRecentColor(color);
        setShowColorPalette(false);
        return;
      }
      editorRef.current?.focus();
      document.execCommand('foreColor', false, color);
      emit();
      setShowColorPalette(false);
    }

    function saveSelection() {
      const sel = window.getSelection();
      if (sel && sel.rangeCount > 0) savedRangeRef.current = sel.getRangeAt(0).cloneRange();
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
      if (viewMode !== 'rich') {
        setLinkUrl('');
        setShowLinkInput(true);
        return;
      }
      saveSelection();
      const sel = window.getSelection();
      let existing = '';
      if (sel?.anchorNode) {
        let node: Node | null = sel.anchorNode;
        while (node && node !== editorRef.current) {
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
      if (viewMode !== 'rich') {
        mdWrapSelection('[', `](${href})`, 'link text');
        setShowLinkInput(false);
        setLinkUrl('');
        return;
      }
      editorRef.current?.focus();
      restoreSelection();
      document.execCommand('createLink', false, href);
      editorRef.current?.querySelectorAll('a').forEach(a => {
        a.setAttribute('target', '_blank');
        a.setAttribute('rel', 'noopener noreferrer');
      });
      emit();
      setShowLinkInput(false);
      setLinkUrl('');
    }

    function applyInlineCode(e: React.MouseEvent) {
      e.preventDefault();
      if (viewMode !== 'rich') {
        mdWrapSelection('`', '`', 'code');
        return;
      }
      editorRef.current?.focus();
      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0) return;
      const range = sel.getRangeAt(0);
      if (range.collapsed) return;

      let codeEl: HTMLElement | null = null;
      let node: Node | null = range.commonAncestorContainer;
      while (node && node !== editorRef.current) {
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
        const beforeRange = document.createRange();
        beforeRange.setStart(codeEl, 0);
        beforeRange.setEnd(range.startContainer, range.startOffset);
        const afterRange = document.createRange();
        afterRange.setStart(range.endContainer, range.endOffset);
        afterRange.setEnd(codeEl, codeEl.childNodes.length);
        const replacement = document.createDocumentFragment();
        if (!beforeRange.collapsed) {
          const bc = document.createElement('code');
          bc.appendChild(beforeRange.cloneContents());
          replacement.appendChild(bc);
        }
        replacement.appendChild(range.cloneContents());
        if (!afterRange.collapsed) {
          const ac = document.createElement('code');
          ac.appendChild(afterRange.cloneContents());
          replacement.appendChild(ac);
        }
        codeEl.parentNode!.replaceChild(replacement, codeEl);
        emit();
        return;
      }

      const code = document.createElement('code');
      try {
        range.surroundContents(code);
      } catch {
        const fragment = range.extractContents();
        code.appendChild(fragment);
        range.insertNode(code);
      }
      emit();
    }

    // ── Markdown keyboard shortcuts ───────────────────────────────────────────

    function blockAncestor(startNode: Node | null): Element | null {
      const blockTags = new Set(['P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'DIV', 'LI', 'BLOCKQUOTE']);
      let n: Node | null = startNode;
      while (n && n !== editorRef.current) {
        if (n.nodeType === Node.ELEMENT_NODE && blockTags.has((n as Element).tagName)) return n as Element;
        n = n.parentNode;
      }
      return editorRef.current;
    }

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

    // Helper: find the table cell ancestor of a node
    function findTableCell(node: Node | null): HTMLTableCellElement | null {
      let n = node;
      while (n && n !== editorRef.current) {
        if (n instanceof HTMLTableCellElement) return n;
        n = n.parentNode;
      }
      return null;
    }

    // Helper: all cells in DOM order within a table
    function allCells(table: HTMLTableElement): HTMLTableCellElement[] {
      return Array.from(table.querySelectorAll('th, td'));
    }

    // Undo / redo — takes over from the browser's native contenteditable undo (which
    // never sees direct DOM mutations like table row/column edits) and from CodeMirror,
    // which deliberately has no history()/historyKeymap of its own (see the CodeMirror
    // setup effect) so this single cross-mode stack is the only undo path in either view.
    function tryHandleUndoRedoKey(e: React.KeyboardEvent): boolean {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) redo(); else undo();
        return true;
      }
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'y') {
        e.preventDefault();
        redo();
        return true;
      }
      return false;
    }

    // Alt+W toggles between the WYSIWYG and Markdown source views. Not Ctrl+W — browsers
    // reserve that to close the current tab and never let a page override it.
    function tryHandleViewToggleKey(e: React.KeyboardEvent): boolean {
      if (e.altKey && !e.ctrlKey && !e.metaKey && e.key.toLowerCase() === 'w') {
        e.preventDefault();
        if (viewMode === 'rich') switchToMarkdownView();
        else if (viewMode === 'markdown') switchToSplitView();
        else switchToRichView();
        return true;
      }
      return false;
    }

    // Attached to the CodeMirror container div — CodeMirror's own keymap never binds
    // Ctrl+Z/Ctrl+Y, so the native keydown event bubbles up to this React handler.
    function handleMarkdownKeyDown(e: React.KeyboardEvent) {
      if (isReadOnly) return;
      if (tryHandleUndoRedoKey(e)) return;
      tryHandleViewToggleKey(e);
    }

    function handleKeyDown(e: React.KeyboardEvent) {
      if (isReadOnly) return;
      if (tryHandleUndoRedoKey(e)) return;
      if (tryHandleViewToggleKey(e)) return;

      // @mention dropdown navigation
      if (mentionQuery !== null && mentionUsers.length > 0) {
        if (e.key === 'ArrowDown') { e.preventDefault(); setMentionHighlight(h => Math.min(h + 1, mentionUsers.length - 1)); return; }
        if (e.key === 'ArrowUp') { e.preventDefault(); setMentionHighlight(h => Math.max(h - 1, 0)); return; }
        if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); insertMention(mentionUsers[mentionHighlight].username); return; }
        if (e.key === 'Escape') { setMentionQuery(null); return; }
      }

      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0) return;

      const inCodeOrPre = (): boolean => {
        let n: Node | null = sel.anchorNode;
        while (n && n !== editorRef.current) {
          if (n.nodeType === Node.ELEMENT_NODE) {
            const tag = (n as Element).tagName;
            if (tag === 'CODE' || tag === 'PRE') return true;
          }
          n = n.parentNode;
        }
        return false;
      };

      // ── Tab: table navigation or list indent/outdent ──────────────────────
      if (e.key === 'Tab') {
        // Check if we're inside a table cell first
        const tableCell = findTableCell(sel.anchorNode);
        if (tableCell) {
          e.preventDefault();
          const table = tableCell.closest('table') as HTMLTableElement | null;
          if (!table) return;
          const cells = allCells(table);
          const idx = cells.indexOf(tableCell);
          if (e.shiftKey) {
            // Move to previous cell
            if (idx > 0) {
              const prevCell = cells[idx - 1];
              prevCell.focus();
              const range = document.createRange();
              range.selectNodeContents(prevCell);
              range.collapse(false);
              sel.removeAllRanges();
              sel.addRange(range);
            }
          } else {
            // Move to next cell
            if (idx < cells.length - 1) {
              const nextCell = cells[idx + 1];
              nextCell.focus();
              const range = document.createRange();
              range.selectNodeContents(nextCell);
              range.collapse(false);
              sel.removeAllRanges();
              sel.addRange(range);
            } else {
              // Last cell — append new row to tbody
              const tbody = table.querySelector('tbody') ?? table;
              const firstBodyRow = (table.querySelector('tbody tr') ?? table.querySelector('tr')) as HTMLTableRowElement | null;
              const colCount = firstBodyRow ? Array.from(firstBodyRow.cells).reduce((sum, cell) => sum + cell.colSpan, 0) : 1;
              const newRow = document.createElement('tr');
              for (let i = 0; i < colCount; i++) {
                const td = document.createElement('td');
                td.innerHTML = '<br>';
                td.contentEditable = 'true';
                newRow.appendChild(td);
              }
              tbody.appendChild(newRow);
              const firstNewCell = newRow.cells[0];
              firstNewCell.focus();
              const range = document.createRange();
              range.setStart(firstNewCell, 0);
              range.collapse(true);
              sel.removeAllRanges();
              sel.addRange(range);
              emit();
            }
          }
          return;
        }

        // List items: Tab nests / Shift+Tab un-nests via the native commands
        let n: Node | null = sel.anchorNode;
        let inList = false;
        while (n && n !== editorRef.current) {
          if (n.nodeType === Node.ELEMENT_NODE && (n as Element).tagName === 'LI') {
            inList = true; break;
          }
          n = n.parentNode;
        }
        if (inList) {
          e.preventDefault();
          document.execCommand(e.shiftKey ? 'outdent' : 'indent', false);
          emit();
          return;
        }

        // Everything else: indent/outdent every top-level block the selection
        // touches by stepping its left margin. Inline margin (not blockquote
        // wrapping) so the indent survives into saved HTML without turndown
        // reading it back as a "> quote" in the markdown view.
        e.preventDefault();
        const editor = editorRef.current;
        if (!editor) return;
        const tabRange = sel.getRangeAt(0);
        // Snapshot targets before any mutation — wrapping a bare text node
        // resets live range endpoints that sit inside it.
        const targets = Array.from(editor.childNodes).filter(node => {
          if (!tabRange.intersectsNode(node)) return false;
          if (node.nodeType === Node.ELEMENT_NODE) return true;
          return node.nodeType === Node.TEXT_NODE && (node.textContent ?? '').trim() !== '';
        });
        let wrapped = false;
        const blocks = targets
          .map(node => {
            if (node.nodeType === Node.TEXT_NODE) {
              // Bare text at the editor root (e.g. the first line of a fresh
              // document) has no block wrapper to carry a margin. Outdent has
              // nothing to remove there; indent wraps it in one.
              if (e.shiftKey) return null;
              const div = document.createElement('div');
              editor.insertBefore(div, node);
              div.appendChild(node);
              wrapped = true;
              return div;
            }
            return node as HTMLElement;
          })
          .filter((el): el is HTMLElement => el !== null);
        if (blocks.length === 0) return;
        const INDENT_STEP_PX = 40;
        blocks.forEach(el => {
          const current = parseInt(el.style.marginLeft || '0', 10) || 0;
          const next = e.shiftKey ? Math.max(0, current - INDENT_STEP_PX) : current + INDENT_STEP_PX;
          el.style.marginLeft = next > 0 ? `${next}px` : '';
        });
        if (wrapped) {
          // Wrapping invalidated the selection — re-span the affected blocks so
          // a repeated Tab keeps operating on the same content.
          const restore = document.createRange();
          restore.setStartBefore(blocks[0]);
          restore.setEndAfter(blocks[blocks.length - 1]);
          sel.removeAllRanges();
          sel.addRange(restore);
        }
        emit();
        return;
      }

      // ── Enter ─────────────────────────────────────────────────────────────
      if (e.key === 'Enter') {
        const range = sel.getRangeAt(0);

        // Table cell: insert <br> instead of new block
        const tableCell = findTableCell(sel.anchorNode);
        if (tableCell) {
          e.preventDefault();
          const br = document.createElement('br');
          range.deleteContents();
          range.insertNode(br);
          range.setStartAfter(br);
          range.collapse(true);
          sel.removeAllRanges();
          sel.addRange(range);
          emit();
          return;
        }

        // Markdown table auto-conversion: detect separator row
        if (range.collapsed && !inCodeOrPre()) {
          const blk = blockAncestor(sel.anchorNode);
          if (blk && blk !== editorRef.current) {
            const blkText = blk.textContent ?? '';
            const sepPattern = /^\s*\|[\s\-:|]+\|\s*$/;
            if (sepPattern.test(blkText)) {
              // Look for header row as previous sibling
              let prevSibling = blk.previousElementSibling;
              const headerPattern = /^\s*\|.+\|\s*$/;
              if (prevSibling && headerPattern.test(prevSibling.textContent ?? '')) {
                e.preventDefault();
                const headerText = prevSibling.textContent ?? '';
                const cols = headerText.split('|').map(s => s.trim()).filter((_s, i, arr) => i > 0 && i < arr.length - 1);
                if (cols.length > 0) {
                  const table = document.createElement('table');
                  table.className = 'rte-table';
                  const thead = document.createElement('thead');
                  const headerRow = document.createElement('tr');
                  for (const col of cols) {
                    const th = document.createElement('th');
                    th.textContent = col;
                    th.contentEditable = 'true';
                    headerRow.appendChild(th);
                  }
                  thead.appendChild(headerRow);
                  table.appendChild(thead);
                  const tbody = document.createElement('tbody');
                  const firstBodyRow = document.createElement('tr');
                  for (let i = 0; i < cols.length; i++) {
                    const td = document.createElement('td');
                    td.innerHTML = '<br>';
                    td.contentEditable = 'true';
                    firstBodyRow.appendChild(td);
                  }
                  tbody.appendChild(firstBodyRow);
                  table.appendChild(tbody);

                  const trailing = document.createElement('p');
                  trailing.innerHTML = '<br>';

                  const parent = blk.parentNode!;
                  parent.insertBefore(table, blk);
                  parent.insertBefore(trailing, blk);
                  prevSibling.remove();
                  blk.remove();

                  // Focus first body cell
                  const firstTd = firstBodyRow.cells[0];
                  firstTd.focus();
                  const newRange = document.createRange();
                  newRange.setStart(firstTd, 0);
                  newRange.collapse(true);
                  sel.removeAllRanges();
                  sel.addRange(newRange);
                  emit();
                  return;
                }
              }
            }
          }
        }

        // Exit <figcaption>: create a plain left-aligned <p> after the <figure>
        let figcap: Element | null = null;
        let n: Node | null = sel.anchorNode;
        while (n && n !== editorRef.current) {
          if (n.nodeType === Node.ELEMENT_NODE && (n as Element).tagName === 'FIGCAPTION') {
            figcap = n as Element; break;
          }
          n = n.parentNode;
        }
        if (figcap) {
          e.preventDefault();
          const figure = figcap.closest('figure') ?? figcap.parentElement;
          const p = document.createElement('p');
          p.style.textAlign = '';
          p.innerHTML = '<br>';
          figure?.parentNode?.insertBefore(p, figure.nextSibling);
          const newRange = document.createRange();
          newRange.setStart(p, 0);
          newRange.collapse(true);
          sel.removeAllRanges();
          sel.addRange(newRange);
          emit();
          return;
        }

        // Heading shortcut: line starting with "#" → heading block
        if (range.collapsed && !inCodeOrPre()) {
          const blk = blockAncestor(sel.anchorNode);
          if (blk) {
            const blkText = blk.textContent || '';
            const headingMatch = /^(#{1,4}) /.exec(blkText);
            if (headingMatch) {
              e.preventDefault();
              const level = headingMatch[1].length;
              const newTag = ['h1', 'h2', 'h3', 'h4'][level - 1];
              const prefixLen = headingMatch[0].length;
              const firstPos = charPosInBlock(blk, 0);
              if (firstPos && firstPos.node.length >= firstPos.offset + prefixLen) {
                firstPos.node.deleteData(firstPos.offset, prefixLen);
              }
              document.execCommand('formatBlock', false, newTag);
              const freshSel = window.getSelection();
              if (freshSel && freshSel.rangeCount > 0) {
                const headingEl = blockAncestor(freshSel.anchorNode);
                if (headingEl && headingEl !== editorRef.current) {
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

        // Exit <pre> / inline <code> on Enter
        let node: Node | null = sel.anchorNode;
        let preEl: Element | null = null;
        let inlineCodeEl: Element | null = null;
        while (node && node !== editorRef.current) {
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
            // A newline at the very end of the <pre> doesn't render a line box
            // under white-space:pre-wrap, so the caret wouldn't visibly move and
            // a second Shift+Enter used to be needed. When inserting at the end,
            // pad with a second newline and keep the caret between the two.
            const tail = document.createRange();
            tail.selectNodeContents(preEl);
            tail.setStart(range.startContainer, range.startOffset);
            const atEnd = tail.toString() === '';
            const nl = document.createTextNode(atEnd ? '\n\n' : '\n');
            range.insertNode(nl);
            range.setStart(nl, 1);
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

      // ── Backspace: prevent cell deletion in empty table cells ─────────────
      if (e.key === 'Backspace') {
        const tableCell = findTableCell(sel.anchorNode);
        if (tableCell) {
          const cellText = tableCell.textContent ?? '';
          if (cellText.trim() === '' || tableCell.innerHTML === '<br>') {
            e.preventDefault();
            return;
          }
        }
      }

      // ── Bold / Italic / Bold+Italic ──────────────────────────────────────
      // *t* / _t_   → <em>      (italic)
      // **t** / __t__ → <strong>  (bold)
      // ***t*** / ___t___ → <strong><em> (bold + italic)
      if (e.key === '*' || e.key === '_') {
        const range = sel.getRangeAt(0);
        if (!range.collapsed || inCodeOrPre()) return;
        if (range.startContainer.nodeType !== Node.TEXT_NODE) return;
        const tn = range.startContainer as Text;
        const textBefore = (tn.nodeValue ?? '').slice(0, range.startOffset);
        const ch = e.key;

        // Count how many `ch` are already at the end of textBefore (partial closing run)
        let closingAlready = 0;
        for (let i = textBefore.length - 1; i >= 0 && textBefore[i] === ch; i--) closingAlready++;
        const totalMarkers = closingAlready + 1; // +1 for the key currently being pressed
        if (totalMarkers > 3) return;

        // The text before the already-typed closing markers
        const textWithoutClosing = textBefore.slice(0, textBefore.length - closingAlready);
        const marker = ch.repeat(totalMarkers);

        // Find the last valid opening marker sequence (not adjacent to more `ch`)
        let openIdx = -1;
        for (let i = textWithoutClosing.length - marker.length; i >= 0; i--) {
          if (textWithoutClosing.slice(i, i + marker.length) !== marker) continue;
          const prevCh = i > 0 ? textWithoutClosing[i - 1] : '';
          const nextCh = textWithoutClosing[i + marker.length] ?? '';
          if (prevCh !== ch && nextCh !== ch) { openIdx = i; break; }
        }
        if (openIdx === -1) return;

        const content = textWithoutClosing.slice(openIdx + marker.length);
        if (content.length === 0 || content.startsWith(' ') || content.endsWith(' ')) return;

        e.preventDefault();

        const applyRange = document.createRange();
        applyRange.setStart(tn, openIdx);
        applyRange.setEnd(tn, range.startOffset); // covers markers + content + partial closing
        applyRange.deleteContents();

        let outerEl: HTMLElement;
        if (totalMarkers === 1) {
          outerEl = document.createElement('em');
          outerEl.textContent = content;
        } else if (totalMarkers === 2) {
          outerEl = document.createElement('strong');
          outerEl.textContent = content;
        } else {
          outerEl = document.createElement('strong');
          const inner = document.createElement('em');
          inner.textContent = content;
          outerEl.appendChild(inner);
        }

        applyRange.insertNode(outerEl);

        const zwsp = document.createTextNode('\u200B');
        outerEl.parentNode?.insertBefore(zwsp, outerEl.nextSibling);
        const r = document.createRange();
        r.setStart(zwsp, 1);
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        emit();
      }

      // ── Plus: ++text++ → <u> (underline) ─────────────────────────────────
      if (e.key === '+') {
        const range = sel.getRangeAt(0);
        if (!range.collapsed || inCodeOrPre()) return;
        if (range.startContainer.nodeType !== Node.TEXT_NODE) return;
        const tn = range.startContainer as Text;
        const textBefore = (tn.nodeValue ?? '').slice(0, range.startOffset);

        // Closing `++`: look for opening `++`
        if (textBefore.endsWith('+')) {
          const withoutLast = textBefore.slice(0, -1);
          const openIdx = withoutLast.lastIndexOf('++');
          if (openIdx !== -1) {
            const content = withoutLast.slice(openIdx + 2);
            if (content.length > 0 && !content.startsWith(' ') && !content.endsWith(' ')) {
              e.preventDefault();
              const uRange = document.createRange();
              uRange.setStart(tn, openIdx);
              uRange.setEnd(tn, range.startOffset);
              uRange.deleteContents();
              const u = document.createElement('u');
              u.textContent = content;
              uRange.insertNode(u);
              const zwsp = document.createTextNode('\u200B');
              u.parentNode?.insertBefore(zwsp, u.nextSibling);
              const r = document.createRange();
              r.setStart(zwsp, 1);
              r.collapse(true);
              sel.removeAllRanges();
              sel.addRange(r);
              emit();
              return;
            }
          }
        }
      }

      // ── Backtick: ``` → code block; `text` → inline code ─────────────────
      if (e.key === '`') {
        const range = sel.getRangeAt(0);
        if (!range.collapsed || inCodeOrPre()) return;
        if (range.startContainer.nodeType !== Node.TEXT_NODE) return;
        const tn = range.startContainer as Text;
        const textBefore = (tn.nodeValue ?? '').slice(0, range.startOffset);

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
        return;
      }

      // ── Space: list shortcuts + triple-space <code> exit ─────────────────
      if (e.key === ' ') {
        const range = sel.getRangeAt(0);

        // "- " or "* " at start of block → bullet list
        // "1. " at start of block → numbered list
        if (range.collapsed && !inCodeOrPre() && range.startContainer.nodeType === Node.TEXT_NODE) {
          const tn = range.startContainer as Text;
          const textBefore = (tn.nodeValue ?? '').slice(0, range.startOffset);
          const blk = blockAncestor(sel.anchorNode);
          // Only trigger when the prefix is the sole content of the block
          if (blk && (blk.textContent ?? '').trimEnd() === textBefore.trimEnd()) {
            let listCmd: string | null = null;
            if (textBefore === '-' || textBefore === '*') listCmd = 'insertUnorderedList';
            else if (textBefore === '1.') listCmd = 'insertOrderedList';
            if (listCmd) {
              e.preventDefault();
              const delRange = document.createRange();
              delRange.setStart(tn, 0);
              delRange.setEnd(tn, textBefore.length);
              delRange.deleteContents();
              if (blk !== editorRef.current && blk.tagName !== 'LI') {
                // Build the list manually. Deleting the prefix leaves the block
                // holding only an empty text node — no valid caret position — and
                // execCommand then normalizes the collapsed selection into the
                // previous block, so the list swallows that block instead (e.g. a
                // heading created just above via the "# " shortcut).
                const list = document.createElement(listCmd === 'insertUnorderedList' ? 'ul' : 'ol');
                const li = document.createElement('li');
                li.innerHTML = '<br>';
                list.appendChild(li);
                blk.replaceWith(list);
                const caretRange = document.createRange();
                caretRange.setStart(li, 0);
                caretRange.collapse(true);
                sel.removeAllRanges();
                sel.addRange(caretRange);
              } else {
                // Bare text directly under the editor root, or already inside a
                // list item — let the browser restructure it.
                document.execCommand(listCmd, false);
              }
              emit();
              return;
            }
          }
        }

        // Triple-space inside inline <code> → exit the element
        let node: Node | null = sel.anchorNode;
        let codeEl: Element | null = null;
        while (node && node !== editorRef.current) {
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

    // ── Table menu state for disabling items ─────────────────────────────────

    const tableMenuCanMergeRight = (() => {
      if (!tableMenu) return false;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return false;
      const { r, c } = origin;
      const rightCol = c + cell.colSpan;
      if (rightCol >= numCols) return false;
      const neighbor = grid[r][rightCol];
      return !!neighbor && neighbor.rowSpan === cell.rowSpan;
    })();

    const tableMenuCanMergeDown = (() => {
      if (!tableMenu) return false;
      const { cell, table } = tableMenu;
      const { grid, numRows, numCols } = buildGrid(table);
      const origin = getCellOrigin(grid, cell, numRows, numCols);
      if (!origin) return false;
      const { r, c } = origin;
      const belowRow = r + cell.rowSpan;
      if (belowRow >= numRows) return false;
      const below = grid[belowRow][c];
      return !!below && below.colSpan === cell.colSpan;
    })();

    // ── Render ────────────────────────────────────────────────────────────────

    return (
      <>
      <div className="rte-wrap">
        {!disabled && (
          <div className={`rte-toolbar${lockedBy ? ' rte-toolbar--locked' : ''}`}>
            <button type="button" className="rte-btn" onMouseDown={e => execFormat(e, 'bold')} title="Bold (Ctrl+B)">
              <Bold size={13} />
            </button>
            <button type="button" className="rte-btn" onMouseDown={e => execFormat(e, 'italic')} title="Italic (Ctrl+I)">
              <Italic size={13} />
            </button>
            <button type="button" className="rte-btn" onMouseDown={e => execFormat(e, 'underline')} title="Underline (Ctrl+U) or ++text++">
              <Underline size={13} />
            </button>

            <div className="rte-sep" />

            <div className="rte-color-wrap" ref={colorWrapRef}>
              <button
                type="button"
                className="rte-btn rte-btn--color"
                onMouseDown={e => e.preventDefault()}
                onClick={() => { saveSelection(); setShowColorPalette(v => !v); }}
                title="Font color"
              >
                <span className="rte-color-a">A</span>
              </button>
              {showColorPalette && (
                <div className="rte-color-palette">
                  {FONT_COLORS.map(color => (
                    <button
                      type="button"
                      key={color}
                      className="rte-color-swatch"
                      style={{ background: color }}
                      onMouseDown={e => applyColor(e, color)}
                      title={color}
                    />
                  ))}
                  {recentColors.length > 0 && (
                    <>
                      <div className="rte-color-palette-label">Recent</div>
                      {recentColors.map(color => (
                        <button
                          type="button"
                          key={color}
                          className="rte-color-swatch"
                          style={{ background: color }}
                          onMouseDown={e => applyColor(e, color)}
                          title={color}
                        />
                      ))}
                    </>
                  )}
                  <div className="rte-color-picker-row" onMouseDown={e => e.stopPropagation()}>
                    <input
                      type="color"
                      className="rte-color-picker"
                      title="Custom color"
                      value={customColor}
                      onChange={e => setCustomColor(e.target.value)}
                    />
                    <button
                      type="button"
                      className="rte-color-picker-apply"
                      onMouseDown={e => e.preventDefault()}
                      onClick={() => {
                        if (viewMode !== 'rich') {
                          mdWrapSelection(`<span style="color: ${customColor}">`, '</span>', 'colored text');
                          recordRecentColor(customColor);
                          setShowColorPalette(false);
                          return;
                        }
                        editorRef.current?.focus();
                        restoreSelection();
                        document.execCommand('foreColor', false, customColor);
                        recordRecentColor(customColor);
                        emit();
                        setShowColorPalette(false);
                      }}
                    >
                      Apply
                    </button>
                  </div>
                </div>
              )}
            </div>

            <div className="rte-sep" />

            <button type="button" className="rte-btn" onMouseDown={e => applyBlock(e, 'p')} title="Normal paragraph">¶</button>
            <button type="button" className="rte-btn rte-btn--heading" onMouseDown={e => applyBlock(e, 'h1')} title="Heading 1">H1</button>
            <button type="button" className="rte-btn rte-btn--heading" onMouseDown={e => applyBlock(e, 'h2')} title="Heading 2">H2</button>
            <button type="button" className="rte-btn rte-btn--heading" onMouseDown={e => applyBlock(e, 'h3')} title="Heading 3">H3</button>

            <div className="rte-sep" />

            <button type="button" className="rte-btn" onMouseDown={e => execFormat(e, 'insertUnorderedList')} title="Bulleted list">
              <List size={13} />
            </button>
            <button type="button" className="rte-btn" onMouseDown={e => execFormat(e, 'insertOrderedList')} title="Numbered list">
              <ListOrdered size={13} />
            </button>

            <div className="rte-sep" />

            <button type="button" className="rte-btn" onMouseDown={e => execFormat(e, 'justifyCenter')} title="Center text">
              <AlignCenter size={13} />
            </button>
            <button type="button" className="rte-btn" onMouseDown={e => execFormat(e, 'justifyLeft')} title="Uncenter text">
              <AlignLeft size={13} />
            </button>

            <div className="rte-sep" />

            <button type="button" className="rte-btn" onMouseDown={openLinkInput} title="Insert link">
              <Link size={13} />
            </button>
            <button type="button" className="rte-btn" onMouseDown={e => execFormat(e, 'unlink')} title="Remove link">
              <Unlink size={13} />
            </button>
            {showLinkInput && (
              <div className="rte-link-input-wrap" onMouseDown={e => e.stopPropagation()}>
                <input
                  className="rte-link-input"
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
                <button type="button" className="rte-btn" onMouseDown={e => { e.preventDefault(); applyLink(); }} title="Apply link">
                  <Check size={13} />
                </button>
                <button type="button" className="rte-btn" onMouseDown={e => { e.preventDefault(); setShowLinkInput(false); setLinkUrl(''); }} title="Cancel">
                  <X size={13} />
                </button>
              </div>
            )}

            <div className="rte-sep" />

            <button type="button" className="rte-btn" onMouseDown={applyInlineCode} title="Inline code">
              <Code size={13} />
            </button>
            <button type="button" className="rte-btn rte-btn--heading" onMouseDown={e => applyBlock(e, 'pre')} title="Code block">
              {'</>'}
            </button>

            <div className="rte-sep" />

            <div className="rte-table-picker-wrap" ref={tablePickerWrapRef}>
              <button
                type="button"
                className="rte-btn"
                title="Insert table"
                onMouseDown={e => {
                  e.preventDefault();
                  setPickerHover({ r: 0, c: 0 });
                  setShowTablePicker(v => !v);
                }}
              >
                <Table2 size={13} />
              </button>
              {showTablePicker && (
                <div className="rte-table-picker">
                  <div className="rte-table-picker-grid">
                    {Array.from({ length: 8 }, (_, r) =>
                      Array.from({ length: 8 }, (_, c) => (
                        <div
                          key={`${r}-${c}`}
                          className={`rte-table-picker-cell${r <= pickerHover.r && c <= pickerHover.c ? ' rte-table-picker-cell--selected' : ''}`}
                          onMouseEnter={() => setPickerHover({ r, c })}
                          onMouseDown={e => {
                            e.preventDefault();
                            insertTable(pickerHover.r + 1, pickerHover.c + 1);
                          }}
                        />
                      ))
                    )}
                  </div>
                  <div className="rte-table-picker-label">
                    {pickerHover.r + 1} × {pickerHover.c + 1}
                  </div>
                </div>
              )}
            </div>

            {onImageUpload && (
              <>
                <div className="rte-sep" />
                <button
                  type="button"
                  className="rte-btn"
                  title="Upload image"
                  onMouseDown={e => e.preventDefault()}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <ImagePlus size={13} />
                </button>
              </>
            )}

            <div className="rte-sep" />

            <button type="button" className="rte-btn" onMouseDown={clearFormatting} title="Clear formatting">
              <RemoveFormatting size={13} />
            </button>

            {aiContext && (
              <>
                <div className="rte-sep" />

                <div className="rte-ai-wrap" ref={aiMenuWrapRef}>
                  <button
                    type="button"
                    className="rte-btn rte-btn--ai"
                    title="AI prompts"
                    onMouseDown={e => e.preventDefault()}
                    onClick={toggleAiMenu}
                    disabled={aiBusy}
                  >
                    <Bot size={13} />
                    <ChevronDown size={9} />
                  </button>
                  {showAiMenu && (
                    <div className="rte-ai-menu">
                      {aiPromptsLoading && (
                        <div className="rte-ai-menu-empty">
                          <Loader2 size={12} className="rte-ai-spin" /> Loading…
                        </div>
                      )}
                      {!aiPromptsLoading && aiPrompts !== null && aiPrompts.length === 0 && (
                        <div className="rte-ai-menu-empty">
                          No AI prompts configured for this area.
                        </div>
                      )}
                      {!aiPromptsLoading && aiPrompts?.map(p => (
                        <button
                          key={p.id}
                          type="button"
                          className="rte-ai-menu-item"
                          title={p.description || `Run "${p.name}" — replaces the current text`}
                          onMouseDown={e => e.preventDefault()}
                          onClick={() => runAiPrompt(p)}
                        >
                          <Sparkles size={12} />
                          <span>{p.name}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>

                <div className="rte-ai-wrap" ref={askAiWrapRef}>
                  <button
                    type="button"
                    className="rte-btn"
                    title="Ask AI"
                    onMouseDown={e => e.preventDefault()}
                    onClick={toggleAskAi}
                    disabled={aiBusy}
                  >
                    <MessageCircleQuestion size={13} />
                  </button>
                  {showAskAi && (
                    <div className="rte-ai-ask">
                      <div className="rte-ai-ask-title">Ask AI</div>
                      <textarea
                        className="rte-ai-ask-input"
                        value={askAiText}
                        onChange={e => setAskAiText(e.target.value)}
                        placeholder="e.g. Summarize the details field, rewrite in simpler language, expand with remediation steps…"
                        rows={3}
                        autoFocus
                        onKeyDown={e => {
                          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                            e.preventDefault();
                            runAskAi();
                          }
                        }}
                      />
                      <div className="rte-ai-ask-hint">
                        The result replaces this editor's text. Ctrl+Z restores it.
                      </div>
                      <div className="rte-ai-ask-actions">
                        <button type="button" className="rte-ai-ask-cancel" onClick={() => setShowAskAi(false)}>
                          Cancel
                        </button>
                        <button
                          type="button"
                          className="rte-ai-ask-generate"
                          onClick={runAskAi}
                          disabled={!askAiText.trim()}
                        >
                          <Sparkles size={12} /> Generate
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              </>
            )}
          </div>
        )}

        {aiError && (
          <div className="rte-ai-error">
            <span>{aiError}</span>
            <button type="button" onClick={() => setAiError(null)} title="Dismiss">
              <X size={12} />
            </button>
          </div>
        )}

        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          style={{ display: 'none' }}
          onChange={async e => {
            const file = e.target.files?.[0];
            e.target.value = '';
            if (!file || !onImageUploadRef.current) return;
            const target = replaceTargetRef.current;
            replaceTargetRef.current = null;
            if (target) {
              try {
                const url = await onImageUploadRef.current(await bakeImageBorder(file));
                target.src = url;
                target.alt = file.name;
                emit();
              } catch { /* upload failed */ }
            } else {
              uploadAndInsert(file);
            }
          }}
        />

        {/* Wrapper keeps both panes mounted; row-reverse flex in split mode puts the
            CodeMirror source (later sibling) on the left, WYSIWYG preview on the right. */}
        <div className={`rte-panes${viewMode === 'split' ? ' rte-panes--split' : ''}`}>
        {aiBusy && (
          <div className="rte-ai-overlay">
            <Loader2 size={18} className="rte-ai-spin" />
            <span>Generating…</span>
          </div>
        )}
        <div
          ref={editorRef}
          className={`rte-body${disabled ? ' rte-body--disabled' : ''}${viewMode === 'markdown' ? ' rte-body--hidden' : ''}`}
          contentEditable={!isReadOnly && viewMode !== 'split'}
          suppressContentEditableWarning
          data-placeholder={placeholder}
          onKeyDown={handleKeyDown}
          onInput={handleInput}
          onPaste={handlePaste}
          onDrop={handleDrop}
          onContextMenu={handleContextMenu}
          onClick={e => {
            // Open anchor links by clicking
            let node: Node | null = e.target as Node;
            while (node && node !== editorRef.current) {
              if (node.nodeType === Node.ELEMENT_NODE && (node as HTMLElement).tagName === 'A') {
                const href = (node as HTMLAnchorElement).getAttribute('href');
                if (href) { e.preventDefault(); window.open(href, '_blank', 'noopener,noreferrer'); }
                return;
              }
              node = node.parentNode;
            }
          }}
        />
        {viewMode !== 'rich' && (
          <div ref={cmContainerRef} className="rte-markdown-body" onKeyDown={handleMarkdownKeyDown} />
        )}
        </div>
        {!disabled && (
          <div className="rte-mode-tabs">
            <button
              type="button"
              className={`rte-mode-tab${viewMode === 'rich' ? ' rte-mode-tab--active' : ''}`}
              onClick={switchToRichView}
              title="Rich Text (Alt+W)"
            >
              Rich Text
            </button>
            <button
              type="button"
              className={`rte-mode-tab${viewMode === 'markdown' ? ' rte-mode-tab--active' : ''}`}
              onClick={switchToMarkdownView}
              title="Markdown (Alt+W)"
            >
              Markdown
            </button>
            <button
              type="button"
              className={`rte-mode-tab${viewMode === 'split' ? ' rte-mode-tab--active' : ''}`}
              onClick={switchToSplitView}
              title="Split view (Alt+W)"
            >
              Split
            </button>
          </div>
        )}
        {/* Another user is editing. A wash rather than a curtain: the content stays
            readable so the viewer can watch their edits land, and pointer-events stay
            off so scrolling and text selection still work. Input is blocked by
            contentEditable=false and the toolbar going inert, not by this element. */}
        {lockedBy && (
          <div className="rte-lock-overlay">
            <span className="rte-lock-pill">
              <Lock size={11} />
              {lockedBy} is editing
            </span>
          </div>
        )}
        {imgMenu && (
          <div
            className="rte-img-menu"
            style={{ left: imgMenu.x, top: imgMenu.y }}
            onMouseDown={e => e.stopPropagation()}
          >
            {[
              { label: 'Small', value: '25%' },
              { label: 'Medium', value: '50%' },
              { label: 'Large', value: '75%' },
              { label: 'Full width', value: '100%' },
            ].map(({ label, value }) => (
              <button
                type="button"
                key={value}
                className={`rte-img-menu-item${imgMenu.img.style.width === value ? ' rte-img-menu-item--active' : ''}`}
                onMouseDown={e => { e.preventDefault(); applyImageSize(value); }}
              >
                {label} <span className="rte-img-menu-size">{value}</span>
              </button>
            ))}
            <div className="rte-img-menu-sep" />
            <button
              type="button"
              className="rte-img-menu-item"
              onMouseDown={e => { e.preventDefault(); applyCaption(); }}
            >
              {imgMenu.img.closest('figure')?.querySelector('figcaption') ? 'Edit caption' : 'Add caption'}
            </button>
            <button
              type="button"
              className="rte-img-menu-item"
              onMouseDown={e => { e.preventDefault(); replaceImage(); }}
            >
              Replace image
            </button>
            <div className="rte-img-menu-sep" />
            <button
              type="button"
              className="rte-img-menu-item rte-img-menu-item--danger"
              onMouseDown={e => { e.preventDefault(); deleteImage(); }}
            >
              Delete
            </button>
          </div>
        )}
        {tableMenu && (
          <div
            className="rte-table-menu"
            style={{ left: tableMenu.x, top: tableMenu.y }}
            onMouseDown={e => e.stopPropagation()}
          >
            <button type="button" className="rte-table-menu-item" onMouseDown={e => { e.preventDefault(); tableAddRowAbove(); }}>
              Add Row Above
            </button>
            <button type="button" className="rte-table-menu-item" onMouseDown={e => { e.preventDefault(); tableAddRowBelow(); }}>
              Add Row Below
            </button>
            <button type="button" className="rte-table-menu-item rte-table-menu-item--danger" onMouseDown={e => { e.preventDefault(); tableDeleteRow(); }}>
              Delete Row
            </button>
            <div className="rte-table-menu-sep" />
            <button type="button" className="rte-table-menu-item" onMouseDown={e => { e.preventDefault(); tableAddColLeft(); }}>
              Add Column Left
            </button>
            <button type="button" className="rte-table-menu-item" onMouseDown={e => { e.preventDefault(); tableAddColRight(); }}>
              Add Column Right
            </button>
            <button type="button" className="rte-table-menu-item rte-table-menu-item--danger" onMouseDown={e => { e.preventDefault(); tableDeleteCol(); }}>
              Delete Column
            </button>
            <div className="rte-table-menu-sep" />
            <button
              type="button"
              className="rte-table-menu-item"
              disabled={!tableMenuCanMergeRight}
              onMouseDown={e => { e.preventDefault(); tableMergeRight(); }}
            >
              Merge Right
            </button>
            <button
              type="button"
              className="rte-table-menu-item"
              disabled={!tableMenuCanMergeDown}
              onMouseDown={e => { e.preventDefault(); tableMergeDown(); }}
            >
              Merge Down
            </button>
            <div className="rte-table-menu-sep" />
            <button
              type="button"
              className="rte-table-menu-item"
              disabled={!tableMenu || tableMenu.cell.colSpan <= 1}
              onMouseDown={e => { e.preventDefault(); tableSplitHorizontal(); }}
            >
              Split Horizontally
            </button>
            <button
              type="button"
              className="rte-table-menu-item"
              disabled={!tableMenu || tableMenu.cell.rowSpan <= 1}
              onMouseDown={e => { e.preventDefault(); tableSplitVertical(); }}
            >
              Split Vertically
            </button>
          </div>
        )}
      </div>
      {mentionQuery !== null && mentionUsers.length > 0 && createPortal(
        <div
          ref={mentionDropdownRef}
          className="rte-mention-dropdown"
          style={{ left: mentionPos.x, top: mentionPos.y }}
          onMouseDown={e => e.preventDefault()}
        >
          {mentionUsers.map((u, i) => (
            <div
              key={u.username}
              className={`rte-mention-item${i === mentionHighlight ? ' rte-mention-item--active' : ''}`}
              onMouseDown={e => { e.preventDefault(); acceptMention(u.username); }}
            >
              <span className="rte-mention-username">@{u.username}</span>
              {u.display !== u.username && <span className="rte-mention-display">{u.display}</span>}
            </div>
          ))}
        </div>,
        document.body
      )}
      </>
    );
  }
);

RichTextEditor.displayName = 'RichTextEditor';
export default RichTextEditor;
