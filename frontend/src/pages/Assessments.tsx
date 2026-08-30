import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Eye, Download, AlertCircle } from 'lucide-react';
import { assessmentsApi, applicationsApi, assessmentTypesApi, workflowConfigApi } from '../api';
import type {
  Assessment,
  Application,
  AssessmentType,
} from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam, FilterChip } from '../components/DataTable';
import SearchableSelect, { SelectOption } from '../components/SearchableSelect';
import { Button, Badge, FormLabel, Input, Checkbox } from '../components';
import { usePermissions } from '../utils/permissions';
import Page from '../components/Page';
import './Assessments.css';

const STATUS_COLORS: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'secondary'> = {
  DRAFT: 'secondary',
  IN_PROGRESS: 'info',
  ON_HOLD: 'warning',
  PENDING_REVIEW: 'info',
  COMPLETED: 'success',
  APPROVED: 'success',
  ARCHIVED: 'secondary',
};

export default function Assessments() {
  const navigate = useNavigate();
  const { hasAnyPermission } = usePermissions();

  const [assessments, setAssessments] = useState<Assessment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [exporting, setExporting] = useState(false);

  // Reference data
  const [applications, setApplications] = useState<Application[]>([]);
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);
  const [statusColors, setStatusColors] = useState<Record<string, string>>({});
  const [wfStatuses, setWfStatuses] = useState<string[]>([]);

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [sort, setSort] = useState<SortState | null>(null);

  // Applied filters — what actually drives the query. Inline filters (search, application, type,
  // status) write here directly (live-apply); advanced filters land here only on Apply.
  const [filters, setFilters] = useState({
    search: '',
    startDateFrom: '',
    startDateTo: '',
    endDateFrom: '',
    endDateTo: '',
    pastDue: false,
    showCompleted: false, // Default to hiding completed assessments
    assignedToMe: false, // Default to showing all assessments
    status: '',
    applicationId: '',
    assessmentTypeId: '',
  });

  // Draft state for the advanced panel — staged until the user presses Apply.
  const ADVANCED_DEFAULTS = {
    startDateFrom: '', startDateTo: '', endDateFrom: '', endDateTo: '',
    pastDue: false, showCompleted: false, assignedToMe: false,
  };
  const [draft, setDraft] = useState(ADVANCED_DEFAULTS);

  // Check if user can view all assessments
  const canViewAll = hasAnyPermission([
    'assessments:read:all',
    'assessments:read:team'
  ]);

  // ── Filter option lists ────────────────────────────────────────────────────
  const appOptions: SelectOption[] = useMemo(
    () => applications.map((a) => ({ value: a.id, label: a.name })), [applications]);
  const typeOptions: SelectOption[] = useMemo(
    () => assessmentTypes.map((t) => ({ value: t.id, label: t.name })), [assessmentTypes]);
  const statusOptions: SelectOption[] = useMemo(
    () => wfStatuses.map((s) => ({ value: s, label: s })), [wfStatuses]);

  // ── Zone 2: inline filters apply immediately ───────────────────────────────
  const applyInline = (patch: Partial<typeof filters>) => {
    setFilters((prev) => ({ ...prev, ...patch }));
    setPagination((prev) => ({ ...prev, page: 0 }));
  };

  // ── Zone 3: advanced panel stages in `draft`, commits on Apply ─────────────
  const applyAdvanced = () => {
    setFilters((prev) => ({ ...prev, ...draft }));
    setPagination((prev) => ({ ...prev, page: 0 }));
  };

  // Clear every structured filter (both zones) and the staged draft.
  const clearAllFilters = () => {
    setDraft(ADVANCED_DEFAULTS);
    setFilters((prev) => ({
      ...prev, ...ADVANCED_DEFAULTS, status: '', applicationId: '', assessmentTypeId: '',
    }));
    setPagination((prev) => ({ ...prev, page: 0 }));
  };

  // Keep the panel's draft in sync when applied advanced values change from elsewhere
  // (chip removal, clear-all), so reopening the panel shows the current state. One effect per
  // field — a combined effect would rebuild the whole draft on any applied change, wiping a
  // field the user is mid-edit on when an unrelated chip is removed.
  useEffect(() => { setDraft((d) => ({ ...d, startDateFrom: filters.startDateFrom })); }, [filters.startDateFrom]);
  useEffect(() => { setDraft((d) => ({ ...d, startDateTo: filters.startDateTo })); }, [filters.startDateTo]);
  useEffect(() => { setDraft((d) => ({ ...d, endDateFrom: filters.endDateFrom })); }, [filters.endDateFrom]);
  useEffect(() => { setDraft((d) => ({ ...d, endDateTo: filters.endDateTo })); }, [filters.endDateTo]);
  useEffect(() => { setDraft((d) => ({ ...d, pastDue: filters.pastDue })); }, [filters.pastDue]);
  useEffect(() => { setDraft((d) => ({ ...d, showCompleted: filters.showCompleted })); }, [filters.showCompleted]);
  useEffect(() => { setDraft((d) => ({ ...d, assignedToMe: filters.assignedToMe })); }, [filters.assignedToMe]);

  // ── Active advanced filters → chips + badge count (inline filters show in their own dropdowns) ──
  const fmtDay = (d: string) => (d ? new Date(`${d}T00:00:00`).toLocaleDateString() : '…');
  const filterChips: FilterChip[] = [];
  if (filters.startDateFrom || filters.startDateTo) {
    filterChips.push({
      key: 'start', label: `Start: ${fmtDay(filters.startDateFrom)} – ${fmtDay(filters.startDateTo)}`,
      onRemove: () => applyInline({ startDateFrom: '', startDateTo: '' }),
    });
  }
  if (filters.endDateFrom || filters.endDateTo) {
    filterChips.push({
      key: 'end', label: `End: ${fmtDay(filters.endDateFrom)} – ${fmtDay(filters.endDateTo)}`,
      onRemove: () => applyInline({ endDateFrom: '', endDateTo: '' }),
    });
  }
  if (filters.pastDue) filterChips.push({ key: 'pastDue', label: 'Past due only', onRemove: () => applyInline({ pastDue: false }) });
  if (filters.showCompleted) filterChips.push({ key: 'showCompleted', label: 'Show completed', onRemove: () => applyInline({ showCompleted: false }) });
  if (filters.assignedToMe) filterChips.push({ key: 'assignedToMe', label: 'Assigned to me', onRemove: () => applyInline({ assignedToMe: false }) });

  useEffect(() => {
    loadReferenceData();
  }, []);

  useEffect(() => {
    loadAssessments();
  }, [pagination.page, pagination.pageSize, filters, sort]);

  const loadAssessments = async () => {
    setLoading(true);
    setError('');
    try {
      const response = await assessmentsApi.search({
        page: pagination.page,
        size: pagination.pageSize,
        search: filters.search || undefined,
        // Date inputs are date-only; widen to full-day bounds (start-of-day / end-of-day) so the
        // "to" day is included and the value parses as the LocalDateTime the API expects.
        startDateFrom: filters.startDateFrom ? `${filters.startDateFrom}T00:00:00` : undefined,
        startDateTo: filters.startDateTo ? `${filters.startDateTo}T23:59:59` : undefined,
        endDateFrom: filters.endDateFrom ? `${filters.endDateFrom}T00:00:00` : undefined,
        endDateTo: filters.endDateTo ? `${filters.endDateTo}T23:59:59` : undefined,
        pastDue: filters.pastDue,
        showCompleted: filters.showCompleted,
        assignedToMe: filters.assignedToMe,
        status: filters.status || undefined,
        applicationId: filters.applicationId || undefined,
        assessmentTypeId: filters.assessmentTypeId || undefined,
        sort: sortParam(sort),
      });

      if (response.success && response.data) {
        setAssessments(response.data);
        if (response.pagination) {
          setPagination((prev) => ({
            ...prev,
            total: response.pagination!.totalElements,
            totalPages: response.pagination!.totalPages,
          }));
        }
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load assessments');
    } finally {
      setLoading(false);
    }
  };

  const loadReferenceData = async () => {
    try {
      const [appsResponse, typesResponse] = await Promise.all([
        applicationsApi.getAll(0, 1000),
        assessmentTypesApi.getAll(0, 1000),
      ]);

      if (appsResponse.success && appsResponse.data) {
        setApplications(appsResponse.data);
      }
      if (typesResponse.success && typesResponse.data) {
        setAssessmentTypes(typesResponse.data);
      }
    } catch (err) {
      console.error('Failed to load reference data:', err);
    }

    workflowConfigApi.getConfig().then(res => {
      if (res.success && res.data) {
        if (res.data.statusColors) setStatusColors(res.data.statusColors);
        if (res.data.statuses) setWfStatuses(res.data.statuses);
      }
    }).catch(() => {});
  };

  const handleViewClick = (assessment: Assessment) => {
    navigate(`/assessments/${assessment.id}`);
  };

  const handleExportCsv = async () => {
    setExporting(true);
    try {
      const blob = await assessmentsApi.exportToCsv({
        applicationId: filters.applicationId || undefined,
        assessmentTypeId: filters.assessmentTypeId || undefined,
        status: filters.status || undefined,
        name: filters.search || undefined,
      });

      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `assessments-${new Date().toISOString().split('T')[0]}.csv`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to export assessments');
    } finally {
      setExporting(false);
    }
  };

  const SEVERITY_CHIPS = [
    { key: 'critical',      label: 'C', color: '#ef4444' },
    { key: 'high',          label: 'H', color: '#f97316' },
    { key: 'medium',        label: 'M', color: '#eab308' },
    { key: 'low',           label: 'L', color: '#22c55e' },
    { key: 'informational', label: 'I', color: '#9ca3af' },
  ] as const;

  const VulnerabilitySummaryCell = ({ summary }: { summary?: Record<string, number> }) => {
    if (!summary) return <span style={{ color: 'var(--text-muted)' }}>-</span>;
    const hasAny = SEVERITY_CHIPS.some(s => (summary[s.key] ?? 0) > 0);
    if (!hasAny) return <span style={{ color: 'var(--text-muted)' }}>None</span>;
    return (
      <div className="vuln-summary-chips">
        {SEVERITY_CHIPS.map(({ key, label, color }) => {
          const count = summary[key] ?? 0;
          if (count === 0) return null;
          return (
            <span key={key} className="vuln-summary-chip" style={{ background: color }}>
              {label}: {count}
            </span>
          );
        })}
      </div>
    );
  };

  const columns: Column<Assessment>[] = [
    {
      header: 'Assessment Name',
      sortKey: 'name',
      accessor: 'name',
      render: (assessment) => (
        <div>
          <div className="fw-medium">{assessment.name}</div>
          {assessment.isPastDue && (
            <Badge variant="danger" size="sm">
              <AlertCircle size={12} style={{ marginRight: '0.25rem' }} />
              Past Due
            </Badge>
          )}
        </div>
      ),
    },
    {
      header: 'Application',
      sortKey: 'applicationName',
      render: (assessment) => assessment.applicationName || '-',
    },
    {
      header: 'Team',
      sortKey: 'teamName',
      render: (assessment) => assessment.teamName || '-',
    },
    {
      header: 'Assessment Type',
      sortKey: 'assessmentTypeName',
      render: (assessment) => assessment.assessmentTypeName || '-',
    },
    {
      header: 'Start Date',
      sortKey: 'startDate',
      accessor: 'startDate',
      render: (assessment) =>
        assessment.startDate ? new Date(assessment.startDate).toLocaleDateString() : '-',
    },
    {
      header: 'End Date',
      sortKey: 'plannedEndDate',
      accessor: 'plannedEndDate',
      render: (assessment) =>
        assessment.plannedEndDate ? new Date(assessment.plannedEndDate).toLocaleDateString() : '-',
    },
    {
      header: 'Completed',
      sortKey: 'completedDate',
      accessor: 'completedDate',
      render: (assessment) =>
        assessment.completedDate ? new Date(assessment.completedDate).toLocaleDateString() : '-',
    },
    {
      header: 'Status',
      sortKey: 'status',
      accessor: 'status',
      render: (assessment) => {
        const custom = statusColors[assessment.status];
        return (
          <Badge variant={custom ? undefined : STATUS_COLORS[assessment.status]} customColor={custom}>
            {assessment.status.replace('_', ' ')}
          </Badge>
        );
      },
    },
    {
      header: 'Assessors',
      render: (assessment) => {
        const assessors = assessment.assessorNames || [];
        if (assessors.length === 0) return '-';
        if (assessors.length <= 2) return assessors.join(', ');
        return (
          <span title={assessors.join(', ')}>
            {assessors[0]}, {assessors[1]} +{assessors.length - 2} more
          </span>
        );
      },
    },
    {
      header: 'Vulnerabilities',
      render: (assessment) => (
        <VulnerabilitySummaryCell summary={assessment.vulnerabilitySummary as Record<string, number> | undefined} />
      ),
    },
    {
      header: 'Actions',
      render: (assessment) => (
        <Button variant="secondary" size="sm" onClick={() => handleViewClick(assessment)}>
          <Eye size={16} />
          View
        </Button>
      ),
    },
  ];

  return (
    <Page className="assessments-page">
      {error && (
        <div className="alert alert-danger alert-dismissible fade show mb-4" role="alert">
          {error}
          <button type="button" className="btn-close" onClick={() => setError('')}></button>
        </div>
      )}

      <DataTable
        columns={columns}
        data={assessments}
        loading={loading}
        pagination={pagination}
        onPageChange={(page) => setPagination({ ...pagination, page })}
        onPageSizeChange={(pageSize) => setPagination({ ...pagination, pageSize, page: 0 })}
        onSearchChange={(search) => {
          setFilters((prev) => ({ ...prev, search }));
          setPagination((prev) => ({ ...prev, page: 0 }));
        }}
        searchPlaceholder="Search assessments"
        idAccessor="id"
        onRowClick={handleViewClick}
        sort={sort}
        onSortChange={(next) => {
          // Re-sorting reshuffles the whole result set, so the current page number
          // is meaningless afterwards — go back to the first page.
          setSort(next);
          setPagination((prev) => ({ ...prev, page: 0 }));
        }}
        headerChildren={
          <div className="ss-filter-bar">
            <SearchableSelect
              value={filters.applicationId}
              onChange={(v) => applyInline({ applicationId: v })}
              options={appOptions}
              placeholder="All Applications"
            />
            <SearchableSelect
              value={filters.assessmentTypeId}
              onChange={(v) => applyInline({ assessmentTypeId: v })}
              options={typeOptions}
              placeholder="All Types"
              searchable={false}
            />
            <SearchableSelect
              value={filters.status}
              onChange={(v) => applyInline({ status: v })}
              options={statusOptions}
              placeholder="All Statuses"
              searchable={false}
            />
            <Button variant="secondary" icon={Download} onClick={handleExportCsv} disabled={exporting}>
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
            <div className="filter-field">
              <FormLabel>Start Date</FormLabel>
              <div className="filter-field-range">
                <Input type="date" value={draft.startDateFrom}
                  onChange={(e) => setDraft({ ...draft, startDateFrom: e.target.value })} />
                <Input type="date" value={draft.startDateTo}
                  onChange={(e) => setDraft({ ...draft, startDateTo: e.target.value })} />
              </div>
            </div>
            <div className="filter-field">
              <FormLabel>End Date</FormLabel>
              <div className="filter-field-range">
                <Input type="date" value={draft.endDateFrom}
                  onChange={(e) => setDraft({ ...draft, endDateFrom: e.target.value })} />
                <Input type="date" value={draft.endDateTo}
                  onChange={(e) => setDraft({ ...draft, endDateTo: e.target.value })} />
              </div>
            </div>
            <div className="filter-field">
              <FormLabel>Options</FormLabel>
              <div className="filter-field-checks">
                <Checkbox id="pastDue" checked={draft.pastDue}
                  onChange={(e) => setDraft({ ...draft, pastDue: e.target.checked })}
                  label="Past Due Only" />
                <Checkbox id="showCompleted" checked={draft.showCompleted}
                  onChange={(e) => setDraft({ ...draft, showCompleted: e.target.checked })}
                  label="Show Completed" />
                {canViewAll && (
                  <Checkbox id="assignedToMe" checked={draft.assignedToMe}
                    onChange={(e) => setDraft({ ...draft, assignedToMe: e.target.checked })}
                    label="Assigned to me" />
                )}
              </div>
            </div>
          </>
        }
      />
    </Page>
  );
}
