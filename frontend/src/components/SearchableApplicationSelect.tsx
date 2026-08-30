import { useState, useRef, useEffect, useCallback } from 'react';
import { applicationsApi } from '../api';
import type { Application } from '../types';
import './SearchableApplicationSelect.css';

interface SearchableApplicationSelectProps {
  value: string;
  appId: string;
  applicationName: string;
  onChange: (value: string, appId: string, name: string) => void;
  onClear: () => void;
  onBlur?: () => void;
  placeholder?: string;
  allowCreate?: boolean;
  disabled?: boolean;
  /** Which field takes visual priority in the trigger text and dropdown list — defaults to appId */
  primaryField?: 'appId' | 'name';
}

export default function SearchableApplicationSelect({
  value,
  appId,
  applicationName,
  onChange,
  onClear,
  onBlur,
  placeholder = 'Search applications...',
  allowCreate = false,
  disabled = false,
  primaryField = 'appId',
}: SearchableApplicationSelectProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Application[]>([]);
  const [loading, setLoading] = useState(false);
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const fetchApplications = useCallback(async (searchQuery: string) => {
    setLoading(true);
    try {
 const res = await applicationsApi.getAll(0, 50, searchQuery);
      if (res.success && res.data) {
        setResults(Array.isArray(res.data) ? res.data : (res.data as any).content || []);
      }
    } catch (err) {
      console.error('Failed to fetch applications:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  const filteredResults = results.filter((app) => {
    const q = query.toLowerCase();
    const matchAppId = app.appId?.toLowerCase().includes(q);
    const matchName = app.name?.toLowerCase().includes(q);
    return matchAppId || matchName;
  });

  useEffect(() => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }
    debounceTimerRef.current = setTimeout(() => {
      if (query.trim().length > 0) {
        fetchApplications(query.trim());
      } else {
        setResults([]);
      }
    }, 300);
    return () => {
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
      }
    };
  }, [query, fetchApplications]);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  useEffect(() => {
    if (isOpen && inputRef.current) {
      inputRef.current.focus();
    }
  }, [isOpen]);

  const handleSelect = (app: Application) => {
    onChange(app.id, app.appId || '', app.name);
    setQuery('');
    setIsOpen(false);
    setHighlightedIndex(-1);
  };

  const handleClear = () => {
    onClear();
    setQuery('');
    setIsOpen(false);
    setHighlightedIndex(-1);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (!isOpen) {
      if (e.key === 'ArrowDown' || e.key === 'Enter') {
        e.preventDefault();
        setIsOpen(true);
      }
      return;
    }

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setHighlightedIndex((prev) => Math.min(prev + 1, filteredResults.length - 1));
        break;
      case 'ArrowUp':
        e.preventDefault();
        setHighlightedIndex((prev) => Math.max(prev - 1, -1));
        break;
      case 'Enter':
        e.preventDefault();
        if (highlightedIndex >= 0 && highlightedIndex < filteredResults.length) {
          handleSelect(filteredResults[highlightedIndex]);
        }
        break;
      case 'Escape':
        setIsOpen(false);
        setHighlightedIndex(-1);
        break;
    }
  };

  const displayText = primaryField === 'name'
    ? (query || (value ? applicationName : ''))
    : (value && appId && applicationName
        ? `${appId} — ${applicationName}`
        : query || (value ? applicationName : ''));

  const canCreate = allowCreate && query.trim().length > 0 && filteredResults.length === 0;

  return (
    <div className="sas-wrap" ref={containerRef}>
      <div className="sas-trigger">
        <input
          ref={inputRef}
          type="text"
          className="sas-input"
          value={displayText}
          onChange={(e) => {
            setQuery(e.target.value);
            if (!isOpen) setIsOpen(true);
            if (e.target.value && !value) {
              onChange('', '', e.target.value);
            }
          }}
          onKeyDown={handleKeyDown}
          onFocus={() => setIsOpen(true)}
          onBlur={onBlur}
          placeholder={placeholder}
          disabled={disabled}
        />
        {value && (
          <button
            type="button"
            className="sas-clear"
            onClick={handleClear}
            disabled={disabled}
          >
            ×
          </button>
        )}
        <button
          type="button"
          className="sas-arrow"
          onClick={() => setIsOpen(!isOpen)}
          disabled={disabled}
        >
          ▾
        </button>
      </div>
      {isOpen && (
        <div className="sas-dropdown">
          {loading && (
            <div className="sas-loading">Loading...</div>
          )}
          {!loading && canCreate && (
            <div className="sas-create" onClick={() => setIsOpen(false)}>
              <span className="sas-create-text">Create new application: "{query}"</span>
            </div>
          )}
          {!loading && !canCreate && filteredResults.length === 0 && (
            <div className="sas-no-results">No results</div>
          )}
          {!loading && (
            <ul className="sas-list">
              {filteredResults.map((app, idx) => (
                <li
                  key={app.id}
                  className={`sas-item${idx === highlightedIndex ? ' sas-highlighted' : ''}`}
                  // mousedown, not click — selection must run BEFORE the input's blur,
                  // otherwise blur-driven autofill sees the typed text instead of the pick
                  onMouseDown={(e) => { e.preventDefault(); handleSelect(app); }}
                  onMouseEnter={() => setHighlightedIndex(idx)}
                >
                  {primaryField === 'name' ? (
                    <>
                      <span className="sas-item-id">{app.name}</span>
                      <span className="sas-item-name">{app.appId || '—'}</span>
                    </>
                  ) : (
                    <>
                      <span className="sas-item-id">{app.appId || '—'}</span>
                      <span className="sas-item-name">{app.name}</span>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
