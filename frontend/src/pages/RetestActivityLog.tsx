import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CheckCircle2, XCircle } from 'lucide-react';
import { auditLogsApi } from '../api';
import type { RetestActivitySummary, RetestCompletionLog } from '../types';
import DataTable, { sortParam } from '../components/DataTable';
import type { Column, SortState } from '../components/DataTable';
import { SeverityBadge } from '../components';
import { useTerminology } from '../context/TerminologyContext';

/** yyyy-MM-dd for a date input, in local time — `toISOString` would shift the day near midnight. */
function isoDate(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function daysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return isoDate(d);
}

/** The windows people actually ask for, rather than making them count back days. */
const QUICK_RANGES: { label: string; from: () => string }[] = [
  { label: 'Last 7 days', from: () => daysAgo(6) },
  { label: 'Last 30 days', from: () => daysAgo(29) },
  { label: 'Last 90 days', from: () => daysAgo(89) },
];

const RESULT_FILTERS: { label: string; value: '' | 'PASS' | 'FAIL' }[] = [
  { label: 'All', value: '' },
  { label: 'Passed', value: 'PASS' },
  { label: 'Failed', value: 'FAIL' },
];

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/**
 * What was verified, when, and by whom — the record behind "how many retests passed and failed
 * this week". The totals are counted server-side over the whole window, not summed from the page
 * on screen, so paging through the log never changes them.
 */
export default function RetestActivityLog() {
  const { organizationSingular } = useTerminology();
  const navigate = useNavigate();

  const [from, setFrom] = useState(() => daysAgo(6));
  const [to, setTo] = useState(() => isoDate(new Date()));
  const [result, setResult] = useState<'' | 'PASS' | 'FAIL'>('');

  const [rows, setRows] = useState<RetestCompletionLog[]>([]);
  const [summary, setSummary] = useState<RetestActivitySummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [sort, setSort] = useState<SortState | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    auditLogsApi.getRetestLog({ page, size: pageSize, sort: sortParam(sort), from, to, result })
      .then(res => {
        if (cancelled) return;
        setRows(res.data || []);
        setTotal(res.pagination?.totalElements ?? 0);
        setTotalPages(res.pagination?.totalPages ?? 0);
      })
      .catch(() => { if (!cancelled) setRows([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [page, pageSize, sort, from, to, result]);

  // Totals cover the window regardless of the result filter, so the split stays readable —
  // filtering to failures should not make "passed" read as zero.
  useEffect(() => {
    let cancelled = false;
    auditLogsApi.getRetestSummary(from, to)
      .then(res => { if (!cancelled) setSummary(res.data ?? null); })
      .catch(() => { if (!cancelled) setSummary(null); });
    return () => { cancelled = true; };
  }, [from, to]);

  const columns: Column<RetestCompletionLog>[] = [
    {
      header: 'Completed',
      sortKey: 'completedAt',
      render: r => <span className="logs-time">{formatTime(r.completedAt)}</span>,
    },
    {
      header: 'Result',
      sortKey: 'status',
      render: r => r.status === 'PASSED'
        ? <span className="logs-result logs-result--ok"><CheckCircle2 size={14} /> Passed</span>
        : <span className="logs-result logs-result--err"><XCircle size={14} /> Failed</span>,
    },
    {
      header: 'Finding',
      render: r => (
        <div>
          <div className="font-medium">{r.vulnerabilityName || r.vulnerabilityId || '—'}</div>
          {r.severity && <SeverityBadge severity={r.severity} />}
        </div>
      ),
    },
    { header: 'Application', render: r => r.applicationName || '—' },
    { header: organizationSingular, render: r => r.organizationName || '—' },
    { header: 'Verified by', render: r => r.completedByName || r.completedBy || '—' },
    {
      header: 'Note',
      render: r => r.comment
        ? <span className="logs-note" title={r.comment}>{r.comment}</span>
        : '—',
    },
  ];

  const applyQuickRange = (rangeFrom: string) => {
    setFrom(rangeFrom);
    setTo(isoDate(new Date()));
    setPage(0);
  };

  return (
    <>
      <p className="logs-intro">
        Every retest a pentester verifies is recorded here — the verdict, the finding it was against,
        and who signed off — so you can report on how much was retested in a period and how it went.
        Cancelled retests are not completions and never appear.
      </p>

      <div className="logs-summary">
        <div className="logs-stat">
          <span className="logs-stat-value">{summary?.total ?? '—'}</span>
          <span className="logs-stat-label">Completed</span>
        </div>
        <div className="logs-stat logs-stat--ok">
          <span className="logs-stat-value">{summary?.passed ?? '—'}</span>
          <span className="logs-stat-label">Passed</span>
        </div>
        <div className="logs-stat logs-stat--err">
          <span className="logs-stat-value">{summary?.failed ?? '—'}</span>
          <span className="logs-stat-label">Failed</span>
        </div>
      </div>

      <div className="logs-range">
        <label className="logs-range-field">
          From
          <input type="date" value={from} max={to}
            onChange={e => { setFrom(e.target.value); setPage(0); }} />
        </label>
        <label className="logs-range-field">
          To
          <input type="date" value={to} min={from}
            onChange={e => { setTo(e.target.value); setPage(0); }} />
        </label>
        {QUICK_RANGES.map(q => (
          <button key={q.label} type="button" className="logs-range-btn"
            onClick={() => applyQuickRange(q.from())}>
            {q.label}
          </button>
        ))}
        <div className="logs-range-spacer" />
        {RESULT_FILTERS.map(f => (
          <button
            key={f.label}
            type="button"
            className={`logs-range-btn${result === f.value ? ' logs-range-btn--active' : ''}`}
            onClick={() => { setResult(f.value); setPage(0); }}
          >
            {f.label}
          </button>
        ))}
      </div>

      <DataTable<RetestCompletionLog>
        columns={columns}
        data={rows}
        loading={loading}
        pagination={{ page, pageSize, total, totalPages }}
        onPageChange={setPage}
        onPageSizeChange={(s) => { setPageSize(s); setPage(0); }}
        onSearchChange={() => { /* server-side text search not enabled for logs */ }}
        searchable={false}
        searchPlaceholder="Search retest activity"
        idAccessor="retestId"
        onRowClick={(r) => navigate(`/retests/${r.retestId}`)}
        emptyMessage="No retests were completed in this period."
        sort={sort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole log, so the current page number is
          // meaningless afterwards — go back to the first page.
          setSort(next);
          setPage(0);
        }}
      />
    </>
  );
}
