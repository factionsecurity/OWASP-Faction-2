// Compact notation for counts that have to fit somewhere small — sidebar badges,
// dashboard stat tiles. Intl handles the threshold itself: anything under 1000 comes
// back as plain digits, 1000+ becomes 1.2K, 1000000+ becomes 1.2M.
const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

/** `999` → "999", `1500` → "1.5K", `2_400_000` → "2.4M". */
export function formatCompact(n: number): string {
  return compact.format(n);
}
