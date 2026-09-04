import { useMemo, useState } from 'react';
import { Search, X } from 'lucide-react';
import './CommentSearch.css';

/**
 * The shape both threads share. `ApplicationComment` and `VulnerabilityComment` are structurally
 * identical (types.ts), but they are separate types, so this takes the fields the search reads
 * and stays assignable from either.
 */
export interface SearchableComment {
  authorId: string;
  authorName?: string;
  content: string;
  systemGenerated?: boolean;
}

/**
 * A comment reduced to the words a person would search for.
 *
 * `content` is two formats in one field: user comments are HTML from the editor, system ones are
 * Markdown rendered at display time. Searching the raw string would make "Retest scheduled" miss
 * `**Retest scheduled** by Sam`, and would match on tag names and attribute values — typing "div"
 * or "href" would light up every comment. So strip tags first, then the Markdown emphasis and
 * heading punctuation, and decode the handful of entities the editor emits.
 */
export function commentSearchText(comment: SearchableComment): string {
  return (comment.content || '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    // Markdown left over from a system comment: emphasis, code ticks, heading and list markers.
    .replace(/[*_`#>]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** True when the comment matches the query, by body text or by who wrote it. */
export function commentMatches(comment: SearchableComment, needle: string): boolean {
  if (!needle) return true;
  const author = (comment.authorName || comment.authorId || '').toLowerCase();
  if (author.includes(needle)) return true;
  if (comment.systemGenerated && 'system'.includes(needle)) return true;
  return commentSearchText(comment).toLowerCase().includes(needle);
}

/**
 * Search state for one comment thread.
 *
 * Filtering is derived rather than stored, so the 20-second poll in `useCommentPolling` replacing
 * the whole array leaves an active search intact — a new message either matches or it doesn't, and
 * the box does not need clearing.
 */
export function useCommentSearch<T extends SearchableComment>(comments: T[]) {
  const [query, setQuery] = useState('');
  const needle = query.trim().toLowerCase();

  const filtered = useMemo(
    () => (needle ? comments.filter(c => commentMatches(c, needle)) : comments),
    [comments, needle]
  );

  return {
    query,
    setQuery,
    /** What to render — the whole thread when the box is empty. */
    filtered,
    /** True while a query is narrowing the thread. */
    active: needle.length > 0,
    clear: () => setQuery(''),
  };
}

interface Props {
  value: string;
  onChange: (next: string) => void;
  /** Comments currently shown, and the size of the whole thread — rendered as "3 of 47". */
  matchCount: number;
  totalCount: number;
  placeholder?: string;
}

/**
 * Search box for a comment thread. Mirrors the table search (`.search-container` in
 * DataTable.css) so the two read as the same control, at the smaller scale a drawer wants.
 */
export default function CommentSearch({
  value,
  onChange,
  matchCount,
  totalCount,
  placeholder = 'Search this conversation',
}: Props) {
  const active = value.trim().length > 0;
  return (
    <div className="comment-search">
      <div className="comment-search-box">
        <Search size={14} className="comment-search-icon" />
        <input
          type="text"
          className="comment-search-input"
          placeholder={placeholder}
          value={value}
          onChange={e => onChange(e.target.value)}
          aria-label={placeholder}
        />
        {active && (
          <button
            type="button"
            className="comment-search-clear"
            onClick={() => onChange('')}
            aria-label="Clear search"
          >
            <X size={14} />
          </button>
        )}
      </div>
      {active && (
        <span className="comment-search-count" role="status">
          {matchCount} of {totalCount}
        </span>
      )}
    </div>
  );
}
