import type { SortState } from '../components/DataTable';

/** A comparable cell value. `null`/`undefined` mean "no value" and always sort last. */
export type SortValue = string | number | boolean | Date | null | undefined;

/** Per-column value extractors, keyed by the column's `sortKey`. */
export type SortAccessors<T> = Record<string, (row: T) => SortValue>;

const isEmpty = (v: SortValue) => v === null || v === undefined || v === '';

/** Compares two present (non-empty) values; empties are handled by the caller. */
function compare(a: SortValue, b: SortValue): number {
  if (typeof a === 'string' && typeof b === 'string') {
    return a.localeCompare(b, undefined, { sensitivity: 'base', numeric: true });
  }
  const av = a instanceof Date ? a.getTime() : Number(a);
  const bv = b instanceof Date ? b.getTime() : Number(b);
  return av === bv ? 0 : av < bv ? -1 : 1;
}

/**
 * Order the rows of a table that holds its whole result set on the client.
 *
 * The server-paginated tables push their sort into the query instead — this is only for the
 * handful of tables backed by an unpaginated endpoint, where every row is already in memory.
 * Returns the input untouched when unsorted or when the key has no accessor.
 */
export function applyClientSort<T>(
  rows: T[],
  sort: SortState | null | undefined,
  accessors: SortAccessors<T>,
): T[] {
  if (!sort) return rows;
  const accessor = accessors[sort.key];
  if (!accessor) return rows;

  const direction = sort.direction === 'desc' ? -1 : 1;
  // Sort a copy — the caller's array is state. Empties are resolved before the
  // direction flip so they stay at the bottom rather than jumping to the top on desc.
  return [...rows].sort((a, b) => {
    const av = accessor(a);
    const bv = accessor(b);
    if (isEmpty(av) || isEmpty(bv)) {
      return isEmpty(av) && isEmpty(bv) ? 0 : isEmpty(av) ? 1 : -1;
    }
    return compare(av, bv) * direction;
  });
}
