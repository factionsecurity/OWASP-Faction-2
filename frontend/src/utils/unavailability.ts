import type { Unavailability } from '../types';

/** "Holiday: Diwali", "OOO: Conference", "Block: Code freeze" — the reason, for tooltips and warnings. */
export function unavailabilityLabel(u: Unavailability): string {
  switch (u.kind) {
    case 'HOLIDAY': return `Holiday: ${u.label}`;
    case 'TIME_OFF': return `OOO: ${u.label}`;
    case 'BLOCK': return `Block: ${u.label}`;
  }
}
