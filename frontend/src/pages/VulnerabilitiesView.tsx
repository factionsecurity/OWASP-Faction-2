import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Download, Eye, RefreshCw } from 'lucide-react';
import { assessmentsApi, applicationsApi, organizationsApi, vulnerabilitiesApi } from '../api';
import type { Assessment, Vulnerability, VulnerabilityListItem } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam, FilterChip } from '../components/DataTable';
import { Badge, Button, SeverityBadge, IconButton, ActionButtons, FormLabel, Checkbox, Input } from '../components';
import SearchableSelect, { MultiSelect, SelectOption } from '../components/SearchableSelect';
import VulnerabilityDetailDrawer from '../components/VulnerabilityDetailDrawer';
import type { VulnSummaryFilters } from '../components/VulnSummaryPanel';
import { DEFAULT_VULN_STATUSES, vulnStatusBadgeVariant } from '../utils/vulnStatus';
import { usePermissions } from '../utils/permissions';
import { useWorkflowsContext } from '../context/WorkflowsContext';
import { mergedVulnerabilityStatuses, mergedStageNames, stageIdForName } from '../utils/workflowLookup';
import './Applications.css';
import { useTerminology } from '../context/TerminologyContext';
import { usePersistedState } from '../hooks/usePersistedState';

const PAGE_SIZE = 10; // must match a DataTable page-size option (10/25/50/100)
// App/assessment dropdowns default to a starter list; typing server-searches the rest.
const OPTION_LIMIT = 250;
// localStorage key for the saved search, filters, sort and paging. Shared by the /vulnerabilities
// route and the Applications "All Vulnerabilities" tab — both render the same unscoped list.
const TABLE_KEY = 'vulnerabilities';

const formatAssessmentLabel = (a: Assessment): SelectOption => {
  const raw = a.startDate ?? a.createdAt;
  const date = raw
    ? new Date(raw).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
    : null;
  return { value: a.id, label: date ? `${a.name}: ${date}` : a.name };
};

// ── View ───────────────────────────────────────────────────────────────────────
// The shared "all vulnerabilities" view: server-paginated table + trend panel +
// detail drawer + retest bar. Rendered by the standalone /vulnerabilities route
// (wrapped in Page) and embedded in the Applications "All Vulnerabilities" tab.
interface VulnerabilitiesViewProps {
  /** Reports the active filter set upward whenever it changes, so a summary rendered outside
   *  this component (the summary donuts above the standalone page) can narrow to the same rows. */
  onFiltersChange?: (filters: VulnSummaryFilters) => void;
}

export default function VulnerabilitiesView({ onFiltersChange }: VulnerabilitiesViewProps = {}) {
  const { severityOptions, organizationPlural, organizationSingular } = useTerminology();
  const navigate = useNavigate();

  // External users (app owners, org users) reach this page on their read permission but
  // hold no vulnerabilities:edit:* — the API rejects their writes, so the panel must not
  // offer status, field or exception editing in the first place. An editable control that
  // silently fails to save is worse than a read-only one.
  const { permissions: userPerms, isExternal } = usePermissions();
  // External accounts never edit a finding, whatever their role was granted — the API refuses
  // it on the account flag, so the panel must open read-only for them too.
  const canEditVulns = userPerms.canEditVulnerabilities && !isExternal;

  const { workflows } = useWorkflowsContext();
  const [vulns, setVulns] = useState<VulnerabilityListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [pageSize, setPageSize] = usePersistedState(TABLE_KEY, 'pageSize', PAGE_SIZE);
  // Starts true so DataTable doesn't clamp a restored page against the empty pre-load total.
  const [loading, setLoading] = useState(true);
  // Off by default: an archived workflow's statuses still stay filterable on request, but
  // shouldn't clutter the everyday dropdown. Coloring a row still always uses the full
  // `workflows` list — an archived workflow's rows still need their colors resolved.
  const [includeArchivedWorkflows, setIncludeArchivedWorkflows] = usePersistedState(TABLE_KEY, 'includeArchivedWorkflows', false);
  // Built-ins plus every workflow's configured statuses, merged — the built-ins are still
  // built in, they just aren't part of `mergedVulnerabilityStatuses`.
  const configuredStatuses = [
    ...DEFAULT_VULN_STATUSES,
    ...mergedVulnerabilityStatuses(includeArchivedWorkflows ? workflows : workflows.filter(w => !w.archived))
      .filter(s => !DEFAULT_VULN_STATUSES.includes(s)),
  ];

  // Filters
  const [search, setSearch] = usePersistedState(TABLE_KEY, 'search', '');
  const [showClosed, setShowClosed] = usePersistedState(TABLE_KEY, 'showClosed', false);
  const [filterSeverities, setFilterSeverities] = usePersistedState<string[]>(TABLE_KEY, 'severities', []);
  const [filterOrganizationIds, setFilterOrganizationIds] = usePersistedState<string[]>(TABLE_KEY, 'organizationIds', []);
  const [filterApplicationId, setFilterApplicationId] = usePersistedState(TABLE_KEY, 'applicationId', '');
  const [filterAssessmentId, setFilterAssessmentId] = usePersistedState(TABLE_KEY, 'assessmentId', '');
  // Opened-date range (date-only YYYY-MM-DD). Advanced filter — applied set below.
  const [filterOpenedFrom, setFilterOpenedFrom] = usePersistedState(TABLE_KEY, 'openedFrom', '');
  const [filterOpenedTo, setFilterOpenedTo] = usePersistedState(TABLE_KEY, 'openedTo', '');
  // Assessment, Opened range and Show Closed live in the advanced panel — staged here until Apply.
  const [draftAssessmentId, setDraftAssessmentId] = useState('');
  const [draftShowClosed, setDraftShowClosed] = useState(false);
  const [draftOpenedFrom, setDraftOpenedFrom] = useState('');
  const [draftOpenedTo, setDraftOpenedTo] = useState('');
  const [filterStatuses, setFilterStatuses] = usePersistedState<string[]>(TABLE_KEY, 'statuses', []);
  const [tablePage, setTablePage] = usePersistedState(TABLE_KEY, 'page', 0);
  const [sort, setSort] = usePersistedState<SortState | null>(TABLE_KEY, 'sort', null);
  const [exporting, setExporting] = useState(false);

  // Dropdown options (org loaded fully; app/assessment server-searched)
  const [orgOptions, setOrgOptions] = useState<SelectOption[]>([]);
  const [appOptions, setAppOptions] = useState<SelectOption[]>([]);
  const [appLoading, setAppLoading] = useState(false);
  // Labels for selected ids are saved with the filters, so a restored app/assessment shows its
  // name rather than its id when it falls outside the starter option list.
  const [appLabels, setAppLabels] = usePersistedState<Record<string, string>>(TABLE_KEY, 'appLabels', {});
  const [assessmentOptions, setAssessmentOptions] = useState<SelectOption[]>([]);
  const [assessmentLoading, setAssessmentLoading] = useState(false);
  const [assessmentLabels, setAssessmentLabels] = usePersistedState<Record<string, string>>(TABLE_KEY, 'assessmentLabels', {});

  // Retest multi-select + detail drawer. Keyed by id → row so the selection survives paging
  // (the current page's `vulns` no longer holds every selected row once you page away).
  const [selectedVulns, setSelectedVulns] = useState<Map<string, VulnerabilityListItem>>(new Map());
  const [selectedVuln, setSelectedVuln] = useState<Vulnerability | null>(null);
  const [selectedAssessment, setSelectedAssessment] = useState<Assessment | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();

  // ── Data loading ──────────────────────────────────────────────────────────
  const loadPageReq = useRef(0);
  const loadPage = async () => {
    const reqId = ++loadPageReq.current;
    setLoading(true);
    try {
      const res = await vulnerabilitiesApi.searchGlobal({
        page: tablePage,
        size: pageSize,
        search: search || undefined,
        severities: filterSeverities.length ? filterSeverities : undefined,
        organizationIds: filterOrganizationIds.length ? filterOrganizationIds : undefined,
        applicationId: filterApplicationId || undefined,
        assessmentId: filterAssessmentId || undefined,
        statuses: filterStatuses.length ? filterStatuses : undefined,
        includeClosed: showClosed,
        openedFrom: filterOpenedFrom ? `${filterOpenedFrom}T00:00:00` : undefined,
        openedTo: filterOpenedTo ? `${filterOpenedTo}T23:59:59` : undefined,
        sort: sortParam(sort),
      });
      if (reqId !== loadPageReq.current) return; // superseded by a newer load
      setVulns(res.data || []);
      setTotal(res.pagination?.totalElements ?? (res.data || []).length);
    } catch {
      // 403 / network error — fail gracefully to an empty page instead of leaving stale rows
      // and an unhandled rejection. Guarded so a superseded request can't clear a newer one.
      if (reqId === loadPageReq.current) { setVulns([]); setTotal(0); }
    } finally {
      if (reqId === loadPageReq.current) setLoading(false);
    }
  };

  // Mirror the filter set outward. Page/size/sort are deliberately excluded — the summary
  // aggregates the whole filtered set, so paging must not change the numbers above the table.
  useEffect(() => {
    onFiltersChange?.({
      organizationIds: filterOrganizationIds.length ? filterOrganizationIds : undefined,
      applicationId: filterApplicationId || undefined,
      assessmentId: filterAssessmentId || undefined,
      severities: filterSeverities.length ? filterSeverities : undefined,
      statuses: filterStatuses.length ? filterStatuses : undefined,
      search: search || undefined,
      openedFrom: filterOpenedFrom ? `${filterOpenedFrom}T00:00:00` : undefined,
      openedTo: filterOpenedTo ? `${filterOpenedTo}T23:59:59` : undefined,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterOrganizationIds, filterApplicationId, filterAssessmentId, filterSeverities,
      filterStatuses, search, filterOpenedFrom, filterOpenedTo]);

  // Re-fetch the page whenever a filter, page, or size changes.
  useEffect(() => {
    loadPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tablePage, pageSize, search, filterSeverities, filterOrganizationIds, filterApplicationId,
      filterAssessmentId, filterStatuses, showClosed, filterOpenedFrom, filterOpenedTo, sort]);

  // Keep the advanced panel's drafts aligned with the applied values when they change from
  // elsewhere (chip removal, clear-all, or an application/org change that clears the assessment).
  useEffect(() => { setDraftAssessmentId(filterAssessmentId); }, [filterAssessmentId]);
  useEffect(() => { setDraftShowClosed(showClosed); }, [showClosed]);
  useEffect(() => { setDraftOpenedFrom(filterOpenedFrom); }, [filterOpenedFrom]);
  useEffect(() => { setDraftOpenedTo(filterOpenedTo); }, [filterOpenedTo]);

  // One-time: all organizations, default app/assessment option lists. Statuses and stage columns
  // come from WorkflowsContext instead of a one-time fetch.
  useEffect(() => {
    organizationsApi.getAll(0, 1000)
      .then(r => setOrgOptions((r.data || []).map(o => ({ value: o.id, label: o.name }))))
      .catch(() => setOrgOptions([]));

    searchApps('');
    searchAssessments('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const appReq = useRef(0);
  const searchApps = async (query: string) => {
    const reqId = ++appReq.current;
    setAppLoading(true);
    try {
      const res = await applicationsApi.getAll(0, OPTION_LIMIT, query);
      if (reqId !== appReq.current) return;
      setAppOptions((res.data || []).map(a => ({ value: a.id, label: a.name })));
    } catch {
      if (reqId === appReq.current) setAppOptions([]);
    } finally {
      if (reqId === appReq.current) setAppLoading(false);
    }
  };

  const assessmentReq = useRef(0);
  const searchAssessments = async (query: string) => {
    const reqId = ++assessmentReq.current;
    setAssessmentLoading(true);
    try {
      const res = await assessmentsApi.search({
        page: 0, size: OPTION_LIMIT,
        search: query || undefined,
        applicationId: filterApplicationId || undefined, // scope to the selected application
      });
      if (reqId !== assessmentReq.current) return;
      setAssessmentOptions((res.data || []).map(formatAssessmentLabel));
    } catch {
      if (reqId === assessmentReq.current) setAssessmentOptions([]);
    } finally {
      if (reqId === assessmentReq.current) setAssessmentLoading(false);
    }
  };

  // Re-scope the assessment options when the application filter changes.
  useEffect(() => {
    searchAssessments('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterApplicationId]);

  // Merge the selected app/assessment into its option list so its label stays visible
  // once the server search narrows away from it.
  const withSelected = (options: SelectOption[], id: string, labels: Record<string, string>): SelectOption[] => {
    if (!id || options.some(o => o.value === id)) return options;
    const label = labels[id];
    return label ? [{ value: id, label }, ...options] : options;
  };
  const appOptionsMerged = withSelected(appOptions, filterApplicationId, appLabels);
  // The advanced panel's Assessment select shows the *draft* value, so merge that one in.
  const assessmentOptionsForDraft = withSelected(assessmentOptions, draftAssessmentId, assessmentLabels);
  const statusOptions: SelectOption[] = configuredStatuses.map(s => ({ value: s, label: s }));

  // ── Toolbar zones ─────────────────────────────────────────────────────────
  // Assessment, Opened range and Show Closed are the advanced (Apply-based) filters; commit the drafts.
  const applyAdvanced = () => {
    setFilterAssessmentId(draftAssessmentId); setShowClosed(draftShowClosed);
    setFilterOpenedFrom(draftOpenedFrom); setFilterOpenedTo(draftOpenedTo); setTablePage(0);
  };

  // Clear every filter across all zones (the inline dropdowns and the advanced drafts).
  const clearAllFilters = () => {
    setFilterOrganizationIds([]); setFilterSeverities([]); setFilterApplicationId('');
    setFilterAssessmentId(''); setDraftAssessmentId(''); setFilterStatuses([]);
    setShowClosed(false); setDraftShowClosed(false);
    setFilterOpenedFrom(''); setFilterOpenedTo(''); setDraftOpenedFrom(''); setDraftOpenedTo('');
    setTablePage(0);
  };

  // Only the advanced (hidden) filters get chips; the inline dropdowns show their own active state.
  const fmtDay = (d: string) => (d ? new Date(`${d}T00:00:00`).toLocaleDateString() : '…');
  const filterChips: FilterChip[] = [];
  if (filterOpenedFrom || filterOpenedTo) {
    filterChips.push({
      key: 'opened', label: `Opened: ${fmtDay(filterOpenedFrom)} – ${fmtDay(filterOpenedTo)}`,
      onRemove: () => {
        setFilterOpenedFrom(''); setFilterOpenedTo('');
        setDraftOpenedFrom(''); setDraftOpenedTo(''); setTablePage(0);
      },
    });
  }
  if (filterAssessmentId) {
    filterChips.push({
      key: 'assessment',
      label: `Assessment: ${assessmentLabels[filterAssessmentId] ?? filterAssessmentId}`,
      onRemove: () => { setFilterAssessmentId(''); setDraftAssessmentId(''); setTablePage(0); },
    });
  }
  if (showClosed) {
    filterChips.push({
      key: 'showClosed', label: 'Show closed',
      onRemove: () => { setShowClosed(false); setDraftShowClosed(false); setTablePage(0); },
    });
  }

  // ── Detail drawer ───────────────────────────────────────────────────────────
  const handleView = async (row: VulnerabilityListItem) => {
    try {
      const [vRes, aRes] = await Promise.all([
        vulnerabilitiesApi.getById(row.assessmentId, row.id),
        assessmentsApi.getById(row.assessmentId),
      ]);
      if (vRes.data) {
        setSelectedVuln(vRes.data);
        setSelectedAssessment(aRes.data ?? null);
      }
    } catch { /* ignore — drawer just won't open */ }
  };

  const handleVulnUpdate = (updated: Vulnerability) => {
    setSelectedVuln(prev => prev?.id === updated.id ? updated : prev);
    const patch = (v: VulnerabilityListItem): VulnerabilityListItem => ({
      ...v,
      name: updated.name, severity: updated.severity, status: updated.status,
      assetLocation: updated.assetLocation, closedAt: updated.closedAt,
      exceptionNumber: updated.exceptionNumber, exceptionState: updated.exceptionState,
      exceptionApproval: updated.exceptionApproval,
    });
    setVulns(prev => prev.map(v => v.id === updated.id ? patch(v) : v));
    // Keep the cross-page retest selection in sync so a scheduled retest doesn't carry stale row data.
    setSelectedVulns(prev => {
      const existing = prev.get(updated.id);
      if (!existing) return prev;
      const next = new Map(prev);
      next.set(updated.id, patch(existing));
      return next;
    });
  };

  // Auto-open the drawer when a notification deep-links ?vuln=. The link carries only the vuln id
  // (no assessment), so we resolve it server-side by id — which also works for links already sent.
  const autoOpenDoneRef = useRef(false);
  useEffect(() => {
    if (autoOpenDoneRef.current) return;
    const vulnId = searchParams.get('vuln');
    if (!vulnId) return;
    autoOpenDoneRef.current = true;
    vulnerabilitiesApi.getByIdGlobal(vulnId)
      .then(res => { if (res.data) handleView(res.data); })
      .catch(() => { /* out of scope / not found — leave the drawer closed */ });
    setSearchParams(prev => { prev.delete('vuln'); prev.delete('assessment'); prev.delete('comment'); return prev; }, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  // ── Retest scheduling ─────────────────────────────────────────────────────────
  const toggleVulnSelection = (v: VulnerabilityListItem) => {
    setSelectedVulns(prev => {
      const next = new Map(prev);
      if (next.has(v.id)) next.delete(v.id); else next.set(v.id, v);
      return next;
    });
  };

  const handleScheduleRetest = () => {
    const selected = Array.from(selectedVulns.values());
    if (selected.length === 0) return;
    const firstAssessmentId = selected[0].assessmentId;
    const vulnsToSchedule = selected.filter(v => v.assessmentId === firstAssessmentId);
    navigate('/retests/schedule', {
      state: {
        vulnerabilityIds: vulnsToSchedule.map(v => v.id),
        assessmentId: firstAssessmentId,
        vulnerabilities: vulnsToSchedule,
      },
    });
  };

  // ── CSV export ──────────────────────────────────────────────────────────────
  // Exports the whole filtered set, not the current page — the server re-runs the same
  // scoped query unpaginated, so the file matches what the filters describe.
  const handleExportCsv = async () => {
    setExporting(true);
    try {
      const blob = await vulnerabilitiesApi.exportGlobalCsv({
        search: search || undefined,
        severities: filterSeverities.length ? filterSeverities : undefined,
        organizationIds: filterOrganizationIds.length ? filterOrganizationIds : undefined,
        applicationId: filterApplicationId || undefined,
        assessmentId: filterAssessmentId || undefined,
        statuses: filterStatuses.length ? filterStatuses : undefined,
        includeClosed: showClosed,
        openedFrom: filterOpenedFrom ? `${filterOpenedFrom}T00:00:00` : undefined,
        openedTo: filterOpenedTo ? `${filterOpenedTo}T23:59:59` : undefined,
        sort: sortParam(sort),
      });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `vulnerabilities-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch { /* 403 / network error — nothing downloads, the table stays as it was */ }
    finally { setExporting(false); }
  };

  const columns: Column<VulnerabilityListItem>[] = [
    {
      header: '',
      // Selecting a row must never open the vulnerability panel, wherever in the cell you click.
      stopRowClick: true,
      render: (v) => (
        <input
          type="checkbox"
          checked={selectedVulns.has(v.id)}
          onChange={() => toggleVulnSelection(v)}
          style={{ cursor: 'pointer', width: '16px', height: '16px' }}
        />
      ),
    },
    {
      header: 'Name',
      sortKey: 'name',
      render: (v) => (
        <div>
          <div className="font-medium">{v.name}</div>
          {v.assetLocation && <div className="text-sm text-muted">{v.assetLocation}</div>}
        </div>
      ),
    },
    { header: 'Severity', sortKey: 'severity', render: (v) => <SeverityBadge severity={v.severity} /> },
    {
      header: 'Status',
      sortKey: 'status',
      render: (v) => {
        const s = v.status || 'None';
        return <Badge variant={vulnStatusBadgeVariant(s)}>{s}</Badge>;
      },
    },
    {
      header: 'Exception',
      sortKey: 'exceptionState',
      render: (v) => {
        if (!v.exceptionState) return <span>-</span>;
        const variant =
          v.exceptionState === 'Closed'            ? 'secondary' :
          v.exceptionState === 'Pending User Info' ? 'warning'   :
          v.exceptionState === 'Work in Progress'  ? 'info'      : 'success';
        return <Badge variant={variant}>{v.exceptionState}</Badge>;
      },
    },
    { header: 'Application', sortKey: 'applicationName', render: (v) => <span>{v.applicationName || '-'}</span> },
    { header: 'Assessment', sortKey: 'assessmentName', render: (v) => <span>{v.assessmentName || '-'}</span> },
    { header: organizationSingular, sortKey: 'organizationName', render: (v) => <span>{v.organizationName || '-'}</span> },
    { header: 'Opened', sortKey: 'openedAt', render: (v) => v.openedAt ? new Date(v.openedAt).toLocaleDateString() : '-' },
    // One column per non-terminal remediation stage name across every workflow, shown only when a
    // row on this page carries that stage's date (the terminal stage IS the Closed column). Each
    // row resolves the stage id from its OWN assessment's workflow — a column is a name, but
    // `stageCompletions` is keyed by stage id, and different workflows can use different ids for
    // stages of the same name (or have no such stage at all, which renders an empty cell).
    ...mergedStageNames(workflows)
      .filter(stageName => vulns.some(v => {
        const stageId = stageIdForName(workflows, v.workflowId, stageName);
        return stageId && v.stageCompletions?.[stageId];
      }))
      .map((stageName): Column<VulnerabilityListItem> => ({
        header: stageName,
        render: (v) => {
          const stageId = stageIdForName(workflows, v.workflowId, stageName);
          const completion = stageId ? v.stageCompletions?.[stageId] : undefined;
          return completion ? new Date(completion).toLocaleDateString() : '-';
        },
      })),
    { header: 'Closed', sortKey: 'closedAt', render: (v) => v.closedAt ? new Date(v.closedAt).toLocaleDateString() : '-' },
    {
      header: 'Actions',
      // Kept from reaching the row, which opens the same panel, so it isn't fetched twice.
      stopRowClick: true,
      render: (v) => (
        <ActionButtons>
          <IconButton icon={Eye} onClick={() => handleView(v)} title="View Details" variant="edit" />
        </ActionButtons>
      ),
    },
  ];

  const pagination: PaginationInfo = {
    page: tablePage,
    pageSize,
    total,
    totalPages: Math.ceil(total / pageSize),
  };

  const headerFilters = (
    <div className="ss-filter-bar">
      <MultiSelect
        selected={filterOrganizationIds}
        onChange={(vals) => {
          // App/assessment are chosen from within an organization, so a change of selection can
          // leave them pointing outside it — clear rather than filter by something unreachable.
          setFilterOrganizationIds(vals); setFilterApplicationId('');
          setFilterAssessmentId(''); setDraftAssessmentId(''); setTablePage(0);
        }}
        options={orgOptions}
        placeholder={`All ${organizationPlural}`}
      />
      <MultiSelect
        selected={filterSeverities}
        onChange={(vals) => { setFilterSeverities(vals); setTablePage(0); }}
        options={severityOptions}
        searchable={false}
        placeholder="All Severities"
      />
      <SearchableSelect
        value={filterApplicationId}
        onChange={(v) => {
          setFilterApplicationId(v); setFilterAssessmentId(''); setDraftAssessmentId(''); setTablePage(0);
          if (v) { const o = appOptionsMerged.find(x => x.value === v); if (o) setAppLabels(p => ({ ...p, [v]: o.label })); }
        }}
        options={appOptionsMerged}
        onQueryChange={searchApps}
        loading={appLoading}
        placeholder="All Applications"
      />
      <MultiSelect
        selected={filterStatuses}
        onChange={(vals) => { setFilterStatuses(vals); setTablePage(0); }}
        options={statusOptions}
        searchable={false}
        placeholder="All Statuses"
      />
      <label className="vulns-include-archived-workflows">
        <input
          type="checkbox"
          checked={includeArchivedWorkflows}
          onChange={(e) => setIncludeArchivedWorkflows(e.target.checked)}
        />
        Include archived workflows
      </label>
      <Button
        variant="secondary"
        icon={Download}
        onClick={handleExportCsv}
        disabled={exporting}
      >
        {exporting ? 'Exporting…' : 'Export CSV'}
      </Button>
    </div>
  );

  return (
    <div className="app-vulns-tab">
      <DataTable
        columns={columns}
        data={vulns}
        loading={loading}
        pagination={pagination}
        onPageChange={(p) => setTablePage(p)}
        onPageSizeChange={(size) => { setPageSize(size); setTablePage(0); }}
        initialSearch={search}
        onSearchChange={(q) => { setSearch(q); setTablePage(0); }}
        searchPlaceholder="Search vulnerabilities"
        emptyMessage={showClosed ? 'No vulnerabilities found' : 'No open vulnerabilities found'}
        idAccessor="id"
        // A row click opens the vulnerability panel; the checkbox and Actions cells opt out.
        onRowClick={handleView}
        headerChildren={headerFilters}
        advancedActiveCount={filterChips.length}
        filterChips={filterChips}
        onApplyAdvanced={applyAdvanced}
        onClearFilters={clearAllFilters}
        advancedFilters={
          <>
            <div className="filter-field">
              <FormLabel>Opened Date</FormLabel>
              <div className="filter-field-range">
                <Input type="date" value={draftOpenedFrom}
                  onChange={(e) => setDraftOpenedFrom(e.target.value)} />
                <Input type="date" value={draftOpenedTo}
                  onChange={(e) => setDraftOpenedTo(e.target.value)} />
              </div>
            </div>
            <div className="filter-field">
              <FormLabel>Assessment</FormLabel>
              <SearchableSelect
                value={draftAssessmentId}
                onChange={(v) => {
                  setDraftAssessmentId(v);
                  if (v) { const o = assessmentOptionsForDraft.find(x => x.value === v); if (o) setAssessmentLabels(p => ({ ...p, [v]: o.label })); }
                }}
                options={assessmentOptionsForDraft}
                onQueryChange={searchAssessments}
                loading={assessmentLoading}
                placeholder="All Assessments"
              />
            </div>
            <div className="filter-field">
              <FormLabel>Options</FormLabel>
              <div className="filter-field-checks">
                <Checkbox
                  id="showClosed"
                  checked={draftShowClosed}
                  onChange={(e) => setDraftShowClosed(e.target.checked)}
                  label="Show Closed"
                />
              </div>
            </div>
          </>
        }
        sort={sort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole result set, so the current page number
          // is meaningless afterwards — go back to the first page.
          setSort(next);
          setTablePage(0);
        }}
      />

      <VulnerabilityDetailDrawer
        vulnerability={selectedVuln}
        assessment={selectedAssessment}
        onClose={() => { setSelectedVuln(null); setSelectedAssessment(null); }}
        allowStatusEdit={canEditVulns}
        allowFieldEdit={canEditVulns}
        showException={true}
        onVulnUpdate={handleVulnUpdate}
        onScheduleRetest={() => {
          if (selectedVuln) {
            navigate('/retests/schedule', {
              state: {
                vulnerabilityIds: [selectedVuln.id],
                assessmentId: selectedVuln.assessmentId,
                vulnerabilities: [selectedVuln],
              },
            });
          }
        }}
      />

      {selectedVulns.size > 0 && (
        <div style={{
          position: 'fixed', bottom: '2rem', left: '50%', transform: 'translateX(-50%)',
          background: 'var(--card-bg)', border: '1px solid var(--border-color)', borderRadius: '8px',
          padding: '0.75rem 1.25rem', display: 'flex', alignItems: 'center', gap: '1rem',
          boxShadow: '0 4px 20px rgba(0,0,0,0.25)', zIndex: 100,
        }}>
          <span style={{ fontSize: '0.875rem', color: 'var(--text-muted)' }}>
            {selectedVulns.size} selected
          </span>
          <button
            onClick={handleScheduleRetest}
            style={{
              display: 'flex', alignItems: 'center', gap: '0.4rem', padding: '0.45rem 0.875rem',
              borderRadius: '6px', border: 'none', background: 'var(--primary-color, #3b82f6)',
              color: '#fff', fontSize: '0.875rem', fontWeight: 500, cursor: 'pointer',
            }}
          >
            <RefreshCw size={14} />
            Schedule Retest
          </button>
          <button
            onClick={() => setSelectedVulns(new Map())}
            style={{
              padding: '0.45rem 0.875rem', borderRadius: '6px', border: '1px solid var(--border-color)',
              background: 'transparent', color: 'var(--text-muted)', fontSize: '0.875rem', cursor: 'pointer',
            }}
          >
            Clear
          </button>
        </div>
      )}
    </div>
  );
}
