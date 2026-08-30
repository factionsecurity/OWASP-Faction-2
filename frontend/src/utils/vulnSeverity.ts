import type { VulnerabilitySeverity } from '../types';

// The fixed, exhaustive set of vulnerability severities. Unlike vulnerability
// statuses (see vulnStatus.ts), severities are a closed backend enum
// (VulnerabilitySeverity, ordinals CRITICAL=0 … INFORMATIONAL=4) — they cannot be
// extended via workflow config, so there is no "default vs. custom" split and no
// DEFAULT_ prefix here. Ordered most- to least-severe, matching the enum ordinals.
export const VULNERABILITY_SEVERITIES: VulnerabilitySeverity[] = [
  'CRITICAL',
  'HIGH',
  'MEDIUM',
  'LOW',
  'INFORMATIONAL',
];

// Title-case display labels for each severity.
export const SEVERITY_LABELS: Record<VulnerabilitySeverity, string> = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
  INFORMATIONAL: 'Informational',
};

// {value,label} pairs for dropdowns / pickers.
export const SEVERITY_OPTIONS: { value: VulnerabilitySeverity; label: string }[] =
  VULNERABILITY_SEVERITIES.map((value) => ({ value, label: SEVERITY_LABELS[value] }));

// Canonical severity palette for inline styles (CVSS score text, left-borders,
// dashboard bars). SeverityBadge renders the same colors via CSS classes; this map
// is the JS-value equivalent for callers that need a color string. INFORMATIONAL is
// theme-aware so it adapts to light/dark like the rest of the muted UI.
export const SEVERITY_COLORS: Record<VulnerabilitySeverity, string> = {
  CRITICAL: '#ef4444',
  HIGH: '#f97316',
  MEDIUM: '#eab308',
  LOW: '#22c55e',
  INFORMATIONAL: 'var(--text-muted)',
};

export type SeverityBadgeVariant = 'danger' | 'warning' | 'info' | 'success' | 'secondary';

// Maps a severity to the standard Badge variant (used by CVSS result badges).
export const SEVERITY_BADGE_VARIANT: Record<VulnerabilitySeverity, SeverityBadgeVariant> = {
  CRITICAL: 'danger',
  HIGH: 'warning',
  MEDIUM: 'info',
  LOW: 'success',
  INFORMATIONAL: 'secondary',
};
