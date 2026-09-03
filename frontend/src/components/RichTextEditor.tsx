import { useRef, useEffect, useState, useMemo, forwardRef, useImperativeHandle } from 'react';
import MermaidDialog from './MermaidDialog';
import { createPortal } from 'react-dom';
import {
  AlignCenter,
  AlignLeft,
  Bold,
  Bot,
  Check,
  ChevronDown,
  ClipboardList,
  Code,
  ImagePlus,
  Workflow,
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
import { EditorView, keymap, drawSelection, placeholder as cmPlaceholder, Decoration, ViewPlugin, WidgetType } from '@codemirror/view';
import type { DecorationSet, ViewUpdate } from '@codemirror/view';
import { Compartment, EditorState, Facet, Prec, RangeSetBuilder, StateField } from '@codemirror/state';
import { defaultKeymap, indentWithTab } from '@codemirror/commands';
import { HighlightStyle, syntaxHighlighting, syntaxTree } from '@codemirror/language';
import { markdown } from '@codemirror/lang-markdown';
import { vim, Vim } from '@replit/codemirror-vim';
import { GFM as lezerGfm } from '@lezer/markdown';
import { tags } from '@lezer/highlight';
import { mentionsApi, aiApi } from '../api';
import type {
  AiPromptScope,
  AiPromptSummary,
  ContentTemplate,
  ContentTemplateInsertMode,
  ContentTemplateScope,
} from '../types';
import ContentTemplateDialog from './ContentTemplateDialog';
import {
  cleanPastedHtml,
  isImageOnlyHtml,
  looksLikeTsvTable,
  readableTextColor,
  TEXT_ON_DARK_FILL,
  TEXT_ON_LIGHT_FILL,
  tsvToTableHtml,
} from '../utils/pasteHtml';
import './RichTextEditor.css';
import './CodeBlock.css';
import './ContentTables.css';

// "> text" markdown is repurposed as CENTERED text, not blockquote. Reports are the
// product here — blockquotes have no meaning in them, and the previous representation
// (<div align="center">) didn't survive the markdown round-trip and broke the docx
// converter. <center> round-trips cleanly and converts.
// ── Line-numbered code blocks ─────────────────────────────────────────────────
// A fence carrying `start=` renders as a two-column table — a gutter of line numbers
// and the code — so a report can style it like a screenshot from an editor. Everything
// the round trip needs is in the markup itself: the fence is rebuilt from the first
// gutter number, so no data-* attribute is involved (the report sanitizer drops those).
//
//   ```start=200          ```python start=200
//   some code             some code
//   ```                   ```
const CODE_BLOCK_CLASS = 'code-block';
const CODE_GUTTER_CLASS = 'code-block-gutter';
const CODE_LINE_CLASS = 'code-block-line';
// The panel's top and bottom padding is a short shaded row of its own, not cell padding.
// Cell padding becomes a w:tcMar, and Word draws a hairline of unpainted white wherever a
// cell margin meets a fill — a line across the panel. A spacer row has no margin to seam.
const CODE_PAD_ROW_CLASS = 'code-block-pad';
const CODE_START_RE = /(?:^|\s)start\s*=\s*(\d+)/i;

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * Indentation as non-breaking spaces. HTML collapses runs of spaces, and the DOCX
 * importer does not honour white-space: pre — without this every line in the report
 * comes out flush left, which for code is the whole structure gone.
 */
function preserveIndent(line: string): string {
  return line.replace(/^[ \t]+/, ws => ws.replace(/\t/g, '    ').replace(/ /g, '\u00a0'));
}

function restoreIndent(text: string): string {
  const restored = text.replace(/\u00a0/g, ' ');
  // A blank line is carried as a single non-breaking space (see lineNumberedCodeHtml);
  // give the fence back an empty line rather than a line holding one space.
  return restored.trim() === '' ? '' : restored;
}

/**
 * Every fence becomes the same panel; only a `start=` adds the gutter of line numbers.
 * Keeping one shape means one set of classes to theme, in the app and in the report.
 */
function codeBlockHtml(code: string, start: number | null, language: string): string {
  const gutter = (content: string) =>
    start === null ? '' : `<td class="${CODE_GUTTER_CLASS}">${content}</td>`;
  const lines = code.replace(/\n+$/, '').split('\n');
  const rows = lines.map((line, i) => {
    // A blank line gets a non-breaking space, not a <br>: a <br> in an otherwise empty
    // cell renders as two lines tall in the DOCX, so one blank line in the source opened
    // a gap twice the height of the others.
    const cell = escapeHtml(preserveIndent(line)) || '&nbsp;';
    return `<tr>${gutter(String((start ?? 0) + i))}`
      + `<td class="${CODE_LINE_CLASS}">${cell}</td></tr>`;
  }).join('');
  const pad = `<tr class="${CODE_PAD_ROW_CLASS}">`
    + (start === null ? '' : `<td class="${CODE_GUTTER_CLASS}" contenteditable="false">&nbsp;</td>`)
    + `<td class="${CODE_LINE_CLASS}" contenteditable="false">&nbsp;</td></tr>`;
  const langClass = language ? ` language-${language.replace(/[^\w-]/g, '')}` : '';
  return `<table class="${CODE_BLOCK_CLASS}${langClass}"><tbody>${pad}${rows}${pad}</tbody></table>`;
}

/** Whether a collapsed caret sits before everything in `node` — a <br> placeholder or
 *  stray whitespace ahead of it does not count as content. */
function atStartOfNode(node: Node, range: Range): boolean {
  const before = range.cloneRange();
  before.selectNodeContents(node);
  before.setEnd(range.startContainer, range.startOffset);
  const fragment = before.cloneContents();
  // Zero-width spaces are caret anchors, not content — see the Enter handler.
  return (fragment.textContent ?? '').replace(/\u200B/g, '').trim() === ''
    && !fragment.querySelector?.('img');
}

/** The line cells of a code block, in order, skipping the padding rows. */
function codeBlockRows(table: HTMLTableElement): HTMLTableRowElement[] {
  return Array.from(table.rows).filter(row => !row.classList.contains(CODE_PAD_ROW_CLASS));
}

/**
 * Rewrites the gutter after a line is added or removed, counting up from whatever the
 * first line is numbered — the whole point of `start=` is that the numbers match the
 * file the excerpt came from, so the first one is the anchor and never recalculated.
 * A block with no gutter (a plain fence) has nothing to renumber.
 */
function renumberCodeBlock(table: HTMLTableElement): void {
  const rows = codeBlockRows(table);
  const first = rows[0]?.querySelector(`.${CODE_GUTTER_CLASS}`);
  if (!first) return;
  const start = parseInt(first.textContent?.trim() || '1', 10);
  rows.forEach((row, i) => {
    const gutter = row.querySelector(`.${CODE_GUTTER_CLASS}`);
    if (gutter) gutter.textContent = String(start + i);
  });
}

/**
 * A line wrapped by hand with Shift+Enter: a <br> with content after it. A trailing one
 * does not count — that is the placeholder that keeps an empty cell focusable, and it is
 * what an empty line looks like.
 */
function hasSoftWrappedLine(table: HTMLTableElement): boolean {
  return Array.from(table.querySelectorAll(`.${CODE_LINE_CLASS}`)).some(cell =>
    Array.from(cell.querySelectorAll('br')).some(br => {
      for (let node = br.nextSibling; node; node = node.nextSibling) {
        if ((node.textContent ?? '').trim() !== '') return true;
      }
      return false;
    }));
}

/** A code block travels as its own table shape, not as a GFM table or a <pre>. */
function isCodeBlockTable(node: Node): boolean {
  return node.nodeName === 'TABLE'
    && (node as HTMLElement).classList.contains(CODE_BLOCK_CLASS);
}

marked.use({
  // Single newlines become <br> instead of collapsing into the previous line —
  // otherwise line breaks typed in the markdown view silently disappear.
  breaks: true,
  renderer: {
    blockquote(token) {
      return `<center>${this.parser.parse(token.tokens)}</center>\n`;
    },
    code({ text, lang }) {
      const info = (lang ?? '').trim();
      const match = info.match(CODE_START_RE);
      return codeBlockHtml(
        text,
        match ? parseInt(match[1], 10) : null,
        info.replace(CODE_START_RE, '').trim(),
      );
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
// A diagram travels as raw HTML for the same reason mentions do: the source that
// produced it lives in data-mermaid, and `![alt](src)` has nowhere to put it. Losing the
// attribute on a trip through the markdown view would leave an image nobody can edit again.
turndownService.keep(node =>
  node.nodeName === 'U' ||
  (node.nodeName === 'IMG' && node.hasAttribute('data-mermaid')) ||
  (node.nodeName === 'SPAN' && (
    node.hasAttribute('data-username') ||
    /(?:^|;)\s*(?:color|background-color|text-decoration)\s*:/i.test(node.getAttribute('style') ?? '')
  ))
);

// A GFM pipe table can express structure and nothing else, so converting a table that
// carries a cell fill, a merged cell, coloured text or block content inside a cell
// silently throws that formatting away — most visibly when pasting a shaded table from
// Word into the markdown view, or merely toggling to markdown and back with one in the
// document. Those tables travel as raw HTML instead (the same escape hatch underline and
// coloured text use, and what marked parses straight back); plain tables stay readable
// pipe tables. Registered after the gfm plugin so it takes precedence over its table
// rules — turndown checks the most recently added rule first.
const NON_MARKDOWN_STYLE = /(?:^|;)\s*(?:background(?:-color)?|color)\s*:/i;
const CELL_BLOCK_CONTENT = 'ul, ol, table, blockquote, pre, h1, h2, h3, h4, h5, h6';

function tableNeedsRawHtml(table: HTMLElement): boolean {
  if (NON_MARKDOWN_STYLE.test(table.getAttribute('style') ?? '')) return true;
  const cells = Array.from(table.querySelectorAll('td, th')) as HTMLTableCellElement[];
  if (cells.some(cell => cell.colSpan > 1 || cell.rowSpan > 1)) return true;
  if (cells.some(cell => cell.querySelector(CELL_BLOCK_CONTENT))) return true;
  return Array.from(table.querySelectorAll('[style]'))
    .some(el => NON_MARKDOWN_STYLE.test(el.getAttribute('style') ?? ''));
}

turndownService.addRule('richTable', {
  filter: node => node.nodeName === 'TABLE' && tableNeedsRawHtml(node as HTMLElement),
  replacement: (_content, node) => '\n\n' + (node as HTMLElement).outerHTML + '\n\n',
});

// Back to a fence, line numbers included: the first gutter cell is the `start=`, and
// the gutter itself is dropped — it is generated, not content. Registered after the
// richTable rule so turndown reaches this one first (it checks newest rules first).
turndownService.addRule('lineNumberedCode', {
  filter: node => isCodeBlockTable(node),
  replacement: (_content, node) => {
    const table = node as HTMLTableElement;
    // A line wrapped by hand has no equivalent in a fence — every newline there starts a
    // new numbered line — so such a block travels as raw HTML rather than losing the
    // wrap (or silently gaining a line number) on the way through the markdown view.
    if (hasSoftWrappedLine(table)) return '\n\n' + table.outerHTML + '\n\n';
    // Spacer rows are the panel's margin, not code.
    const rows = Array.from(table.querySelectorAll('tr'))
      .filter(row => !row.classList.contains(CODE_PAD_ROW_CLASS));
    const firstNumber = rows[0]?.querySelector(`.${CODE_GUTTER_CLASS}`)?.textContent?.trim() ?? '';
    const language = (Array.from(table.classList).find(c => c.startsWith('language-')) ?? '')
      .replace('language-', '');
    const code = rows
      .map(row => restoreIndent(row.querySelector(`.${CODE_LINE_CLASS}`)?.textContent ?? ''))
      .join('\n');
    // No gutter means no `start=` — it goes back out as the plain fence it came from.
    const info = [language, /^\d+$/.test(firstNumber) ? `start=${firstNumber}` : '']
      .filter(Boolean).join(' ');
    return `\n\n\`\`\`${info}\n${code}\n\`\`\`\n\n`;
  },
});

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

/**
 * Pads a pasted chunk out to its own block when it carries raw block HTML — the form a
 * table with fills or merged cells takes in the markdown source. A markdown HTML block
 * runs until the next blank line, so a table pasted flush against the following line
 * swallows it, and one pasted mid-line lands inside a paragraph. Plain markdown needs
 * none of this and is inserted exactly where the cursor is.
 */
function asOwnBlock(view: EditorView, markdown: string): string {
  if (!/^\s*<(?:table|div|ul|ol|blockquote|pre|h[1-6])\b/im.test(markdown)) return markdown;
  const { from, to } = view.state.selection.main;
  const before = view.state.sliceDoc(Math.max(0, from - 2), from);
  const after = view.state.sliceDoc(to, Math.min(view.state.doc.length, to + 2));
  const lead = from === 0 || before.endsWith('\n\n') ? '' : before.endsWith('\n') ? '\n' : '\n\n';
  const trail = to === view.state.doc.length || after.startsWith('\n\n') ? '' : after.startsWith('\n') ? '\n' : '\n\n';
  return lead + markdown.replace(/^\n+|\n+$/g, '') + trail;
}

/**
 * The vim mark — the arrow-through-diamond over "vim" — supplied by the author. Fills
 * are currentColor so it takes the toggle's colour in both states, and it renders a
 * touch larger than the lucide icons around it because it carries lettering that goes
 * to mush at 14px.
 */
function VimIcon({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M14.931 21.5h-1.087a.501.501 0 0 1-.478-.646l1.189-3.892a.5.5 0 0 1 .188-.964h.477a.501.501 0 0 1 .478.646L14.52 20.5h.411a.5.5 0 0 1 0 1z" />
      <path d="M18.087 21.5H17a.5.5 0 0 1-.472-.662l1.319-3.84h-.014l-.057.164a.501.501 0 0 1-.473.336h-2.155a.5.5 0 0 1 0-1h1.8l.057-.164a.501.501 0 0 1 .473-.336h1.083a.5.5 0 0 1 .469.674l-.186.5L17.7 20.5h.387a.5.5 0 0 1 0 1z" />
      <path d="M21.312 21.5h-1.088a.5.5 0 0 1-.472-.662l1.319-3.84h-.014l-.057.164a.501.501 0 0 1-.473.336h-2.154a.5.5 0 0 1 0-1h1.799l.057-.164a.501.501 0 0 1 .473-.336h1.083a.5.5 0 0 1 .469.674l-.186.5-1.144 3.328h.388a.5.5 0 0 1 0 1zM5 22H3.28a.5.5 0 0 1-.248-.065l-.28-.16a.502.502 0 0 1-.252-.435V3.49h-.89a.5.5 0 0 1-.337-.131l-.11-.101A.5.5 0 0 1 1 2.89V1.66a.5.5 0 0 1 .136-.342l.15-.16A.5.5 0 0 1 1.65 1h7.63a.5.5 0 0 1 .346.139l.229.22a.497.497 0 0 1 .155.361v1.16a.501.501 0 0 1-.115.319l-.149.18a.507.507 0 0 1-.386.181H9v7.367l7.482-7.367h-.792a.498.498 0 0 1-.361-.154l-.19-.199A.505.505 0 0 1 15 2.86V1.65c0-.14.059-.272.16-.367l.13-.12a.504.504 0 0 1 .34-.133h7.73c.133 0 .26.053.354.146l.14.14A.504.504 0 0 1 24 1.67v1.12a.5.5 0 0 1-.145.352l-10.43 10.55a.5.5 0 0 1-.711-.703L23 2.585V2.03h-7v.53h1.7a.5.5 0 0 1 .352.856l-9.201 9.062A.502.502 0 0 1 8 12.12V3.06a.5.5 0 0 1 .5-.5h.51V2H2v.49h1a.5.5 0 0 1 .5.5V21h1.292l4.852-4.91a.5.5 0 0 1 .713.701l-5.002 5.062A.503.503 0 0 1 5 22z" />
      <path d="M12 24a.502.502 0 0 1-.354-.146l-4.51-4.51a.5.5 0 0 1 .707-.707L12 22.793l.816-.816a.5.5 0 0 1 .707.707l-1.17 1.17A.498.498 0 0 1 12 24zm8-8a.5.5 0 0 1-.354-.853L22.793 12l-4.227-4.227a.5.5 0 0 1 .707-.707l4.58 4.58a.5.5 0 0 1 0 .707l-3.5 3.5A.495.495 0 0 1 20 16zM16.12 5.12a.502.502 0 0 1-.354-.146L12 1.207 8.854 4.354a.5.5 0 0 1-.707-.707l3.5-3.5a.5.5 0 0 1 .707 0l4.12 4.12a.5.5 0 0 1-.354.853zM3 15a.502.502 0 0 1-.354-.146l-2.5-2.5a.5.5 0 0 1 0-.707l2.5-2.5a.5.5 0 0 1 .707.707L1.207 12l2.146 2.146A.5.5 0 0 1 3 15z" />
      <path d="M11.5 21.5h-1a.496.496 0 0 1-.405-.208.496.496 0 0 1-.069-.45l1.292-3.876A.5.5 0 0 1 11.5 16h.5c.161 0 .312.077.405.208.095.13.12.298.069.45L11.193 20.5h.307a.5.5 0 0 1 0 1zM10 16.94h-.021a.501.501 0 0 1-.479-.521 1.99 1.99 0 0 1 .541-1.287A2.002 2.002 0 0 1 12 12.72h.5c.279 0 .551.058.783.168a.499.499 0 1 1-.426.904.858.858 0 0 0-.357-.072H12a1 1 0 0 0-.928 1.379.5.5 0 0 1-.157.588 1.02 1.02 0 0 0-.415.774.5.5 0 0 1-.5.479z" />
      <path d="M12.5 15.223H12a.5.5 0 0 1 0-1h.5a.5.5 0 0 1 0 1z" />
    </svg>
  );
}

// ── Vim mode ──────────────────────────────────────────────────────────────────
// Vim keybindings apply to the markdown/split pane only — the rich-text side is a
// contenteditable, where modal editing would mean writing the whole thing by hand.
//
// One preference shared by every editor on the page: localStorage carries it across
// reloads and tabs, and the event carries it to the other editors mounted in THIS one,
// which a storage event never fires for.
// Cmd/Ctrl+B, I and U in the markdown pane. The rich view gets these free from the
// browser's contenteditable handling of execCommand; CodeMirror has no such default, so
// the toolbar's advertised "Bold (Ctrl+B)" did nothing there. Bound to the same markdown
// commands the toolbar buttons run.
const MARKDOWN_SHORTCUTS: Record<string, string> = { b: 'bold', i: 'italic', u: 'underline' };

const VIM_MODE_KEY = 'rte-vim-mode';
const VIM_MODE_EVENT = 'rte-vim-mode-change';

function readVimMode(): boolean {
  try { return localStorage.getItem(VIM_MODE_KEY) === 'true'; } catch { return false; }
}

function useVimMode(): [boolean, (on: boolean) => void] {
  const [enabled, setEnabled] = useState(readVimMode);
  useEffect(() => {
    const sync = () => setEnabled(readVimMode());
    window.addEventListener(VIM_MODE_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(VIM_MODE_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);
  const set = (on: boolean) => {
    try { localStorage.setItem(VIM_MODE_KEY, String(on)); } catch { /* private mode */ }
    window.dispatchEvent(new Event(VIM_MODE_EVENT));
  };
  return [enabled, set];
}

// The markdown pane deliberately ships without CodeMirror's history() — Ctrl+Z runs the
// editor's own stack instead, so undo crosses the rich/markdown boundary (see undo() and
// redo()). Vim's `u` and Ctrl+R call CodeMirror's history commands, which would be dead
// keys here, so they are re-pointed at that same stack: each view carries its editor's
// undo/redo in a facet, and the vim actions below read it back out.
type UndoBridge = { undo: () => void; redo: () => void };

const vimUndoBridge = Facet.define<UndoBridge, UndoBridge | null>({
  combine: values => values[0] ?? null,
});

Vim.defineAction('rteUndo', ((cm: { cm6: EditorView }) =>
  cm.cm6.state.facet(vimUndoBridge)?.undo()) as never);
Vim.defineAction('rteRedo', ((cm: { cm6: EditorView }) =>
  cm.cm6.state.facet(vimUndoBridge)?.redo()) as never);
Vim.mapCommand('u', 'action', 'rteUndo', {}, { context: 'normal' });
Vim.mapCommand('<C-r>', 'action', 'rteRedo', {}, { context: 'normal' });

/**
 * drawSelection rides along with vim rather than being installed globally: visual mode
 * moves the selection programmatically, and the browser's native selection does not
 * follow it, so without this a `v` followed by movement highlights nothing at all. It
 * replaces the native selection with CodeMirror's own layer (see .cm-selectionBackground
 * in the stylesheet), so it stays out of the way entirely when vim is off.
 */
function vimExtensions(claimKeydown: (e: KeyboardEvent) => boolean) {
  // Prec.highest, or vim only gets the keys no keymap claimed. CodeMirror runs every
  // keymap through one handler at Prec.high — above a plugin's own keydown handler,
  // whatever order the extensions are listed in — and defaultKeymap binds the emacs
  // motions on macOS. That quietly ate Ctrl-V (page down, so visual block never
  // started), and with it Ctrl-D/F/B/A/E/O/N/P/K. Vim mode is only vim mode if it owns
  // the keyboard while it is on; when it is off this whole extension is absent.
  return Prec.highest([
    // The editor's own shortcuts, claimed above vim — in normal mode vim swallows every
    // key it does not recognise (right for vim: a stray key must not type into the
    // buffer), and that took Alt+W, Ctrl+Z and the formatting shortcuts with it.
    EditorView.domEventHandlers({ keydown: claimKeydown }),
    vim({ status: true }),
    drawSelection(),
    // Block insert (Ctrl-V, then I or A) edits every line at once through multiple
    // cursors, which CodeMirror drops to a single range unless this is on — the prefix
    // landed on the first line of the block only.
    EditorState.allowMultipleSelections.of(true),
  ]);
}

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
  // Raw HTML is first-class content in this pane, not an oddity: a table with cell fills
  // or merged cells travels through markdown as HTML (see the richTable turndown rule),
  // and one long unbroken string of tags and inline styles is unreadable. The markdown
  // language nests the HTML parser already, so these tags are being produced and were
  // simply falling through unstyled. Brackets take the same dim as markdown's own markup
  // characters, so the element names and attributes are what the eye lands on.
  { tag: tags.angleBracket, color: '#9ca3af' },
  { tag: tags.tagName, color: '#0f766e' },
  { tag: tags.attributeName, color: '#b45309' },
  { tag: tags.attributeValue, color: '#15803d' },
  { tag: tags.definitionOperator, color: '#9ca3af' },
  { tag: tags.blockComment, color: '#9ca3af', fontStyle: 'italic' },
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
    // A table that travels as raw HTML keeps its own markup — promoting a row here would
    // rebuild the cells as <th> and drop the very styles that kept it out of markdown.
    // A code block is not a GFM table at all: its first row is line one of the code.
    if (isCodeBlockTable(table) || tableNeedsRawHtml(table)) return;
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
/** The editor's own table class — styling only, never an author-authored one. */
const EDITOR_TABLE_CLASS = 'rte-table';

/**
 * Classes the editor puts on a table itself. They are kept out of the context menu's
 * class box and preserved through it: `code-block` is what makes a code block one, and
 * `rte-table` on a code block would hand it the report's table borders.
 */
function isInternalTableClass(name: string): boolean {
  return name === EDITOR_TABLE_CLASS
    || name === CODE_BLOCK_CLASS
    || name.startsWith('language-');
}

function applyEditorPresentation(html: string): string {
  if (!html.includes('<table') && !html.includes('<img')) return html;
  const container = document.createElement('div');
  container.innerHTML = html;
  container.querySelectorAll('table').forEach(table => {
    // A code block is chrome, not a content table: rte-table would give it the borders
    // and cell padding the report applies to real tables.
    if (!isCodeBlockTable(table)) table.classList.add(EDITOR_TABLE_CLASS);
  });
  // The padding rows are the panel's margin, not a place to write: at 5pt, text typed
  // into one is barely legible and belongs to no line. Applied here rather than only at
  // generation so blocks already saved without it are locked too.
  container.querySelectorAll(`.${CODE_PAD_ROW_CLASS} td`).forEach(cell => {
    cell.setAttribute('contenteditable', 'false');
  });
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
   * Offers the saved content templates for this scope from a toolbar button. Independent of
   * `aiContext` on purpose: templates are plain admin-written boilerplate and work whether or
   * not an AI provider is configured.
   */
  templateScope?: ContentTemplateScope;
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

/** How many recently used colours each palette remembers. */
const RECENT_COLOR_LIMIT = 10;

/**
 * A most-recently-used colour list, persisted per key so it survives a reload and is shared
 * by every editor on the page.
 *
 * Colours already in the fixed palette are not recorded: they are one click away as it is,
 * and repeating them here would push the custom colours — the only ones actually worth
 * remembering — straight off the end of the list.
 */
function useRecentColors(storageKey: string, palette: string[]) {
  const [recent, setRecent] = useState<string[]>(() => {
    try {
      const stored: unknown = JSON.parse(localStorage.getItem(storageKey) ?? '[]');
      return Array.isArray(stored)
        ? stored.filter((c): c is string => typeof c === 'string').slice(0, RECENT_COLOR_LIMIT)
        : [];
    } catch {
      // Unparseable entry, or storage blocked (private windows) — start empty
      return [];
    }
  });

  function record(color: string) {
    const normalized = color.trim().toLowerCase();
    if (!normalized || palette.some(c => c.toLowerCase() === normalized)) return;
    setRecent(prev => {
      const next = [normalized, ...prev.filter(c => c.toLowerCase() !== normalized)]
        .slice(0, RECENT_COLOR_LIMIT);
      try { localStorage.setItem(storageKey, JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  }

  return [recent, record] as const;
}

/**
 * Cell fills offered in the table context menu. Tints first — they are what a report table
 * actually wants, since text stays legible on them in either theme — then the stronger
 * shades of the same hues as FONT_COLORS, so the two palettes read as one set.
 */
const CELL_BACKGROUNDS = [
  '#fee2e2', '#ffedd5', '#fef3c7', '#dcfce7', '#dbeafe', '#ede9fe', '#f3f4f6',
  '#dc2626', '#ea580c', '#d97706', '#16a34a', '#2563eb', '#7c3aed', '#374151',
];

// Border colours: the greys a rule is usually drawn in, then the same accents the fill
// palette offers, so a cell can be outlined to match what it is filled with.
const CELL_BORDERS = [
  '#111827', '#374151', '#6b7280', '#9ca3af', '#d1d5db', '#e5e7eb', '#ffffff',
  '#dc2626', '#ea580c', '#d97706', '#16a34a', '#2563eb', '#7c3aed', '#db2777',
];

const FONT_COLORS = [
  '#000000', '#374151', '#6b7280',
  '#dc2626', '#ea580c', '#d97706',
  '#16a34a', '#2563eb', '#7c3aed',
  '#db2777', '#ffffff',
];

const RichTextEditor = forwardRef<RichTextEditorRef, RichTextEditorProps>(
  ({ value = '', onChange, onImageUpload, placeholder, disabled = false, lockedBy, aiContext, templateScope, mentions = false, mentionContext }, ref) => {
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
    const mermaidTargetRef = useRef<HTMLImageElement | null>(null);
    const [mermaidOpen, setMermaidOpen] = useState(false);
    const [mermaidSource, setMermaidSource] = useState('');
    const tablePickerWrapRef = useRef<HTMLDivElement>(null);

    // Undo/redo history — a custom stack, since structural edits (table row/col
    // add/delete/merge, image ops) mutate the DOM directly and are invisible to
    // the browser's native contenteditable undo manager.
    const historyRef = useRef<{ stack: string[]; index: number }>({ stack: [value], index: 0 });
    const typingDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const isApplyingHistoryRef = useRef(false);

    const [showColorPalette, setShowColorPalette] = useState(false);
    const [recentColors, recordRecentColor] = useRecentColors('rte-recent-colors', FONT_COLORS);
    const [recentCellColors, recordRecentCellColor] =
      useRecentColors('rte-recent-cell-colors', CELL_BACKGROUNDS);
    const [recentBorderColors, recordRecentBorderColor] =
      useRecentColors('rte-recent-border-colors', CELL_BORDERS);
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

    const [vimMode, setVimMode] = useVimMode();
    // Read at mount time rather than listed as an effect dependency: toggling vim
    // reconfigures the live view (below) instead of tearing it down and rebuilding it,
    // which would drop the cursor and scroll position mid-edit.
    const vimModeRef = useRef(vimMode);
    useEffect(() => { vimModeRef.current = vimMode; }, [vimMode]);
    const vimCompartment = useMemo(() => new Compartment(), []);

    /**
     * Keys vim must not swallow while it is on. Alt+W and Cmd/Ctrl+Z only need to be
     * marked handled — returning true stops vim, and the event still bubbles to
     * handleMarkdownKeyDown, which runs them. The formatting shortcuts have no such
     * handler behind them, so they run here.
     *
     * Ctrl+B / Ctrl+I / Ctrl+U are deliberately left to vim — they are its page-up,
     * jump-forward and half-page-up. The Cmd forms stay with the editor.
     */
    function claimEditorShortcut(e: KeyboardEvent): boolean {
      const key = e.key.toLowerCase();
      if (e.altKey && !e.ctrlKey && !e.metaKey && key === 'w') return true;
      if ((e.ctrlKey || e.metaKey) && !e.altKey && (key === 'z' || key === 'y')) return true;
      if (e.metaKey && !e.ctrlKey && !e.altKey && MARKDOWN_SHORTCUTS[key]) {
        e.preventDefault();
        execMarkdownFormat(MARKDOWN_SHORTCUTS[key]);
        return true;
      }
      return false;
    }

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
          // First in the list, so vim's keymap outranks everything below it — normal
          // mode has to own the keyboard, not share it with the default bindings.
          vimCompartment.of(vimModeRef.current ? vimExtensions(claimEditorShortcut) : []),
          vimUndoBridge.of({ undo, redo }),
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
          keymap.of(Object.entries(MARKDOWN_SHORTCUTS).map(([key, cmd]) => ({
            key: `Mod-${key}`,
            run: () => { execMarkdownFormat(cmd); return true; },
          }))),
          keymap.of([indentWithTab, ...defaultKeymap]),
          cmPlaceholder(placeholder ?? ''),
          EditorView.updateListener.of(update => {
            if (update.docChanged) markdownDocChanged(update.state.doc.toString());
            // Selection changes matter too: clicking or arrowing to sit just after an
            // existing "@word" should offer the picker exactly as typing it does.
            if (update.docChanged || update.selectionSet) detectCmMention(update.view);
          }),
          // CodeMirror pastes as plain text, so without this an image dropped here does
          // nothing and rich content from Word or Excel arrives as its bare text with the
          // structure gone. Images reuse uploadAndInsert (which already inserts markdown
          // image syntax whenever viewMode is 'markdown'); everything else is converted
          // to the markdown this pane holds.
          EditorView.domEventHandlers({
            paste: (event, view) => {
              if (isReadOnlyRef.current) return false;
              const items = Array.from(event.clipboardData?.items ?? []);
              const imageItem = onImageUploadRef.current
                ? items.find(item => item.type.startsWith('image/'))
                : undefined;

              const rawHtml = event.clipboardData?.getData('text/html') ?? '';
              const cleanedHtml = rawHtml ? cleanPastedHtml(rawHtml) : '';

              if (imageItem && (!cleanedHtml || isImageOnlyHtml(cleanedHtml))) {
                event.preventDefault();
                const file = imageItem.getAsFile();
                if (file) uploadAndInsert(file);
                return true;
              }

              const text = event.clipboardData?.getData('text/plain') ?? '';
              const html = cleanedHtml || (looksLikeTsvTable(text) ? tsvToTableHtml(text) : '');
              if (!html) return false;

              event.preventDefault();
              const markdown = htmlToMarkdown(applyEditorPresentation(DOMPurify.sanitize(html)));
              view.dispatch(view.state.replaceSelection(asOwnBlock(view, markdown)));
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

    // ── Content templates (reusable boilerplate) ──
    const [showTemplateDialog, setShowTemplateDialog] = useState(false);

    /** Custom fill staged in the table menu's colour picker, applied on its Apply button. */
    const [cellBgColor, setCellBgColor] = useState('#dbeafe');
    const [cellBorderColor, setCellBorderColor] = useState('#374151');
    const [codeLineStart, setCodeLineStart] = useState('1');
    // Custom classes on the right-clicked table, edited as the space-separated string
    // the author typed. Seeded when the menu opens; see tableSetClasses.
    const [tableClasses, setTableClasses] = useState('');

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

    /**
     * A checkpoint can still be waiting out the typing-coalesce window when undo is
     * asked for — vim's `u` straight after a `dd`, or Ctrl+Z mid-burst. Commit it first
     * so undo steps back over that edit instead of discarding it and stepping through to
     * the one before. commitHistory ignores a state identical to the current one, so
     * this is a no-op when nothing is pending.
     */
    function flushPendingHistory() {
      if (!typingDebounceRef.current) return;
      clearTimeout(typingDebounceRef.current);
      typingDebounceRef.current = null;
      if (editorRef.current) {
        commitHistory(normalizeBlocks(editorRef.current.innerHTML.replace(/\u200B/g, '')));
      }
    }

    function undo() {
      flushPendingHistory();
      const h = historyRef.current;
      if (h.index <= 0) return;
      h.index--;
      restoreFromHistory(h.stack[h.index]);
    }

    function redo() {
      flushPendingHistory();
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
     * Replaces the editor content with sanitized HTML — AI output or a content template.
     * In markdown/split view the change goes through CodeMirror so both panes stay in
     * sync; either path commits to the undo history, so Ctrl+Z restores the previous text.
     */
    function replaceEditorContent(rawHtml: string) {
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
          replaceEditorContent(res.data.content);
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
          replaceEditorContent(res.data.content);
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

    // ── Content templates ─────────────────────────────────────────────────────

    /** The live HTML, whichever pane is showing — markdown edits mirror into this element. */
    function editorHtml(): string {
      return editorRef.current?.innerHTML ?? '';
    }

    /** Whether there is anything worth preserving, so the picker knows to offer prepend/append. */
    function editorHasContent(): boolean {
      const el = editorRef.current;
      if (!el) return false;
      return (el.textContent ?? '').trim() !== '' || el.querySelector('img, table, hr') !== null;
    }

    function insertTemplate(template: ContentTemplate, mode: ContentTemplateInsertMode) {
      setShowTemplateDialog(false);
      const current = editorHtml();
      // With an empty editor every mode is the same insert; the dialog only offers
      // Overwrite there, but guard anyway so a stale click can't wrap blank markup.
      const merged = mode === 'OVERWRITE' || !editorHasContent()
        ? template.content
        : mode === 'PREPEND'
          ? `${template.content}${current}`
          : `${current}${template.content}`;
      replaceEditorContent(merged);
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

    /** Compares two CSS colour spellings by the value the browser normalises each one to. */
    function sameCssColor(a: string, b: string): boolean {
      if (!a || !b) return false;
      const probe = document.createElement('div');
      probe.style.color = a;
      const first = probe.style.color;
      probe.style.color = '';
      probe.style.color = b;
      return first !== '' && first === probe.style.color;
    }

    /**
     * Fills the right-clicked cell, or clears the fill when passed null.
     *
     * The text colour rides along, for the same reason it does on a paste from Word: the
     * editor is themed and the fill is not, so a shade chosen while in one theme would
     * leave unreadable text in the other. A colour the author picked for themselves is
     * left alone — only the one this menu supplied is re-paired or removed.
     */
    function tableSetCellBackground(color: string | null) {
      if (!tableMenu) return;
      const { cell } = tableMenu;
      const ownsTextColor = sameCssColor(cell.style.color, TEXT_ON_LIGHT_FILL)
        || sameCssColor(cell.style.color, TEXT_ON_DARK_FILL);

      if (color) {
        recordRecentCellColor(color);
        cell.style.backgroundColor = color;
        if (!cell.style.color || ownsTextColor) cell.style.color = readableTextColor(color);
      } else {
        cell.style.removeProperty('background-color');
        if (ownsTextColor) cell.style.removeProperty('color');
        if (!cell.getAttribute('style')?.trim()) cell.removeAttribute('style');
      }
      emit();
      setTableMenu(null);
    }

    /**
     * Outlines the right-clicked cell, or clears the outline when passed null.
     *
     * <p>Written as the `border` shorthand rather than `border-color`: the cell's border
     * comes from a stylesheet, so a colour on its own has no width or style to attach to
     * and nothing would change. The shorthand also reaches the DOCX intact — the report
     * sanitizer allows border properties on cells, and the importer maps them to the
     * cell's own w:tcBorders, which outrank whatever the table style would draw.
     */
    function tableSetCellBorder(color: string | null) {
      if (!tableMenu) return;
      const { cell } = tableMenu;

      if (color) {
        recordRecentBorderColor(color);
        cell.style.border = `1px solid ${color}`;
      } else {
        // "none", not removeProperty: the cell has a border from the stylesheet, and
        // dropping the inline rule would hand it straight back.
        cell.style.border = 'none';
      }
      emit();
      setTableMenu(null);
    }

    /**
     * Turns a code block's line numbers on (counting from `start`) or off. The gutter is
     * a column of its own, so this adds or removes one cell per row — the padding rows
     * included, or the panel's margin would sit narrower than the code above it.
     */
    function codeBlockSetLineNumbers(start: number | null) {
      if (!tableMenu) return;
      const { table } = tableMenu;
      if (!isCodeBlockTable(table)) return;

      Array.from(table.rows).forEach(row => {
        const gutter = row.querySelector(`.${CODE_GUTTER_CLASS}`);
        if (start === null) {
          gutter?.remove();
          return;
        }
        if (!gutter) {
          const cell = document.createElement('td');
          cell.className = CODE_GUTTER_CLASS;
          if (row.classList.contains(CODE_PAD_ROW_CLASS)) {
            cell.innerHTML = '&nbsp;';
            cell.setAttribute('contenteditable', 'false');
          }
          row.insertBefore(cell, row.firstChild);
        }
      });

      if (start !== null) {
        // renumberCodeBlock counts up from the first line, so seed that one first
        const first = codeBlockRows(table)[0]?.querySelector(`.${CODE_GUTTER_CLASS}`);
        if (first) first.textContent = String(start);
        renumberCodeBlock(table);
      }
      emit();
      setTableMenu(null);
    }

    /**
     * Classes the report template can style, e.g. a "findings-summary" table that the
     * report CSS lays out differently. Stored on the table element itself so they ride
     * along with the saved HTML into report generation, where the backend sanitizer
     * allows class through.
     *
     * <p>The editor's own classes (`rte-table`, and a code block's `code-block` /
     * `language-*`) are kept out of the box and carried through here, rather than left
     * for the author to preserve by hand — see isInternalTableClass.
     */
    function tableSetClasses(value: string) {
      if (!tableMenu) return;
      const { table } = tableMenu;
      const internal = Array.from(table.classList).filter(isInternalTableClass);
      const custom = value.split(/\s+/).filter(c => c && !isInternalTableClass(c));
      table.className = [...internal, ...custom].join(' ');
      emit();
      setTableMenu(null);
    }

    // ── Image upload ──────────────────────────────────────────────────────────

    /**
     * Inserts a rendered diagram, or replaces the one being edited.
     *
     * <p>Deliberately an ordinary <img>: from here on the diagram is exactly as portable
     * as a pasted screenshot, which is what makes it appear in DOCX and PDF reports
     * without the report pipeline knowing mermaid exists. The source rides along in
     * data-mermaid so the diagram stays editable.
     */
    async function insertMermaid(file: File, source: string) {
      const upload = onImageUploadRef.current;
      if (!upload) return;
      const url = await upload(file);
      const target = mermaidTargetRef.current;
      mermaidTargetRef.current = null;

      if (target) {
        target.src = url;
        target.setAttribute('data-mermaid', source);
        emit();
        return;
      }

      if (viewMode !== 'rich') {
        insertMarkdownImage(url, 'diagram');
        return;
      }
      editorRef.current?.focus();
      const img = document.createElement('img');
      img.src = url;
      img.alt = 'Diagram';
      img.setAttribute('data-mermaid', source);
      img.style.maxWidth = '100%';
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
    }

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

    /**
     * Inserts pasted markup at the caret. execCommand splits the block the caret sits in,
     * so a pasted table or list lands as a sibling of the current paragraph instead of
     * nested inside it; insertHtmlAtCursor is the fallback where it is unavailable.
     */
    function pasteHtmlAtCursor(html: string) {
      const prepared = applyEditorPresentation(DOMPurify.sanitize(html));
      editorRef.current?.focus();
      if (!document.execCommand('insertHTML', false, prepared)) {
        insertHtmlAtCursor(prepared);
      }
      emit();
    }

    function handlePaste(e: React.ClipboardEvent) {
      if (isReadOnly) return;

      const items = Array.from(e.clipboardData.items);
      const imageItem = onImageUploadRef.current ? items.find(item => item.type.startsWith('image/')) : undefined;

      // Word, Excel and Google Docs all write their own layout engine's HTML — fonts,
      // sizes and colours on every run, MSO metadata, Word's bullets as plain paragraphs.
      // Reduce it to the editor's own markup rather than letting the browser paste it raw.
      const rawHtml = e.clipboardData.getData('text/html');
      const cleanedHtml = rawHtml ? cleanPastedHtml(rawHtml) : '';

      // An image copied out of a document puts both a picture and a one-<img> HTML
      // fragment on the clipboard, and that fragment points at the author's own disk —
      // the file is the only copy that resolves, so upload it. A copied spreadsheet
      // range can carry a picture of the cells too, which is why the HTML wins whenever
      // it holds anything more than a bare image.
      if (imageItem && (!cleanedHtml || isImageOnlyHtml(cleanedHtml))) {
        e.preventDefault();
        const file = imageItem.getAsFile();
        if (file) uploadAndInsert(file);
        return;
      }

      if (cleanedHtml) {
        e.preventDefault();
        pasteHtmlAtCursor(cleanedHtml);
        return;
      }

      const text = e.clipboardData.getData('text/plain');
      if (!text) return;

      // A spreadsheet range with no HTML alongside it (a TSV file, terminal output,
      // an app that only writes plain text) — rebuild the grid as a real table.
      if (looksLikeTsvTable(text)) {
        e.preventDefault();
        pasteHtmlAtCursor(tsvToTableHtml(text));
        return;
      }

      // Plain text that reads as markdown source — render it to rich text instead
      // of dropping the raw "**bold**" / "# Heading" syntax into the document.
      if (!looksLikeMarkdown(text)) return;

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
            // Menu height varies with the Recent fills, and a viewport shorter than the
            // menu would otherwise place it off the top of the screen.
            const recentRows = (colors: string[]) =>
              colors.length > 0 ? 18 + Math.ceil(colors.length / 7) * 21 : 0;
            const menuHeight = 594
              + recentRows(recentCellColors)
              + recentRows(recentBorderColors);
            setTableClasses(
              Array.from(tableEl.classList).filter(c => !isInternalTableClass(c)).join(' ')
            );
            // Seeded from the block's own first line, so re-applying keeps its numbering
            // rather than silently resetting it to 1.
            setCodeLineStart(
              codeBlockRows(tableEl)[0]?.querySelector(`.${CODE_GUTTER_CLASS}`)?.textContent?.trim()
              || '1'
            );
            setTableMenu({
              x: Math.min(e.clientX, window.innerWidth - 210),
              y: Math.max(8, Math.min(e.clientY, window.innerHeight - menuHeight)),
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
      // A code block is the panel, not a <pre> — the same shape a fence renders as, so
      // what the button makes and what the markdown makes are one thing.
      if (tag === 'pre') {
        insertCodeBlock();
        return;
      }
      document.execCommand('formatBlock', false, tag);
      emit();
    }

    /**
     * Drops a code panel at the caret, taking any selected text as its first lines. Line
     * numbers are off to start with — the right-click menu turns them on — matching a
     * plain fence, which is what most code in a finding is.
     */
    function insertCodeBlock() {
      const editor = editorRef.current;
      const selection = window.getSelection();
      if (!editor || !selection || selection.rangeCount === 0) return;

      const range = selection.getRangeAt(0);
      const selected = selection.toString();
      if (selected) range.deleteContents();

      const template = document.createElement('template');
      template.innerHTML = codeBlockHtml(selected, null, '');
      const table = template.content.firstElementChild as HTMLTableElement | null;
      if (!table) return;

      // A table cannot live inside the <p> the caret is in, so it goes between the
      // editor's own blocks — replacing the one the caret was in when that leaves it
      // empty, which is the usual case of clicking the button on a blank line.
      let block: Node | null = range.startContainer;
      while (block && block.parentNode !== editor) block = block.parentNode;
      if (block && block.nodeType === Node.ELEMENT_NODE
          && !(block.textContent ?? '').trim() && !(block as Element).querySelector('img')) {
        editor.replaceChild(table, block);
      } else if (block) {
        (block as ChildNode).after(table);
      } else {
        editor.appendChild(table);
      }

      const firstLine = table.querySelector(
        `tr:not(.${CODE_PAD_ROW_CLASS}) .${CODE_LINE_CLASS}`) as HTMLElement | null;
      if (firstLine) {
        const caret = document.createRange();
        if (selected) {
          caret.selectNodeContents(firstLine);
          caret.collapse(false);   // after the code that was just brought in
        } else {
          // An empty block is generated with an &nbsp; placeholder, which would sit at
          // the end of the first thing typed. Swap it for the caret anchor, which emit()
          // strips out. Same reason the Enter handler uses one.
          firstLine.textContent = '';
          const anchor = document.createTextNode('\u200B');
          firstLine.appendChild(anchor);
          caret.setStart(anchor, 1);
          caret.collapse(true);
        }
        selection.removeAllRanges();
        selection.addRange(caret);
      }
      emit();
    }

    function applyColor(e: React.MouseEvent, color: string) {
      e.preventDefault();
      // Both view modes record, so re-picking a colour from the Recent row moves it back to
      // the front either way; the rich-text path used to skip this entirely. Palette colours
      // are ignored inside record().
      recordRecentColor(color);
      if (viewMode !== 'rich') {
        mdWrapSelection(`<span style="color: ${color}">`, '</span>', 'colored text');
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

    // Toggling vim swaps just that slice of the configuration on the live view, so the
    // document, cursor and scroll position all survive the switch.
    useEffect(() => {
      cmViewRef.current?.dispatch({
        effects: vimCompartment.reconfigure(vimMode ? vimExtensions(claimEditorShortcut) : []),
      });
    }, [vimMode, vimCompartment]);

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

        const tableCell = findTableCell(sel.anchorNode);

        // Code block: Enter starts the next line — a row of its own, with the next
        // number — and Shift+Enter wraps within the current line, which keeps its
        // number. Falls through to the plain-cell handling below for Shift+Enter,
        // whose <br> is exactly the wrap wanted.
        const codeTable = tableCell?.closest('table');
        if (tableCell?.classList.contains(CODE_LINE_CLASS) && codeTable
            && isCodeBlockTable(codeTable) && !e.shiftKey
            && !tableCell.parentElement?.classList.contains(CODE_PAD_ROW_CLASS)) {
          e.preventDefault();
          const row = tableCell.parentElement as HTMLTableRowElement | null;
          if (!row) return;

          // Everything to the right of the caret moves down with it, as it would in
          // any editor when you break a line in the middle.
          const tail = range.cloneRange();
          tail.selectNodeContents(tableCell);
          tail.setStart(range.endContainer, range.endOffset);
          const moved = tail.extractContents();

          const newRow = document.createElement('tr');
          if (row.querySelector(`.${CODE_GUTTER_CLASS}`)) {
            const gutter = document.createElement('td');
            gutter.className = CODE_GUTTER_CLASS;
            newRow.appendChild(gutter);   // numbered by renumberCodeBlock below
          }
          const line = document.createElement('td');
          line.className = CODE_LINE_CLASS;
          line.appendChild(moved);
          // The caret goes in a zero-width space rather than at offset 0 of the cell.
          // An element offset is only a position between child nodes, and in a cell whose
          // one child is a <br> the browser resolves it to the next text position it can
          // find — the row below — so on a block with no gutter column the new line was
          // created correctly and then typed straight past. A real text node has no such
          // ambiguity. emit() strips these out of the saved HTML.
          const caretAnchor = document.createTextNode('\u200B');
          line.insertBefore(caretAnchor, line.firstChild);
          newRow.appendChild(line);
          row.after(newRow);
          if (!tableCell.firstChild) tableCell.appendChild(document.createElement('br'));

          renumberCodeBlock(codeTable as HTMLTableElement);

          const caret = document.createRange();
          caret.setStart(caretAnchor, 1);
          caret.collapse(true);
          sel.removeAllRanges();
          sel.addRange(caret);
          emit();
          return;
        }

        // Table cell: insert <br> instead of new block
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

        // Code block: at the start of a line, Backspace joins it to the line above and
        // takes its number with it — the row is the line, so leaving the row behind would
        // strand an empty numbered line no keystroke could remove.
        const codeTable = tableCell?.closest('table');
        const range = sel.getRangeAt(0);
        if (tableCell?.classList.contains(CODE_LINE_CLASS) && codeTable
            && isCodeBlockTable(codeTable) && range.collapsed
            && !tableCell.parentElement?.classList.contains(CODE_PAD_ROW_CLASS)
            && atStartOfNode(tableCell, range)) {
          const row = tableCell.parentElement as HTMLTableRowElement | null;
          const previous = row?.previousElementSibling as HTMLTableRowElement | null;
          const previousLine = previous?.classList.contains(CODE_PAD_ROW_CLASS)
            ? null
            : previous?.querySelector(`.${CODE_LINE_CLASS}`) ?? null;
          // The first line has nothing to join to; swallow the key rather than let the
          // browser start dismantling the table.
          e.preventDefault();
          if (!row || !previousLine) return;

          // An empty line above is just its placeholder <br> — drop it, or the joined
          // text lands on a second visual line.
          if (previousLine.childNodes.length === 1 && previousLine.firstChild?.nodeName === 'BR') {
            previousLine.firstChild.remove();
          }
          const joinAt = previousLine.childNodes.length;
          while (tableCell.firstChild) previousLine.appendChild(tableCell.firstChild);
          if (!previousLine.firstChild) previousLine.appendChild(document.createElement('br'));
          row.remove();
          renumberCodeBlock(codeTable as HTMLTableElement);

          const caret = document.createRange();
          caret.setStart(previousLine, joinAt);
          caret.collapse(true);
          sel.removeAllRanges();
          sel.addRange(caret);
          emit();
          return;
        }

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
          insertCodeBlock();
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

    // A code block is a table only in how it is built. Its rows are lines and its columns
    // are the gutter and the code, so the structural operations below would only break
    // it — lines are added with Enter and removed with Backspace instead.
    const menuOnCodeBlock = !!tableMenu && isCodeBlockTable(tableMenu.table);

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
                <button
                  type="button"
                  className="rte-btn"
                  title="Insert diagram"
                  onMouseDown={e => e.preventDefault()}
                  onClick={() => { mermaidTargetRef.current = null; setMermaidSource(''); setMermaidOpen(true); }}
                >
                  <Workflow size={13} />
                </button>
              </>
            )}

            <div className="rte-sep" />

            <button type="button" className="rte-btn" onMouseDown={clearFormatting} title="Clear formatting">
              <RemoveFormatting size={13} />
            </button>

            {templateScope && (
              <>
                <div className="rte-sep" />
                <button
                  type="button"
                  className="rte-btn"
                  title="Insert a saved template"
                  onMouseDown={e => e.preventDefault()}
                  onClick={() => setShowTemplateDialog(true)}
                >
                  <ClipboardList size={13} />
                </button>
              </>
            )}

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

        {templateScope && (
          <ContentTemplateDialog
            isOpen={showTemplateDialog}
            scope={templateScope}
            hasContent={editorHasContent()}
            onClose={() => setShowTemplateDialog(false)}
            onInsert={insertTemplate}
          />
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
          onDoubleClick={e => {
            if (isReadOnly) return;
            const img = (e.target as HTMLElement)?.closest?.('img[data-mermaid]');
            if (!(img instanceof HTMLImageElement)) return;
            e.preventDefault();
            mermaidTargetRef.current = img;
            setMermaidSource(img.getAttribute('data-mermaid') ?? '');
            setMermaidOpen(true);
          }}
          onMouseDown={e => {
            // Clicking a code block's margin: the row is locked, so the browser would
            // drop the caret at the nearest editable spot it can find — often the
            // paragraph after the block, where the next keystroke quietly lands. Send it
            // to the code instead, the way clicking the padding of an editor does: the
            // top margin to the start of the first line, the bottom to the end of the last.
            const padRow = (e.target as HTMLElement).closest?.(`.${CODE_PAD_ROW_CLASS}`);
            const table = padRow?.closest('table');
            if (!padRow || !table || !isCodeBlockTable(table)) return;
            const lines = codeBlockRows(table as HTMLTableElement)
              .map(row => row.querySelector(`.${CODE_LINE_CLASS}`))
              .filter((cell): cell is HTMLTableCellElement => !!cell);
            if (lines.length === 0) return;
            const first = table.querySelector(`.${CODE_PAD_ROW_CLASS}`) === padRow;
            const target = first ? lines[0] : lines[lines.length - 1];
            e.preventDefault();
            const caret = document.createRange();
            caret.selectNodeContents(target);
            caret.collapse(first);
            const selection = window.getSelection();
            selection?.removeAllRanges();
            selection?.addRange(caret);
          }}
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
            {/* Not a fourth view — a mode for the markdown pane, so it only appears
                when that pane is on screen and sits apart from the view tabs. */}
            {viewMode !== 'rich' && (
              <button
                type="button"
                className={`rte-mode-tab rte-mode-tab--vim${vimMode ? ' rte-mode-tab--active' : ''}`}
                onClick={() => setVimMode(!vimMode)}
                aria-pressed={vimMode}
                aria-label="Vim keybindings"
                title={vimMode
                  ? 'Turn off vim keybindings'
                  : 'Vim keybindings in the markdown pane'}
              >
                <VimIcon />
              </button>
            )}
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
        <MermaidDialog
          isOpen={mermaidOpen}
          initialSource={mermaidSource}
          onClose={() => { setMermaidOpen(false); mermaidTargetRef.current = null; }}
          onInsert={insertMermaid}
        />

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
            {!menuOnCodeBlock && (<>
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
            <div className="rte-table-menu-sep" />
            </>)}
            {menuOnCodeBlock && (
              <>
                <div className="rte-table-menu-label">Line Numbers</div>
                <div className="rte-table-menu-classes">
                  <input
                    type="number"
                    min={0}
                    className="rte-table-class-input"
                    title="First line number"
                    value={codeLineStart}
                    onMouseDown={e => e.stopPropagation()}
                    onChange={e => setCodeLineStart(e.target.value)}
                    onKeyDown={e => {
                      e.stopPropagation();
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        codeBlockSetLineNumbers(parseInt(codeLineStart, 10) || 1);
                      }
                    }}
                  />
                  <button
                    type="button"
                    className="rte-color-picker-apply"
                    onMouseDown={e => e.preventDefault()}
                    onClick={() => codeBlockSetLineNumbers(parseInt(codeLineStart, 10) || 1)}
                  >
                    Number
                  </button>
                  <button
                    type="button"
                    className="rte-table-menu-item rte-table-menu-item--inline"
                    onMouseDown={e => { e.preventDefault(); codeBlockSetLineNumbers(null); }}
                  >
                    Off
                  </button>
                </div>
                <div className="rte-table-menu-sep" />
              </>
            )}
            <div className="rte-table-menu-label">Cell Background</div>
            <div className="rte-table-menu-colors">
              {CELL_BACKGROUNDS.map(color => (
                <button
                  type="button"
                  key={color}
                  className="rte-color-swatch"
                  style={{ background: color }}
                  title={color}
                  onMouseDown={e => { e.preventDefault(); tableSetCellBackground(color); }}
                />
              ))}
              {recentCellColors.length > 0 && (
                <>
                  <div className="rte-color-palette-label">Recent</div>
                  {recentCellColors.map(color => (
                    <button
                      type="button"
                      key={color}
                      className="rte-color-swatch"
                      style={{ background: color }}
                      title={color}
                      onMouseDown={e => { e.preventDefault(); tableSetCellBackground(color); }}
                    />
                  ))}
                </>
              )}
              <div className="rte-color-picker-row">
                <button
                  type="button"
                  className="rte-color-swatch rte-color-swatch--none"
                  title="No fill"
                  onMouseDown={e => { e.preventDefault(); tableSetCellBackground(null); }}
                />
                <input
                  type="color"
                  className="rte-color-picker"
                  title="Custom cell background"
                  value={cellBgColor}
                  onChange={e => setCellBgColor(e.target.value)}
                />
                <button
                  type="button"
                  className="rte-color-picker-apply"
                  onMouseDown={e => e.preventDefault()}
                  onClick={() => tableSetCellBackground(cellBgColor)}
                >
                  Apply
                </button>
              </div>
            </div>
            <div className="rte-table-menu-sep" />
            <div className="rte-table-menu-label">Cell Border</div>
            <div className="rte-table-menu-colors">
              {CELL_BORDERS.map(color => (
                <button
                  type="button"
                  key={color}
                  className="rte-color-swatch"
                  style={{ background: color }}
                  title={color}
                  onMouseDown={e => { e.preventDefault(); tableSetCellBorder(color); }}
                />
              ))}
              {recentBorderColors.length > 0 && (
                <>
                  <div className="rte-color-palette-label">Recent</div>
                  {recentBorderColors.map(color => (
                    <button
                      type="button"
                      key={color}
                      className="rte-color-swatch"
                      style={{ background: color }}
                      title={color}
                      onMouseDown={e => { e.preventDefault(); tableSetCellBorder(color); }}
                    />
                  ))}
                </>
              )}
              <div className="rte-color-picker-row">
                <button
                  type="button"
                  className="rte-color-swatch rte-color-swatch--none"
                  title="No border"
                  onMouseDown={e => { e.preventDefault(); tableSetCellBorder(null); }}
                />
                <input
                  type="color"
                  className="rte-color-picker"
                  title="Custom border colour"
                  value={cellBorderColor}
                  onChange={e => setCellBorderColor(e.target.value)}
                />
                <button
                  type="button"
                  className="rte-color-picker-apply"
                  onMouseDown={e => e.preventDefault()}
                  onClick={() => tableSetCellBorder(cellBorderColor)}
                >
                  Apply
                </button>
              </div>
            </div>
            <div className="rte-table-menu-sep" />
            <div className="rte-table-menu-label">Table CSS Classes</div>
            <div className="rte-table-menu-classes">
              <input
                type="text"
                className="rte-table-class-input"
                placeholder="e.g. findings-summary"
                title="Space-separated class names, applied to the table for report styling"
                value={tableClasses}
                onMouseDown={e => e.stopPropagation()}
                onChange={e => setTableClasses(e.target.value)}
                onKeyDown={e => {
                  e.stopPropagation();
                  if (e.key === 'Enter') { e.preventDefault(); tableSetClasses(tableClasses); }
                }}
              />
              <button
                type="button"
                className="rte-color-picker-apply"
                onMouseDown={e => e.preventDefault()}
                onClick={() => tableSetClasses(tableClasses)}
              >
                Apply
              </button>
            </div>
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
