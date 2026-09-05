import { useTerminology } from '../context/TerminologyContext';
import './SeverityBadge.css';

// A Badge with the canonical severity COLORS — everything else (shape, size,
// typography, text) is the standard Badge rendering via its .badge classes.
// The palette matches the assessment page's vulnerability count pills
// (.vuln-stat), including their darker light-mode variants, which an inline
// customColor can't express.
//
// The colour is chosen from the value the API sent, never from the rendered text.
// An installation that renames Critical to "Sev-1" still gets the red badge;
// keying the class off the label would drop every severity to the grey default.
const KNOWN = new Set(['critical', 'high', 'medium', 'low', 'info', 'informational']);

interface SeverityBadgeProps {
  /** The severity itself — drives the colour, and the text unless `label` overrides it. */
  severity?: string | null;
  size?: 'sm' | 'md' | 'lg';
  /**
   * Text to show instead of this installation's configured label, rendered verbatim.
   *
   * <p>For previewing a name as it is being typed, on the Severity Names screen. Without it that
   * preview has to pass the half-typed word in as `severity`, which both picks the wrong colour
   * and runs the word back through the rename a second time.
   */
  label?: string;
}

export default function SeverityBadge({ severity, size = 'md', label }: SeverityBadgeProps) {
  const { severityLabel } = useTerminology();
  const raw = String(severity ?? '').trim();
  if (!raw) return <>-</>;
  const lower = raw.toLowerCase();
  const key = lower === 'informational' ? 'info' : KNOWN.has(lower) ? lower : 'info';
  // Also renders likelihood and impact, which are free text — severityLabel passes
  // anything that isn't one of the five severities straight through.
  return (
    <span className={`badge badge-${size} severity-badge--${key}`}>
      {label ?? severityLabel(raw)}
    </span>
  );
}
