import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AppWindow, ClipboardList, Loader2, Search, ShieldAlert, Tags, Users, X } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { searchApi } from '../api';
import type { GlobalSearchGroup, GlobalSearchGroupType, GlobalSearchItem } from '../types';
import { usePermissions } from '../utils/permissions';
import { Badge, SeverityBadge } from '../components';
import './GlobalSearch.css';

/** Matches the server's minimum; shorter text is not sent. */
const MIN_LENGTH = 2;
/** Long enough that typing a word sends one request, short enough to feel live. */
const DEBOUNCE_MS = 250;

const GROUPS: Record<GlobalSearchGroupType, { label: string; icon: LucideIcon; listPath?: string }> = {
  vulnerabilities: { label: 'Vulnerabilities', icon: ShieldAlert, listPath: '/vulnerabilities' },
  assessments: { label: 'Assessments', icon: ClipboardList, listPath: '/assessments' },
  applications: { label: 'Applications', icon: AppWindow, listPath: '/applications' },
  users: { label: 'Users', icon: Users, listPath: '/users' },
  assessmentTypes: { label: 'Assessment Types', icon: Tags },
};

/** Where a result opens. */
function pathFor(type: GlobalSearchGroupType, item: GlobalSearchItem): string {
  switch (type) {
    case 'vulnerabilities': return `/vulnerabilities?vuln=${encodeURIComponent(item.id)}`;
    case 'assessments': return `/assessments/${encodeURIComponent(item.id)}`;
    case 'applications': return `/applications/${encodeURIComponent(item.id)}/edit`;
    // The users list has no page per user; it opens filtered down to this one.
    case 'users': return `/users?search=${encodeURIComponent(item.subtitle?.split(' · ')[0] || item.title)}`;
    case 'assessmentTypes': return `/assessments/type/${encodeURIComponent(item.id)}`;
  }
}

type Row =
  | { kind: 'item'; type: GlobalSearchGroupType; item: GlobalSearchItem; path: string }
  | { kind: 'more'; type: GlobalSearchGroupType; total: number; path: string };

/**
 * The magnifying glass in the top bar: finds vulnerabilities, assessments, applications, users and
 * assessment types in one box. Staff only — the server refuses client-side accounts, so the button
 * is not offered to them either. Opens with a click or Ctrl/⌘+K.
 */
export default function GlobalSearch() {
  const { isInternal } = usePermissions();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [groups, setGroups] = useState<GlobalSearchGroup[]>([]);
  const [searchedFor, setSearchedFor] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [active, setActive] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const requestRef = useRef<AbortController | null>(null);

  const close = useCallback(() => {
    setOpen(false);
    requestRef.current?.abort();
  }, []);

  // Ctrl/⌘+K from anywhere.
  useEffect(() => {
    if (!isInternal) return;
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen(prev => !prev);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [isInternal]);

  useEffect(() => {
    if (open) inputRef.current?.select();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) close();
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open, close]);

  // Search as the text settles. Each new request cancels the one before it, so a slow answer to
  // an earlier keystroke can never land on top of a newer one.
  useEffect(() => {
    const query = text.trim();
    if (query.length < MIN_LENGTH) {
      requestRef.current?.abort();
      setGroups([]);
      setSearchedFor('');
      setLoading(false);
      setError('');
      return;
    }
    setLoading(true);
    const timer = window.setTimeout(() => {
      requestRef.current?.abort();
      const controller = new AbortController();
      requestRef.current = controller;
      searchApi.search(query, controller.signal)
        .then(res => {
          if (controller.signal.aborted) return;
          setGroups(res.data?.groups ?? []);
          setSearchedFor(query);
          setError('');
          setActive(0);
        })
        .catch(err => {
          if (controller.signal.aborted || err?.name === 'CanceledError') return;
          setGroups([]);
          setError('Search failed. Try again in a moment.');
        })
        .finally(() => {
          if (!controller.signal.aborted) setLoading(false);
        });
    }, DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [text]);

  // Every row the keyboard can land on, in display order.
  const rows = useMemo<Row[]>(() => groups.flatMap(group => {
    const items: Row[] = group.items.map(item => ({
      kind: 'item', type: group.type, item, path: pathFor(group.type, item),
    }));
    const listPath = GROUPS[group.type]?.listPath;
    if (listPath && group.total > group.items.length) {
      items.push({
        kind: 'more', type: group.type, total: group.total,
        path: `${listPath}?search=${encodeURIComponent(searchedFor)}`,
      });
    }
    return items;
  }), [groups, searchedFor]);

  const go = (row: Row | undefined) => {
    if (!row) return;
    close();
    navigate(row.path);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      close();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive(i => (rows.length ? (i + 1) % rows.length : 0));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive(i => (rows.length ? (i - 1 + rows.length) % rows.length : 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      go(rows[active]);
    }
  };

  // Keep the highlighted row in view while arrowing through a long list.
  useEffect(() => {
    containerRef.current?.querySelector(`[data-row="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  if (!isInternal) return null;

  const query = text.trim();
  const isMac = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform);
  let rowIndex = -1;

  return (
    <div className="global-search" ref={containerRef}>
      <button
        type="button"
        className="global-search-btn"
        onClick={() => (open ? close() : setOpen(true))}
        title={`Search (${isMac ? '⌘' : 'Ctrl'}+K)`}
        aria-label="Search"
        aria-expanded={open}
      >
        <Search size={17} />
      </button>

      {open && (
        <div className="global-search-panel" role="dialog" aria-label="Search">
          <div className="global-search-input-row">
            <Search size={16} className="global-search-input-icon" />
            <input
              ref={inputRef}
              className="global-search-input"
              value={text}
              onChange={e => setText(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder="Search vulnerabilities, assessments, applications, users…"
              aria-label="Search everything"
              role="combobox"
              aria-expanded={rows.length > 0}
              aria-controls="global-search-results"
              aria-activedescendant={rows.length ? `global-search-row-${active}` : undefined}
              autoComplete="off"
              spellCheck={false}
            />
            {loading && <Loader2 size={15} className="global-search-spinner" />}
            {text && !loading && (
              <button type="button" className="global-search-clear" onClick={() => { setText(''); inputRef.current?.focus(); }}
                aria-label="Clear search">
                <X size={14} />
              </button>
            )}
          </div>

          <div className="global-search-results" id="global-search-results" role="listbox">
            {query.length < MIN_LENGTH ? (
              <div className="global-search-hint">
                Type at least {MIN_LENGTH} characters. Names, tracking IDs, app IDs, usernames and emails all work.
              </div>
            ) : error ? (
              <div className="global-search-hint global-search-error">{error}</div>
            ) : !loading && searchedFor === query && groups.length === 0 ? (
              <div className="global-search-hint">Nothing matches “{query}”.</div>
            ) : (
              groups.map(group => {
                const meta = GROUPS[group.type];
                if (!meta) return null;
                const Icon = meta.icon;
                return (
                  <div key={group.type} className="global-search-group">
                    <div className="global-search-group-header">
                      <Icon size={13} />
                      <span>{meta.label}</span>
                      <span className="global-search-group-count">{group.total}</span>
                    </div>
                    {group.items.map(item => {
                      rowIndex += 1;
                      const index = rowIndex;
                      return (
                        <div
                          key={item.id}
                          id={`global-search-row-${index}`}
                          data-row={index}
                          role="option"
                          aria-selected={index === active}
                          className={`global-search-item${index === active ? ' active' : ''}`}
                          onMouseEnter={() => setActive(index)}
                          onMouseDown={e => e.preventDefault()}
                          onClick={() => go(rows[index])}
                        >
                          <div className="global-search-item-text">
                            <span className="global-search-item-title">{item.title}</span>
                            {item.subtitle && <span className="global-search-item-subtitle">{item.subtitle}</span>}
                          </div>
                          {item.badge && (group.type === 'vulnerabilities'
                            ? <SeverityBadge severity={item.badge} />
                            : <Badge variant="secondary" size="sm">{item.badge}</Badge>)}
                        </div>
                      );
                    })}
                    {meta.listPath && group.total > group.items.length && (() => {
                      rowIndex += 1;
                      const index = rowIndex;
                      return (
                        <div
                          id={`global-search-row-${index}`}
                          data-row={index}
                          role="option"
                          aria-selected={index === active}
                          className={`global-search-more${index === active ? ' active' : ''}`}
                          onMouseEnter={() => setActive(index)}
                          onMouseDown={e => e.preventDefault()}
                          onClick={() => go(rows[index])}
                        >
                          See all {group.total} in {meta.label}
                        </div>
                      );
                    })()}
                  </div>
                );
              })
            )}
          </div>

          <div className="global-search-footer">
            <span><kbd>↑</kbd><kbd>↓</kbd> to move</span>
            <span><kbd>Enter</kbd> to open</span>
            <span><kbd>Esc</kbd> to close</span>
          </div>
        </div>
      )}
    </div>
  );
}
