import { useCallback, useEffect, useRef, useState } from 'react';
import Modal from './Modal';
import { Button } from './Button';
import './MermaidDialog.css';

/**
 * Renders mermaid source to a PNG.
 *
 * <p>PNG rather than SVG because that is what a report can carry: report generation
 * inlines images as base64 and the DOCX pipeline is raster-only, so an SVG diagram would
 * survive the editor and vanish from the deliverable.
 *
 * <p>Mermaid is a large dependency — bigger than the rest of the editor put together — so
 * it is imported on first use rather than bundled into the initial load. Nothing pays for
 * it until someone opens this dialog.
 */
async function renderToSvg(source: string, id: string): Promise<string> {
  const mermaid = (await import('mermaid')).default;
  mermaid.initialize({
    startOnLoad: false,
    // Rendered once into a static image, so it must not follow the author's theme —
    // a diagram drawn in dark mode is unreadable in a report on white paper.
    theme: 'default',
    securityLevel: 'strict',
    // Labels as SVG <text>, not HTML in a <foreignObject>.
    //
    // This is the difference between a diagram that rasterises and one that cannot:
    // drawing an SVG containing a foreignObject taints the canvas, and the export then
    // fails with "Tainted canvases may not be exported". Mermaid embeds HTML labels by
    // default, so every flowchart hit it.
    htmlLabels: false,
    // Pinned to fonts the browser already has. A webfont would be an external fetch
    // inside the SVG — the other way this canvas gets tainted — and it also keeps the
    // raster identical between machines.
    fontFamily: 'trebuchet ms, verdana, arial, sans-serif',
  });
  const { svg } = await mermaid.render(id, source);
  return svg;
}

/** Rasterises rendered SVG at 2x so the diagram is not soft in a printed report. */
async function svgToPngFile(svg: string, name: string): Promise<File> {
  const SCALE = 2;
  const blob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image();
      el.onload = () => resolve(el);
      el.onerror = () => reject(new Error('Could not rasterise the diagram'));
      el.src = url;
    });

    // Firefox gives an SVG image zero intrinsic size unless the markup carries width and
    // height; fall back to the viewBox the renderer always emits.
    let { width, height } = img;
    if (!width || !height) {
      const box = /viewBox="[\d.]+ [\d.]+ ([\d.]+) ([\d.]+)"/.exec(svg);
      width = box ? Number(box[1]) : 800;
      height = box ? Number(box[2]) : 600;
    }

    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(width * SCALE));
    canvas.height = Math.max(1, Math.round(height * SCALE));
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Could not rasterise the diagram');
    // Diagrams are drawn for a light page and their text is dark; without this the
    // transparent background turns black wherever the viewer composites it.
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

    let png: Blob | null;
    try {
      png = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/png'));
    } catch {
      // Only reachable if something in the SVG made the canvas cross-origin — an
      // embedded image or webfont the diagram pulled in. Say so, rather than passing
      // the browser's "Tainted canvases may not be exported" through to the author.
      throw new Error(
        'This diagram references something outside the page and cannot be turned into an '
        + 'image. Remove any external image or font reference and try again.');
    }
    if (!png) throw new Error('Could not rasterise the diagram');
    return new File([png], name, { type: 'image/png' });
  } finally {
    URL.revokeObjectURL(url);
  }
}

/**
 * The diagram source, encoded for storage in an attribute.
 *
 * <p>Base64 rather than the raw text because DOMPurify — which the editor runs on every
 * inbound path — drops any attribute whose value contains `-->`. That is the arrow in
 * essentially every flowchart, so storing the source verbatim loses it on the first paste
 * or markdown round trip and leaves a diagram nobody can edit again.
 *
 * <p>Via TextEncoder so a diagram containing non-ASCII survives; btoa alone throws on it.
 */
export function encodeMermaidSource(source: string): string {
  const bytes = new TextEncoder().encode(source);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

/** Tolerates a raw source too, so diagrams stored before the encoding still open. */
export function decodeMermaidSource(stored: string): string {
  if (!stored) return '';
  try {
    const binary = atob(stored);
    const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  } catch {
    return stored;
  }
}

/** The three things a mermaid `style` statement can set on a node. */
export interface NodeStyle {
  /** Background. */
  fill?: string;
  /** Border. */
  stroke?: string;
  /** Label text. */
  color?: string;
}

/**
 * Coordinated triples rather than three independent pickers, because picking a fill, a
 * border and a text colour that read well together is the slow part. One click is the
 * common case; the pickers below are for when it is not.
 *
 * <p>All light-on-white with dark text: a diagram is rasterised as shown and ends up on a
 * white page in a report.
 */
const PRESETS: { name: string; style: NodeStyle }[] = [
  { name: 'Red', style: { fill: '#fdecec', stroke: '#c62828', color: '#7f1d1d' } },
  { name: 'Orange', style: { fill: '#fff4e5', stroke: '#ef6c00', color: '#7c4a03' } },
  { name: 'Yellow', style: { fill: '#fdf7d8', stroke: '#b59b00', color: '#5c4d00' } },
  { name: 'Green', style: { fill: '#e9f7ef', stroke: '#2e7d32', color: '#14532d' } },
  { name: 'Blue', style: { fill: '#e8f0fe', stroke: '#1565c0', color: '#0d3b66' } },
  { name: 'Purple', style: { fill: '#f3e8fd', stroke: '#6a1b9a', color: '#4a148c' } },
  { name: 'Grey', style: { fill: '#f1f3f5', stroke: '#6c757d', color: '#343a40' } },
];

/** What mermaid draws with when a node carries no style of its own. */
const UNSTYLED: Required<NodeStyle> = { fill: '#ececff', stroke: '#9370db', color: '#333333' };

const STYLE_LINE = /^(\s*)style\s+(\S+)\s+(.+)$/;

/** `fill:#eee,stroke-width:2px` to an ordered map, so declarations we do not set survive. */
function parseDeclarations(text: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const part of text.split(',')) {
    const at = part.indexOf(':');
    if (at > 0) out.set(part.slice(0, at).trim(), part.slice(at + 1).trim());
  }
  return out;
}

function findStyleLine(lines: string[], id: string): number {
  return lines.findIndex(line => {
    const m = STYLE_LINE.exec(line);
    return !!m && m[2] === id;
  });
}

export function readNodeStyle(source: string, id: string): NodeStyle {
  const at = findStyleLine(source.split('\n'), id);
  if (at < 0) return {};
  const decls = parseDeclarations(STYLE_LINE.exec(source.split('\n')[at])![3]);
  return { fill: decls.get('fill'), stroke: decls.get('stroke'), color: decls.get('color') };
}

/**
 * Upserts `style <id> …` for one node.
 *
 * <p>The source stays canonical mermaid — the pickers are a way of writing a statement the
 * author could have typed, not a private format. Anything already on the line that is not
 * one of our three properties is preserved, so hand-written `stroke-width` survives a click.
 *
 * <p>A patch value of empty string removes that property; a null patch removes the whole
 * statement.
 */
export function writeNodeStyle(source: string, id: string, patch: NodeStyle | null): string {
  const lines = source.split('\n');
  const at = findStyleLine(lines, id);
  const existing = at >= 0 ? STYLE_LINE.exec(lines[at])! : null;
  const decls = patch && existing ? parseDeclarations(existing[3]) : new Map<string, string>();

  if (patch) {
    for (const [key, value] of Object.entries(patch)) {
      if (value) decls.set(key, value);
      else decls.delete(key);
    }
  }

  if (!patch || decls.size === 0) {
    if (at >= 0) lines.splice(at, 1);
    return lines.join('\n');
  }

  const body = [...decls].map(([key, value]) => `${key}:${value}`).join(',');
  const line = `${existing ? existing[1] : bodyIndent(lines)}style ${id} ${body}`;
  if (at >= 0) lines[at] = line;
  // After the last real line, not after the trailing blank a source often ends with.
  else lines.splice(lastContentLine(lines) + 1, 0, line);
  return lines.join('\n');
}

/** Matches the indentation the author already uses for statements, not the header line. */
function bodyIndent(lines: string[]): string {
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim()) return /^\s*/.exec(lines[i])![0];
  }
  return '  ';
}

function lastContentLine(lines: string[]): number {
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].trim()) return i;
  }
  return lines.length - 1;
}

/** How mermaid prefixes a rendered node, by diagram family. All three accept `style`. */
const NODE_PREFIXES = ['flowchart-', 'state-', 'classId-'];

/**
 * The author's node id, recovered from the id mermaid puts on the rendered group
 * (`<renderId>-<family>-<id>-<index>`), so a click on the picture can be turned into a
 * statement about the source.
 *
 * <p>Returns null when the id is not in that shape, rather than guessing: a wrong id would
 * write a `style` statement for a node that does not exist, which is either inert or a
 * parse error, and either way looks like the colour picker is broken.
 */
export function nodeIdFromElementId(elementId: string, renderId: string): string | null {
  if (!elementId || !elementId.startsWith(`${renderId}-`)) return null;
  let rest = elementId.slice(renderId.length + 1);
  const prefix = NODE_PREFIXES.find(candidate => rest.startsWith(candidate));
  if (!prefix) return null;
  rest = rest.slice(prefix.length);
  const dash = rest.lastIndexOf('-');
  if (dash > 0 && /^\d+$/.test(rest.slice(dash + 1))) rest = rest.slice(0, dash);
  return rest || null;
}

/** `<input type="color">` only accepts 6-digit hex; anything else shows as the default. */
function asHexInput(value: string | undefined, fallback: string): string {
  return value && /^#[0-9a-f]{6}$/i.test(value) ? value : fallback;
}

/** Matches the indentation the starter diagram and the generated `style` lines use. */
const INDENT = '    ';

/**
 * Applies an edit through the browser rather than through React state.
 *
 * <p>Setting the value from a key handler would work, but it clears the textarea's native
 * undo stack — so one Tab would make everything typed before it unreachable by ctrl-Z.
 * `insertText` edits the field the way typing does, and the `input` event it fires is what
 * carries the change back into React.
 */
function replaceRange(el: HTMLTextAreaElement, from: number, to: number, text: string): boolean {
  el.focus();
  el.setSelectionRange(from, to);
  return document.execCommand('insertText', false, text);
}

/** The line boundaries fully containing [start, end) — what Tab indents as a block. */
function selectedLines(value: string, start: number, end: number): [number, number] {
  const from = value.lastIndexOf('\n', start - 1) + 1;
  const after = value.indexOf('\n', end);
  return [from, after === -1 ? value.length : after];
}

/** Leading run of up to one indent's worth of whitespace, which Shift+Tab removes. */
function outdentWidth(line: string): number {
  if (line.startsWith('\t')) return 1;
  const spaces = /^ */.exec(line)![0].length;
  return Math.min(spaces, INDENT.length);
}

interface MermaidDialogProps {
  isOpen: boolean;
  /** Existing source when re-editing a diagram; empty for a new one. */
  initialSource?: string;
  onClose: () => void;
  /** Receives the rasterised diagram and the source that produced it. */
  onInsert: (file: File, source: string) => Promise<void> | void;
}

const STARTER = 'flowchart LR\n    A(Log in) --> B(Send Payload) --> C(Get Reverse Shell)';

export default function MermaidDialog({ isOpen, initialSource, onClose, onInsert }: MermaidDialogProps) {
  const [source, setSource] = useState(initialSource || STARTER);
  const [error, setError] = useState('');
  const [inserting, setInserting] = useState(false);
  const [selected, setSelected] = useState<{ id: string; label: string } | null>(null);
  const previewRef = useRef<HTMLDivElement>(null);
  const renderSeq = useRef(0);
  // The id of the render currently in the preview, needed to read a node id back off a
  // clicked element.
  const previewId = useRef('');
  // Bumped after each successful render so the selection highlight, which lives on DOM
  // mermaid replaces wholesale, is reapplied without re-rendering the diagram itself.
  const [rendered, setRendered] = useState(0);

  useEffect(() => {
    if (isOpen) {
      setSource(initialSource || STARTER);
      setError('');
      setSelected(null);
    }
  }, [isOpen, initialSource]);

  // Debounced so a syntax error mid-keystroke does not flash an error on every character.
  useEffect(() => {
    if (!isOpen) return;
    const seq = ++renderSeq.current;
    const timer = setTimeout(async () => {
      const id = `mermaid-preview-${seq}`;
      try {
        const svg = await renderToSvg(source, id);
        // A slower earlier render must not overwrite a newer one.
        if (seq !== renderSeq.current || !previewRef.current) return;
        previewRef.current.innerHTML = svg;
        previewId.current = id;
        setRendered(n => n + 1);
        setError('');
      } catch (e) {
        if (seq !== renderSeq.current) return;
        setError(e instanceof Error ? e.message : 'Diagram could not be rendered');
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [source, isOpen]);

  // Marks the selected node in the preview, and drops a selection whose node the author
  // has since renamed or deleted in the source.
  useEffect(() => {
    const root = previewRef.current;
    if (!root || !rendered) return;
    root.querySelectorAll('g.node.is-selected').forEach(el => el.classList.remove('is-selected'));
    if (!selected) return;
    const match = [...root.querySelectorAll<SVGGElement>('g.node')]
      .find(el => nodeIdFromElementId(el.id, previewId.current) === selected.id);
    if (match) match.classList.add('is-selected');
    else setSelected(null);
  }, [selected, rendered]);

  const handlePreviewClick = useCallback((event: React.MouseEvent) => {
    const group = (event.target as Element).closest?.('g.node') as SVGGElement | null;
    if (!group) {
      setSelected(null);
      return;
    }
    const id = nodeIdFromElementId(group.id, previewId.current);
    if (id) setSelected({ id, label: group.textContent?.trim() || id });
  }, []);

  const restyle = useCallback((patch: NodeStyle | null) => {
    if (selected) setSource(current => writeNodeStyle(current, selected.id, patch));
  }, [selected]);

  const selectedStyle = selected ? readNodeStyle(source, selected.id) : {};

  // Tab types an indent here instead of moving focus, which is what anyone writing
  // nested source expects — but it also removes the only way a keyboard user leaves the
  // field. Escape arms the next Tab to move focus normally, the convention code editors
  // use; the hidden hint below is how a screen reader learns that.
  const tabMovesFocus = useRef(false);

  const handleSourceKeyDown = useCallback((event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    const el = event.currentTarget;

    if (event.key === 'Escape') {
      tabMovesFocus.current = true;
      return;
    }

    if (event.key === 'Enter' && !event.shiftKey) {
      // Carry the current line's indentation onto the next one; without it every line of
      // an indented body has to be re-indented by hand.
      const { selectionStart: start, selectionEnd: end, value } = el;
      const [lineStart] = selectedLines(value, start, start);
      const indent = /^[ \t]*/.exec(value.slice(lineStart, start))![0];
      if (!indent) return;
      event.preventDefault();
      if (!replaceRange(el, start, end, `\n${indent}`)) {
        setSource(`${value.slice(0, start)}\n${indent}${value.slice(end)}`);
      }
      return;
    }

    if (event.key !== 'Tab') {
      tabMovesFocus.current = false;
      return;
    }

    if (tabMovesFocus.current) {
      tabMovesFocus.current = false;
      return;
    }

    const { selectionStart: start, selectionEnd: end, value } = el;
    const spansLines = value.slice(start, end).includes('\n');

    if (!spansLines && !event.shiftKey) {
      event.preventDefault();
      if (!replaceRange(el, start, end, INDENT)) {
        setSource(value.slice(0, start) + INDENT + value.slice(end));
      }
      return;
    }

    // A selection touching several lines shifts all of them, so a whole subgraph moves at
    // once — and Shift+Tab does the same in reverse, including on a single line.
    event.preventDefault();
    const [from, to] = selectedLines(value, start, end);
    const lines = value.slice(from, to).split('\n');
    let headDelta = 0;
    let totalDelta = 0;
    const shifted = lines.map((line, index) => {
      // Blank lines are left alone, so indenting a block never leaves trailing whitespace.
      const width = event.shiftKey ? -outdentWidth(line) : (line.trim() ? INDENT.length : 0);
      if (index === 0) headDelta = width;
      totalDelta += width;
      return width < 0 ? line.slice(-width) : INDENT.slice(0, width) + line;
    }).join('\n');

    if (totalDelta === 0) return;
    if (!replaceRange(el, from, to, shifted)) {
      setSource(value.slice(0, from) + shifted + value.slice(to));
    }
    el.setSelectionRange(Math.max(from, start + headDelta), Math.max(from, end + totalDelta));
  }, []);

  const handleInsert = useCallback(async () => {
    setInserting(true);
    try {
      const svg = await renderToSvg(source, `mermaid-insert-${Date.now()}`);
      const file = await svgToPngFile(svg, 'diagram.png');
      await onInsert(file, source);
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Diagram could not be inserted');
    } finally {
      setInserting(false);
    }
  }, [source, onInsert, onClose]);

  if (!isOpen) return null;

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={initialSource ? 'Edit diagram' : 'Insert diagram'}
      size="xl"
      // Nothing here is a click away from being retyped: this modal holds authored source,
      // and a drag-select that ends past the modal edge, or a stray click while the native
      // colour picker is up, would otherwise land on the overlay and discard the diagram.
      // Cancel, the close button, and inserting are the only ways out.
      closeOnOverlayClick={false}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={inserting}>Cancel</Button>
          <Button variant="primary" onClick={handleInsert} disabled={inserting || !!error}>
            {inserting ? 'Inserting…' : initialSource ? 'Replace' : 'Insert'}
          </Button>
        </>
      }
    >
      <div className="mermaid-dialog">
        <div className="mermaid-dialog__pane">
          <label className="mermaid-dialog__label" htmlFor="mermaid-source">Mermaid</label>
          <textarea
            id="mermaid-source"
            className="mermaid-dialog__source"
            value={source}
            spellCheck={false}
            aria-describedby="mermaid-source-keys"
            onChange={e => setSource(e.target.value)}
            onKeyDown={handleSourceKeyDown}
          />
          <span id="mermaid-source-keys" className="mermaid-dialog__sr">
            Tab indents, Shift Tab outdents, and both apply to every line of a selection.
            Press Escape then Tab to move out of this field.
          </span>
          {error
            ? <p className="mermaid-dialog__error">{error}</p>
            : <p className="mermaid-dialog__hint">
                Inserted as an image, so it appears in reports. Double-click it later to edit.
              </p>}
        </div>
        <div className="mermaid-dialog__pane">
          <span className="mermaid-dialog__label">Preview</span>
          <div className="mermaid-dialog__preview" ref={previewRef} onClick={handlePreviewClick} />
        </div>

        <div className="mermaid-dialog__styler">
          {!selected
            ? <p className="mermaid-dialog__hint mermaid-dialog__hint--styler">
                Click a shape in the preview to colour it.
              </p>
            : <>
                <span className="mermaid-dialog__chip" title={`Node ${selected.id}`}>
                  {selected.label}
                </span>
                <div className="mermaid-dialog__swatches">
                  {PRESETS.map(preset => (
                    <button
                      key={preset.name}
                      type="button"
                      className="mermaid-dialog__swatch"
                      title={preset.name}
                      aria-label={preset.name}
                      style={{ background: preset.style.fill, borderColor: preset.style.stroke }}
                      onClick={() => restyle(preset.style)}
                    />
                  ))}
                </div>
                <div className="mermaid-dialog__pickers">
                  {([
                    ['fill', 'Background'],
                    ['stroke', 'Border'],
                    ['color', 'Text'],
                  ] as const).map(([key, label]) => (
                    <label key={key} className="mermaid-dialog__picker">
                      <input
                        type="color"
                        value={asHexInput(selectedStyle[key], UNSTYLED[key])}
                        onChange={e => restyle({ [key]: e.target.value })}
                      />
                      {label}
                    </label>
                  ))}
                </div>
                <button
                  type="button"
                  className="mermaid-dialog__clear"
                  onClick={() => restyle(null)}
                >
                  Clear
                </button>
              </>}
        </div>
      </div>
    </Modal>
  );
}
