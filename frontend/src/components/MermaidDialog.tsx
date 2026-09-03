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

interface MermaidDialogProps {
  isOpen: boolean;
  /** Existing source when re-editing a diagram; empty for a new one. */
  initialSource?: string;
  onClose: () => void;
  /** Receives the rasterised diagram and the source that produced it. */
  onInsert: (file: File, source: string) => Promise<void> | void;
}

const STARTER = 'graph TD\n  A[Finding] --> B{Exploitable?}\n  B -->|Yes| C[Critical]\n  B -->|No| D[Informational]';

export default function MermaidDialog({ isOpen, initialSource, onClose, onInsert }: MermaidDialogProps) {
  const [source, setSource] = useState(initialSource || STARTER);
  const [error, setError] = useState('');
  const [inserting, setInserting] = useState(false);
  const previewRef = useRef<HTMLDivElement>(null);
  const renderSeq = useRef(0);

  useEffect(() => {
    if (isOpen) {
      setSource(initialSource || STARTER);
      setError('');
    }
  }, [isOpen, initialSource]);

  // Debounced so a syntax error mid-keystroke does not flash an error on every character.
  useEffect(() => {
    if (!isOpen) return;
    const seq = ++renderSeq.current;
    const timer = setTimeout(async () => {
      try {
        const svg = await renderToSvg(source, `mermaid-preview-${seq}`);
        // A slower earlier render must not overwrite a newer one.
        if (seq !== renderSeq.current || !previewRef.current) return;
        previewRef.current.innerHTML = svg;
        setError('');
      } catch (e) {
        if (seq !== renderSeq.current) return;
        setError(e instanceof Error ? e.message : 'Diagram could not be rendered');
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [source, isOpen]);

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
            onChange={e => setSource(e.target.value)}
          />
          {error
            ? <p className="mermaid-dialog__error">{error}</p>
            : <p className="mermaid-dialog__hint">
                Inserted as an image, so it appears in reports. Double-click it later to edit.
              </p>}
        </div>
        <div className="mermaid-dialog__pane">
          <span className="mermaid-dialog__label">Preview</span>
          <div className="mermaid-dialog__preview" ref={previewRef} />
        </div>
      </div>
    </Modal>
  );
}
