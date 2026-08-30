import { useEffect, useRef, useState } from 'react';
import { ChevronDown, X as XIcon } from 'lucide-react';
import './SearchableSelect.css';

/** One dropdown choice. `value` is what the filter state holds; `label` is what the user sees. */
export interface SelectOption { value: string; label: string; }

export interface SearchableSelectProps {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  placeholder: string;
  /** When set, typing debounces a server search instead of filtering `options` locally. */
  onQueryChange?: (query: string) => void;
  loading?: boolean;
  /** Show the search box — off for small fixed lists (e.g. severity). Default on. */
  searchable?: boolean;
}

export interface MultiSelectProps {
  selected: string[];
  onChange: (values: string[]) => void;
  options: SelectOption[];
  placeholder: string;
  /** Show the search box — off for small fixed lists (e.g. statuses). Default on. */
  searchable?: boolean;
  /** When set, typing debounces a server search instead of filtering `options` locally
   *  (so an unbounded set is reachable, not just what's loaded). */
  onQueryChange?: (query: string) => void;
  loading?: boolean;
}

/** Close the dropdown on any click outside `ref`. */
function useClickOutside(ref: React.RefObject<HTMLElement | null>, close: () => void) {
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}

/** Focus the search box when the dropdown opens; reset the query when it closes. */
function useDropdownQuery(open: boolean) {
  const [query, setQuery] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!open) { setQuery(''); return; }
    setTimeout(() => inputRef.current?.focus(), 0);
  }, [open]);
  return { query, setQuery, inputRef };
}

/**
 * Multi-choice filter pill. The collapsed label shows the single selection's label, or
 * "N <noun>" for several — the noun comes from the placeholder ("All Statuses" → "Statuses").
 */
export function MultiSelect({ selected, onChange, options, placeholder, searchable = true,
                              onQueryChange, loading }: MultiSelectProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const { query, setQuery, inputRef } = useDropdownQuery(open);
  useClickOutside(ref, () => setOpen(false));

  // Server-search mode: debounce the query up to the parent (kept in a ref so it isn't a dep).
  const onQueryChangeRef = useRef(onQueryChange);
  useEffect(() => { onQueryChangeRef.current = onQueryChange; }, [onQueryChange]);
  useEffect(() => {
    if (!open || !onQueryChangeRef.current) return;
    const t = setTimeout(() => onQueryChangeRef.current?.(query), 300);
    return () => clearTimeout(t);
  }, [query, open]);

  // Server mode renders whatever the parent supplies; client mode filters locally.
  const displayed = onQueryChange ? options : options.filter(o => o.label.toLowerCase().includes(query.toLowerCase()));
  const label = selected.length === 0
    ? placeholder
    : selected.length === 1
      ? (options.find(o => o.value === selected[0])?.label ?? placeholder)
      : `${selected.length} ${placeholder.replace(/^all\s+/i, '')}`;

  const toggle = (value: string) =>
    onChange(selected.includes(value) ? selected.filter(v => v !== value) : [...selected, value]);

  return (
    <div className="ss-wrap" ref={ref}>
      <button
        type="button"
        className={`ss-trigger${selected.length > 0 ? ' ss-trigger--active' : ''}`}
        onClick={() => setOpen(v => !v)}
      >
        <span className="ss-trigger-label">{label}</span>
        {selected.length > 0
          ? <XIcon size={13} onClick={e => { e.stopPropagation(); onChange([]); setOpen(false); }} className="ss-clear" />
          : <ChevronDown size={13} className="ss-chevron" />
        }
      </button>
      {open && (
        <div className="ss-dropdown">
          {searchable && (
            <div className="ss-search-wrap">
              <input ref={inputRef} className="ss-search" value={query}
                onChange={e => setQuery(e.target.value)} placeholder="Search…" />
            </div>
          )}
          <div className="ss-list">
            {displayed.map(o => (
              <button key={o.value} type="button"
                className={`ss-option${selected.includes(o.value) ? ' ss-option--selected' : ''}`}
                onClick={() => toggle(o.value)}>
                {o.label}
              </button>
            ))}
            {loading && <div className="ss-empty">Searching…</div>}
            {!loading && displayed.length === 0 && <div className="ss-empty">No results</div>}
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Single-choice filter pill with an optional search box. Filters `options` locally by default; pass
 * `onQueryChange` to debounce the typed query up to the parent for a server search instead. The
 * first entry always clears the filter back to `placeholder`.
 */
export default function SearchableSelect({
  value, onChange, options, placeholder, onQueryChange, loading, searchable = true,
}: SearchableSelectProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const { query, setQuery, inputRef } = useDropdownQuery(open);
  useClickOutside(ref, () => setOpen(false));

  // Server-search mode: debounce the query up to the parent (kept in a ref so it isn't a dep).
  const onQueryChangeRef = useRef(onQueryChange);
  useEffect(() => { onQueryChangeRef.current = onQueryChange; }, [onQueryChange]);
  useEffect(() => {
    if (!open || !onQueryChangeRef.current) return;
    const t = setTimeout(() => onQueryChangeRef.current?.(query), 300);
    return () => clearTimeout(t);
  }, [query, open]);

  const displayed = onQueryChange ? options : options.filter(o => o.label.toLowerCase().includes(query.toLowerCase()));
  const selected = options.find(o => o.value === value);

  return (
    <div className="ss-wrap" ref={ref}>
      <button
        type="button"
        className={`ss-trigger${value ? ' ss-trigger--active' : ''}`}
        onClick={() => setOpen(v => !v)}
      >
        <span className="ss-trigger-label">{selected?.label ?? placeholder}</span>
        {value
          ? <XIcon size={13} onClick={(e) => { e.stopPropagation(); onChange(''); setOpen(false); }} className="ss-clear" />
          : <ChevronDown size={13} className="ss-chevron" />
        }
      </button>
      {open && (
        <div className="ss-dropdown">
          {searchable && (
            <div className="ss-search-wrap">
              <input ref={inputRef} className="ss-search" value={query}
                onChange={e => setQuery(e.target.value)} placeholder="Search…" />
            </div>
          )}
          <div className="ss-list">
            <button
              type="button"
              className={`ss-option${!value ? ' ss-option--selected' : ''}`}
              onClick={() => { onChange(''); setOpen(false); }}
            >
              {placeholder}
            </button>
            {displayed.map(o => (
              <button
                key={o.value}
                type="button"
                className={`ss-option${value === o.value ? ' ss-option--selected' : ''}`}
                onClick={() => { onChange(o.value); setOpen(false); }}
              >
                {o.label}
              </button>
            ))}
            {loading && <div className="ss-empty">Searching…</div>}
            {!loading && displayed.length === 0 && <div className="ss-empty">No results</div>}
          </div>
        </div>
      )}
    </div>
  );
}
