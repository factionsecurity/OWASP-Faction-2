import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Timer, CalendarClock, AlertTriangle } from 'lucide-react';
import { applicationsApi, assessmentsApi, remediationApi, vulnerabilitiesApi, workflowConfigApi } from '../api';
import { Badge, SeverityBadge } from '../components';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import SearchableSelect, { MultiSelect, SelectOption } from '../components/SearchableSelect';
import VulnerabilityDetailDrawer from '../components/VulnerabilityDetailDrawer';
import SeverityPillCard, { positiveEmpty } from '../components/SeverityPillCard';
import Page from '../components/Page';
import { DEFAULT_VULN_STATUSES } from '../utils/vulnStatus';
import { SEVERITY_OPTIONS } from '../utils/vulnSeverity';
import type {
  Assessment, RemediationQueueRow, Vulnerability, VulnerabilityTrendSummary,
} from '../types';
import './AppOwnerDashboard.css';

const DAY_MS = 86_400_000;

// The due-soon table is a view of the remediation queue's vulnerability half — server-paginated
// and server-filtered, so the page never holds more than one screenful of findings.
const PAGE_SIZE = 10; // must match a DataTable page-size option (10/25/50/100)
const OPTION_LIMIT = 250; // starter list for the application dropdown; typing server-searches

const STATUS_COLORS: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'secondary'> = {
  'None': 'secondary',
  'Open': 'warning',
  'Past Due': 'danger',
  'Closed': 'success',
  'Exception': 'info',
};

const fmtDate = (d?: string | Date) => (d ? new Date(d).toLocaleDateString() : '—');

/** Whole days from today to a due date, for the "Nd left" hint on rows not yet overdue. */
const daysLeft = (due?: string) => {
  if (!due) return 0;
  const today = new Date().setHours(0, 0, 0, 0);
  return Math.round((new Date(due).setHours(0, 0, 0, 0) - today) / DAY_MS);
};

/**
 * Mean open-to-close days across the given severities. The server returns a per-severity sum
 * and count rather than a mean, so combining severities weights them correctly — averaging
 * per-severity means would count a severity with two closures the same as one with two hundred.
 */
function meanDays(summary: VulnerabilityTrendSummary | null, ...severities: string[]) {
  if (!summary) return { mean: null as number | null, count: 0 };
  const count = severities.reduce((n, sev) => n + (summary.closedWithDates[sev] || 0), 0);
  if (count === 0) return { mean: null as number | null, count: 0 };
  const total = severities.reduce((d, sev) => d + (summary.daysToCloseTotal[sev] || 0), 0);
  return { mean: Math.round(total / count), count };
}

export default function AppOwnerDashboard() {
  const [statsLoading, setStatsLoading] = useState(true);
  const [summary, setSummary] = useState<VulnerabilityTrendSummary | null>(null);
  const [nextAssessment, setNextAssessment] = useState<Assessment | null>(null);
  const [nextLoading, setNextLoading] = useState(true);

  // Due-date table: server-paginated, -sorted and -filtered off the remediation queue.
  const [dueSoon, setDueSoon] = useState<RemediationQueueRow[]>([]);
  const [tableLoading, setTableLoading] = useState(true);
  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0, pageSize: PAGE_SIZE, total: 0, totalPages: 0,
  });
  const [sort, setSort] = useState<SortState | null>(null);
  const [search, setSearch] = useState('');
  const [filterSeverity, setFilterSeverity] = useState('');
  const [filterApplicationId, setFilterApplicationId] = useState('');
  const [filterStatuses, setFilterStatuses] = useState<string[]>([]);

  // Dropdown options
  const [appOptions, setAppOptions] = useState<SelectOption[]>([]);
  const [appLoading, setAppLoading] = useState(false);
  const [appLabels, setAppLabels] = useState<Record<string, string>>({});
  const [configuredStatuses, setConfiguredStatuses] = useState<string[]>(DEFAULT_VULN_STATUSES);

  const [selectedVuln, setSelectedVuln] = useState<Vulnerability | null>(null);
  const [selectedAssessment, setSelectedAssessment] = useState<Assessment | null>(null);

  // Fetched once: the next assessment (not a vulnerability aggregate, so the findings filters
  // below don't apply to it) and the status list the filter offers.
  useEffect(() => {
    const today = new Date().toISOString().slice(0, 10);
    // Only the soonest upcoming assessment is shown, so ask for exactly that one row.
    assessmentsApi.search({ size: 1, sort: 'startDate,asc', startDateFrom: `${today}T00:00:00` })
      .then(res => setNextAssessment(res.data?.[0] ?? null))
      .catch(() => setNextAssessment(null))
      .finally(() => setNextLoading(false));

    workflowConfigApi.getConfig()
      .then(res => {
        const custom = res.data?.vulnerabilityStatuses || [];
        setConfiguredStatuses([...DEFAULT_VULN_STATUSES,
          ...custom.filter(st => !DEFAULT_VULN_STATUSES.includes(st))]);
      })
      .catch(() => setConfiguredStatuses(DEFAULT_VULN_STATUSES));

    searchApps('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The severity bars and MTTR re-aggregate with the table's filters, so the figures always
  // describe the rows below them. Page/size/sort are excluded — the summary covers the whole
  // filtered set, so paging must not move the numbers.
  //
  // This page used to load every assessment and then fan out one vulnerability request per
  // assessment — hundreds of round-trips, throttled six at a time by the browser — to recompute
  // counts, SLA windows and MTTR on the client. It is one scoped server aggregate now.
  const summaryReq = useRef(0);
  useEffect(() => {
    const reqId = ++summaryReq.current;
    setStatsLoading(true);
    vulnerabilitiesApi.getSummary({
      applicationId: filterApplicationId || undefined,
      severities: filterSeverity ? [filterSeverity] : undefined,
      statuses: filterStatuses.length ? filterStatuses : undefined,
      search: search || undefined,
    })
      .then(res => { if (reqId === summaryReq.current) setSummary(res.data ?? null); })
      .catch(() => { if (reqId === summaryReq.current) setSummary(null); })
      .finally(() => { if (reqId === summaryReq.current) setStatsLoading(false); });
  }, [filterApplicationId, filterSeverity, filterStatuses, search]);

  // Re-fetch the table whenever a filter, page, size or sort changes. Every one of these is
  // applied server-side, so the page size is the whole cost regardless of how many findings exist.
  useEffect(() => {
    let cancelled = false;
    setTableLoading(true);
    remediationApi.getQueue({
      type: 'VULNERABILITY',
      page: pagination.page,
      size: pagination.pageSize,
      sort: sortParam(sort),
      search: search || undefined,
      severity: filterSeverity || undefined,
      applicationId: filterApplicationId || undefined,
      statuses: filterStatuses.length ? filterStatuses : undefined,
    })
      .then(res => {
        if (cancelled) return;
        setDueSoon(res.data || []);
        setPagination(prev => ({
          ...prev,
          total: res.pagination?.totalElements ?? 0,
          totalPages: res.pagination?.totalPages ?? 0,
        }));
      })
      .catch(() => { if (!cancelled) { setDueSoon([]); } })
      .finally(() => { if (!cancelled) setTableLoading(false); });
    return () => { cancelled = true; };
  }, [pagination.page, pagination.pageSize, sort, search, filterSeverity, filterApplicationId,
      filterStatuses]);

  const searchApps = async (query: string) => {
    setAppLoading(true);
    try {
      const res = await applicationsApi.getAll(0, OPTION_LIMIT, query);
      setAppOptions((res.data || []).map(a => ({ value: a.id, label: a.name })));
    } catch {
      setAppOptions([]);
    } finally {
      setAppLoading(false);
    }
  };

  const handlePageChange = useCallback((page: number) =>
    setPagination(prev => ({ ...prev, page })), []);
  const handlePageSizeChange = useCallback((pageSize: number) =>
    setPagination(prev => ({ ...prev, page: 0, pageSize })), []);
  const handleSearchChange = useCallback((next: string) => {
    setSearch(next);
    setPagination(prev => ({ ...prev, page: 0 }));
  }, []);

  const openBySeverity = useMemo(() => summary?.openFindings ?? {}, [summary]);

  // Mean Time to Remediation over closed Critical/High findings
  const mttr = useMemo(() => {
    const combined = meanDays(summary, 'CRITICAL', 'HIGH');
    return {
      combined: combined.mean,
      critical: meanDays(summary, 'CRITICAL').mean,
      high: meanDays(summary, 'HIGH').mean,
      count: combined.count,
    };
  }, [summary]);

  // The queue row carries only ids, so resolve the finding and its assessment on click —
  // one pair of requests for the row actually opened, rather than for every row up front.
  const openDrawer = async (row: RemediationQueueRow) => {
    if (!row.assessmentId) return;
    try {
      const [vulnRes, asmtRes] = await Promise.all([
        vulnerabilitiesApi.getById(row.assessmentId, row.vulnerabilityId),
        assessmentsApi.getById(row.assessmentId),
      ]);
      if (vulnRes.data) {
        setSelectedVuln(vulnRes.data);
        setSelectedAssessment(asmtRes.data ?? null);
      }
    } catch { /* ignore — the drawer just won't open */ }
  };

  const daysUntilNext = nextAssessment?.startDate
    ? Math.round((new Date(nextAssessment.startDate).setHours(0, 0, 0, 0) - new Date().setHours(0, 0, 0, 0)) / DAY_MS)
    : null;

  const columns: Column<RemediationQueueRow>[] = [
    {
      header: 'Vulnerability',
      sortKey: 'name',
      render: r => <span className="aod-primary-text">{r.vulnerabilityName || '—'}</span>,
    },
    { header: 'Application', sortKey: 'applicationName', render: r => r.applicationName || '—' },
    {
      header: 'Severity',
      sortKey: 'severity',
      render: r => (r.severity ? <SeverityBadge severity={r.severity} /> : '—'),
    },
    {
      header: 'Status',
      sortKey: 'vulnerabilityStatus',
      render: r => (
        <Badge variant={STATUS_COLORS[r.vulnerabilityStatus || 'None'] ?? 'info'} size="sm">
          {r.vulnerabilityStatus || 'None'}
        </Badge>
      ),
    },
    {
      header: 'Due Date',
      sortKey: 'dueDate',
      render: r => (
        <span className={r.urgent ? 'aod-due aod-due--overdue' : 'aod-due aod-due--warning'}>
          {fmtDate(r.dueDate)}
          {r.urgent ? ' · Overdue' : ` · ${daysLeft(r.dueDate)}d left`}
        </span>
      ),
    },
  ];

  // Merge the selected application into the option list so its label survives a server search
  // that narrows away from it.
  const appOptionsMerged = filterApplicationId && !appOptions.some(o => o.value === filterApplicationId)
    && appLabels[filterApplicationId]
      ? [{ value: filterApplicationId, label: appLabels[filterApplicationId] }, ...appOptions]
      : appOptions;

  const anyFilterActive = !!(search || filterSeverity || filterApplicationId || filterStatuses.length);

  const headerFilters = (
    <div className="ss-filter-bar">
      <SearchableSelect
        value={filterSeverity}
        onChange={(v) => { setFilterSeverity(v); setPagination(prev => ({ ...prev, page: 0 })); }}
        options={SEVERITY_OPTIONS}
        searchable={false}
        placeholder="All Severities"
      />
      <SearchableSelect
        value={filterApplicationId}
        onChange={(v) => {
          setFilterApplicationId(v); setPagination(prev => ({ ...prev, page: 0 }));
          if (v) {
            const o = appOptionsMerged.find(x => x.value === v);
            if (o) setAppLabels(p => ({ ...p, [v]: o.label }));
          }
        }}
        options={appOptionsMerged}
        onQueryChange={searchApps}
        loading={appLoading}
        placeholder="All Applications"
      />
      <MultiSelect
        selected={filterStatuses}
        onChange={(vals) => { setFilterStatuses(vals); setPagination(prev => ({ ...prev, page: 0 })); }}
        options={configuredStatuses.map(st => ({ value: st, label: st }))}
        searchable={false}
        placeholder="All Statuses"
      />
    </div>
  );

  return (
    <Page className="app-owner-dashboard">
      <div className="aod-stats">
        <SeverityPillCard
          title="Open Vulnerabilities"
          counts={openBySeverity}
          loading={statsLoading}
          emptyContent={positiveEmpty('No Open Issues')}
        />

        <SeverityPillCard
          title="Tracked Open Findings"
          counts={summary?.trackedOpen ?? {}}
          loading={statsLoading}
          emptyContent={positiveEmpty('Nothing Tracked')}
        />

        <SeverityPillCard
          title="Exceptions"
          counts={summary?.exceptions ?? {}}
          loading={statsLoading}
        />

        <div className="aod-stat-card">
          <div className="aod-stat-icon aod-stat-icon--info">
            <Timer size={22} />
          </div>
          <div className="aod-stat-info">
            <div className="aod-stat-label">Mean Time to Remediation</div>
            <div className="aod-stat-value">
              {statsLoading ? '…' : mttr.combined !== null ? `${mttr.combined} days` : '—'}
            </div>
            <div className="aod-stat-sub">
              {statsLoading
                ? ''
                : mttr.count > 0
                  ? `Critical: ${mttr.critical !== null ? `${mttr.critical}d` : '—'} · High: ${mttr.high !== null ? `${mttr.high}d` : '—'} (${mttr.count} closed)`
                  : 'No closed Critical/High findings yet'}
            </div>
          </div>
        </div>

        <div className="aod-stat-card">
          <div className="aod-stat-icon aod-stat-icon--primary">
            <CalendarClock size={22} />
          </div>
          <div className="aod-stat-info">
            <div className="aod-stat-label">Next Assessment</div>
            <div className="aod-stat-value">
              {nextLoading ? '…' : nextAssessment ? fmtDate(nextAssessment.startDate) : '—'}
            </div>
            <div className="aod-stat-sub">
              {nextLoading ? '' : nextAssessment ? (
                <>
                  <Link to={`/assessments/${nextAssessment.id}`} className="aod-link">
                    {nextAssessment.name}
                  </Link>
                  {daysUntilNext !== null && ` · ${daysUntilNext === 0 ? 'today' : `in ${daysUntilNext}d`}`}
                </>
              ) : 'None scheduled'}
            </div>
          </div>
        </div>
      </div>

      <section className="aod-card">
        <header className="aod-card-header">
          <h2 className="aod-card-title">
            <AlertTriangle size={16} />
            Vulnerabilities Approaching Due Date
            {!tableLoading && <span className="aod-count">{pagination.total}</span>}
          </h2>
        </header>
        <DataTable
          columns={columns}
          data={dueSoon}
          loading={tableLoading}
          pagination={pagination}
          onPageChange={handlePageChange}
          onPageSizeChange={handlePageSizeChange}
          onSearchChange={handleSearchChange}
          searchPlaceholder="Search vulnerabilities"
          emptyMessage={anyFilterActive
            ? 'No findings match these filters.'
            : 'No open vulnerabilities near their SLA due date.'}
          idAccessor="key"
          onRowClick={openDrawer}
          headerChildren={headerFilters}
          sort={sort}
          onSortChange={(next) => {
            // Re-sorting reshuffles the whole result set, so the current page number is
            // meaningless afterwards — go back to the first page. Clearing it restores the
            // queue's default urgent → warning → upcoming ordering.
            setSort(next);
            setPagination(prev => ({ ...prev, page: 0 }));
          }}
        />
      </section>

      {selectedVuln && selectedAssessment && (
        <VulnerabilityDetailDrawer
          vulnerability={selectedVuln}
          assessment={selectedAssessment}
          onClose={() => { setSelectedVuln(null); setSelectedAssessment(null); }}
          showException
        />
      )}
    </Page>
  );
}
