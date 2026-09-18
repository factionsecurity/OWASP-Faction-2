import { useEffect, useState, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { usePageTitle } from '../context/PageTitleContext';
import {
  Calendar,
  CalendarCheck,
  CalendarClock,
  Download,
  ExternalLink,
  ShieldAlert,
  ListChecks,
  Bug,
} from 'lucide-react';
import {
  managerDashboardApi,
  assessmentsApi,
  assessmentTypesApi,
  teamsApi,
  usersApi,
  campaignsApi,
} from '../api';
import type {
  Assessment,
  AssessmentType,
  Campaign,
  ManagerDashboardFilters,
  ManagerDashboardStats,
  ManagerDashboardSummary,
  Team,
  User,
} from '../types';

// Flattened assessment row: the assessment plus its assessors' team names
type AssessmentRow = Assessment & { teamNames: string[] };
import DataTable, { Column, PaginationInfo, SortState, sortParam, FilterChip } from '../components/DataTable';
import SearchableSelect, { MultiSelect, SelectOption } from '../components/SearchableSelect';
import { Button, Checkbox, FormLabel, Input, Select, IconButton } from '../components';
import Page from '../components/Page';
import { VULNERABILITY_SEVERITIES, SEVERITY_COLORS } from '../utils/vulnSeverity';
import '../components/SearchableSelect.css';
// Abbreviates 6-figure totals (e.g. 260,750 -> "261K") so they don't overflow the stat tiles;
// the exact value stays in each tile's title tooltip.
import { formatCompact as fmtStat } from '../utils/formatNumber';
import './ManagerDashboard.css';
import { useTerminology } from '../context/TerminologyContext';
import { usePersistedState } from '../hooks/usePersistedState';
import { useWorkflowsContext } from '../context/WorkflowsContext';
import { mergedStatusNames, workflowsForSelectedTypes } from '../utils/workflowLookup';

const TABLE_KEY = 'managerDashboard';

// Dashboard severity chips: lowercase keys + single-letter labels are presentational
// and dashboard-specific; the color comes from the canonical palette.
const SEVERITY_CHIPS = [
  { key: 'critical', label: 'C', color: SEVERITY_COLORS.CRITICAL },
  { key: 'high', label: 'H', color: SEVERITY_COLORS.HIGH },
  { key: 'medium', label: 'M', color: SEVERITY_COLORS.MEDIUM },
  { key: 'low', label: 'L', color: SEVERITY_COLORS.LOW },
  { key: 'informational', label: 'I', color: SEVERITY_COLORS.INFORMATIONAL },
] as const;

const QUICK_RANGES = [
  { key: 'today', label: 'Today' },
  { key: 'yesterday', label: 'Yesterday' },
  { key: '7days', label: 'Last 7 Days' },
  { key: '30days', label: 'Last 30 Days' },
  { key: 'month', label: 'This Month' },
  { key: 'lastmonth', label: 'Last Month' },
  { key: 'year', label: 'This Year' },
  { key: 'alltime', label: 'All Time' },
] as const;

function toDateInput(d: Date): string {
  return d.toISOString().split('T')[0];
}

function quickRangeDates(key: string): { from: string; to: string } {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  switch (key) {
    case 'today':
      return { from: toDateInput(today), to: toDateInput(today) };
    case 'yesterday': {
      const y = new Date(today);
      y.setDate(y.getDate() - 1);
      return { from: toDateInput(y), to: toDateInput(y) };
    }
    case '7days': {
      const from = new Date(today);
      from.setDate(from.getDate() - 6);
      return { from: toDateInput(from), to: toDateInput(today) };
    }
    case '30days': {
      const from = new Date(today);
      from.setDate(from.getDate() - 29);
      return { from: toDateInput(from), to: toDateInput(today) };
    }
    case 'month':
      return {
        from: toDateInput(new Date(now.getFullYear(), now.getMonth(), 1)),
        to: toDateInput(new Date(now.getFullYear(), now.getMonth() + 1, 0)),
      };
    case 'lastmonth':
      return {
        from: toDateInput(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
        to: toDateInput(new Date(now.getFullYear(), now.getMonth(), 0)),
      };
    case 'year':
      return { from: toDateInput(new Date(now.getFullYear(), 0, 1)), to: toDateInput(today) };
    case 'alltime':
    default:
      return { from: '', to: '' };
  }
}

interface FilterFormState {
  startDate: string;
  endDate: string;
  assessmentTypeId: string;
  teamId: string;
  status: string;
  assessorId: string;
  campaignId: string;
  severities: string[];
  /** Offer statuses from archived workflows too. Lives in the advanced panel, so it applies with it. */
  includeArchivedWorkflows: boolean;
}

function defaultFilterForm(): FilterFormState {
  const { from, to } = quickRangeDates('30days');
  return {
    startDate: from,
    endDate: to,
    assessmentTypeId: '',
    teamId: '',
    status: '',
    assessorId: '',
    campaignId: '',
    severities: [],
    includeArchivedWorkflows: false,
  };
}

function toApiFilters(form: FilterFormState): ManagerDashboardFilters {
  return {
    assessmentTypeId: form.assessmentTypeId || undefined,
    teamId: form.teamId || undefined,
    status: form.status || undefined,
    assessorId: form.assessorId || undefined,
    campaignId: form.campaignId || undefined,
    severities: form.severities.length > 0 ? form.severities : undefined,
    startDateFrom: form.startDate ? `${form.startDate}T00:00:00` : undefined,
    startDateTo: form.endDate ? `${form.endDate}T23:59:59` : undefined,
  };
}

export default function ManagerDashboard() {
  const navigate = useNavigate();
  const { setPageTitle } = usePageTitle();
  const { severityLabel, severityOptions } = useTerminology();
  const { workflows } = useWorkflowsContext();

  const [summary, setSummary] = useState<ManagerDashboardSummary | null>(null);
  const [stats, setStats] = useState<ManagerDashboardStats | null>(null);
  const [error, setError] = useState('');

  // Applied filters — the source of truth that drives getStats + the table. Inline filters
  // (severity/status/assessor/type) write here live; advanced filters land here on Apply.
  const [applied, setApplied] = usePersistedState<FilterFormState>(TABLE_KEY, 'applied', defaultFilterForm());
  const [appliedQuickRange, setAppliedQuickRange] = usePersistedState(TABLE_KEY, 'appliedQuickRange', '30days');
  // A saved quick range stores the dates it resolved to on the day it was applied. Re-resolve them
  // once on return so "Last 30 Days" still ends today. This is a render-phase update, so the first
  // fetch already uses the fresh dates.
  const [rangeRebased, setRangeRebased] = useState(false);
  if (!rangeRebased) {
    setRangeRebased(true);
    if (appliedQuickRange) {
      const { from, to } = quickRangeDates(appliedQuickRange);
      if (from !== applied.startDate || to !== applied.endDate) {
        setApplied((prev) => ({ ...prev, startDate: from, endDate: to }));
      }
    }
  }
  // Advanced panel draft (dates, team, campaign and the archived-workflows toggle), staged until
  // Apply. Starts from the applied (possibly restored) values.
  const advancedDraft = (f: FilterFormState) => ({
    startDate: f.startDate, endDate: f.endDate, teamId: f.teamId, campaignId: f.campaignId,
    includeArchivedWorkflows: f.includeArchivedWorkflows,
  });
  const [draft, setDraft] = useState(() => advancedDraft(applied));
  // Quick range: the panel's picked preset (drives the date fields); the applied one labels the chip.
  const [quickRange, setQuickRange] = useState(appliedQuickRange);
  const [exporting, setExporting] = useState(false);
  const [earliestStart, setEarliestStart] = useState<string | null>(null);

  const appliedFilters = useMemo<ManagerDashboardFilters>(() => toApiFilters(applied), [applied]);

  // Dropdown data
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [assessors, setAssessors] = useState<User[]>([]);
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);

  // Results table
  const [assessmentRows, setAssessmentRows] = useState<AssessmentRow[]>([]);
  const [assessmentsLoading, setAssessmentsLoading] = useState(false);
  const [assessmentPagination, setAssessmentPagination] = usePersistedState<PaginationInfo>(TABLE_KEY, 'pagination', {
    page: 0, pageSize: 25, total: 0, totalPages: 0,
  });
  // Free-text search from the DataTable search box
  const [assessmentSearch, setAssessmentSearch] = usePersistedState(TABLE_KEY, 'search', '');
  const [assessmentSort, setAssessmentSort] = usePersistedState<SortState | null>(TABLE_KEY, 'sort', null);

  useEffect(() => {
    setPageTitle('Operational Dashboard');
    loadSummary();
    loadDropdownData();
  }, []);

  useEffect(() => {
    loadStats();
  }, [appliedFilters]);

  useEffect(() => {
    loadAssessments();
  }, [appliedFilters, assessmentSearch, assessmentPagination.page, assessmentPagination.pageSize,
      assessmentSort]);

  const loadSummary = async () => {
    try {
      const response = await managerDashboardApi.getSummary();
      if (response.data) setSummary(response.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load dashboard summary');
    }
  };

  const loadDropdownData = async () => {
    const [typesRes, teamsRes, usersRes, campaignsRes, earliestRes] = await Promise.all([
      assessmentTypesApi.getAll(0, 1000).catch(() => null),
      teamsApi.getAll(0, 1000).catch(() => null),
      usersApi.getAll(0, 1000).catch(() => null),
      campaignsApi.getAllUnpaged().catch(() => null),
      // Oldest dated assessment, for the All Time hint. startDateFrom excludes undated rows,
      // so the first ascending row is the true earliest start date rather than a null.
      assessmentsApi.search({ page: 0, size: 1, sort: 'startDate,asc', startDateFrom: '1970-01-01T00:00:00' })
        .catch(() => null),
    ]);
    setEarliestStart(earliestRes?.data?.[0]?.startDate ?? null);
    if (typesRes?.data) setAssessmentTypes(typesRes.data);
    if (teamsRes?.data) setTeams(teamsRes.data);
    if (usersRes?.data) setAssessors(usersRes.data);
    if (campaignsRes?.data) setCampaigns(campaignsRes.data);
  };

  const loadStats = async () => {
    try {
      const response = await managerDashboardApi.getStats(appliedFilters);
      if (response.data) setStats(response.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load dashboard statistics');
    }
  };

  const loadAssessments = async () => {
    try {
      setAssessmentsLoading(true);
      const response = await managerDashboardApi.searchAssessments(
        { ...appliedFilters, search: assessmentSearch || undefined },
        assessmentPagination.page, assessmentPagination.pageSize,
        sortParam(assessmentSort) ?? 'startDate,desc');
      if (response.data) {
        setAssessmentRows(response.data.map((row) => ({
          ...row.assessment,
          teamNames: row.teamNames || [],
        })));
        setAssessmentPagination((prev) => ({
          ...prev,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        }));
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load assessments');
    } finally {
      setAssessmentsLoading(false);
    }
  };

  const handleAssessmentPageChange = useCallback((page: number) =>
    setAssessmentPagination((prev) => ({ ...prev, page })), []);
  const handleAssessmentPageSizeChange = useCallback((pageSize: number) =>
    setAssessmentPagination((prev) => ({ ...prev, page: 0, pageSize })), []);
  const handleAssessmentSearchChange = useCallback((search: string) => {
    setAssessmentSearch(search);
    setAssessmentPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  const resetPage = () => setAssessmentPagination((prev) => ({ ...prev, page: 0 }));

  // Zone 2 — inline filters apply live.
  const applyInline = (patch: Partial<FilterFormState>) => {
    setApplied((prev) => ({ ...prev, ...patch }));
    resetPage();
  };

  // Zone 3 — the advanced panel stages in `draft`, commits on Apply.
  const applyAdvanced = () => {
    setApplied((prev) => ({ ...prev, ...draft }));
    setAppliedQuickRange(quickRange);
    resetPage();
  };

  // Clear every filter across both zones — including the date range (→ all-time).
  const clearAllFilters = () => {
    const cleared: FilterFormState = {
      startDate: '', endDate: '', assessmentTypeId: '', teamId: '', status: '',
      assessorId: '', campaignId: '', severities: [], includeArchivedWorkflows: false,
    };
    setApplied(cleared);
    setDraft(advancedDraft(cleared));
    setQuickRange('');
    setAppliedQuickRange('');
    resetPage();
  };

  // Keep the panel's draft aligned with the applied advanced fields when they change from
  // elsewhere (a chip removal or clear-all). One effect per field — a combined effect would
  // rebuild the whole draft on any applied change, wiping a field the user is mid-edit on when
  // an unrelated chip (e.g. Team) is removed.
  useEffect(() => { setDraft((d) => ({ ...d, startDate: applied.startDate })); }, [applied.startDate]);
  useEffect(() => { setDraft((d) => ({ ...d, endDate: applied.endDate })); }, [applied.endDate]);
  useEffect(() => { setDraft((d) => ({ ...d, teamId: applied.teamId })); }, [applied.teamId]);
  useEffect(() => { setDraft((d) => ({ ...d, campaignId: applied.campaignId })); }, [applied.campaignId]);
  useEffect(() => { setDraft((d) => ({ ...d, includeArchivedWorkflows: applied.includeArchivedWorkflows })); },
    [applied.includeArchivedWorkflows]);

  const handleQuickRange = (key: string) => {
    setQuickRange(key);
    if (!key) return;
    // All Time deliberately leaves both dates blank rather than filling in the data's span:
    // an active date filter excludes undated assessments (AssessmentRepositoryImpl says so),
    // and most assessments here have no start date — so a populated "all time" would show
    // far less than all of it. The span is surfaced as a hint instead.
    const { from, to } = quickRangeDates(key);
    setDraft((prev) => ({ ...prev, startDate: from, endDate: to }));
  };

  // Editing a date by hand no longer matches whatever range was picked — drop the label.
  const setDate = (field: 'startDate' | 'endDate', value: string) => {
    setQuickRange('');
    setDraft((prev) => ({ ...prev, [field]: value }));
  };

  const downloadBlob = (blob: Blob, filename: string) => {
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    window.URL.revokeObjectURL(url);
    document.body.removeChild(a);
  };

  const handleExportAssessments = async () => {
    setExporting(true);
    try {
      const blob = await managerDashboardApi.exportAssessmentsCsv(appliedFilters);
      downloadBlob(blob, `operational-dashboard-assessments-${toDateInput(new Date())}.csv`);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to export assessments');
    } finally {
      setExporting(false);
    }
  };

  const userDisplayName = (user: User) =>
    user.firstName && user.lastName ? `${user.firstName} ${user.lastName}` : user.username;

  const formatDate = (value?: string) =>
    value ? new Date(value).toLocaleDateString() : '-';

  // Date-only (YYYY-MM-DD) values must be parsed at local midnight, not UTC — otherwise the chip
  // label reads a day early in UTC-negative zones. (formatDate stays UTC-parsed for its full-ISO callers.)
  const fmtDay = (d?: string) => (d ? new Date(`${d}T00:00:00`).toLocaleDateString() : '…');

  // ── Filter option lists + active-filter chips ───────────────────────────────
  const typeOptions: SelectOption[] = assessmentTypes.map((t) => ({ value: t.id, label: t.name }));
  // Statuses follow the selected type: none selected lets the archived toggle decide the list, as
  // before; a type selected offers only the statuses of the workflow that type runs on.
  const statusOptions: SelectOption[] = useMemo(() => {
    const offered = workflowsForSelectedTypes(
      workflows,
      assessmentTypes,
      applied.assessmentTypeId ? [applied.assessmentTypeId] : [],
      applied.includeArchivedWorkflows ? workflows : workflows.filter((w) => !w.archived),
    );
    return mergedStatusNames(offered).map((s) => ({ value: s, label: s }));
  }, [workflows, assessmentTypes, applied.assessmentTypeId, applied.includeArchivedWorkflows]);

  // Clear a selected status the list no longer offers, so the table is never filtered by a value
  // the status select can't show. Waits for types and workflows: a persisted selection is restored
  // before either loads, and clearing it in that window would wipe a saved filter on reload.
  useEffect(() => {
    if (!applied.status || assessmentTypes.length === 0 || workflows.length === 0) return;
    if (statusOptions.some((o) => o.value === applied.status)) return;
    applyInline({ status: '' });
  }, [statusOptions, applied.status, assessmentTypes.length, workflows.length]);
  const assessorOptions: SelectOption[] = assessors.map((u) => ({ value: u.id, label: userDisplayName(u) }));
  const teamOptions: SelectOption[] = teams.map((t) => ({ value: t.id, label: t.name }));
  const campaignOptions: SelectOption[] = campaigns.map((c) => ({ value: c.id, label: c.name }));
  const teamName = (id: string) => teams.find((t) => t.id === id)?.name ?? id;
  const campaignName = (id: string) => campaigns.find((c) => c.id === id)?.name ?? id;

  // Chips surface the active *advanced* filters (the inline filters show their own pill state).
  const filterChips: FilterChip[] = [];
  if (applied.startDate || applied.endDate) {
    const preset = appliedQuickRange ? QUICK_RANGES.find((r) => r.key === appliedQuickRange)?.label : '';
    filterChips.push({
      key: 'date',
      label: preset || `${fmtDay(applied.startDate)} – ${fmtDay(applied.endDate)}`,
      onRemove: () => {
        setApplied((prev) => ({ ...prev, startDate: '', endDate: '' }));
        setQuickRange(''); setAppliedQuickRange(''); resetPage();
      },
    });
  }
  if (applied.teamId) {
    filterChips.push({ key: 'team', label: `Team: ${teamName(applied.teamId)}`, onRemove: () => applyInline({ teamId: '' }) });
  }
  if (applied.campaignId) {
    filterChips.push({ key: 'campaign', label: `Campaign: ${campaignName(applied.campaignId)}`, onRemove: () => applyInline({ campaignId: '' }) });
  }

  const VulnerabilitySummaryCell = ({ summary: vulnSummary }: { summary?: Record<string, number> }) => {
    if (!vulnSummary) return <span className="text-muted">-</span>;
    const hasAny = SEVERITY_CHIPS.some((s) => (vulnSummary[s.key] ?? 0) > 0);
    if (!hasAny) return <span className="text-muted">None</span>;
    return (
      <div className="md-vuln-chips">
        {SEVERITY_CHIPS.map(({ key, label, color }) => {
          const count = vulnSummary[key] ?? 0;
          if (count === 0) return null;
          return (
            <span key={key} className="md-vuln-chip" style={{ background: color }}>
              {label}: {count}
            </span>
          );
        })}
      </div>
    );
  };

  const assessmentColumns: Column<AssessmentRow>[] = [
    {
      header: '',
      width: '48px',
      render: (row) => (
        <IconButton
          icon={ExternalLink}
          variant="info"
          title="Open Assessment"
          onClick={() => navigate(`/assessments/${row.id}`)}
        />
      ),
    },
    { header: 'App ID', sortKey: 'appId', render: (row) => row.appId || '-' },
    {
      header: 'Name',
      sortKey: 'name',
      render: (row) => <span className="font-medium">{row.name}</span>,
    },
    { header: 'Type', sortKey: 'assessmentTypeName', render: (row) => row.assessmentTypeName || '-' },
    {
      header: 'Team',
      render: (row) => (row.teamNames.length > 0 ? row.teamNames.join(', ') : '-'),
    },
    {
      header: 'Assessors',
      render: (row) =>
        row.assessorNames && row.assessorNames.length > 0
          ? row.assessorNames.join(', ')
          : '-',
    },
    { header: 'Start', sortKey: 'startDate', render: (row) => formatDate(row.startDate) },
    { header: 'End', sortKey: 'plannedEndDate', render: (row) => formatDate(row.plannedEndDate) },
    { header: 'Completed', sortKey: 'completedDate', render: (row) => formatDate(row.completedDate) },
    { header: 'Status', sortKey: 'status', render: (row) => row.status },
    {
      header: 'Findings',
      // Counts only mean something once the assessment is finished: while it is open they are a
      // snapshot of work in progress, and reading them as a result is misleading. `completed` is
      // the server's answer for the assessment's own workflow, not a status name comparison.
      render: (row) => (
        row.completed
          ? <VulnerabilitySummaryCell summary={row.vulnerabilitySummary as unknown as Record<string, number>} />
          : '-'
      ),
    },
  ];

  const summaryCards = summary
    ? [
        { group: 'Completed Assessments', icon: CalendarCheck, values: summary.completedAssessments },
        { group: 'Vulnerabilities', icon: ShieldAlert, values: summary.vulnerabilities },
      ]
    : [];

  return (
    <Page className="manager-dashboard-page">
      {error && (
        <div className="alert alert-danger alert-dismissible fade show mb-4" role="alert">
          {error}
          <button type="button" className="btn-close" onClick={() => setError('')}></button>
        </div>
      )}

      {/* Global stats cards (unaffected by filters) */}
      <div className="md-summary-groups">
        {summaryCards.map(({ group, icon: Icon, values }) => (
          <div key={group} className="md-summary-group">
            <h4 className="md-summary-title">
              <Icon size={18} /> {group}
            </h4>
            <div className="md-stats-grid">
              <div className="stat-card">
                <div className="stat-icon primary"><Calendar size={24} /></div>
                <div className="stat-info">
                  <p className="stat-label">This Week</p>
                  <p className="stat-value" title={values.week.toLocaleString()}>{fmtStat(values.week)}</p>
                </div>
              </div>
              <div className="stat-card">
                <div className="stat-icon success"><CalendarClock size={24} /></div>
                <div className="stat-info">
                  <p className="stat-label">This Month</p>
                  <p className="stat-value" title={values.month.toLocaleString()}>{fmtStat(values.month)}</p>
                </div>
              </div>
              <div className="stat-card">
                <div className="stat-icon warning"><CalendarCheck size={24} /></div>
                <div className="stat-info">
                  <p className="stat-label">This Year</p>
                  <p className="stat-value" title={values.year.toLocaleString()}>{fmtStat(values.year)}</p>
                </div>
              </div>
              <div className="stat-card">
                <div className="stat-icon danger"><ListChecks size={24} /></div>
                <div className="stat-info">
                  <p className="stat-label">All Time</p>
                  <p className="stat-value" title={values.allTime.toLocaleString()}>{fmtStat(values.allTime)}</p>
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>


      {/* Filtered breakdown stats */}
      {stats && (
        <div className="md-breakdown-grid">
          <div className="md-breakdown-card">
            <h6 className="md-breakdown-title"><Bug size={16} /> Vulnerability Severity Breakdown</h6>
            <table className="md-breakdown-table">
              <tbody>
                {Object.entries(stats.severityBreakdown).length === 0 && (
                  <tr><td className="text-muted">No vulnerabilities found</td></tr>
                )}
                {VULNERABILITY_SEVERITIES.filter((s) => (stats.severityBreakdown[s] ?? 0) > 0).map((severity) => (
                  <tr key={severity}>
                    <td>{severityLabel(severity)}</td>
                    <td className="md-breakdown-count">
                      <span
                        className="md-breakdown-badge"
                        style={{ background: SEVERITY_COLORS[severity] }}
                      >
                        {stats.severityBreakdown[severity]}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <td>Total</td>
                  <td className="md-breakdown-count">
                    <span className="md-breakdown-badge total">{stats.totalVulnerabilities}</span>
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>

          <div className="md-breakdown-card">
            <h6 className="md-breakdown-title"><ListChecks size={16} /> Assessment Status Breakdown</h6>
            <table className="md-breakdown-table">
              <tbody>
                {Object.entries(stats.statusBreakdown).length === 0 && (
                  <tr><td className="text-muted">No assessments found</td></tr>
                )}
                {Object.entries(stats.statusBreakdown).map(([status, count]) => (
                  <tr key={status}>
                    <td>{status}</td>
                    <td className="md-breakdown-count">
                      <span className="md-breakdown-badge status">{count}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <td>Total</td>
                  <td className="md-breakdown-count">
                    <span className="md-breakdown-badge total">{stats.totalAssessments}</span>
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>

          <div className="md-breakdown-card">
            <h6 className="md-breakdown-title"><CalendarCheck size={16} /> Completed by Assessor</h6>
            <table className="md-breakdown-table">
              <tbody>
                {stats.completedByAssessor.length === 0 && (
                  <tr><td className="text-muted">No completed assessments found</td></tr>
                )}
                {stats.completedByAssessor.map((entry) => (
                  <tr key={entry.assessorId}>
                    <td>{entry.assessorName}</td>
                    <td className="md-breakdown-count">
                      <span className="md-breakdown-badge success">{entry.count}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <td>Total</td>
                  <td className="md-breakdown-count">
                    <span className="md-breakdown-badge total">{stats.totalCompletedAssessments}</span>
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>
      )}

      <DataTable
        columns={assessmentColumns}
        data={assessmentRows}
        loading={assessmentsLoading}
        pagination={assessmentPagination}
        onPageChange={handleAssessmentPageChange}
        onPageSizeChange={handleAssessmentPageSizeChange}
        onSearchChange={handleAssessmentSearchChange}
        initialSearch={assessmentSearch}
        searchPlaceholder="Search assessments"
        emptyMessage="No assessments match the current filters"
        idAccessor="id"
        sort={assessmentSort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole result set, so the current page number
          // is meaningless afterwards — go back to the first page.
          setAssessmentSort(next);
          setAssessmentPagination((prev) => ({ ...prev, page: 0 }));
        }}
        headerChildren={
          <div className="ss-filter-bar">
            <MultiSelect
              selected={applied.severities}
              onChange={(vals) => applyInline({ severities: vals })}
              options={severityOptions}
              searchable={false}
              placeholder="All Severities"
            />
            <SearchableSelect
              value={applied.assessmentTypeId}
              onChange={(v) => applyInline({ assessmentTypeId: v })}
              options={typeOptions}
              searchable={false}
              placeholder="All Types"
            />
            <SearchableSelect
              value={applied.status}
              onChange={(v) => applyInline({ status: v })}
              options={statusOptions}
              searchable={false}
              placeholder="All Statuses"
            />
            <SearchableSelect
              value={applied.assessorId}
              onChange={(v) => applyInline({ assessorId: v })}
              options={assessorOptions}
              placeholder="All Assessors"
            />
            <Button variant="secondary" icon={Download} onClick={handleExportAssessments} disabled={exporting}>
              {exporting ? 'Exporting…' : 'Export CSV'}
            </Button>
          </div>
        }
        advancedActiveCount={filterChips.length}
        filterChips={filterChips}
        onApplyAdvanced={applyAdvanced}
        onClearFilters={clearAllFilters}
        advancedFilters={
          <>
            {/* One window, not two per-field filters: an assessment matches when its start,
                planned end, or completed date falls inside it. */}
            <div className="filter-field">
              <FormLabel>From</FormLabel>
              <Input type="date" value={draft.startDate} onChange={(e) => setDate('startDate', e.target.value)} />
            </div>
            <div className="filter-field">
              <FormLabel>To</FormLabel>
              <Input type="date" value={draft.endDate} onChange={(e) => setDate('endDate', e.target.value)} />
            </div>
            <div className="filter-field">
              <FormLabel>Quick Ranges</FormLabel>
              <Select value={quickRange} onChange={(e) => handleQuickRange(e.target.value)}>
                <option value="">Select range...</option>
                {QUICK_RANGES.map((r) => (
                  <option key={r.key} value={r.key}>{r.label}</option>
                ))}
              </Select>
              {quickRange === 'alltime' && (
                <small className="text-muted">
                  {earliestStart
                    ? `Covers ${formatDate(earliestStart)} – ${formatDate(new Date().toISOString())}, plus assessments with no start date.`
                    : 'No date limit — includes assessments with no start date.'}
                </small>
              )}
            </div>
            <div className="filter-field">
              <FormLabel>Team</FormLabel>
              <SearchableSelect
                value={draft.teamId}
                onChange={(v) => setDraft((prev) => ({ ...prev, teamId: v }))}
                options={teamOptions}
                searchable={false}
                placeholder="All Teams"
              />
            </div>
            <div className="filter-field">
              <FormLabel>Campaign</FormLabel>
              <SearchableSelect
                value={draft.campaignId}
                onChange={(v) => setDraft((prev) => ({ ...prev, campaignId: v }))}
                options={campaignOptions}
                searchable={false}
                placeholder="All Campaigns"
              />
            </div>
            <div className="filter-field">
              <FormLabel>Options</FormLabel>
              <div className="filter-field-checks">
                <Checkbox
                  id="includeArchivedWorkflows"
                  checked={draft.includeArchivedWorkflows}
                  onChange={(e) => setDraft((prev) => ({ ...prev, includeArchivedWorkflows: e.target.checked }))}
                  label="Include archived workflows"
                />
              </div>
            </div>
          </>
        }
      />
    </Page>
  );
}
