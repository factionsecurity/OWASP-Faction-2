import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Eye, ClipboardList } from 'lucide-react';
import { assessmentsApi, workflowConfigApi, assessmentSurveysApi, applicationsApi } from '../api';
import type { Assessment, AssessmentSurvey } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import { Badge, IconButton, ActionButtons } from '../components';
import { MultiSelect, SelectOption } from '../components/SearchableSelect';
import ReportPreviewDrawer from '../components/ReportPreviewDrawer';
import SurveyDrawer from '../components/SurveyDrawer';
import '../components/SearchableSelect.css';

const STATUS_COLORS: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'secondary'> = {
  DRAFT: 'secondary',
  IN_PROGRESS: 'info',
  ON_HOLD: 'warning',
  PENDING_REVIEW: 'info',
  COMPLETED: 'success',
  APPROVED: 'success',
  ARCHIVED: 'secondary',
};

const PAGE_SIZE = 10;
// App-filter dropdown only shows a starter list; server-side search reaches the rest, so a
// small limit keeps the default load fast and the list short.
const APP_OPTION_LIMIT = 250;

// ── Tab ───────────────────────────────────────────────────────────────────────
export default function ApplicationsAssessmentsTab() {
  const [allAssessments, setAllAssessments] = useState<Assessment[]>([]);
  const [statusColors, setStatusColors] = useState<Record<string, string>>({});
  const [completedStatus, setCompletedStatus] = useState('');
  const [loading, setLoading] = useState(false);
  const [tablePage, setTablePage] = useState(0);
  const [pageSize, setPageSize] = useState(PAGE_SIZE);
  const [total, setTotal] = useState(0);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);
  const [filterApplicationIds, setFilterApplicationIds] = useState<string[]>([]);
  const [filterStatuses, setFilterStatuses] = useState<string[]>([]);
  // Only assessments still waiting on a survey response.
  const [openSurveysOnly, setOpenSurveysOnly] = useState(false);
  // Statuses are configured per install, so the options come from the workflow config.
  const [statusOptions, setStatusOptions] = useState<string[]>([]);
  const [appOptions, setAppOptions] = useState<SelectOption[]>([]);
  const [appSearchLoading, setAppSearchLoading] = useState(false);
  const [selectedAppLabels, setSelectedAppLabels] = useState<Record<string, string>>({});
  const [previewAssessment, setPreviewAssessment] = useState<Assessment | null>(null);
  const [surveyAssessment, setSurveyAssessment] = useState<Assessment | null>(null);
  const [initialSurveyId, setInitialSurveyId] = useState<string | undefined>(undefined);
  const [surveyMap, setSurveyMap] = useState<Record<string, AssessmentSurvey[]>>({});
  const [searchParams, setSearchParams] = useSearchParams();

  // Request-sequence guards so a slow earlier response can't overwrite a newer one.
  const loadPageReq = useRef(0);
  const searchAppsReq = useRef(0);

  // App-filter options: default to a starter list, but server-search on type so all are reachable.
  const searchApps = async (query: string) => {
    const reqId = ++searchAppsReq.current;
    setAppSearchLoading(true);
    try {
      const res = await applicationsApi.getAll(0, APP_OPTION_LIMIT, query);
      if (reqId !== searchAppsReq.current) return; // a newer search superseded this one
      setAppOptions((res.data || []).map(app => ({ value: app.id, label: app.name })));
    } catch {
      if (reqId === searchAppsReq.current) setAppOptions([]);
    } finally {
      if (reqId === searchAppsReq.current) setAppSearchLoading(false);
    }
  };

  // One page of assessments from the server, plus surveys for just those rows (page size 10–100),
  // instead of loading the first 1k assessments and fanning out a survey call per assessment.
  const loadPage = async () => {
    const reqId = ++loadPageReq.current;
    setLoading(true);
    try {
      const res = await assessmentsApi.search({
        page: tablePage,
        size: pageSize,
        search: search || undefined,
        applicationIds: filterApplicationIds.length ? filterApplicationIds : undefined,
        statuses: filterStatuses.length ? filterStatuses : undefined,
        openSurveys: openSurveysOnly || undefined,
        sort: sortParam(sort) ?? 'createdAt,desc',
      });
      if (reqId !== loadPageReq.current) return; // superseded by a newer page/filter load
      const rows = res.data || [];
      setAllAssessments(rows);
      setTotal(res.pagination?.totalElements ?? rows.length);

      const surveyResults = await Promise.all(rows.map(a =>
        assessmentSurveysApi.getByAssessment(a.id)
          .then(r => ({ id: a.id, surveys: r.data || [] }))
          .catch(() => ({ id: a.id, surveys: [] as AssessmentSurvey[] }))
      ));
      if (reqId !== loadPageReq.current) return; // a newer load started during the survey fetch
      const map: Record<string, AssessmentSurvey[]> = {};
      surveyResults.forEach(({ id, surveys }) => { map[id] = surveys; });
      setSurveyMap(map);
    } finally {
      if (reqId === loadPageReq.current) setLoading(false);
    }
  };

  // Re-fetch the page whenever the page, size, search, or app filter changes.
  useEffect(() => {
    loadPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tablePage, pageSize, search, filterApplicationIds, filterStatuses, openSurveysOnly, sort]);

  // One-time: workflow config, default app options, and the chat deep link
  // (?assessment=&survey=) — the target may not be on the current page, so fetch it directly.
  useEffect(() => {
    workflowConfigApi.getConfig().then(res => {
      if (res.success && res.data?.statusColors) setStatusColors(res.data.statusColors);
      if (res.success && res.data?.completedStatus) setCompletedStatus(res.data.completedStatus);
      if (res.success && res.data?.statuses) setStatusOptions(res.data.statuses);
    }).catch(() => {});

    searchApps('');

    const linkedAssessmentId = searchParams.get('assessment');
    if (linkedAssessmentId) {
      assessmentsApi.getById(linkedAssessmentId).then(r => {
        if (r.data) {
          setInitialSurveyId(searchParams.get('survey') ?? undefined);
          setSurveyAssessment(r.data);
        }
      }).catch(() => {});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Options come from the server (a starter list by default; server-searched on type). Merge in any
  // selected apps by their cached label so a selection stays visible when the search narrows.
  const applicationOptions: SelectOption[] = (() => {
    const byId = new Map<string, SelectOption>();
    filterApplicationIds.forEach(id => {
      const label = selectedAppLabels[id];
      if (label) byId.set(id, { value: id, label });
    });
    appOptions.forEach(o => byId.set(o.value, o));
    return Array.from(byId.values()).sort((a, b) => a.label.localeCompare(b.label));
  })();

  const pagination: PaginationInfo = {
    page: tablePage,
    pageSize,
    total,
    totalPages: Math.ceil(total / pageSize),
  };

  const columns: Column<Assessment>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      render: (a) => (
        <div>
          <div>{a.name}</div>
          {a.isPastDue && <Badge variant="danger" size="sm">Past Due</Badge>}
        </div>
      ),
    },
    {
      header: 'Application',
      sortKey: 'applicationName',
      render: (a) => <span>{a.applicationName || '-'}</span>,
    },
    {
      header: 'Status',
      sortKey: 'status',
      render: (a) => {
        const custom = statusColors[a.status];
        return (
          <Badge
            variant={custom ? undefined : (STATUS_COLORS[a.status] || 'secondary')}
            customColor={custom}
          >
            {a.status}
          </Badge>
        );
      },
    },
    {
      header: 'Start Date',
      sortKey: 'startDate',
      render: (a) => a.startDate ? new Date(a.startDate).toLocaleDateString() : '-',
    },
    {
      header: 'End Date',
      sortKey: 'plannedEndDate',
      render: (a) => a.plannedEndDate ? new Date(a.plannedEndDate).toLocaleDateString() : '-',
    },
    {
      header: 'Vulnerabilities',
      render: (a) => {
        // Counts only apply once the assessment is finalized (its vulns are opened); read the
        // server-computed per-assessment summary on the DTO instead of a client-side fan-out.
        const isFinalized = a.status === completedStatus
          || ['COMPLETED', 'APPROVED', 'ARCHIVED'].includes(a.status);
        if (!isFinalized) return <span className="text-muted">-</span>;
        const vs = a.vulnerabilitySummary;
        const critical = vs?.critical ?? 0;
        const high = vs?.high ?? 0;
        const medium = vs?.medium ?? 0;
        const low = vs?.low ?? 0;
        if (critical + high + medium + low === 0) return <span className="text-muted">None</span>;
        return (
          <div className="vuln-severity-counts">
            {critical > 0 && <span className="vuln-count vuln-critical">{critical}C</span>}
            {high > 0 && <span className="vuln-count vuln-high">{high}H</span>}
            {medium > 0 && <span className="vuln-count vuln-medium">{medium}M</span>}
            {low > 0 && <span className="vuln-count vuln-low">{low}L</span>}
          </div>
        );
      },
    },
    {
      header: 'Surveys',
      render: (a) => {
        const surveys = surveyMap[a.id] ?? [];
        if (surveys.length === 0) return null;
        const incomplete = surveys.filter(s => s.status !== 'COMPLETE').length;
        const allDone = incomplete === 0;
        return (
          <ActionButtons>
            <IconButton
              icon={ClipboardList}
              onClick={() => { setInitialSurveyId(undefined); setSurveyAssessment(a); }}
              title="Open Surveys"
              variant={allDone ? 'success' : 'warning'}
            />
            <Badge variant={allDone ? 'success' : 'warning'} size="sm">
              {allDone ? 'Complete' : `${incomplete} pending`}
            </Badge>
          </ActionButtons>
        );
      },
    },
    {
      header: 'Report',
      render: (a) =>
        a.generatedReportFileId ? (
          <ActionButtons>
            <IconButton
              icon={Eye}
              onClick={() => setPreviewAssessment(a)}
              title="Preview PenTest Report"
              variant="edit"
            />
          </ActionButtons>
        ) : null,
    },
  ];

  const headerFilters = (
    <div className="ss-filter-bar">
      <MultiSelect
        selected={filterApplicationIds}
        onChange={(vals) => {
          setFilterApplicationIds(vals);
          setTablePage(0);
          // Cache labels so selected apps stay labeled once the search narrows/changes.
          setSelectedAppLabels(prev => {
            const next = { ...prev };
            vals.forEach(id => {
              if (!next[id]) {
                const o = applicationOptions.find(x => x.value === id);
                if (o) next[id] = o.label;
              }
            });
            return next;
          });
        }}
        options={applicationOptions}
        onQueryChange={searchApps}
        loading={appSearchLoading}
        placeholder="All Applications"
      />
      <MultiSelect
        selected={filterStatuses}
        onChange={(vals) => { setFilterStatuses(vals); setTablePage(0); }}
        options={statusOptions.map(s => ({ value: s, label: s }))}
        searchable={false}
        placeholder="All Statuses"
      />
      <label className="app-vulns-show-closed">
        <input
          type="checkbox"
          checked={openSurveysOnly}
          onChange={e => { setOpenSurveysOnly(e.target.checked); setTablePage(0); }}
        />
        Open Surveys
      </label>
    </div>
  );

  return (
    <div className="app-assessments-tab">
      <DataTable
        columns={columns}
        data={allAssessments}
        loading={loading}
        pagination={pagination}
        onPageChange={(page) => setTablePage(page)}
        onPageSizeChange={(size) => { setPageSize(size); setTablePage(0); }}
        onSearchChange={(q) => { setSearch(q); setTablePage(0); }}
        searchPlaceholder="Search assessments"
        emptyMessage="No assessments found"
        idAccessor="id"
        headerChildren={headerFilters}
        sort={sort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole result set, so the current page number
          // is meaningless afterwards — go back to the first page.
          setSort(next);
          setTablePage(0);
        }}
      />

      <ReportPreviewDrawer
        assessment={previewAssessment}
        onClose={() => setPreviewAssessment(null)}
      />

      <SurveyDrawer
        assessment={surveyAssessment}
        initialSurveyId={initialSurveyId}
        onClose={() => {
          setSurveyAssessment(null);
          setInitialSurveyId(undefined);
          // Drop the deep-link params so the drawer doesn't reopen on reload/back
          if (searchParams.has('assessment') || searchParams.has('survey')) {
            const next = new URLSearchParams(searchParams);
            next.delete('assessment');
            next.delete('survey');
            setSearchParams(next, { replace: true });
          }
        }}
      />
    </div>
  );
}
