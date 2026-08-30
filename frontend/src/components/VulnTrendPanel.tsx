import { PieChart, Pie, Cell, Label, Tooltip, ResponsiveContainer } from 'recharts';

// ── Constants ─────────────────────────────────────────────────────────────────

export const SEV_COLORS: Record<string, string> = {
  CRITICAL: '#ef4444',
  HIGH: '#f97316',
  MEDIUM: '#eab308',
  LOW: '#22c55e',
};

export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const;

// ── Donut center label ────────────────────────────────────────────────────────

function CenterLabel({ viewBox, total }: { viewBox?: { cx: number; cy: number }; total: number }) {
  if (!viewBox) return null;
  const { cx, cy } = viewBox;
  if (!isFinite(cx) || !isFinite(cy)) return null;
  return (
    <text>
      <tspan
        x={cx} y={cy - 2}
        textAnchor="middle" dominantBaseline="middle"
        style={{ fontSize: '1.25rem', fontWeight: 700, fill: 'var(--text-primary, #111)' }}
      >
        {total}
      </tspan>
      <tspan
        x={cx} y={cy + 16}
        textAnchor="middle"
        style={{ fontSize: '0.65rem', fill: 'var(--text-muted, #9ca3af)', letterSpacing: '0.05em' }}
      >
        TOTAL
      </tspan>
    </text>
  );
}

// ── Shared severity legend ────────────────────────────────────────────────────

/** The C/H/M/L legend rows every summary card carries, so a donut and a bar sitting
 *  side by side label their severities identically.
 *
 *  `reverse` flips to least-severe-first, for a card whose graphic reads that way
 *  (the pill bar builds up to Critical at its right-hand end) — the legend then runs
 *  in the same direction as the thing it labels. */
export function SeverityLegend({ counts, reverse = false }: {
  counts: Record<string, number>;
  reverse?: boolean;
}) {
  const order = reverse ? [...SEVERITIES].reverse() : SEVERITIES;
  return (
    <div className="vdash-legend">
      {order.map(s => (
        <div key={s} className="vdash-legend-row">
          <span className="vdash-legend-dot" style={{ background: SEV_COLORS[s] }} />
          <span className="vdash-legend-name">{s.charAt(0) + s.slice(1).toLowerCase()}</span>
          <span className="vdash-legend-count">{counts[s] || 0}</span>
        </div>
      ))}
    </div>
  );
}

// ── Donut chart card ──────────────────────────────────────────────────────────

interface DonutProps {
  title: string;
  counts: Record<string, number>;
  loading: boolean;
  emptyContent?: React.ReactNode;
}

export function DonutCard({ title, counts, loading, emptyContent }: DonutProps) {
  const total = SEVERITIES.reduce((s, k) => s + (counts[k] || 0), 0);
  const pieData = SEVERITIES
    .filter(s => (counts[s] || 0) > 0)
    .map(s => ({ name: s.charAt(0) + s.slice(1).toLowerCase(), value: counts[s], key: s }));

  return (
    <div className="vdash-card">
      <div className="vdash-card-title">{title}</div>
      {loading ? (
        <div className="vdash-card-body vdash-card-empty">Loading…</div>
      ) : total === 0 ? (
        <div className="vdash-card-body vdash-card-empty">
          {emptyContent ?? 'No data'}
        </div>
      ) : (
        <div className="vdash-card-body">
          <div className="vdash-donut-col">
          <div className="vdash-donut">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={pieData}
                  cx="50%"
                  cy="50%"
                  innerRadius="52%"
                  outerRadius="76%"
                  dataKey="value"
                  startAngle={90}
                  endAngle={-270}
                  strokeWidth={1.5}
                >
                  {pieData.map(entry => (
                    <Cell key={entry.key} fill={SEV_COLORS[entry.key]} />
                  ))}
                  <Label
                    content={(props: any) =>
                      <CenterLabel viewBox={props.viewBox} total={total} />
                    }
                    position="center"
                  />
                </Pie>
                <Tooltip formatter={(val, name) => [`${val}`, name]} contentStyle={{ fontSize: 12 }} />
              </PieChart>
            </ResponsiveContainer>
          </div>
          <div className="vdash-total">Total: {total}</div>
          </div>
          <SeverityLegend counts={counts} />
        </div>
      )}
    </div>
  );
}
