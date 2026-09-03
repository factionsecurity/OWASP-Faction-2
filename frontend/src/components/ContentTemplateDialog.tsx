import { useEffect, useRef, useState } from 'react';
import { ArrowDownToLine, ArrowUpToLine, Replace, X } from 'lucide-react';
import DOMPurify from 'dompurify';
import { contentTemplatesApi } from '../api';
import type { ContentTemplate, ContentTemplateInsertMode, ContentTemplateScope } from '../types';
import './ContentTemplateDialog.css';

interface Props {
  isOpen: boolean;
  scope: ContentTemplateScope;
  /** True when the editor already holds text — prepend/append are pointless without it. */
  hasContent: boolean;
  onClose: () => void;
  onInsert: (template: ContentTemplate, mode: ContentTemplateInsertMode) => void;
}

// Template bodies are rich HTML — reduce to plain text for search and card previews
function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
}

function contentPreview(html: string): string {
  const text = stripHtml(html);
  return text.length > 160 ? text.slice(0, 160).trimEnd() + '…' : text;
}

const MODES: Array<{ mode: ContentTemplateInsertMode; label: string; icon: typeof Replace; hint: string }> = [
  { mode: 'OVERWRITE', label: 'Overwrite', icon: Replace, hint: 'Replace everything in the editor with this template' },
  { mode: 'PREPEND', label: 'Prepend', icon: ArrowUpToLine, hint: 'Insert the template above the current text' },
  { mode: 'APPEND', label: 'Append', icon: ArrowDownToLine, hint: 'Insert the template below the current text' },
];

/**
 * Picks a content template and how it lands in the editor. Selecting a template swaps the
 * list for a confirmation step, because "which template" and "overwrite or not" are two
 * separate decisions — the second one is destructive and shouldn't ride on a single click.
 */
export default function ContentTemplateDialog({ isOpen, scope, hasContent, onClose, onInsert }: Props) {
  const [items, setItems] = useState<ContentTemplate[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<ContentTemplate | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    setSearch('');
    setSelected(null);
    setError(null);
    setLoading(true);
    contentTemplatesApi.getForScope(scope)
      .then(res => setItems(res.data ?? []))
      .catch(() => { setItems([]); setError('Could not load templates.'); })
      .finally(() => setLoading(false));
    setTimeout(() => searchRef.current?.focus(), 50);
  }, [isOpen, scope]);

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // Escape backs out of the mode step first, so a misclick doesn't close the whole dialog
      if (selected) setSelected(null);
      else onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [isOpen, onClose, selected]);

  if (!isOpen) return null;

  const q = search.toLowerCase();
  const filtered = items.filter(t =>
    t.name.toLowerCase().includes(q) ||
    (t.description || '').toLowerCase().includes(q) ||
    stripHtml(t.content).toLowerCase().includes(q)
  );

  return (
    <div className="ct-overlay" onClick={onClose}>
      <div className="ct-dialog" onClick={e => e.stopPropagation()}>
        <div className="ct-dialog-header">
          <h3>{selected ? 'Insert Template' : 'Templates'}</h3>
          <button className="ct-close-btn" onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        {selected ? (
          <>
            <div className="ct-chosen">
              <div className="ct-chosen-name">{selected.name}</div>
              {selected.description && <div className="ct-chosen-desc">{selected.description}</div>}
              <div className="ct-chosen-preview" dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(selected.content) }} />
            </div>
            <div className="ct-modes">
              {MODES.map(({ mode, label, icon: Icon, hint }) => {
                // With an empty editor all three modes do the same thing — only offer the honest one
                const disabled = !hasContent && mode !== 'OVERWRITE';
                return (
                  <button
                    key={mode}
                    type="button"
                    className="ct-mode-btn"
                    disabled={disabled}
                    title={disabled ? 'The editor is empty — the template is inserted as-is' : hint}
                    onClick={() => onInsert(selected, mode)}
                  >
                    <Icon size={14} />
                    <span>{hasContent ? label : 'Insert'}</span>
                  </button>
                );
              })}
            </div>
            <div className="ct-back-row">
              <button type="button" className="ct-back-btn" onClick={() => setSelected(null)}>
                Back to templates
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="ct-search-row">
              <input
                ref={searchRef}
                className="ct-search-input"
                type="text"
                placeholder="Search templates..."
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
            </div>

            <div className="ct-results">
              {loading && <div className="ct-empty">Loading...</div>}
              {!loading && error && <div className="ct-empty">{error}</div>}
              {!loading && !error && filtered.length === 0 && (
                <div className="ct-empty">
                  {items.length === 0
                    ? 'No templates configured for this area yet.'
                    : 'No templates match that search.'}
                </div>
              )}
              {!loading && filtered.map(t => (
                <div key={t.id} className="ct-card" onClick={() => setSelected(t)}>
                  <div className="ct-card-name">{t.name}</div>
                  {t.description && <div className="ct-card-desc">{t.description}</div>}
                  <div className="ct-card-preview">{contentPreview(t.content)}</div>
                </div>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
