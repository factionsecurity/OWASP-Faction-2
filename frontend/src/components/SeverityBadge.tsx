import './SeverityBadge.css';

// A Badge with the canonical severity COLORS — everything else (shape, size,
// typography, text) is the standard Badge rendering via its .badge classes.
// The palette matches the assessment page's vulnerability count pills
// (.vuln-stat), including their darker light-mode variants, which an inline
// customColor can't express.
const KNOWN = new Set(['critical', 'high', 'medium', 'low', 'info', 'informational']);

interface SeverityBadgeProps {
  severity?: string | null;
  size?: 'sm' | 'md' | 'lg';
}

export default function SeverityBadge({ severity, size = 'md' }: SeverityBadgeProps) {
  const raw = String(severity ?? '').trim();
  if (!raw) return <>-</>;
  const lower = raw.toLowerCase();
  const key = lower === 'informational' ? 'info' : KNOWN.has(lower) ? lower : 'info';
  return <span className={`badge badge-${size} severity-badge--${key}`}>{raw}</span>;
}
