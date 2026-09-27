import { useCallback, useEffect, useMemo, useState } from 'react';
import { SeverityBadge, Badge } from '../components';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { FileText } from 'lucide-react';
import { retestApi } from '../api';
import type { Retest, RetestReportReady } from '../types';
import RetestReportButton from '../components/RetestReportButton';
import AssessmentReportsDialog from '../components/AssessmentReportsDialog';
import DataTable, { Column, PaginationInfo, SortState } from '../components/DataTable';
import { applyClientSort, SortAccessors } from '../utils/tableSort';
import Page from '../components/Page';
import { usePersistedState } from '../hooks/usePersistedState';
import './RetestsPage.css';

const fmtDate = (d?: string) => (d ? new Date(d).toLocaleDateString() : '-');

const columns: Column<Retest>[] = [
  {
    header: 'Vulnerability',
    sortKey: 'vulnerabilityName',
    render: r => r.vulnerabilityName || r.vulnerabilityId,
  },
  {
    header: 'Assessment',
    sortKey: 'assessmentName',
    render: r => r.assessmentName || r.assessmentId,
  },
  {
    header: 'Severity',
    sortKey: 'vulnerabilitySeverity',
    render: r => r.vulnerabilitySeverity ? (
      <SeverityBadge severity={r.vulnerabilitySeverity} />
    ) : '-',
  },
  {
    header: 'Start Date',
    sortKey: 'scheduledStartDate',
    render: r => r.scheduledStartDate ? new Date(r.scheduledStartDate).toLocaleDateString() : '-',
  },
  {
    header: 'End Date',
    sortKey: 'scheduledEndDate',
    render: r => r.scheduledEndDate ? new Date(r.scheduledEndDate).toLocaleDateString() : '-',
  },
  {
    header: 'Assigned To',
    sortKey: 'assignedAssessorNames',
    render: r => r.assignedAssessorNames?.join(', ') || '-',
  },
];

// This list comes from an unpaginated endpoint, so sorting happens here rather than server-side.
const SORT_ACCESSORS: SortAccessors<Retest> = {
  vulnerabilityName: r => r.vulnerabilityName || r.vulnerabilityId,
  assessmentName: r => r.assessmentName || r.assessmentId,
  vulnerabilitySeverity: r => r.vulnerabilitySeverity,
  scheduledStartDate: r => r.scheduledStartDate,
  scheduledEndDate: r => r.scheduledEndDate,
  assignedAssessorNames: r => r.assignedAssessorNames?.join(', '),
};

const PAGE_SIZE = 15;
// The Completed tab's default window: retests completed in the last this-many days.
const RECENT_DAYS = 30;
// localStorage key for this table's saved search, sort and paging.
const TABLE_KEY = 'retests';

/** One assessment's completed retests, folded into a single card for the Completed tab. */
interface RetestGroup {
  assessmentId: string;
  assessmentName: string;
  applicationName?: string;
  applicationAppId?: string;
  passedCount: number;
  /** Findings on the assessment with no passed or failed retest; undefined until loaded. */
  untestedCount?: number;
  failedCount: number;
  newestClosedDate: string;
  reportGeneratedAt?: string;
  ready?: RetestReportReady;
  retests: Retest[];
}

function groupCompletedRetests(
  retests: Retest[],
  ready: RetestReportReady[],
  untested: Record<string, number>,
): RetestGroup[] {
  const readyById = new Map(ready.map(r => [r.assessmentId, r]));
  const groups = new Map<string, RetestGroup>();
  for (const r of retests) {
    let g = groups.get(r.assessmentId);
    if (!g) {
      g = {
        assessmentId: r.assessmentId,
        assessmentName: r.assessmentName || r.assessmentId,
        applicationName: r.applicationName,
        applicationAppId: r.applicationAppId,
        passedCount: 0,
        failedCount: 0,
        newestClosedDate: '',
        reportGeneratedAt: undefined,
        ready: readyById.get(r.assessmentId),
        untestedCount: untested[r.assessmentId],
        retests: [],
      };
      groups.set(r.assessmentId, g);
    }
    g.retests.push(r);
    if (!g.applicationName && r.applicationName) g.applicationName = r.applicationName;
    if (!g.applicationAppId && r.applicationAppId) g.applicationAppId = r.applicationAppId;
    if (!g.reportGeneratedAt && r.assessmentRetestReportGeneratedAt) {
      g.reportGeneratedAt = r.assessmentRetestReportGeneratedAt;
    }
    if (r.closedDate && r.closedDate > g.newestClosedDate) g.newestClosedDate = r.closedDate;
  }
  for (const g of groups.values()) {
    // Counts always come from the retests shown below the header (assigned-to-me, all-time) —
    // never from the ready-for-report endpoint, whose counts cover a different scope (every
    // assessor, only since the last report) and would disagree with the rows underneath them.
    // `g.ready` is used only to decide whether the Generate button (and its hasRetestTemplate)
    // shows.
    g.passedCount = g.retests.filter(r => r.result === 'PASS').length;
    g.failedCount = g.retests.filter(r => r.result === 'FAIL').length;
    g.retests.sort((a, b) => (b.closedDate || '').localeCompare(a.closedDate || ''));
  }
  return Array.from(groups.values()).sort((a, b) => b.newestClosedDate.localeCompare(a.newestClosedDate));
}

function CompletedGroupCard({ group, onRowClick, onOpenReports }: {
  group: RetestGroup;
  onRowClick: (id: string) => void;
  onOpenReports: (group: RetestGroup) => void;
}) {
  return (
    <div className="retest-group-card">
      <div className="retest-group-header">
        <div className="retest-group-title">
          <span className="retest-group-name">{group.assessmentName}</span>
          {(group.applicationName || group.applicationAppId) && (
            <span className="retest-group-meta">
              {group.applicationName}
              {group.applicationAppId && <span className="retest-group-appid">{group.applicationAppId}</span>}
            </span>
          )}
          <span className="retest-group-counts">
            <span className="retest-group-count retest-group-count--pass">{group.passedCount} passed</span>
            <span className="retest-group-count retest-group-count--fail">{group.failedCount} failed</span>
            {group.untestedCount !== undefined && (
              <span className="retest-group-count retest-group-count--untested">{group.untestedCount} not tested</span>
            )}
          </span>
        </div>
        <div className="retest-group-actions">
          {group.ready ? (
            <RetestReportButton assessmentId={group.assessmentId} hasRetestTemplate={group.ready.hasRetestTemplate} />
          ) : (
            <span className="retest-group-meta">
              {group.reportGeneratedAt
                ? `No new retests since ${fmtDate(group.reportGeneratedAt)}`
                : 'No retest report yet'}
            </span>
          )}
          <button type="button" className="retest-group-reports-btn" onClick={() => onOpenReports(group)}>
            <FileText size={14} /> Reports
          </button>
        </div>
      </div>
      <table className="retest-group-table">
        {/* Fixed widths so the columns line up from one assessment card to the next. */}
        <colgroup>
          <col />
          <col style={{ width: '120px' }} />
          <col style={{ width: '100px' }} />
          <col style={{ width: '120px' }} />
          <col style={{ width: '200px' }} />
        </colgroup>
        <thead>
          <tr>
            <th>Vulnerability</th>
            <th>Severity</th>
            <th>Result</th>
            <th>Closed</th>
            <th>Completed By</th>
          </tr>
        </thead>
        <tbody>
          {group.retests.map(r => (
            <tr key={r.id} onClick={() => onRowClick(r.id)}>
              <td className="retest-group-vuln">{r.vulnerabilityName || r.vulnerabilityId}</td>
              <td>{r.vulnerabilitySeverity ? <SeverityBadge severity={r.vulnerabilitySeverity} /> : '-'}</td>
              <td>
                <Badge variant={r.result === 'PASS' ? 'success' : 'danger'} size="sm">
                  {r.result === 'PASS' ? 'Passed' : 'Failed'}
                </Badge>
              </td>
              <td>{fmtDate(r.closedDate)}</td>
              <td>{r.completedByName || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function RetestsPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [tab, setTab] = useState<'scheduled' | 'completed'>(
    searchParams.get('tab') === 'completed' ? 'completed' : 'scheduled'
  );

  const [allRetests, setAllRetests] = useState<Retest[]>([]);
  // Starts true so DataTable doesn't clamp a restored page against the empty pre-load list.
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = usePersistedState(TABLE_KEY, 'search', '');
  const [sort, setSort] = usePersistedState<SortState | null>(TABLE_KEY, 'sort', null);
  const [page, setPage] = usePersistedState(TABLE_KEY, 'page', 0);
  const [pageSize, setPageSize] = usePersistedState(TABLE_KEY, 'pageSize', PAGE_SIZE);

  const [completedRetests, setCompletedRetests] = useState<Retest[]>([]);
  const [readyReports, setReadyReports] = useState<RetestReportReady[]>([]);
  const [untestedCounts, setUntestedCounts] = useState<Record<string, number>>({});
  const [completedLoading, setCompletedLoading] = useState(false);
  // Off: only retests completed in the last RECENT_DAYS. On: every completed retest.
  const [showAllDates, setShowAllDates] = usePersistedState(TABLE_KEY, 'completedAllDates', false);
  // Both tabs. Off: retests assigned to me. On: every retest I can see, so a tester can find and
  // pick up one assigned to someone else — the server scopes the list to the caller's teams or
  // own assessments where their role is restricted.
  const [showAllAssessors, setShowAllAssessors] = usePersistedState(TABLE_KEY, 'allAssessors', false);
  const [reportsFor, setReportsFor] = useState<RetestGroup | null>(null);

  useEffect(() => {
    setLoading(true);
    retestApi.getAll({ assignedToMe: !showAllAssessors })
      .then(res => {
        if (res.success && res.data) {
          setAllRetests((res.data as Retest[]).filter(r => r.status === 'SCHEDULED'));
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [showAllAssessors]);

  useEffect(() => {
    if (tab !== 'completed') return;
    setCompletedLoading(true);
    Promise.all([
      retestApi.getAll({ assignedToMe: !showAllAssessors, status: 'PASSED,FAILED' }),
      retestApi.getReadyForReport(),
    ])
      .then(([retestsRes, readyRes]) => {
        const completed = (retestsRes.data as Retest[]) ?? [];
        setCompletedRetests(completed);
        if (readyRes.data) setReadyReports(readyRes.data);
        const ids = Array.from(new Set(completed.map(r => r.assessmentId)));
        if (ids.length > 0) {
          retestApi.getUntestedCounts(ids)
            .then(res => { if (res.data) setUntestedCounts(res.data); })
            .catch(() => {});
        }
      })
      .catch(() => {})
      .finally(() => setCompletedLoading(false));
  }, [tab, showAllAssessors]);

  const handleTabChange = useCallback((next: 'scheduled' | 'completed') => {
    setTab(next);
    setPage(0);
    const params = new URLSearchParams(searchParams);
    if (next === 'completed') params.set('tab', 'completed');
    else params.delete('tab');
    setSearchParams(params, { replace: true });
  }, [searchParams, setSearchParams]);

  const filtered = applyClientSort(allRetests.filter(r => {
    if (!search) return true;
    const q = search.toLowerCase();
    return (
      (r.vulnerabilityName || '').toLowerCase().includes(q) ||
      (r.assessmentName || '').toLowerCase().includes(q) ||
      (r.vulnerabilitySeverity || '').toLowerCase().includes(q) ||
      (r.assignedAssessorNames || []).some(n => n.toLowerCase().includes(q))
    );
  }), sort, SORT_ACCESSORS);

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const pageData = filtered.slice(page * pageSize, (page + 1) * pageSize);

  const pagination: PaginationInfo = {
    page,
    pageSize,
    total: filtered.length,
    totalPages,
  };

  const completedGroups = useMemo(() => {
    const cutoff = new Date();
    cutoff.setDate(cutoff.getDate() - RECENT_DAYS);
    const cutoffIso = cutoff.toISOString();
    const inRange = showAllDates
      ? completedRetests
      : completedRetests.filter(r => (r.closedDate || '') >= cutoffIso.slice(0, 19));
    return groupCompletedRetests(inRange, readyReports, untestedCounts);
  }, [completedRetests, readyReports, untestedCounts, showAllDates]);

  const completedFiltered = useMemo(() => {
    if (!search) return completedGroups;
    const q = search.toLowerCase();
    const has = (v?: string) => (v || '').toLowerCase().includes(q);
    return completedGroups.filter(g =>
      has(g.assessmentName) || has(g.applicationName) || has(g.applicationAppId)
      || g.retests.some(r => has(r.vulnerabilityName))
    );
  }, [completedGroups, search]);

  const completedTotalPages = Math.max(1, Math.ceil(completedFiltered.length / pageSize));
  const completedPageData = completedFiltered.slice(page * pageSize, (page + 1) * pageSize);

  const completedPagination: PaginationInfo = {
    page,
    pageSize,
    total: completedFiltered.length,
    totalPages: completedTotalPages,
  };

  const completedColumns: Column<RetestGroup>[] = [
    {
      header: '',
      render: group => (
        <CompletedGroupCard
          group={group}
          onRowClick={id => navigate(`/retests/${id}`)}
          onOpenReports={setReportsFor}
        />
      ),
    },
  ];

  const handleSearchChange = useCallback((s: string) => {
    setSearch(s);
    setPage(0);
  }, []);

  const handlePageSizeChange = useCallback((ps: number) => {
    setPageSize(ps);
    setPage(0);
  }, []);

  const tabToggle = (
    <div className="retests-tab-toggle" role="group" aria-label="View">
      <button
        type="button"
        className={tab === 'scheduled' ? 'active' : ''}
        onClick={() => handleTabChange('scheduled')}
      >
        Scheduled
      </button>
      <button
        type="button"
        className={tab === 'completed' ? 'active' : ''}
        onClick={() => handleTabChange('completed')}
      >
        Completed
      </button>
    </div>
  );

  const assessorToggle = (
    <label className="retests-filter-toggle">
      <input
        type="checkbox"
        checked={showAllAssessors}
        onChange={e => { setShowAllAssessors(e.target.checked); setPage(0); }}
      />
      Show all assessors
    </label>
  );

  return (
    <Page className="retests-page">
      {tab === 'scheduled' ? (
        <DataTable
          columns={columns}
          data={pageData}
          loading={loading}
          pagination={pagination}
          onPageChange={setPage}
          onPageSizeChange={handlePageSizeChange}
          initialSearch={search}
          onSearchChange={handleSearchChange}
          searchPlaceholder="Search retests"
          headerChildren={<>{tabToggle}{assessorToggle}</>}
          emptyMessage={showAllAssessors ? 'No scheduled retests.' : 'No scheduled retests assigned to you.'}
          idAccessor="id"
          onRowClick={r => navigate(`/retests/${r.id}`)}
          sort={sort}
          onSortChange={next => { setSort(next); setPage(0); }}
        />
      ) : (
        <div className="retests-completed">
          <DataTable
            columns={completedColumns}
            data={completedPageData}
            loading={completedLoading}
            pagination={completedPagination}
            onPageChange={setPage}
            onPageSizeChange={handlePageSizeChange}
            initialSearch={search}
            onSearchChange={handleSearchChange}
            searchPlaceholder="Search assessment, application or app ID"
            headerChildren={
              <>
                {tabToggle}
                <label className="retests-filter-toggle">
                  <input
                    type="checkbox"
                    checked={showAllDates}
                    onChange={e => { setShowAllDates(e.target.checked); setPage(0); }}
                  />
                  Show all dates
                </label>
                {assessorToggle}
              </>
            }
            emptyMessage={showAllDates
              ? 'No completed retests yet.'
              : `No retests completed in the last ${RECENT_DAYS} days.`}
            idAccessor="assessmentId"
          />
        </div>
      )}
      {reportsFor && (
        <AssessmentReportsDialog
          isOpen
          assessmentId={reportsFor.assessmentId}
          assessmentName={reportsFor.assessmentName}
          onClose={() => setReportsFor(null)}
        />
      )}
    </Page>
  );
}
