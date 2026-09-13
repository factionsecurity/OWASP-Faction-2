import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CalendarRange, Download, Eye, Pencil, Trash2, XCircle, AlertTriangle } from 'lucide-react';
import GliderIcon from '../components/icons/GliderIcon';
import {
  vulnerabilitiesApi, retestApi, assessmentsApi, applicationsApi, organizationsApi,
  workflowConfigApi, remediationApi,
} from '../api';
import type { Assessment, Vulnerability, RemediationQueueRow, RemediationQueueSummary } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam, FilterChip } from '../components/DataTable';
import { Badge, FormLabel, Checkbox } from '../components';
import { MultiSelect, SelectOption } from '../components/SearchableSelect';
import ConfirmDialog from '../components/ConfirmDialog';
import Modal from '../components/Modal';
import { Button } from '../components/Button';
import VulnerabilityDetailDrawer from '../components/VulnerabilityDetailDrawer';
import { DEFAULT_VULN_STATUSES, vulnStatusBadgeVariant } from '../utils/vulnStatus';
import { usePermissions } from '../utils/permissions';
import RichTextEditor from '../components/RichTextEditor';
import Page from '../components/Page';
import './RemediationPage.css';
import { useTerminology } from '../context/TerminologyContext';
import { usePersistedState } from '../hooks/usePersistedState';

// 10 matches the app-wide default and is one of DataTable's page-size options.
const PAGE_SIZE = 10;
// App/assessment dropdowns default to a starter list; typing server-searches the rest
// (same contract as the vulnerabilities list's header filters).
const OPTION_LIMIT = 250;
/**
 * The queue's two row kinds, each with its own page: Vuln Alerts and Retest Alerts. A page is locked
 * to one kind, so the server's `type` filter is always set and the table never mixes the two.
 */
export type RemediationAlertKind = 'VULNERABILITY' | 'RETEST';

// localStorage key per kind, so each page keeps its own saved search, filters, sort and paging.
const TABLE_KEYS: Record<RemediationAlertKind, string> = {
  VULNERABILITY: 'remediation.vulnerabilities',
  RETEST: 'remediation.retests',
};

// Stat badges above the table, in display order. Each (except Total) toggles a server-side bucket;
// `count` picks the matching figure out of the summary response.
const EMPTY_SUMMARY: RemediationQueueSummary = {
  total: 0, pastDue: 0, dueSoon: 0, retestRequested: 0, retestScheduled: 0, retestInProgress: 0,
};
type BucketBadge = { bucket: string; label: string; color: string; count: keyof RemediationQueueSummary };
const BUCKET_BADGES: Record<RemediationAlertKind, BucketBadge[]> = {
  VULNERABILITY: [
    { bucket: 'PAST_DUE', label: 'Past Due', color: '#ef4444', count: 'pastDue' },
    { bucket: 'DUE_SOON', label: 'Due Soon', color: '#f59e0b', count: 'dueSoon' },
  ],
  RETEST: [
    { bucket: 'RETEST_REQUESTED', label: 'Requested', color: '#8b5cf6', count: 'retestRequested' },
    { bucket: 'RETEST_SCHEDULED', label: 'Scheduled', color: '#3b82f6', count: 'retestScheduled' },
    { bucket: 'RETEST_IN_PROGRESS', label: 'In Progress', color: '#0ea5e9', count: 'retestInProgress' },
  ],
};

// The Retest Status column on Retest Alerts. Passed and Failed only appear with completed retests shown.
const RETEST_STATUS_BADGES: Record<string, { label: string; variant: 'primary' | 'info' | 'success' | 'danger' }> = {
  REQUESTED: { label: 'Requested', variant: 'primary' },
  SCHEDULED: { label: 'Scheduled', variant: 'info' },
  IN_PROGRESS: { label: 'In Progress', variant: 'info' },
  PASSED: { label: 'Passed', variant: 'success' },
  FAILED: { label: 'Failed', variant: 'danger' },
};

/** A column that only belongs on one kind's page; columns without `kind` show on both. */
type AlertColumn = Column<RemediationQueueRow> & { kind?: RemediationAlertKind };

const formatAssessmentLabel = (a: Assessment): SelectOption => {
  const raw = a.startDate ?? a.createdAt;
  const date = raw
    ? new Date(raw).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
    : null;
  return { value: a.id, label: date ? `${a.name}: ${date}` : a.name };
};

export default function RemediationPage({ kind }: { kind: RemediationAlertKind }) {
  // Both alert routes render this page in the same spot, so React would otherwise keep one kind's
  // state (restored from its own saved filters) when you switch to the other. The key remounts it.
  return <RemediationAlerts key={kind} kind={kind} />;
}

function RemediationAlerts({ kind }: { kind: RemediationAlertKind }) {
  const isRetest = kind === 'RETEST';
  const tableKey = TABLE_KEYS[kind];
  const { severityOptions, organizationPlural, organizationSingular } = useTerminology();
  const navigate = useNavigate();

  // The queue is reachable on a retest permission alone, which external users hold. Editing
  // a finding needs vulnerabilities:edit:* — without it the API rejects the write, so the
  // panel opens read-only rather than offering controls that silently do nothing.
  const { permissions: userPerms, isExternal } = usePermissions();
  // External accounts never edit a finding, whatever their role was granted — the API refuses
  // it on the account flag, so the panel must open read-only for them too.
  const canEditVulns = userPerms.canEditVulnerabilities && !isExternal;
  // App owners and org users reach this queue to watch their own findings and manage their own
  // retest requests. Scheduling and editing are staff actions, and the retest detail page is
  // behind a permission they do not have — so for them a retest row opens the finding it is
  // against. Cancelling stays: whoever may ask for a retest may call it off.
  const requestOnly = userPerms.canRequestRetestOnly || isExternal;

  const [rows, setRows] = useState<RemediationQueueRow[]>([]);
  // Starts true so DataTable doesn't clamp a restored page against the empty pre-load total.
  const [loading, setLoading] = useState(true);

  const [search, setSearch] = usePersistedState(tableKey, 'search', '');
  const [page, setPage] = usePersistedState(tableKey, 'page', 0);
  const [sort, setSort] = usePersistedState<SortState | null>(tableKey, 'sort', null);
  const [pageSize, setPageSize] = usePersistedState(tableKey, 'pageSize', PAGE_SIZE);
  const [total, setTotal] = useState(0);

  // Header filters — the same set the vulnerabilities list offers, minus "show closed" (a closed
  // vulnerability is never a queue row on its own). Statuses filter on the vulnerability's status,
  // which is what the Status column shows for both row types.
  // Every filter is multi-select: several values in one filter match any of them, and separate
  // filters must all match.
  const [filterSeverities, setFilterSeverities] = usePersistedState<string[]>(tableKey, 'severities', []);
  const [filterOrganizationIds, setFilterOrganizationIds] = usePersistedState<string[]>(tableKey, 'organizationIds', []);
  const [filterApplicationIds, setFilterApplicationIds] = usePersistedState<string[]>(tableKey, 'applicationIds', []);
  const [filterAssessmentIds, setFilterAssessmentIds] = usePersistedState<string[]>(tableKey, 'assessmentIds', []);
  const [filterStatuses, setFilterStatuses] = usePersistedState<string[]>(tableKey, 'statuses', []);
  const [exporting, setExporting] = useState(false);
  // The queue is a worklist, so verified retests are off by default; on, it becomes a record of
  // what has been checked as well as what is outstanding.
  const [showCompletedRetests, setShowCompletedRetests] = usePersistedState(tableKey, 'showCompletedRetests', false);
  // Assessment and (on Retest Alerts) Show Completed Retests live in the advanced panel — staged until Apply.
  const [draftAssessmentIds, setDraftAssessmentIds] = useState<string[]>([]);
  const [draftShowCompletedRetests, setDraftShowCompletedRetests] = useState(false);
  // Stat-badge selection: empty means "Total" (no bucket narrowing); several may be active at once.
  const [buckets, setBuckets] = usePersistedState<string[]>(tableKey, 'buckets', []);
  const [summary, setSummary] = useState<RemediationQueueSummary>(EMPTY_SUMMARY);

  // Dropdown options (orgs loaded fully; apps/assessments server-searched)
  const [orgOptions, setOrgOptions] = useState<SelectOption[]>([]);
  const [appOptions, setAppOptions] = useState<SelectOption[]>([]);
  const [appLoading, setAppLoading] = useState(false);
  // Labels for selected ids are saved with the filters, so a restored app/assessment shows its
  // name rather than its id when it falls outside the starter option list.
  const [appLabels, setAppLabels] = usePersistedState<Record<string, string>>(tableKey, 'appLabels', {});
  const [assessmentOptions, setAssessmentOptions] = useState<SelectOption[]>([]);
  const [assessmentLoading, setAssessmentLoading] = useState(false);
  const [assessmentLabels, setAssessmentLabels] = usePersistedState<Record<string, string>>(tableKey, 'assessmentLabels', {});

  // Vulnerability detail drawer (full vuln + assessment fetched on demand)
  const [selectedVuln, setSelectedVuln] = useState<Vulnerability | null>(null);
  const [selectedAssessment, setSelectedAssessment] = useState<Assessment | null>(null);
  // Seeded with the built-ins so the status filter is populated before the config request lands.
  const [configuredStatuses, setConfiguredStatuses] = useState<string[]>(DEFAULT_VULN_STATUSES);

  // Cancel retest dialog
  const [cancelRetestRow, setCancelRetestRow] = useState<RemediationQueueRow | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState('');

  // Delete vulnerability dialogs (two-step)
  const [deleteVulnRow, setDeleteVulnRow] = useState<RemediationQueueRow | null>(null);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleting, setDeleting] = useState(false);

  // ── Data loading (server-paginated + server-searched) ──────────────────────
  const loadPageReq = useRef(0);
  const loadPage = useCallback(async () => {
    const reqId = ++loadPageReq.current;
    setLoading(true);
    try {
      const res = await remediationApi.getQueue({
        page, size: pageSize,
        sort: sortParam(sort),
        search: search || undefined,
        severities: filterSeverities.length ? filterSeverities : undefined,
        organizationIds: filterOrganizationIds.length ? filterOrganizationIds : undefined,
        applicationIds: filterApplicationIds.length ? filterApplicationIds : undefined,
        assessmentIds: filterAssessmentIds.length ? filterAssessmentIds : undefined,
        statuses: filterStatuses.length ? filterStatuses : undefined,
        type: kind,
        includeCompletedRetests: showCompletedRetests || undefined,
        buckets: buckets.length ? buckets : undefined,
      });
      if (reqId !== loadPageReq.current) return; // superseded by a newer load
      const data = res.data || [];
      // If a delete/cancel emptied the last page, step back a page rather than show an empty slice.
      // (Search/filter/size changes reset to page 0, so this only fires after a mutation.)
      if (data.length === 0 && page > 0) { setPage(p => Math.max(0, p - 1)); return; }
      setRows(data);
      setTotal(res.pagination?.totalElements ?? data.length);
    } catch {
      // 403 / network error — fail gracefully to an empty page rather than leaving stale rows.
      if (reqId === loadPageReq.current) { setRows([]); setTotal(0); }
    } finally {
      if (reqId === loadPageReq.current) setLoading(false);
    }
  }, [page, pageSize, search, filterSeverities, filterOrganizationIds, filterApplicationIds,
      filterAssessmentIds, filterStatuses, kind, showCompletedRetests, buckets, sort]);

  useEffect(() => { loadPage(); }, [loadPage]);

  // Badge counts follow the header filters but deliberately not the bucket selection or the
  // completed-retests toggle — otherwise selecting a badge would zero out all the others.
  const loadSummaryReq = useRef(0);
  const loadSummary = useCallback(async () => {
    const reqId = ++loadSummaryReq.current;
    try {
      const res = await remediationApi.summary({
        search: search || undefined,
        severities: filterSeverities.length ? filterSeverities : undefined,
        organizationIds: filterOrganizationIds.length ? filterOrganizationIds : undefined,
        applicationIds: filterApplicationIds.length ? filterApplicationIds : undefined,
        assessmentIds: filterAssessmentIds.length ? filterAssessmentIds : undefined,
        statuses: filterStatuses.length ? filterStatuses : undefined,
        type: kind,
      });
      if (reqId === loadSummaryReq.current && res.data) setSummary(res.data);
    } catch {
      // Keep the previous counts — the badges are informational and must not break the page.
    }
  }, [search, filterSeverities, filterOrganizationIds, filterApplicationIds, filterAssessmentIds,
      filterStatuses, kind]);

  useEffect(() => { loadSummary(); }, [loadSummary]);

  // Total clears the selection; any other badge toggles its bucket. Either way, back to page 0.
  const handleBadgeClick = (bucket: string | null) => {
    if (bucket === null) setBuckets([]);
    else setBuckets(prev => prev.includes(bucket) ? prev.filter(b => b !== bucket) : [...prev, bucket]);
    setPage(0);
  };

  // Keep the advanced panel's drafts aligned with the applied values when they change from
  // elsewhere (chip removal, clear-all, or an application/org change that clears the assessment).
  useEffect(() => { setDraftAssessmentIds(filterAssessmentIds); }, [filterAssessmentIds]);
  useEffect(() => { setDraftShowCompletedRetests(showCompletedRetests); }, [showCompletedRetests]);

  // One-time: vulnerability status labels for the detail drawer, plus the organization options.
  useEffect(() => {
    workflowConfigApi.getConfig().then(res => {
      const custom = res.success && res.data ? (res.data.vulnerabilityStatuses || []) : [];
      setConfiguredStatuses([...DEFAULT_VULN_STATUSES, ...custom.filter(s => !DEFAULT_VULN_STATUSES.includes(s))]);
    }).catch(() => setConfiguredStatuses(DEFAULT_VULN_STATUSES));

    organizationsApi.getAll(0, 1000)
      .then(r => setOrgOptions((r.data || []).map(o => ({ value: o.id, label: o.name }))))
      .catch(() => setOrgOptions([]));

    searchApps('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Filter option lists (server-searched, latest-request-wins) ─────────────
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
        applicationIds: filterApplicationIds.length ? filterApplicationIds : undefined, // scope to the selected applications
      });
      if (reqId !== assessmentReq.current) return;
      setAssessmentOptions((res.data || []).map(formatAssessmentLabel));
    } catch {
      if (reqId === assessmentReq.current) setAssessmentOptions([]);
    } finally {
      if (reqId === assessmentReq.current) setAssessmentLoading(false);
    }
  };

  // Load the assessment options, and re-scope them whenever the application filter changes.
  useEffect(() => {
    searchAssessments('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterApplicationIds]);

  // Merge the selected apps/assessments into their option list so their labels stay visible
  // once a server search narrows away from them.
  const withSelected = (options: SelectOption[], ids: string[], labels: Record<string, string>): SelectOption[] => {
    const missing = ids.filter(id => !options.some(o => o.value === id))
      .map(id => ({ value: id, label: labels[id] ?? id }));
    return missing.length ? [...missing, ...options] : options;
  };
  // Remember the name behind each picked id, so a restored selection shows names rather than ids.
  const labelsFor = (prev: Record<string, string>, ids: string[], options: SelectOption[]) => {
    const next = { ...prev };
    ids.forEach(id => { const o = options.find(x => x.value === id); if (o) next[id] = o.label; });
    return next;
  };
  const appOptionsMerged = withSelected(appOptions, filterApplicationIds, appLabels);
  // The advanced panel's Assessment select shows the *draft* values, so merge those in.
  const assessmentOptionsForDraft = withSelected(assessmentOptions, draftAssessmentIds, assessmentLabels);
  // The drawer's status list doubles as the filter's — every status a queue row can carry.
  const statusOptions: SelectOption[] = configuredStatuses.map(s => ({ value: s, label: s }));

  // ── Row actions ────────────────────────────────────────────────────────────

  // The queue row is a summary; fetch the full vuln + assessment to open the edit drawer.
  const openVulnDrawer = async (r: RemediationQueueRow) => {
    if (!r.assessmentId) return;
    try {
      const [vulnRes, asmtRes] = await Promise.all([
        vulnerabilitiesApi.getById(r.assessmentId, r.vulnerabilityId),
        assessmentsApi.getById(r.assessmentId),
      ]);
      if (vulnRes.data && asmtRes.data) {
        setSelectedVuln(vulnRes.data);
        setSelectedAssessment(asmtRes.data);
      }
    } catch {
      // vuln/assessment no longer readable — leave the drawer closed.
    }
  };

  // Schedule (requested) or edit (scheduled/in-progress) a retest — the schedule page needs the
  // full retest, which the summary row doesn't carry, so fetch it on click.
  const openRetestSchedule = async (r: RemediationQueueRow) => {
    try {
      const res = await retestApi.getById(r.id);
      const retest = res.data;
      if (!retest) { loadPage(); loadSummary(); return; }
      navigate('/retests/schedule', {
        state: {
          retestId: retest.id,
          existingRetest: retest,
          assessmentId: retest.assessmentId,
          vulnerabilityIds: [retest.vulnerabilityId],
          vulnerabilities: [{
            id: retest.vulnerabilityId,
            name: retest.vulnerabilityName || retest.vulnerabilityId,
            severity: retest.vulnerabilitySeverity,
          }],
        },
      });
    } catch {
      // retest gone — refresh so it drops off the queue.
      loadPage();
      loadSummary();
    }
  };

  const handleVulnUpdate = (updated: Vulnerability) => {
    setSelectedVuln(prev => prev?.id === updated.id ? updated : prev);
    loadPage();
    loadSummary();
  };

  // Cancel retest: add the reason as a comment on the vulnerability, then move the retest to
  // CANCELLED. It leaves the queue (which shows open retests) but stays on the finding's record.
  const handleCancelRetest = async () => {
    if (!cancelRetestRow) return;
    const reasonText = cancelReason.replace(/<[^>]*>/g, '').trim();
    if (!reasonText) {
      setCancelError('A reason is required to cancel a retest.');
      return;
    }
    setCancelling(true);
    setCancelError('');
    try {
      const { id, vulnerabilityId, assessmentId } = cancelRetestRow;
      if (vulnerabilityId && assessmentId) {
        const commentText = `<p><strong>Retest Cancelled</strong></p>${cancelReason}`;
        await vulnerabilitiesApi.addComment(assessmentId, vulnerabilityId, commentText);
      }
      await retestApi.cancel(id);
      setCancelRetestRow(null);
      setCancelReason('');
      loadPage();
      loadSummary();
    } catch {
      setCancelError('Failed to cancel retest. Please try again.');
    } finally {
      setCancelling(false);
    }
  };

  // Delete vulnerability (final step)
  const handleDeleteVuln = async () => {
    if (!deleteVulnRow?.assessmentId) return;
    setDeleting(true);
    try {
      await vulnerabilitiesApi.delete(deleteVulnRow.assessmentId, deleteVulnRow.vulnerabilityId);
      setShowDeleteConfirm(false);
      setDeleteVulnRow(null);
      loadPage();
      loadSummary();
    } catch {
      // error handled silently; could surface via toast
    } finally {
      setDeleting(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const pagination: PaginationInfo = { page, pageSize, total, totalPages };

  const handleSearchChange = useCallback((s: string) => { setSearch(s); setPage(0); }, []);
  const handlePageSizeChange = useCallback((ps: number) => { setPageSize(ps); setPage(0); }, []);

  const fmt = (d?: string) => {
    if (!d) return '—';
    // Date-only values (yyyy-MM-dd) parse as UTC midnight; pin to local so the day doesn't shift.
    const iso = d.length === 10 ? `${d}T00:00:00` : d;
    return new Date(iso).toLocaleDateString();
  };

  // A retest's name is its underlying vuln's, which the server leaves null if that vuln is gone;
  // fall back to the id so the row is never blank.
  const displayName = (r: RemediationQueueRow) => r.vulnerabilityName || r.vulnerabilityId;

  /** URGENT on the name: past its SLA, or a retest someone has asked for and nobody has scheduled yet. */
  const isUrgent = (r: RemediationQueueRow) =>
    r.urgent || (r.type === 'RETEST' && r.retestStatus === 'REQUESTED');

  /** A retest row that has already been verified — only shown when "Show Completed Retests" is
   *  on, and read-only: rescheduling or cancelling a finished retest is not a move. */
  const isCompletedRetest = (r: RemediationQueueRow) =>
    r.type === 'RETEST' && (r.retestStatus === 'PASSED' || r.retestStatus === 'FAILED');

  // ── CSV export ──────────────────────────────────────────────────────────────
  // Exports the whole filtered queue, not the current page — the server re-runs the same scoped
  // query unpaginated. Retest rows also carry their completed date / result / verifier, so
  // ticking "Show Completed Retests" first gives a file you can pivot by completion month.
  const handleExportCsv = async () => {
    setExporting(true);
    try {
      const blob = await remediationApi.exportQueueCsv({
        search: search || undefined,
        severities: filterSeverities.length ? filterSeverities : undefined,
        organizationIds: filterOrganizationIds.length ? filterOrganizationIds : undefined,
        applicationIds: filterApplicationIds.length ? filterApplicationIds : undefined,
        assessmentIds: filterAssessmentIds.length ? filterAssessmentIds : undefined,
        statuses: filterStatuses.length ? filterStatuses : undefined,
        type: kind,
        includeCompletedRetests: showCompletedRetests || undefined,
        buckets: buckets.length ? buckets : undefined,
        sort: sortParam(sort),
      });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${isRetest ? 'retest' : 'vulnerability'}-alerts-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch { /* 403 / network error — nothing downloads, the table stays as it was */ }
    finally { setExporting(false); }
  };

  const allColumns: AlertColumn[] = [
    {
      header: 'Vulnerability Name',
      sortKey: 'name',
      render: r => (
        <span className="remediation-vuln-name">
          <span style={{ fontWeight: 500 }}>{displayName(r)}</span>
          {isUrgent(r) && <span className="remediation-badge remediation-badge--urgent">URGENT</span>}
          {r.warning && !isUrgent(r) && <span className="remediation-badge remediation-badge--warning">WARNING</span>}
        </span>
      ),
    },
    {
      header: 'Application',
      sortKey: 'applicationName',
      render: r => r.applicationName || '—',
    },
    {
      header: organizationSingular,
      sortKey: 'organizationName',
      render: r => r.organizationName || '—',
    },
    {
      // The retest's own progress. No sort key: the queue query has no retest-status ordering.
      kind: 'RETEST',
      header: 'Retest Status',
      render: r => {
        const s = RETEST_STATUS_BADGES[r.retestStatus ?? ''];
        return s ? <Badge variant={s.variant}>{s.label}</Badge> : (r.retestStatus || '—');
      },
    },
    {
      // Always the underlying vulnerability's status — on a retest row too — so it reads the same
      // as the Vulnerabilities table (same labels, same badge colors).
      header: isRetest ? 'Vulnerability Status' : 'Status',
      sortKey: 'vulnerabilityStatus',
      render: r => {
        const s = r.vulnerabilityStatus || 'None';
        return <Badge variant={vulnStatusBadgeVariant(s)}>{s}</Badge>;
      },
    },
    {
      kind: 'VULNERABILITY',
      header: 'Last Retest',
      sortKey: 'lastRetestStatus',
      render: r => {
        if (!r.lastRetestStatus) return '—';
        return (
          <Badge variant={r.lastRetestStatus === 'PASSED' ? 'success' : 'danger'}>
            {r.lastRetestStatus === 'PASSED' ? 'Passed' : 'Failed'}
          </Badge>
        );
      },
    },
    {
      kind: 'VULNERABILITY',
      header: 'Last Retest Date',
      sortKey: 'lastRetestDate',
      render: r => (r.lastRetestDate ? fmt(r.lastRetestDate) : '—'),
    },
    {
      kind: 'RETEST',
      header: 'Start Date',
      sortKey: 'startDate',
      render: r => fmt(r.startDate),
    },
    {
      kind: 'RETEST',
      header: 'End Date',
      sortKey: 'endDate',
      render: r => fmt(r.endDate),
    },
    {
      header: 'Due Date',
      sortKey: 'dueDate',
      render: r => (
        <span style={{
          color: r.urgent ? '#ef4444' : r.warning ? '#f97316' : undefined,
          fontWeight: r.urgent ? 600 : undefined,
        }}>
          {fmt(r.dueDate)}
        </span>
      ),
    },
    {
      header: 'Actions',
      render: r => (
        <div className="remediation-actions" onClick={e => e.stopPropagation()}>
          <button
            type="button"
            className="remediation-action-btn"
            title="View"
            onClick={() => {
              if (r.type === 'VULNERABILITY' || requestOnly) {
                openVulnDrawer(r);
              } else {
                navigate(`/retests/${r.id}`);
              }
            }}
          >
            <Eye size={15} />
          </button>
          {!isCompletedRetest(r) && !(requestOnly && r.type === 'RETEST') && (
          <button
            type="button"
            className="remediation-action-btn"
            title={r.type === 'VULNERABILITY' ? 'Open Assessment'
              : r.retestStatus === 'REQUESTED' ? 'Schedule Retest' : 'Edit'}
            onClick={() => {
              if (r.type === 'VULNERABILITY') {
                if (r.assessmentId) navigate(`/assessments/${r.assessmentId}`);
              } else {
                openRetestSchedule(r);
              }
            }}
          >
            {r.type === 'VULNERABILITY' ? <GliderIcon size={15} />
              : r.retestStatus === 'REQUESTED' ? <CalendarRange size={15} /> : <Pencil size={15} />}
          </button>
          )}
          {!isCompletedRetest(r) && !(requestOnly && r.type === 'VULNERABILITY') && (
          <button
            type="button"
            className="remediation-action-btn remediation-action-btn--danger"
            title={r.type === 'RETEST' ? 'Cancel Retest' : 'Delete Vulnerability'}
            onClick={() => {
              if (r.type === 'RETEST') {
                setCancelRetestRow(r);
                setCancelReason('');
                setCancelError('');
              } else {
                setDeleteVulnRow(r);
                setShowDeleteConfirm(false);
              }
            }}
          >
            {r.type === 'RETEST' ? <XCircle size={15} /> : <Trash2 size={15} />}
          </button>
          )}
        </div>
      ),
    },
  ];
  const columns: Column<RemediationQueueRow>[] = allColumns.filter(c => !c.kind || c.kind === kind);

  const anyFilterActive = !!(search || filterSeverities.length || filterOrganizationIds.length
    || filterApplicationIds.length || filterAssessmentIds.length || filterStatuses.length
    || showCompletedRetests || buckets.length > 0);

  // ── Toolbar zones ─────────────────────────────────────────────────────────
  // Assessment and (on Retest Alerts) Show Completed Retests are the advanced (Apply-based) filters.
  const applyAdvanced = () => {
    setFilterAssessmentIds(draftAssessmentIds);
    setShowCompletedRetests(draftShowCompletedRetests);
    setPage(0);
  };

  // Clear every filter across all zones (inline dropdowns + advanced drafts).
  const clearAllFilters = () => {
    setFilterOrganizationIds([]); setFilterSeverities([]); setFilterApplicationIds([]);
    setFilterStatuses([]);
    setFilterAssessmentIds([]); setDraftAssessmentIds([]);
    setShowCompletedRetests(false); setDraftShowCompletedRetests(false);
    setBuckets([]);
    setPage(0);
  };

  // Only the advanced (hidden) filters get chips; the inline dropdowns show their own active state.
  const filterChips: FilterChip[] = [];
  // One chip per chosen assessment, so each can be removed on its own.
  filterAssessmentIds.forEach(id => {
    filterChips.push({
      key: `assessment-${id}`,
      label: `Assessment: ${assessmentLabels[id] ?? id}`,
      onRemove: () => {
        const next = filterAssessmentIds.filter(x => x !== id);
        setFilterAssessmentIds(next); setDraftAssessmentIds(next); setPage(0);
      },
    });
  });
  if (showCompletedRetests) {
    filterChips.push({
      key: 'showCompletedRetests', label: 'Show completed retests',
      onRemove: () => { setShowCompletedRetests(false); setDraftShowCompletedRetests(false); setPage(0); },
    });
  }

  const headerFilters = (
    <div className="ss-filter-bar">
      {/* Picking more values never clears another filter: with several choices allowed, wiping the
          applications on every organization tick would throw away selections. */}
      <MultiSelect
        selected={filterOrganizationIds}
        onChange={(vals) => { setFilterOrganizationIds(vals); setPage(0); }}
        options={orgOptions}
        placeholder={`All ${organizationPlural}`}
      />
      <MultiSelect
        selected={filterSeverities}
        onChange={(vals) => { setFilterSeverities(vals); setPage(0); }}
        options={severityOptions}
        searchable={false}
        placeholder="All Severities"
      />
      <MultiSelect
        selected={filterApplicationIds}
        onChange={(vals) => {
          setFilterApplicationIds(vals); setPage(0);
          setAppLabels(p => labelsFor(p, vals, appOptionsMerged));
        }}
        options={appOptionsMerged}
        onQueryChange={searchApps}
        loading={appLoading}
        placeholder="All Applications"
      />
      <MultiSelect
        selected={filterStatuses}
        onChange={(vals) => { setFilterStatuses(vals); setPage(0); }}
        options={statusOptions}
        searchable={false}
        placeholder="All Statuses"
      />
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
    <Page className="remediation-page">
      <div className="rq-stats-bar">
        <button
          type="button"
          className={`rq-stat${buckets.length === 0 ? ' active' : ''}`}
          onClick={() => handleBadgeClick(null)}
        >
          <span className="rq-stat-dot" style={{ background: '#94a3b8' }} />
          Total <strong>{summary.total}</strong>
        </button>
        {BUCKET_BADGES[kind].map(b => (
          <button
            key={b.bucket}
            type="button"
            className={`rq-stat${buckets.includes(b.bucket) ? ' active' : ''}`}
            onClick={() => handleBadgeClick(b.bucket)}
          >
            <span className="rq-stat-dot" style={{ background: b.color }} />
            {b.label} <strong>{summary[b.count]}</strong>
          </button>
        ))}
      </div>
      <DataTable
        columns={columns}
        data={rows}
        loading={loading}
        pagination={pagination}
        onPageChange={setPage}
        onPageSizeChange={handlePageSizeChange}
        initialSearch={search}
        onSearchChange={handleSearchChange}
        searchPlaceholder={isRetest ? 'Search retest alerts' : 'Search vulnerability alerts'}
        emptyMessage={anyFilterActive
          ? `No ${isRetest ? 'retest' : 'vulnerability'} alerts match these filters.`
          : isRetest ? 'No retests are waiting on anyone.' : 'No vulnerabilities currently need attention.'}
        idAccessor="key"
        // A row click opens the vulnerability panel: the finding itself, or on a retest row the
        // finding being retested. The Actions cell stops its clicks from reaching the row.
        onRowClick={openVulnDrawer}
        headerChildren={headerFilters}
        advancedActiveCount={filterChips.length}
        filterChips={filterChips}
        onApplyAdvanced={applyAdvanced}
        onClearFilters={clearAllFilters}
        advancedFilters={
          <>
            <div className="filter-field">
              <FormLabel>Assessment</FormLabel>
              <MultiSelect
                selected={draftAssessmentIds}
                onChange={(vals) => {
                  setDraftAssessmentIds(vals);
                  setAssessmentLabels(p => labelsFor(p, vals, assessmentOptionsForDraft));
                }}
                options={assessmentOptionsForDraft}
                onQueryChange={searchAssessments}
                loading={assessmentLoading}
                placeholder="All Assessments"
              />
            </div>
            {isRetest && (
              <div className="filter-field">
                <FormLabel>Options</FormLabel>
                <div className="filter-field-checks">
                  <Checkbox
                    id="showCompletedRetests"
                    checked={draftShowCompletedRetests}
                    onChange={(e) => setDraftShowCompletedRetests(e.target.checked)}
                    label="Show Completed Retests"
                  />
                </div>
              </div>
            )}
          </>
        }
        sort={sort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole queue, so the current page number is
          // meaningless afterwards — go back to the first page. Clearing the sort
          // restores the queue's default urgent → warning → upcoming ordering.
          setSort(next);
          setPage(0);
        }}
      />

      {/* Vulnerability detail / edit drawer */}
      {selectedVuln && selectedAssessment && (
        <VulnerabilityDetailDrawer
          vulnerability={selectedVuln}
          assessment={selectedAssessment}
          onClose={() => { setSelectedVuln(null); setSelectedAssessment(null); }}
          // External users reach this queue on their retest permission alone; they may
          // read a finding and request a retest, not restate its status or its exception.
          allowStatusEdit={canEditVulns}
          allowFieldEdit={canEditVulns}
          // The exception workflow is this page's job — accepting a risk is what you do
          // with a finding you cannot close before its SLA runs out.
          showException
          configuredStatuses={configuredStatuses}
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
      )}

      {/* Cancel Retest modal */}
      <Modal
        isOpen={!!cancelRetestRow}
        onClose={() => { if (!cancelling) { setCancelRetestRow(null); setCancelReason(''); setCancelError(''); } }}
        title="Cancel Retest"
        size="md"
        closeOnOverlayClick={!cancelling}
        footer={
          <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end', width: '100%' }}>
            <Button
              variant="secondary"
              onClick={() => { setCancelRetestRow(null); setCancelReason(''); setCancelError(''); }}
              disabled={cancelling}
            >
              Back
            </Button>
            <Button variant="danger" onClick={handleCancelRetest} disabled={cancelling}>
              {cancelling ? 'Cancelling…' : 'Cancel Retest'}
            </Button>
          </div>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          <p style={{ margin: 0, color: 'var(--text-secondary)' }}>
            Are you sure you want to cancel the retest for{' '}
            <strong>{cancelRetestRow && displayName(cancelRetestRow)}</strong>?
            This action cannot be undone. The reason will be logged as a comment on the vulnerability.
          </p>
          <div>
            <label className="form-label">
              Reason for Cancellation <span style={{ color: '#ef4444' }}>*</span>
            </label>
            <RichTextEditor
              value={cancelReason}
              onChange={val => { setCancelReason(val); setCancelError(''); }}
              placeholder="Enter the reason for cancelling this retest…"
            />
            {cancelError && (
              <div style={{ color: '#ef4444', fontSize: '0.85rem', marginTop: '0.25rem' }}>
                {cancelError}
              </div>
            )}
          </div>
        </div>
      </Modal>

      {/* Delete Vulnerability — Step 1: offer alternatives */}
      <Modal
        isOpen={!!deleteVulnRow && !showDeleteConfirm}
        onClose={() => setDeleteVulnRow(null)}
        title="Delete Vulnerability"
        size="md"
        footer={
          <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end', width: '100%' }}>
            <Button variant="secondary" onClick={() => setDeleteVulnRow(null)}>
              Cancel
            </Button>
            <Button
              variant="warning"
              onClick={() => {
                if (deleteVulnRow?.assessmentId) {
                  const vulnId = deleteVulnRow.vulnerabilityId;
                  const assessmentId = deleteVulnRow.assessmentId;
                  setDeleteVulnRow(null);
                  navigate(`/assessments/${assessmentId}`, { state: { editVulnId: vulnId } });
                }
              }}
            >
              Lower Severity / Add Exception
            </Button>
            <Button variant="danger" onClick={() => setShowDeleteConfirm(true)}>
              Delete Anyway
            </Button>
          </div>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1rem', padding: '1rem 0' }}>
          <AlertTriangle size={48} className="text-warning" />
          <p style={{ textAlign: 'center', margin: 0, color: 'var(--text-secondary)' }}>
            Before deleting <strong>{deleteVulnRow?.vulnerabilityName}</strong>, would you like to
            lower its severity or mark it as an exception instead? Either option keeps the record
            for audit purposes.
          </p>
        </div>
      </Modal>

      {/* Delete Vulnerability — Step 2: final confirmation */}
      <ConfirmDialog
        isOpen={showDeleteConfirm}
        onClose={() => { setShowDeleteConfirm(false); setDeleteVulnRow(null); }}
        onConfirm={handleDeleteVuln}
        title="Permanently Delete Vulnerability"
        message={`Are you absolutely sure you want to delete "${deleteVulnRow?.vulnerabilityName}"? This cannot be undone.`}
        confirmText="Delete"
        variant="danger"
        isLoading={deleting}
      />
    </Page>
  );
}
