import { useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { SeverityLegend, SEVERITIES, SEV_COLORS } from './VulnTrendPanel';
import './SeverityPillCard.css';

/** The green "all clear" empty state, for the buckets where zero is good news. */
export function positiveEmpty(text: string) {
  return (
    <div className="vpill-empty-good">
      <ShieldCheck size={80} strokeWidth={1.25} />
      <span>{text}</span>
    </div>
  );
}

/** "CRITICAL" -> "Critical", matching the legend's labels. */
function label(severity: string): string {
  return severity.charAt(0) + severity.slice(1).toLowerCase();
}

// ── Pill card ─────────────────────────────────────────────────────────────────

/**
 * One summary bucket: the total set large, a single horizontal stacked bar beneath it,
 * and the severity legend at the bottom. The bar is plain flex boxes rather than a chart
 * library — a stacked pill is a few proportional widths, and rounding it cleanly at both
 * ends is easier without one.
 */
export default function SeverityPillCard({ title, counts, loading, emptyContent }: {
  title: string;
  counts: Record<string, number>;
  loading: boolean;
  emptyContent?: React.ReactNode;
}) {
  const [hovered, setHovered] = useState<string | null>(null);

  const total = SEVERITIES.reduce((sum, k) => sum + (counts[k] || 0), 0);
  // SEVERITIES runs most- to least-severe; the bar reads the other way, building up to
  // Critical at the right-hand end. `center` is the segment's midpoint along the bar, so
  // the hover tooltip can sit over it without measuring the DOM.
  let offset = 0;
  const segments = [...SEVERITIES].reverse()
    .filter(sev => (counts[sev] || 0) > 0)
    .map(sev => {
      const pct = (counts[sev] / total) * 100;
      const center = offset + pct / 2;
      offset += pct;
      return { sev, count: counts[sev], pct, center };
    });
  const active = segments.find(seg => seg.sev === hovered);

  return (
    <div className="vdash-card">
      <div className="vdash-card-title">{title}</div>
      {loading ? (
        <div className="vdash-card-body vdash-card-empty">Loading…</div>
      ) : total === 0 ? (
        <div className="vdash-card-body vdash-card-empty">{emptyContent ?? 'No data'}</div>
      ) : (
        <div className="vdash-card-body vpill-body">
          <div className="vpill-total">{total}</div>
          {/* Widths are percentages of the total; `min-width` keeps a single finding among
              thousands from rounding away to an invisible sliver. */}
          <div className="vpill-bar-wrap">
            {active && (
              <div className="vpill-tip" style={{ left: `${active.center}%` }}>
                <span className="vpill-tip-dot" style={{ background: SEV_COLORS[active.sev] }} />
                {label(active.sev)}: <strong>{active.count}</strong>
              </div>
            )}
            <div
              // Remounting on a change of counts replays the grow-from-left animation, so the
              // bar re-draws whenever new data lands (a filter change) as well as on first load.
              key={segments.map(seg => `${seg.sev}:${seg.count}`).join('|')}
              className="vpill-bar"
              role="img"
              aria-label={segments.map(({ sev, count }) => `${label(sev)}: ${count}`).join(', ')}
              onMouseLeave={() => setHovered(null)}
            >
              {segments.map(({ sev, pct }) => (
                <span
                  key={sev}
                  className={`vpill-seg${hovered && hovered !== sev ? ' dimmed' : ''}`}
                  style={{ width: `${pct}%`, background: SEV_COLORS[sev] }}
                  onMouseEnter={() => setHovered(sev)}
                />
              ))}
            </div>
          </div>
          <SeverityLegend counts={counts} reverse />
        </div>
      )}
    </div>
  );
}

