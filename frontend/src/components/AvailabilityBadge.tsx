import Badge from './Badge';
import type { AssessorAvailability } from '../types';
import { unavailabilityLabel, blockedRanges, formatDayRange } from '../utils/unavailability';

/**
 * Parse a `YYYY-MM-DD` (optionally with a trailing `T...` time component) as a LOCAL
 * date and format it. Never `new Date('YYYY-MM-DD')` directly — that parses as UTC
 * midnight and can render as the previous day in negative-UTC-offset zones.
 */
function fmtLocalDate(d: string): string {
  const [y, m, day] = d.split('T')[0].split('-').map(Number);
  return new Date(y, m - 1, day).toLocaleDateString();
}

const MAX_RANGES_SHOWN = 2;

/**
 * Free/busy mark for one candidate assessor in the assessor picker. Nothing until both
 * dates are set: with no window chosen, an "Available" badge would be an answer to a
 * question nobody asked.
 */
export function AvailabilityBadge({
  availability,
  windowStart,
  windowEnd,
}: {
  availability?: AssessorAvailability;
  windowStart: string;
  windowEnd: string;
}): JSX.Element | null {
  if (!windowStart || !windowEnd || !availability) return null;

  if (!availability.busy) {
    return <Badge variant="success" size="sm">Free</Badge>;
  }

  // The names/dates go in a title rather than the badge: the picker is a narrow column,
  // and "why" is a follow-up question, not the thing being scanned for.
  const clashes = availability.conflicts;
  const away = availability.unavailable ?? [];
  const lines = [
    ...clashes.map((c) => `${c.name} (${fmtLocalDate(c.startDate)} – ${fmtLocalDate(c.plannedEndDate)})`),
    ...away.map(
      (u) => `${unavailabilityLabel(u)} (${u.start === u.end ? fmtLocalDate(u.start) : `${fmtLocalDate(u.start)} – ${fmtLocalDate(u.end)}`})`
    ),
  ];
  const title = `Unavailable:\n${lines.join('\n')}`;

  if (clashes.length > 0) {
    const count = clashes.length + away.length;
    return (
      <span title={title}>
        <Badge variant="danger" size="sm">Busy{count > 1 ? ` (${count})` : ''}</Badge>
      </span>
    );
  }

  const refYear = new Date(`${windowStart}T00:00:00`).getFullYear();
  const datesFor = (entries: typeof away) => {
    const ranges = blockedRanges(entries, windowStart, windowEnd);
    const shown = ranges.slice(0, MAX_RANGES_SHOWN).map((r) => formatDayRange(r, refYear));
    const remaining = ranges.length - shown.length;
    return shown.join(', ') + (remaining > 0 ? ` +${remaining}` : '');
  };

  // A holiday overlapping the window is called out separately from other unavailability
  // (time off, blocks) — it's a different kind of "can't schedule this" for the reader.
  const holidays = away.filter((u) => u.kind === 'HOLIDAY');
  const others = away.filter((u) => u.kind !== 'HOLIDAY');
  const holidayDates = datesFor(holidays);
  const otherDates = datesFor(others);

  return (
    <>
      {holidays.length > 0 && (
        <span title={title}>
          <Badge variant="info" size="sm">Holiday{holidayDates ? ` · ${holidayDates}` : ''}</Badge>
        </span>
      )}
      {others.length > 0 && (
        <span title={title}>
          <Badge variant="warning" size="sm">Away{otherDates ? ` · ${otherDates}` : ''}</Badge>
        </span>
      )}
    </>
  );
}
