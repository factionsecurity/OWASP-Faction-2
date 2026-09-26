import { assessmentsApi } from '../api';
import type { Unavailability } from '../types';
import { formatDayRange, unavailabilityLabel } from '../utils/unavailability';

/**
 * Time off, holidays and blocks for the chosen assessors across a window, for the warning shown
 * before saving. Always empty in the open source edition, so the warning never appears there.
 * A failed lookup returns empty: a warning is advice, and must never block a save.
 */
export async function findUnavailability(
  assessmentId: string | null,
  assessorIds: string[],
  startApiDate: string,
  endApiDate: string,
): Promise<Unavailability[]> {
  if (assessorIds.length === 0) return [];
  try {
    const res = await assessmentsApi.getAssessorAvailability(assessmentId, assessorIds, startApiDate, endApiDate);
    return (res.data ?? []).flatMap((a) => a.unavailable ?? []);
  } catch {
    return [];
  }
}

/** Local, compact dates like the picker badges: "Nov 26", "Nov 23–25"; the year only when it isn't this one. */
const range = (u: Unavailability) => formatDayRange({ start: u.start, end: u.end }, new Date().getFullYear());

/** One line per conflict: who, why, when. */
export function UnavailabilityList({ entries, names }: { entries: Unavailability[]; names: Record<string, string> }) {
  return (
    <div>
      <div>Some assessors are unavailable in these dates:</div>
      <ul style={{ textAlign: 'left', margin: '0.75rem 0 0', paddingLeft: '1.25rem' }}>
        {entries.map((u, i) => (
          <li key={`${u.userId}-${u.kind}-${u.start}-${i}`}>
            <strong>{names[u.userId] ?? 'Unknown user'}</strong>: {unavailabilityLabel(u)} ({range(u)})
          </li>
        ))}
      </ul>
    </div>
  );
}
