import { ReactNode, useState, useEffect, useRef } from 'react';
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, ChevronsUpDown, ChevronUp, ChevronDown, Search, SlidersHorizontal, X } from 'lucide-react';
import './DataTable.css';

export interface Column<T> {
  header: string;
  accessor?: keyof T;
  render?: (item: T) => ReactNode;
  width?: string;
  /**
   * Backend sort field for this column (the entity property the API sorts on,
   * e.g. `'name'` or `'application.name'`). Present ⇒ the header is clickable
   * and sorting is delegated to the server. Omit for columns the API cannot
   * order by (computed/aggregated cells, action buttons).
   */
  sortKey?: string;
}

export type SortDirection = 'asc' | 'desc';

export interface SortState {
  key: string;
  direction: SortDirection;
}

/**
 * Serialize a sort state into the `field,dir` query parameter every paginated
 * endpoint accepts (parsed server-side by `PageableUtil`). Returns `undefined`
 * when unsorted so the caller omits the param and the endpoint's default order
 * applies.
 */
export function sortParam(sort: SortState | null | undefined): string | undefined {
  return sort ? `${sort.key},${sort.direction}` : undefined;
}

/**
 * Advance a column through the header-click cycle: unsorted → asc → desc →
 * unsorted (back to the endpoint's default order).
 */
export function nextSort(current: SortState | null | undefined, key: string): SortState | null {
  if (!current || current.key !== key) return { key, direction: 'asc' };
  if (current.direction === 'asc') return { key, direction: 'desc' };
  return null;
}

export interface PaginationInfo {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  loading: boolean;
  pagination: PaginationInfo;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
  onSearchChange: (search: string) => void;
  searchPlaceholder?: string;
  /** Render the search box. Off for tables whose endpoint has no text search — a search box that
   *  silently ignores input is worse than none. The placeholder is kept for when search is re-enabled. */
  searchable?: boolean;
  emptyMessage?: string;
  idAccessor: keyof T;
  onRowClick?: (item: T) => void;
  /** Zone 2 — inline filter controls rendered to the right of the search box. */
  headerChildren?: ReactNode;
  /** Zone 3 — advanced-filter fields shown in the collapsible panel below the toolbar.
   *  When provided, an "Advanced" toggle appears; the panel stages changes until Apply. */
  advancedFilters?: ReactNode;
  /** Number of active advanced filters. Drives the presence dot on the Advanced toggle
   *  (>0 shows the dot); the chips below the toolbar carry the per-filter detail. */
  advancedActiveCount?: number;
  /** Active-filter chips shown under the toolbar so a collapsed filter is never invisible. */
  filterChips?: FilterChip[];
  /** Apply the staged advanced filters (the panel's footer button). */
  onApplyAdvanced?: () => void;
  /** Clear every filter across all zones (chip-row link + panel footer). */
  onClearFilters?: () => void;
  /** Disable the Apply button (e.g. no staged changes). */
  applyDisabled?: boolean;
  /** Current sort, or null for the endpoint's default order. */
  sort?: SortState | null;
  /** Required alongside `sortKey` columns to make headers interactive. */
  onSortChange?: (sort: SortState | null) => void;
}

/** One active-filter chip: a human label and how to remove just that filter. */
export interface FilterChip {
  key: string;
  label: string;
  onRemove: () => void;
}

export default function DataTable<T>({
  columns,
  data,
  loading,
  pagination,
  onPageChange,
  onPageSizeChange,
  onSearchChange,
  searchPlaceholder = 'Search...',
  searchable = true,
  emptyMessage = 'No data found',
  idAccessor,
  onRowClick,
  headerChildren,
  advancedFilters,
  advancedActiveCount = 0,
  filterChips,
  onApplyAdvanced,
  onClearFilters,
  applyDisabled,
  sort,
  onSortChange,
}: DataTableProps<T>) {
  const [searchValue, setSearchValue] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const wasFocusedRef = useRef(false);

  // Debounce search input - only search after 3+ characters or when empty (to clear)
  useEffect(() => {
    const timer = setTimeout(() => {
      // Only trigger search if 3+ characters or empty (for clearing search)
      if (searchValue.length === 0 || searchValue.length >= 3) {
        setDebouncedSearch(searchValue);
      }
    }, 500);

    return () => clearTimeout(timer);
  }, [searchValue]);

  // Keep the latest onSearchChange in a ref so it is NOT an effect dependency.
  // Parents commonly pass a new inline callback each render; if this effect
  // depended on onSearchChange it would re-run on every render and call it —
  // and for callbacks that reset page state (e.g. `setVulnPage(0)`), that makes
  // pagination impossible (clicking page 2 immediately snaps back to page 1).
  const onSearchChangeRef = useRef(onSearchChange);
  useEffect(() => { onSearchChangeRef.current = onSearchChange; }, [onSearchChange]);

  // Fire the search callback only when the debounced value actually changes.
  useEffect(() => {
    onSearchChangeRef.current(debouncedSearch);
  }, [debouncedSearch]);

  // "Clear all" clears every zone — the search box (owned here) plus the page's
  // structured filters (via onClearFilters). Clearing debouncedSearch fires the search reset now.
  const handleClearAll = () => {
    setSearchValue('');
    setDebouncedSearch('');
    onClearFilters?.();
  };

  // Clears the search text only (the ✕ inside the box) — distinct from "Clear all" (every filter).
  const clearSearch = () => { setSearchValue(''); setDebouncedSearch(''); };

  const hasChips = !!filterChips && filterChips.length > 0;

  // Track if search was active before loading started
  useEffect(() => {
    if (loading && searchInputRef.current === document.activeElement) {
      wasFocusedRef.current = true;
    }
  }, [loading]);

  // Restore focus after loading completes if input was previously focused
  useEffect(() => {
    if (!loading && wasFocusedRef.current && searchInputRef.current) {
      // Use setTimeout to ensure focus is restored after render cycle completes
      // This is especially important for Firefox which handles focus differently
      setTimeout(() => {
        if (searchInputRef.current) {
          searchInputRef.current.focus();
        }
      }, 0);
    }
  }, [loading]);

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchValue(e.target.value);
    // Mark as focused when user is actively typing
    wasFocusedRef.current = true;
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    // Prevent form submission on Enter key to avoid losing focus
    if (e.key === 'Enter') {
      e.preventDefault();
      // Trigger immediate search if 3+ characters
      if (searchValue.length >= 3) {
        setDebouncedSearch(searchValue);
      }
    }
  };

  const handleFocus = () => {
    wasFocusedRef.current = true;
  };

  const handleBlur = (e: React.FocusEvent<HTMLInputElement>) => {
    // Only mark as not focused if blur is not caused by a re-render
    // Check if the new focus target is outside the search input
    const relatedTarget = e.relatedTarget as HTMLElement;
    if (relatedTarget && !searchInputRef.current?.contains(relatedTarget)) {
      wasFocusedRef.current = false;
    }
  };

  const renderPageNumbers = () => {
    const pages = [];
    const { page, totalPages } = pagination;

    // Show max 5 page numbers
    let startPage = Math.max(0, page - 2);
    let endPage = Math.min(totalPages - 1, startPage + 4);

    // Adjust start if we're near the end
    if (endPage - startPage < 4) {
      startPage = Math.max(0, endPage - 4);
    }

    for (let i = startPage; i <= endPage; i++) {
      pages.push(
        <button
          key={i}
          type="button"
          onClick={() => onPageChange(i)}
          className={`pagination-number ${i === page ? 'active' : ''}`}
          disabled={loading}
        >
          {i + 1}
        </button>
      );
    }

    return pages;
  };

  return (
    <div className="data-table-container">
      {/* Search Bar */}
      <div className="data-table-header">
        {searchable && (
        <div className="search-container">
          <Search size={18} className="search-icon" />
          <input
            ref={searchInputRef}
            type="text"
            placeholder={searchPlaceholder}
            value={searchValue}
            onChange={handleSearchChange}
            onKeyDown={handleKeyDown}
            onFocus={handleFocus}
            onBlur={handleBlur}
            className="dt-search-input"
            disabled={loading}
          />
          <div className="search-actions">
            {searchValue && (
              <button
                type="button"
                className="search-action search-clear"
                onClick={clearSearch}
                aria-label="Clear search"
              >
                <X size={16} />
              </button>
            )}
            {advancedFilters && (
              <button
                type="button"
                className={`search-action search-advanced${advancedOpen ? ' search-advanced--open' : ''}${advancedActiveCount > 0 ? ' search-advanced--active' : ''}`}
                onClick={() => setAdvancedOpen((o) => !o)}
                aria-label="Advanced filters"
                title="Advanced filters"
                aria-expanded={advancedOpen}
                disabled={loading}
              >
                <SlidersHorizontal size={16} />
                {advancedActiveCount > 0 && <span className="search-advanced-dot" />}
              </button>
            )}
          </div>
        </div>
        )}

        {headerChildren}

        <div className="page-size-selector">
          <label>Show:</label>
          <select
            value={pagination.pageSize}
            onChange={(e) => onPageSizeChange(Number(e.target.value))}
            className="page-size-select"
            disabled={loading}
          >
            <option value={10}>10</option>
            <option value={25}>25</option>
            <option value={50}>50</option>
            <option value={100}>100</option>
          </select>
        </div>
      </div>

      {/* Active-filter chips — a second row only when filters are active, so nothing is wasted when idle. */}
      {hasChips && (
        <div className="filter-row">
          <div className="filter-chips">
            {filterChips!.map((chip) => (
              <span key={chip.key} className="filter-chip">
                {chip.label}
                <button
                  type="button"
                  className="filter-chip-remove"
                  onClick={chip.onRemove}
                  aria-label={`Remove ${chip.label} filter`}
                >
                  <X size={13} />
                </button>
              </span>
            ))}
            {onClearFilters && (
              <button type="button" className="filter-chips-clear" onClick={handleClearAll}>
                Clear all
              </button>
            )}
          </div>
        </div>
      )}

      {/* Zone 3 — collapsible advanced-filter panel, dropping below the toggle */}
      {advancedFilters && advancedOpen && (
        <div className="advanced-panel">
          <div className="advanced-panel-fields">{advancedFilters}</div>
          <div className="advanced-panel-footer">
            {onClearFilters && (
              <button type="button" className="advanced-clear" onClick={handleClearAll}>
                Clear all
              </button>
            )}
            {onApplyAdvanced && (
              <button
                type="button"
                className="advanced-apply"
                onClick={() => { onApplyAdvanced(); setAdvancedOpen(false); }}
                disabled={applyDisabled}
              >
                Apply filters
              </button>
            )}
          </div>
        </div>
      )}

      {/* Table */}
      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              {columns.map((column, index) => {
                const sortable = !!column.sortKey && !!onSortChange;
                const active = sortable && sort?.key === column.sortKey;
                const direction = active ? sort!.direction : undefined;

                return (
                  <th
                    key={index}
                    style={column.width ? { width: column.width } : undefined}
                    aria-sort={
                      !sortable ? undefined
                        : direction === 'asc' ? 'ascending'
                        : direction === 'desc' ? 'descending'
                        : 'none'
                    }
                  >
                    {sortable ? (
                      <button
                        type="button"
                        className={`th-sort ${active ? 'active' : ''}`}
                        onClick={() => onSortChange!(nextSort(sort, column.sortKey!))}
                        disabled={loading}
                        title={`Sort by ${column.header}`}
                      >
                        {column.header}
                        {direction === 'asc' ? (
                          <ChevronUp size={14} className="th-sort-icon" />
                        ) : direction === 'desc' ? (
                          <ChevronDown size={14} className="th-sort-icon" />
                        ) : (
                          <ChevronsUpDown size={14} className="th-sort-icon idle" />
                        )}
                      </button>
                    ) : (
                      column.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={columns.length} className="text-center">
                  <div className="loading-spinner">Loading...</div>
                </td>
              </tr>
            ) : data.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="text-center text-muted">
                  {emptyMessage}
                </td>
              </tr>
            ) : (
              data.map((item) => (
                <tr
                  key={String(item[idAccessor])}
                  onClick={() => onRowClick?.(item)}
                  style={onRowClick ? { cursor: 'pointer' } : undefined}
                >
                  {columns.map((column, colIndex) => (
                    <td key={colIndex}>
                      {column.render
                        ? column.render(item)
                        : column.accessor
                        ? String(item[column.accessor])
                        : ''}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="pagination-container">
        <div className="pagination-info">
          Showing {pagination.page * pagination.pageSize + 1} to{' '}
          {Math.min((pagination.page + 1) * pagination.pageSize, pagination.total)} of{' '}
          {pagination.total} entries
        </div>

        <div className="pagination-controls">
          <button
            type="button"
            onClick={() => onPageChange(0)}
            disabled={pagination.page === 0 || loading}
            className="pagination-button pagination-button--icon"
            aria-label="First page"
            title="First page"
          >
            <ChevronsLeft size={16} />
          </button>

          <button
            type="button"
            onClick={() => onPageChange(pagination.page - 1)}
            disabled={pagination.page === 0 || loading}
            className="pagination-button pagination-button--icon"
            aria-label="Previous page"
            title="Previous page"
          >
            <ChevronLeft size={16} />
          </button>

          <div className="pagination-numbers">{renderPageNumbers()}</div>

          <button
            type="button"
            onClick={() => onPageChange(pagination.page + 1)}
            disabled={pagination.page >= pagination.totalPages - 1 || loading}
            className="pagination-button pagination-button--icon"
            aria-label="Next page"
            title="Next page"
          >
            <ChevronRight size={16} />
          </button>

          <button
            type="button"
            onClick={() => onPageChange(pagination.totalPages - 1)}
            disabled={pagination.page >= pagination.totalPages - 1 || loading}
            className="pagination-button pagination-button--icon"
            aria-label="Last page"
            title="Last page"
          >
            <ChevronsRight size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}
