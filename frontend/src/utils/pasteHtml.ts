/**
 * Clipboard HTML normalisation.
 *
 * Word, Excel, Outlook and Google Docs all put HTML on the clipboard, but Office's is an
 * export of its own layout engine: MSO conditional blocks, `<o:p>` / `<v:*>` namespaced
 * elements, a `<style>` sheet of generated class names, and an explicit font, size and
 * colour on every single run. Pasted natively, all of that lands in the document verbatim —
 * text arrives as 11pt black Calibri no matter what the report styling is, tables carry
 * Word's own borders and column widths, and bulleted lists come through as ordinary
 * paragraphs with a literal "·" where the bullet should be.
 *
 * This module reduces any of it to the subset the editor stores: block structure, real
 * `<ul>`/`<ol>` lists, tables with colspan/rowspan, links, images, and the few inline
 * styles that carry meaning rather than Office's defaults.
 */

/** Kept as-is. Anything outside this list is unwrapped (children survive) or dropped. */
const ALLOWED_TAGS = new Set([
  'P', 'DIV', 'SPAN', 'BR', 'HR', 'CENTER',
  'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
  'STRONG', 'B', 'EM', 'I', 'U', 'S', 'STRIKE', 'DEL', 'INS', 'MARK', 'SUB', 'SUP',
  'CODE', 'PRE', 'BLOCKQUOTE',
  'UL', 'OL', 'LI',
  'TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR', 'TD', 'TH', 'CAPTION',
  'A', 'IMG',
]);

/** Dropped with everything inside them — presentation metadata, not content. */
const DROP_TAGS = new Set([
  'SCRIPT', 'STYLE', 'LINK', 'META', 'TITLE', 'BASE', 'NOSCRIPT',
  'IFRAME', 'OBJECT', 'EMBED', 'APPLET', 'SVG', 'CANVAS', 'AUDIO', 'VIDEO',
  'FORM', 'INPUT', 'TEXTAREA', 'SELECT', 'BUTTON', 'OPTION',
  // Excel emits a <colgroup> of pixel widths; keeping it pins the pasted table to
  // whatever the spreadsheet's columns happened to be.
  'COLGROUP', 'COL',
]);

/** Attributes kept per tag. `style` is handled separately, on every element. */
const ALLOWED_ATTRS: Record<string, string[]> = {
  A: ['href', 'title'],
  IMG: ['src', 'alt'],
  TD: ['colspan', 'rowspan'],
  TH: ['colspan', 'rowspan'],
  OL: ['start'],
};

/**
 * Inline styles that survive. Everything else is Office describing its own defaults.
 *
 * Colour and fill are kept as a pair, because neither is readable without the other: a
 * shaded table header whose fill is dropped leaves white text on the page background, and
 * a fill whose text colour is dropped leaves theme-coloured text on a fixed shade. The
 * rules in filterStyle keep an authored pair intact, supply the missing half where only
 * one was authored, and drop Office's defaults (`windowtext`, `background:white`) that
 * carry no intent at all.
 */
const KEEP_STYLE_PROPS = new Set([
  'text-align', 'font-weight', 'font-style', 'text-decoration', 'text-decoration-line',
  'color', 'background-color', 'background', 'vertical-align',
]);

const BLOCK_TAGS = new Set([
  'P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'UL', 'OL', 'LI',
  'TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR', 'TD', 'TH',
  'PRE', 'BLOCKQUOTE', 'HR', 'CENTER',
]);

/** Elements that mean something even with no text in them. */
const VOID_OR_STRUCTURAL = new Set(['BR', 'HR', 'IMG', 'TD', 'TH', 'TR', 'TABLE', 'THEAD', 'TBODY', 'TFOOT']);

// ── Colour helpers ──────────────────────────────────────────────────────────
// Word writes an explicit colour on nearly every run — usually its default black, or
// `windowtext`. Carried into a dark-themed editor that reads as black-on-black, so the
// near-defaults are dropped and only a deliberate colour survives.

/**
 * Resolves any CSS colour name to a hex string. A canvas context only takes a value it can
 * parse, so a probe that survives two different starting values is a real colour — which is
 * how `yellow` is told apart from Word's `windowtext`, a legacy system colour no browser
 * resolves. Cached: this runs on every styled element of a paste.
 */
let colorProbe: CanvasRenderingContext2D | null | undefined;
const namedColorCache = new Map<string, string | null>();

function normalizeColorName(value: string): string | null {
  const cached = namedColorCache.get(value);
  if (cached !== undefined) return cached;
  if (colorProbe === undefined) colorProbe = document.createElement('canvas').getContext('2d');
  let resolved: string | null = null;
  if (colorProbe) {
    colorProbe.fillStyle = '#000000';
    colorProbe.fillStyle = value;
    const first = String(colorProbe.fillStyle);
    colorProbe.fillStyle = '#ffffff';
    colorProbe.fillStyle = value;
    resolved = String(colorProbe.fillStyle) === first ? first : null;
  }
  namedColorCache.set(value, resolved);
  return resolved;
}

function parseColor(value: string): [number, number, number] | null {
  const v = value.trim().toLowerCase();
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/.exec(v);
  if (hex) {
    const h = hex[1].length === 3 ? hex[1].split('').map(c => c + c).join('') : hex[1];
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
  }
  const rgb = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)/.exec(v);
  if (rgb) return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])];
  const named = normalizeColorName(v);
  return named && named !== v ? parseColor(named) : null;
}

function rgbToHex([r, g, b]: [number, number, number]): string {
  return '#' + [r, g, b].map(c => c.toString(16).padStart(2, '0')).join('');
}

/** Perceived brightness, 0–255. Decides which way a supplied contrast colour goes. */
function luminance([r, g, b]: [number, number, number]): number {
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** The two colours supplied when a fill arrives (or is chosen) without one of its own. */
export const TEXT_ON_LIGHT_FILL = '#111827';
export const TEXT_ON_DARK_FILL = '#ffffff';

/**
 * The text colour to pair with a fill. Shared with the table editor's cell-background
 * menu, so a shade picked there and a shade pasted from Word behave identically: the
 * editor is themed and a fill is not, so a fill without a text colour of its own would
 * otherwise be readable in one theme and not the other.
 */
export function readableTextColor(fill: string): string {
  const rgb = parseColor(fill);
  return rgb && luminance(rgb) < 140 ? TEXT_ON_DARK_FILL : TEXT_ON_LIGHT_FILL;
}

/** Word's default text colour, in any of the spellings it uses. */
function isDefaultTextColor(value: string): boolean {
  const v = value.trim().toLowerCase();
  if (['windowtext', 'auto', 'inherit', 'initial', 'currentcolor', 'unset'].includes(v)) return true;
  const rgb = parseColor(v);
  return rgb !== null && rgb.every(c => c <= 0x22);
}

/** Office writes `background:white none repeat scroll 0% 0%`; only the colour matters. */
function backgroundColor(value: string): string | null {
  const v = value.trim();
  const fn = /^(rgba?\([^)]*\))/i.exec(v);
  const token = fn ? fn[1] : v.split(/\s+/)[0];
  if (['transparent', 'none', 'auto', 'inherit', 'initial', 'unset', 'window'].includes(token.toLowerCase())) {
    return null;
  }
  const rgb = parseColor(token);
  // Word paints `background:white` on nearly every cell and run; that is the page, not a fill
  if (rgb && rgb.every(c => c >= 0xf0)) return null;
  // A named colour we can't parse (`yellow` from the highlighter) is still a real fill
  return rgb || /^[a-z]+$/i.test(token) ? token : null;
}

/** Reduces one style attribute to the declarations worth keeping, or '' for none. */
function filterStyle(style: string, tagName: string): string {
  const declared = new Map<string, string>();
  for (const declaration of style.split(';')) {
    const idx = declaration.indexOf(':');
    if (idx < 0) continue;
    const prop = declaration.slice(0, idx).trim().toLowerCase();
    const value = declaration.slice(idx + 1).trim();
    if (value && KEEP_STYLE_PROPS.has(prop) && !/mso-/i.test(value)) declared.set(prop, value);
  }

  const kept: string[] = [];

  // Fill first: whether one survives decides how the text colour is treated below.
  const fill = backgroundColor(declared.get('background-color') ?? declared.get('background') ?? '');
  if (fill) kept.push(`background-color: ${fill}`);

  if (fill) {
    const fillRgb = parseColor(fill);
    const authored = declared.get('color');
    const authoredRgb = authored ? parseColor(authored) : null;
    // Keep the author's own pairing, Word's black included — on a fill that is a choice
    // about the fill, not the document default. But only when the two actually contrast:
    // Word writes `color:windowtext` (a legacy system colour nothing resolves) over its
    // shading, which would leave the theme's own text colour on a fixed background.
    const readable = authoredRgb !== null && fillRgb !== null
      && Math.abs(luminance(authoredRgb) - luminance(fillRgb)) >= 60;
    // Written as hex, not as authored: Word's spellings for its default text include
    // `windowtext`, which only some browsers resolve and the DOCX converter does not.
    if (authoredRgb && readable) kept.push(`color: ${rgbToHex(authoredRgb)}`);
    else kept.push(`color: ${readableTextColor(fill)}`);
  }

  for (const [prop, value] of declared) {
    if (prop === 'background' || prop === 'background-color') continue;
    if (prop === 'color' && fill) continue;

    switch (prop) {
      case 'text-align':
        if (!/^(left|right|center|justify)$/i.test(value)) continue;
        break;
      case 'font-weight': {
        const bold = /^(bold|bolder)$/i.test(value) || Number(value) >= 600;
        if (!bold) continue;
        kept.push('font-weight: bold');
        continue;
      }
      case 'font-style':
        if (!/^(italic|oblique)$/i.test(value)) continue;
        break;
      case 'text-decoration':
      case 'text-decoration-line':
        if (!/(underline|line-through)/i.test(value)) continue;
        kept.push(`text-decoration: ${/line-through/i.test(value) ? 'line-through' : 'underline'}`);
        continue;
      case 'color':
        if (isDefaultTextColor(value)) continue;
        break;
      case 'vertical-align':
        // Only meaningful on cells (Word writes `vertical-align:baseline` everywhere else)
        if (tagName !== 'TD' && tagName !== 'TH') continue;
        if (!/^(top|middle|bottom)$/i.test(value)) continue;
        break;
    }
    kept.push(`${prop}: ${value}`);
  }
  return kept.join('; ');
}

// ── Word list reconstruction ────────────────────────────────────────────────

/** `1.` `a)` `iv.` — Word's own numbering, as rendered into the marker span. */
const ORDERED_MARKER = /^\s*([0-9]+|[a-z]|[ivxlcdm]+)\s*[.)]/i;

/** The bullet glyphs Word uses for unordered levels, plus a hyphen for plain-text fallbacks. */
const BULLET_MARKER = /^[\s\u00a0]*[·•▪◦‣o§*+\-–—][\s\u00a0]+/;

interface ListItemInfo {
  level: number;
  ordered: boolean;
  paragraph: HTMLElement;
}

/**
 * Word does not emit `<ul>`/`<ol>` at all: every bullet is a `<p style="mso-list:l0 level1
 * lfo1">` whose visible marker is a leading span. Reads the level and marker off each such
 * paragraph, strips the marker, and rebuilds real nested lists.
 */
function rebuildWordLists(root: ParentNode): void {
  const paragraphs = Array.from(root.querySelectorAll<HTMLElement>('p[style*="mso-list"], p[class*="MsoList"]'));
  if (paragraphs.length === 0) return;

  const info = new Map<HTMLElement, ListItemInfo>();
  for (const p of paragraphs) {
    const style = p.getAttribute('style') ?? '';
    const level = Number(/level(\d+)/i.exec(style)?.[1] ?? 1);

    // The marker lives in a `mso-list:Ignore` span (or the `<![if !supportLists]>` block,
    // which the pre-pass tags as data-mso-marker). Read it, then take it out of the text.
    const marker = p.querySelector<HTMLElement>('[data-mso-marker], span[style*="mso-list:Ignore"], span[style*="mso-list: Ignore"]');
    let markerText = marker?.textContent ?? '';
    if (marker) {
      marker.remove();
    } else {
      // No marker span (Outlook, and Word's plainer HTML flavour): the glyph is just the
      // paragraph's first text, so match it there instead.
      const text = p.textContent ?? '';
      const m = ORDERED_MARKER.exec(text) ?? BULLET_MARKER.exec(text);
      if (!m) continue;
      markerText = m[0];
      stripLeadingText(p, m[0].length);
    }

    info.set(p, { level, ordered: ORDERED_MARKER.test(markerText), paragraph: p });
  }
  if (info.size === 0) return;

  // Group runs of consecutive list paragraphs (siblings) and replace each run with one tree.
  let run: ListItemInfo[] = [];
  const flush = () => {
    if (run.length > 0) buildList(run);
    run = [];
  };
  const walk = (parent: ParentNode) => {
    for (const child of Array.from(parent.children)) {
      const entry = info.get(child as HTMLElement);
      if (entry) {
        run.push(entry);
        continue;
      }
      flush();
      if (child.children.length > 0) walk(child);
    }
    flush();
  };
  walk(root);
}

/** Drops the first `count` characters of an element's text, marker glyph and all. */
function stripLeadingText(el: HTMLElement, count: number): void {
  let remaining = count;
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  const empties: Text[] = [];
  while (remaining > 0) {
    const node = walker.nextNode() as Text | null;
    if (!node) break;
    const take = Math.min(remaining, node.data.length);
    node.data = node.data.slice(take);
    remaining -= take;
    if (node.data.length === 0) empties.push(node);
  }
  empties.forEach(n => n.remove());
  // Word pads the marker with non-breaking spaces or a tab before the text starts
  const first = document.createTreeWalker(el, NodeFilter.SHOW_TEXT).nextNode() as Text | null;
  if (first) first.data = first.data.replace(/^[\s\u00a0]+/, '');
}

/** Turns one run of flat Word list paragraphs into nested `<ul>`/`<ol>` elements. */
function buildList(items: ListItemInfo[]): void {
  const anchor = items[0].paragraph;
  const doc = anchor.ownerDocument;
  const roots: HTMLElement[] = [];
  const stack: Array<{ level: number; ordered: boolean; list: HTMLElement }> = [];

  const openList = (item: ListItemInfo) => {
    const list = doc.createElement(item.ordered ? 'ol' : 'ul');
    const parentItem = stack[stack.length - 1]?.list.lastElementChild;
    if (parentItem) parentItem.appendChild(list);
    else roots.push(list);
    stack.push({ level: item.level, ordered: item.ordered, list });
  };

  for (const item of items) {
    while (stack.length > 0 && stack[stack.length - 1].level > item.level) stack.pop();
    const top = stack[stack.length - 1];
    if (!top || top.level < item.level) {
      openList(item);
    } else if (top.ordered !== item.ordered) {
      // Word writes a numbered list right after a bulleted one as more paragraphs at the
      // same level; they are two lists, and merging them would renumber the steps as bullets.
      stack.pop();
      openList(item);
    }

    const li = doc.createElement('li');
    while (item.paragraph.firstChild) li.appendChild(item.paragraph.firstChild);
    stack[stack.length - 1].list.appendChild(li);
  }

  roots.forEach(list => anchor.parentNode?.insertBefore(list, anchor));
  items.forEach(item => item.paragraph.remove());
}

// ── Main entry point ────────────────────────────────────────────────────────

/**
 * Extracts the copied selection from a CF_HTML payload. Browsers hand over the whole
 * synthesised document, of which only the fragment between the markers was selected.
 */
function extractFragment(html: string): string {
  const start = html.indexOf('<!--StartFragment-->');
  const end = html.indexOf('<!--EndFragment-->');
  if (start >= 0 && end > start) return html.slice(start + '<!--StartFragment-->'.length, end);
  return html;
}

/**
 * Normalises clipboard HTML into the editor's own markup. Returns '' when the clipboard
 * held nothing worth inserting, so callers can fall through to the plain-text paths.
 */
export function cleanPastedHtml(html: string): string {
  if (!html || !html.trim()) return '';

  let source = extractFragment(html);
  // Downlevel-hidden MSO blocks: <xml> islands, VML shape fallbacks, list galleries.
  source = source.replace(/<!--\[if[\s\S]*?<!\[endif\]-->/gi, '');
  // Downlevel-revealed blocks: the list marker lives here, so keep the content and tag it.
  source = source.replace(/<!\[if\s+!supportLists\]>([\s\S]*?)<!\[endif\]>/gi,
    (_m, inner) => `<span data-mso-marker="1">${inner}</span>`);
  source = source.replace(/<!\[if[\s\S]*?\]>/gi, '').replace(/<!\[endif\]>/gi, '');

  // DOMParser is inert: no scripts run and no images are fetched while we clean.
  const doc = new DOMParser().parseFromString(source, 'text/html');
  const body = doc.body;
  if (!body) return '';

  // Namespaced Office elements (<o:p>, <v:shape>, <w:sdt>, <m:oMath>) never survive; they
  // carry layout metadata, and the visible text is duplicated in ordinary markup alongside.
  Array.from(body.querySelectorAll('*'))
    .filter(el => el.tagName.includes(':'))
    .forEach(el => el.remove());

  Array.from(body.querySelectorAll('*'))
    .filter(el => DROP_TAGS.has(el.tagName))
    .forEach(el => el.remove());

  // Comments (including the bogus-comment leftovers of MSO conditionals)
  const commentWalker = doc.createTreeWalker(body, NodeFilter.SHOW_COMMENT);
  const comments: Comment[] = [];
  let comment: Node | null;
  while ((comment = commentWalker.nextNode())) comments.push(comment as Comment);
  comments.forEach(c => c.remove());

  rebuildWordLists(body);

  convertFontTags(body);
  dropCosmeticEmphasisWrappers(body);
  cleanAttributes(body);
  unwrapUnknownTags(body);
  dropUnusableImages(body);
  simplifyStructure(body);

  return body.innerHTML.trim();
}

/**
 * `<font color=…>` carries its colour in an attribute, not a style, so unwrapping the
 * legacy tag would silently drop it. Excel and older editors still emit these.
 */
function convertFontTags(root: ParentNode): void {
  root.querySelectorAll('font').forEach(font => {
    const color = font.getAttribute('color');
    if (!color) return;
    const span = font.ownerDocument.createElement('span');
    span.setAttribute('style', `color: ${color}`);
    while (font.firstChild) span.appendChild(font.firstChild);
    font.replaceWith(span);
  });
}

/**
 * Removes emphasis tags that are cancelled by their own style. Google Docs wraps an entire
 * copied selection in `<b style="font-weight:normal">`; keeping the tag once the style is
 * filtered away would bold the whole paste, and it also masks the genuinely bold runs
 * inside it (which then read as redundant emphasis and get dropped).
 */
function dropCosmeticEmphasisWrappers(root: ParentNode): void {
  const cancelled: Array<[string, RegExp]> = [
    ['b, strong', /font-weight:\s*(normal|400)/i],
    ['i, em', /font-style:\s*normal/i],
    ['u', /text-decoration:\s*none/i],
  ];
  for (const [selector, cancels] of cancelled) {
    root.querySelectorAll(selector).forEach(el => {
      if (cancels.test(el.getAttribute('style') ?? '')) unwrap(el);
    });
  }
}

/** Strips every attribute that isn't on the per-tag allowlist, and filters `style`. */
function cleanAttributes(root: ParentNode): void {
  root.querySelectorAll('*').forEach(el => {
    // The editor's own mention chips survive a copy/paste inside the app intact
    const isMention = el.tagName === 'SPAN' && el.classList.contains('mention') && el.hasAttribute('data-username');
    const allowed = ALLOWED_ATTRS[el.tagName] ?? [];

    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase();
      if (isMention && (name === 'class' || name === 'data-username' || name === 'contenteditable')) continue;
      if (name === 'style') {
        const style = filterStyle(attr.value, el.tagName);
        if (style) el.setAttribute('style', style);
        else el.removeAttribute('style');
        continue;
      }
      if (allowed.includes(name)) {
        // `colspan="1"` is the default and only adds noise
        if ((name === 'colspan' || name === 'rowspan') && attr.value.trim() === '1') el.removeAttribute(name);
        continue;
      }
      el.removeAttribute(attr.name);
    }

    // Links to anything but the web are Office's internal bookmarks and local file paths
    if (el.tagName === 'A') {
      const href = el.getAttribute('href') ?? '';
      if (!/^(https?:|mailto:|#|\/)/i.test(href)) el.removeAttribute('href');
    }
  });
}

/** Unwraps tags outside the allowlist (`<font>`, `<article>`, custom elements) keeping their text. */
function unwrapUnknownTags(root: ParentNode): void {
  let unknown = Array.from(root.querySelectorAll('*')).filter(el => !ALLOWED_TAGS.has(el.tagName));
  // Nested unknowns need more than one pass; bounded so malformed input can't spin.
  for (let pass = 0; pass < 5 && unknown.length > 0; pass++) {
    unknown.forEach(unwrap);
    unknown = Array.from(root.querySelectorAll('*')).filter(el => !ALLOWED_TAGS.has(el.tagName));
  }
}

function unwrap(el: Element): void {
  const parent = el.parentNode;
  if (!parent) return;
  while (el.firstChild) parent.insertBefore(el.firstChild, el);
  parent.removeChild(el);
}

/**
 * Word references pasted pictures as `file:///C:/…/clip_image001.png` — a path on the
 * author's own machine that resolves to nothing here, so the image would render broken.
 */
function dropUnusableImages(root: ParentNode): void {
  root.querySelectorAll('img').forEach(img => {
    const src = img.getAttribute('src') ?? '';
    if (!/^(https?:|data:image\/|blob:|\/)/i.test(src)) img.remove();
  });
}

/**
 * Word and Excel pretty-print their table markup. Those newlines are layout whitespace,
 * not content: inside a `<tr>` they are stray text nodes, and in a cell they stop an empty
 * cell from reading as empty (which is what puts the `<br>` placeholder in it later).
 * Whitespace *between* a cell's own elements is left alone — it separates words.
 */
function dropLayoutWhitespace(root: ParentNode): void {
  root.querySelectorAll('table, thead, tbody, tfoot, tr, ul, ol').forEach(el => {
    Array.from(el.childNodes)
      .filter(node => node.nodeType === Node.TEXT_NODE && !(node.textContent ?? '').trim())
      .forEach(node => node.remove());
  });

  root.querySelectorAll('td, th').forEach(cell => {
    for (const edge of [cell.firstChild, cell.lastChild]) {
      if (edge && edge.nodeType === Node.TEXT_NODE && !(edge.textContent ?? '').trim()) edge.remove();
    }
  });
}

/**
 * Word marks a bold run up twice — `<b><span style='font-weight:bold'>` — so the span says
 * nothing its parent tag doesn't, and drops out entirely once emptied.
 */
function dropRedundantEmphasis(root: ParentNode): void {
  root.querySelectorAll('span[style]').forEach(span => {
    const kept = (span.getAttribute('style') ?? '').split(';')
      .map(declaration => declaration.trim())
      .filter(declaration => {
        if (!declaration) return false;
        if (/^font-weight:\s*bold$/i.test(declaration)) return !span.closest('b, strong');
        if (/^font-style:\s*italic$/i.test(declaration)) return !span.closest('i, em');
        if (/^text-decoration:\s*underline$/i.test(declaration)) return !span.closest('u');
        if (/^text-decoration:\s*line-through$/i.test(declaration)) return !span.closest('s, strike, del');
        return true;
      });
    if (kept.length > 0) span.setAttribute('style', kept.join('; '));
    else span.removeAttribute('style');
  });
}

/** Collapses the wrappers Office leaves behind once its attributes are gone. */
function simplifyStructure(root: ParentNode): void {
  const doc = root.ownerDocument ?? document;

  dropLayoutWhitespace(root);
  dropRedundantEmphasis(root);

  // Attribute-less <span>/<b>-style wrappers are pure noise once the fonts are stripped
  for (let pass = 0; pass < 5; pass++) {
    const redundant = Array.from(root.querySelectorAll('span')).filter(el => el.attributes.length === 0);
    if (redundant.length === 0) break;
    redundant.forEach(unwrap);
  }

  // Word wraps the whole paste in <div class=WordSection1>; other apps use <div> as a
  // paragraph. Unwrap the former (it holds blocks), turn the latter into a real <p>.
  Array.from(root.querySelectorAll('div')).forEach(div => {
    if (div.attributes.length > 0) return;
    const hasBlockChild = Array.from(div.children).some(c => BLOCK_TAGS.has(c.tagName));
    if (hasBlockChild) {
      unwrap(div);
      return;
    }
    const p = doc.createElement('p');
    while (div.firstChild) p.appendChild(div.firstChild);
    div.replaceWith(p);
  });

  // A cell whose only child is a paragraph (how Word writes every table cell) reads as an
  // indented block inside the cell; the editor's own cells hold their text directly.
  root.querySelectorAll('td, th').forEach(cell => {
    if (cell.childNodes.length !== 1) return;
    const only = cell.firstElementChild;
    if (!only || only.tagName !== 'P' || only.attributes.length > 0) return;
    unwrap(only);
  });

  // Word ends every paragraph with an <o:p>, so once those are gone a genuinely blank
  // line is an empty <p>. Keep it as a blank line rather than dropping it silently.
  root.querySelectorAll('p, li, h1, h2, h3, h4, h5, h6').forEach(el => {
    if (el.children.length === 0 && !(el.textContent ?? '').replace(/\u00a0/g, ' ').trim()) {
      el.innerHTML = '<br>';
    }
  });

  // Inline wrappers left holding nothing
  for (let pass = 0; pass < 3; pass++) {
    const empties = Array.from(root.querySelectorAll('span, a, b, strong, i, em, u, s, sub, sup, code'))
      .filter(el => !el.hasChildNodes()
        || (!(el.textContent ?? '').trim() && !el.querySelector([...VOID_OR_STRUCTURAL].join(','))));
    if (empties.length === 0) break;
    empties.forEach(el => el.remove());
  }

  dropUnbackedLightText(root);
  promoteTableHeaders(root);
}

/**
 * Drops near-white text that has no fill behind it. Word puts white text on shapes and
 * shaded cells; where the fill did not survive (Office's own `background:white`, or a
 * shape we cannot represent) the colour that is left renders as invisible text on the
 * page. Falling back to the theme's colour is the only readable outcome.
 */
function dropUnbackedLightText(root: ParentNode): void {
  root.querySelectorAll('[style*="color"]').forEach(el => {
    const style = el.getAttribute('style') ?? '';
    const color = /(?:^|;)\s*color:\s*([^;]+)/i.exec(style)?.[1]?.trim();
    if (!color) return;
    const rgb = parseColor(color);
    const isLight = color.toLowerCase() === 'white' || (rgb !== null && luminance(rgb) >= 0xe0);
    if (!isLight) return;
    if (el.closest('[style*="background-color"]')) return;

    const remaining = style.split(';')
      .map(d => d.trim())
      .filter(d => d && !/^color\s*:/i.test(d));
    if (remaining.length > 0) el.setAttribute('style', remaining.join('; '));
    else el.removeAttribute('style');
  });
}

/**
 * Gives a pasted table the header row the editor's own tables always have. Word and Excel
 * emit plain `<tbody><tr><td>` grids, and the markdown conversion promotes the first row to
 * a header regardless — doing it here means the rich view shows what a round-trip will keep.
 */
function promoteTableHeaders(root: ParentNode): void {
  root.querySelectorAll('table').forEach(table => {
    if (table.querySelector('thead')) return;
    const firstRow = (table as HTMLTableElement).rows[0];
    if (!firstRow || firstRow.cells.length === 0) return;
    if (Array.from(firstRow.cells).every(cell => cell.tagName === 'TH')) return;

    const doc = table.ownerDocument;
    const thead = doc.createElement('thead');
    const headerRow = doc.createElement('tr');
    Array.from(firstRow.cells).forEach(cell => {
      const th = doc.createElement('th');
      for (const attr of Array.from(cell.attributes)) th.setAttribute(attr.name, attr.value);
      th.innerHTML = cell.innerHTML;
      headerRow.appendChild(th);
    });
    thead.appendChild(headerRow);
    table.prepend(thead);
    firstRow.remove();
  });
}

/**
 * True when the clipboard HTML is nothing but an image — a picture copied out of Word or a
 * web page. The caller uploads the accompanying image file instead, which is the only copy
 * that actually resolves.
 */
export function isImageOnlyHtml(html: string): boolean {
  if (!html) return true;
  const doc = new DOMParser().parseFromString(html, 'text/html');
  if ((doc.body.textContent ?? '').trim()) return false;
  return doc.body.querySelectorAll('table').length === 0;
}

// ── Plain-text spreadsheet fallback ─────────────────────────────────────────

/** Rows of a TSV block, with the trailing newline every spreadsheet appends removed. */
function tsvRows(text: string): string[][] | null {
  const lines = text.replace(/\r\n?/g, '\n').replace(/\n+$/, '').split('\n');
  if (lines.length < 2) return null;
  const rows = lines.map(line => line.split('\t'));
  const width = rows[0].length;
  if (width < 2) return null;
  if (rows.some(row => row.length !== width)) return null;
  return rows;
}

/**
 * True for a tab-separated grid — what a spreadsheet leaves on the clipboard as plain text.
 * Only a fallback: Excel, Sheets and Numbers all put real HTML there too, which carries the
 * merges and formatting this cannot.
 */
export function looksLikeTsvTable(text: string): boolean {
  return tsvRows(text) !== null;
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Builds the editor's own table markup — first row as the header — from a TSV block. */
export function tsvToTableHtml(text: string): string {
  const rows = tsvRows(text);
  if (!rows) return '';
  const cell = (value: string, tag: 'td' | 'th') =>
    `<${tag}>${escapeHtml(value).trim() || '<br>'}</${tag}>`;
  const head = `<thead><tr>${rows[0].map(v => cell(v, 'th')).join('')}</tr></thead>`;
  const body = rows.slice(1)
    .map(row => `<tr>${row.map(v => cell(v, 'td')).join('')}</tr>`)
    .join('');
  return `<table>${head}<tbody>${body}</tbody></table>`;
}
