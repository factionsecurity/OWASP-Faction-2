import { assessmentsApi } from '../api';
import type { Unavailability } from '../types';
import { unavailabilityLabel } from '../utils/unavailability';

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

const range = (u: Unavailability) => (u.start === u.end ? u.start : `${u.start} – ${u.end}`);

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
