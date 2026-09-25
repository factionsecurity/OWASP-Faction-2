import type { Unavailability } from '../types';

/** "Holiday: Diwali", "OOO: Conference", "Block: Code freeze" — the reason, for tooltips and warnings. */
export function unavailabilityLabel(u: Unavailability): string {
  switch (u.kind) {
    case 'HOLIDAY': return `Holiday: ${u.label}`;
    case 'TIME_OFF': return `OOO: ${u.label}`;
    case 'BLOCK': return `Block: ${u.label}`;
  }
}

/**
 * Parse a `YYYY-MM-DD` string as a LOCAL date (never `new Date('YYYY-MM-DD')`, which
 * parses as UTC midnight and can shift a day backwards in negative-UTC-offset zones).
 */
function parseLocalDate(dateOnly: string): Date {
  const [y, m, d] = dateOnly.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function toDateOnly(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** One day later than `dateOnly`, as a `YYYY-MM-DD` string. */
function nextDay(dateOnly: string): string {
  const date = parseLocalDate(dateOnly);
  date.setDate(date.getDate() + 1);
  return toDateOnly(date);
}

export interface DayRange {
  start: string;
  end: string;
}

/**
 * The union of `entries`' day spans, clipped to `[windowStart, windowEnd]` and merged
 * into contiguous ranges (overlapping or adjacent days join into one range). Entries
 * entirely outside the window are dropped. `YYYY-MM-DD` strings sort lexicographically
 * in date order, so plain string comparison is enough — no Date parsing needed here.
 */
export function blockedRanges(entries: Unavailability[], windowStart: string, windowEnd: string): DayRange[] {
  if (!windowStart || !windowEnd) return [];

  const clipped: DayRange[] = [];
  for (const u of entries) {
    const start = u.start < windowStart ? windowStart : u.start;
    const end = u.end > windowEnd ? windowEnd : u.end;
    if (start > end) continue; // entirely outside the window
    clipped.push({ start, end });
  }
  clipped.sort((a, b) => a.start.localeCompare(b.start));

  const merged: DayRange[] = [];
  for (const range of clipped) {
    const last = merged[merged.length - 1];
    if (last && range.start <= nextDay(last.end)) {
      if (range.end > last.end) last.end = range.end;
    } else {
      merged.push({ ...range });
    }
  }
  return merged;
}

/**
 * Compact, locale-formatted rendering of one day range: single day `Nov 26`,
 * same-month range `Nov 23–25`, cross-month `Nov 30 – Dec 2`. The year is appended
 * only when it differs from `refYear` (the proposed window's year).
 */
export function formatDayRange(range: DayRange, refYear: number): string {
  const start = parseLocalDate(range.start);
  const end = parseLocalDate(range.end);
  const startYear = start.getFullYear();
  const endYear = end.getFullYear();

  const short = (d: Date) => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  const withYear = (d: Date) => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  const label = (d: Date, year: number) => (year !== refYear ? withYear(d) : short(d));

  if (range.start === range.end) {
    return label(start, startYear);
  }

  const sameMonth = startYear === endYear && start.getMonth() === end.getMonth();
  if (sameMonth) {
    if (startYear !== refYear) {
      return `${short(start)}–${end.getDate()}, ${startYear}`;
    }
    return `${short(start)}–${end.getDate()}`;
  }

  return `${label(start, startYear)} – ${label(end, endYear)}`;
}
