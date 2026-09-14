import { Dispatch, SetStateAction, useEffect, useState } from 'react';
import { getCurrentUser } from '../utils/permissions';

/**
 * Table view state that survives leaving the page: filters, search text, sort, the current page and
 * page size. A drop-in for `useState`, keyed by table and field, so a page converts one piece of
 * state at a time without reshaping it.
 *
 * <p>Everything for one table lives under one localStorage entry, and the entry is per user — on a
 * shared browser, one person's saved filters never appear for whoever signs in next.
 *
 * <p>Restoring is defensive by design. localStorage can be blocked, full or hold a value written by
 * an older build, so a read that fails, or a value whose shape no longer matches, falls back to the
 * page's default rather than breaking the page. A saved object is merged over the default, so a
 * filter added later picks up its default instead of arriving undefined.
 */

function storageKey(table: string): string {
  const userId = getCurrentUser()?.id ?? 'anonymous';
  return `faction.tableState.${userId}.${table}`;
}

function readTable(table: string): Record<string, unknown> {
  try {
    const raw = localStorage.getItem(storageKey(table));
    const parsed: unknown = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/** The saved value if it still fits the default's shape; otherwise the default. */
function restore<T>(saved: unknown, initial: T): T {
  if (saved === undefined) return initial;
  if (initial === null || initial === undefined) return saved as T; // e.g. sort: null ↔ {key,dir}
  if (Array.isArray(initial)) return Array.isArray(saved) ? (saved as T) : initial;
  if (isPlainObject(initial)) {
    return isPlainObject(saved) ? ({ ...initial, ...saved } as T) : initial;
  }
  return typeof saved === typeof initial ? (saved as T) : initial;
}

export function usePersistedState<T>(
  table: string,
  field: string,
  initial: T,
): [T, Dispatch<SetStateAction<T>>] {
  const [value, setValue] = useState<T>(() => restore(readTable(table)[field], initial));

  useEffect(() => {
    try {
      const all = readTable(table);
      all[field] = value;
      localStorage.setItem(storageKey(table), JSON.stringify(all));
    } catch {
      // Blocked or full storage: the page still works, it just won't remember.
    }
  }, [table, field, value]);

  return [value, setValue];
}
