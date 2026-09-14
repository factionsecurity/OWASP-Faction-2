import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Edit2, Trash2, Plus, Calendar, List, Download, Eye } from 'lucide-react';
import { assessmentsApi, applicationsApi, assessmentTypesApi, workflowConfigApi, vulnerabilitiesApi } from '../api';
import type {
  Assessment,
  AssessmentMetrics,
  Application,
  AssessmentType,
  Vulnerability,
} from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam, FilterChip } from '../components/DataTable';
import SearchableSelect, { MultiSelect, SelectOption } from '../components/SearchableSelect';
import { Button, Badge, ConfirmDialog, IconButton, ActionButtons, FormLabel, Input } from '../components';
import AssessmentCalendar from '../components/AssessmentCalendar';
import Page from '../components/Page';
import { usePersistedState } from '../hooks/usePersistedState';
import { usePermissions } from '../utils/permissions';
import './Engagements.css';

/** A calendar Date as the zone-less ISO datetime the API uses for these date-only fields. */
const toApiDate = (dt: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}T00:00:00`;
};

// localStorage key for the list view's saved search, filters, sort and paging.
const TABLE_KEY = 'scheduling';

export default function Engagements() {
  const navigate = useNavigate();
  // The View action opens the assessment detail page, which sits behind its own permission —
  // scheduling access alone does not imply it.
  const { permissions } = usePermissions();
  const [assessments, setAssessments] = useState<Assessment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [view, setView] = useState<'calendar' | 'list'>(() => {
    const saved = localStorage.getItem('engagements-view');
    return (saved === 'list' || saved === 'calendar') ? saved : 'list';
  });
  const [metrics, setMetrics] = useState<AssessmentMetrics | null>(null);

  // Reference data
  const [applications, setApplications] = useState<Application[]>([]);
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);
  const [statusColors, setStatusColors] = useState<Record<string, string>>({});
  const [wfStatuses, setWfStatuses] = useState<string[]>([]);

  const [pagination, setPagination] = usePersistedState<PaginationInfo>(TABLE_KEY, 'pagination', {
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [sort, setSort] = usePersistedState<SortState | null>(TABLE_KEY, 'sort', null);

  // Date ranges live in the advanced panel and are staged in `draft` until Apply; everything
  // else applies live. Date-only strings (YYYY-MM-DD); widened to full days when sent.
  const DATE_DEFAULTS = {
    startDateFrom: '', startDateTo: '',
    endDateFrom: '', endDateTo: '',
    completedDateFrom: '', completedDateTo: '',
  };
  const [filters, setFilters] = usePersistedState(TABLE_KEY, 'filters', {
    statuses: [] as string[],
    applicationId: '',
    assessmentTypeIds: [] as string[],
    name: '',
    pastDue: false,
    ...DATE_DEFAULTS,
  });
  const [draft, setDraft] = useState(DATE_DEFAULTS);

  // Inline filter options + live-apply. Status is not here — the stat pills own status filtering.
  const appOptions: SelectOption[] = useMemo(
    () => applications.map((a) => ({ value: a.id, label: a.name })), [applications]);
  const typeOptions: SelectOption[] = useMemo(
    () => assessmentTypes.map((t) => ({ value: t.id, label: t.name })), [assessmentTypes]);
  const applyInline = (patch: Partial<typeof filters>) => {
    setFilters((prev) => ({ ...prev, ...patch }));
    setPagination((prev) => ({ ...prev, page: 0 }));
  };
  const applyAdvanced = () => applyInline({ ...draft });
  const clearAllFilters = () => {
    setDraft(DATE_DEFAULTS);
    applyInline({ ...DATE_DEFAULTS, applicationId: '', assessmentTypeIds: [], statuses: [], pastDue: false });
  };
  // Keep the panel's draft in step when an applied range is removed via its chip or clear-all.
  useEffect(() => { setDraft((d) => ({ ...d, startDateFrom: filters.startDateFrom, startDateTo: filters.startDateTo })); },
    [filters.startDateFrom, filters.startDateTo]);
  useEffect(() => { setDraft((d) => ({ ...d, endDateFrom: filters.endDateFrom, endDateTo: filters.endDateTo })); },
    [filters.endDateFrom, filters.endDateTo]);
  useEffect(() => { setDraft((d) => ({ ...d, completedDateFrom: filters.completedDateFrom, completedDateTo: filters.completedDateTo })); },
    [filters.completedDateFrom, filters.completedDateTo]);

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
      key: 'end', label: `Planned end: ${fmtDay(filters.endDateFrom)} – ${fmtDay(filters.endDateTo)}`,
      onRemove: () => applyInline({ endDateFrom: '', endDateTo: '' }),
    });
  }
  if (filters.completedDateFrom || filters.completedDateTo) {
    filterChips.push({
      key: 'completed', label: `Completed: ${fmtDay(filters.completedDateFrom)} – ${fmtDay(filters.completedDateTo)}`,
      onRemove: () => applyInline({ completedDateFrom: '', completedDateTo: '' }),
    });
  }
  const dayStart = (d: string) => (d ? `${d}T00:00:00` : undefined);
  const dayEnd = (d: string) => (d ? `${d}T23:59:59` : undefined);

  const [deleteTarget, setDeleteTarget] = useState<Assessment | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [pageVulnerabilities, setPageVulnerabilities] = useState<Vulnerability[]>([]);

  const [showDateChangeConfirm, setShowDateChangeConfirm] = useState(false);
  const [pendingDateChange, setPendingDateChange] = useState<{
    assessmentId: string;
    assessmentName: string;
    newStart: Date;
    newEnd: Date;
    revert: () => void;
  } | null>(null);

  useEffect(() => {
    loadReferenceData();
    loadMetrics();
  }, []);

  // Persist view preference
  useEffect(() => {
    localStorage.setItem('engagements-view', view);
  }, [view]);

  useEffect(() => {
    if (view === 'calendar') {
      loadCalendarData();
    } else {
      loadAssessments();
    }
  }, [view, pagination.page, pagination.pageSize, filters, sort]);

  // Load opened vulns for the current page of assessments
  useEffect(() => {
    if (assessments.length === 0) {
      setPageVulnerabilities([]);
      return;
    }
    Promise.all(
      assessments.map(a =>
        vulnerabilitiesApi.getAll(a.id, 0, 1000)
          .then(r => (r.data || []).filter((v: Vulnerability) => !!v.openedAt))
          .catch(() => [] as Vulnerability[])
      )
    ).then(results => setPageVulnerabilities(results.flat()));
  }, [assessments]);

  const loadData = () => {
    if (view === 'calendar') {
      loadCalendarData();
    } else {
      loadAssessments();
    }
  };

  const loadAssessments = async () => {
    setLoading(true);
    setError('');
    try {
      // The list endpoint reads the search box as `search` (matching assessment or application
      // name); the older getAll helper sent it as `name`, which the endpoint ignored.
      const response = await assessmentsApi.search({
        page: pagination.page,
        size: pagination.pageSize,
        search: filters.name || undefined,
        applicationId: filters.applicationId || undefined,
        statuses: filters.statuses,
        assessmentTypeIds: filters.assessmentTypeIds,
        pastDue: filters.pastDue || undefined,
        startDateFrom: dayStart(filters.startDateFrom),
        startDateTo: dayEnd(filters.startDateTo),
        endDateFrom: dayStart(filters.endDateFrom),
        endDateTo: dayEnd(filters.endDateTo),
        completedDateFrom: dayStart(filters.completedDateFrom),
        completedDateTo: dayEnd(filters.completedDateTo),
        sort: sortParam(sort) ?? 'createdAt,desc',
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

  const loadCalendarData = async () => {
    setLoading(true);
    setError('');
    try {
      const now = new Date();
      const startOfMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1);
      const endOfMonth = new Date(now.getFullYear(), now.getMonth() + 2, 0);

      const response = await assessmentsApi.getCalendarView(
        startOfMonth.toISOString(),
        endOfMonth.toISOString(),
        0,
        1000
      );

      if (response.success && response.data) {
        setAssessments(response.data);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load calendar data');
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

  const loadMetrics = async () => {
    try {
      const response = await assessmentsApi.getMetrics();
      if (response.success && response.data) {
        setMetrics(response.data);
      }
    } catch (err) {
      console.error('Failed to load metrics:', err);
    }
  };

  const handleCreateClick = () => {
    navigate('/scheduling/create');
  };

  // The status chips are a multi-select: each click toggles that status in or out, so two or
  // three statuses can be viewed together. Total clears everything; Past Due is its own toggle.
  const handleStatChipClick = (chip: string) => {
    if (chip === 'total') {
      setFilters(prev => ({ ...prev, statuses: [], pastDue: false }));
    } else if (chip === 'pastDue') {
      setFilters(prev => ({ ...prev, pastDue: !prev.pastDue }));
    } else {
      setFilters(prev => ({
        ...prev,
        statuses: prev.statuses.includes(chip)
          ? prev.statuses.filter(s => s !== chip)
          : [...prev.statuses, chip],
      }));
    }
    setPagination(prev => ({ ...prev, page: 0 }));
  };

  const handleViewClick = (assessment: Assessment) => {
    navigate(`/assessments/${assessment.id}`);
  };

  const handleEditClick = (assessment: Assessment) => {
    navigate(`/scheduling/edit/${assessment.id}`);
  };

  const handleDeleteClick = (assessment: Assessment) => {
    setDeleteTarget(assessment);
  };

  const handleConfirmDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await assessmentsApi.delete(deleteTarget.id);
      setDeleteTarget(null);
      loadData();
      loadMetrics();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to delete assessment');
      setDeleteTarget(null);
    } finally {
      setDeleting(false);
    }
  };

  const handleEventClick = (assessment: Assessment) => {
    handleEditClick(assessment);
  };

  const handleEventDrop = async (assessmentId: string, newStart: Date, newEnd: Date, revert: () => void) => {
    // Find the assessment to get its name
    const assessment = assessments.find((a) => a.id === assessmentId);
    if (!assessment) {
      revert();
      return;
    }

    // Show confirmation dialog
    setPendingDateChange({
      assessmentId,
      assessmentName: assessment.name,
      newStart,
      newEnd,
      revert,
    });
    setShowDateChangeConfirm(true);
  };

  const handleConfirmDateChange = async () => {
    if (!pendingDateChange) return;

    try {
      // Send the dropped calendar dates as zone-less midnights. toISOString() would convert
      // the local Date to UTC first, moving the day for anyone east of Greenwich.
      await assessmentsApi.update(pendingDateChange.assessmentId, {
        startDate: toApiDate(pendingDateChange.newStart),
        plannedEndDate: toApiDate(pendingDateChange.newEnd),
      });
      setShowDateChangeConfirm(false);
      setPendingDateChange(null);
      loadCalendarData();
      loadMetrics();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to update assessment dates');
      setShowDateChangeConfirm(false);
      if (pendingDateChange.revert) {
        pendingDateChange.revert();
      }
      setPendingDateChange(null);
    }
  };

  const handleCancelDateChange = () => {
    if (pendingDateChange?.revert) {
      pendingDateChange.revert();
    }
    setShowDateChangeConfirm(false);
    setPendingDateChange(null);
  };

  const handleExportCsv = async () => {
    setExporting(true);
    try {
      // The export endpoint takes one type and one status; pass them through only when the
      // multi-select has exactly one, otherwise export unfiltered on that dimension.
      const blob = await assessmentsApi.exportToCsv({
        applicationId: filters.applicationId || undefined,
        assessmentTypeId: filters.assessmentTypeIds.length === 1 ? filters.assessmentTypeIds[0] : undefined,
        status: filters.statuses.length === 1 ? filters.statuses[0] : undefined,
        name: filters.name || undefined,
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

  const columns: Column<Assessment>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      accessor: 'name',
      render: (assessment) => (
        <div>
          <div>{assessment.name}</div>
          {assessment.isPastDue && (
            <Badge variant="danger" size="sm">
              Past Due
            </Badge>
          )}
        </div>
      ),
    },
    {
      header: 'Application',
      sortKey: 'applicationName',
      render: (assessment) => (
        <span>
          {assessment.appId && <span className="eng-app-id">{assessment.appId}</span>}
          {assessment.applicationName || '-'}
        </span>
      ),
    },
    {
      header: 'Status',
      sortKey: 'status',
      accessor: 'status',
      render: (assessment) => {
        const custom = statusColors[assessment.status];
        return (
          <Badge variant={custom ? undefined : 'secondary'} customColor={custom}>
            {assessment.status.replace('_', ' ')}
          </Badge>
        );
      },
    },
    {
      header: 'Assessment Type',
      sortKey: 'assessmentTypeName',
      render: (assessment) => {
        const type = assessmentTypes.find((t) => t.id === assessment.assessmentTypeId);
        return <span>{type?.name || '-'}</span>;
      },
    },
    {
      header: 'Start Date',
      sortKey: 'startDate',
      accessor: 'startDate',
      render: (assessment) =>
        assessment.startDate ? new Date(assessment.startDate).toLocaleDateString() : '-',
    },
    {
      header: 'Planned End',
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
      header: 'Assessors',
      render: (assessment) => {
        const count = assessment.assessorIds?.length || 0;
        return <span>{count} assessor{count !== 1 ? 's' : ''}</span>;
      },
    },
    {
      header: 'Vulnerabilities',
      render: (assessment) => {
        const aVulns = pageVulnerabilities.filter(v => v.assessmentId === assessment.id);
        const critical = aVulns.filter(v => v.severity === 'CRITICAL').length;
        const high = aVulns.filter(v => v.severity === 'HIGH').length;
        const medium = aVulns.filter(v => v.severity === 'MEDIUM').length;
        const low = aVulns.filter(v => v.severity === 'LOW').length;
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
      header: 'Actions',
      render: (assessment) => (
        <ActionButtons>
          {permissions.canViewAssessments && (
            <IconButton
              icon={Eye}
              onClick={() => handleViewClick(assessment)}
              title="View assessment"
              variant="info"
            />
          )}
          <IconButton
            icon={Edit2}
            onClick={() => handleEditClick(assessment)}
            title="Edit scheduling"
            variant="edit"
          />
          <IconButton
            icon={Trash2}
            onClick={() => handleDeleteClick(assessment)}
            title="Delete"
            variant="delete"
          />
        </ActionButtons>
      ),
    },
  ];

  return (
    <Page className="engagements-page">
      <div className="eng-toolbar">
        <div className="eng-stats-bar">
          <button
            className={`eng-stat${filters.statuses.length === 0 && !filters.pastDue ? ' active' : ''}`}
            onClick={() => handleStatChipClick('total')}
          >
            <span className="eng-stat-dot" style={{ background: '#94a3b8' }} />
            Total <strong>{metrics?.totalCount ?? 0}</strong>
          </button>
          <button
            className={`eng-stat${filters.pastDue ? ' active' : ''}`}
            onClick={() => handleStatChipClick('pastDue')}
          >
            <span className="eng-stat-dot" style={{ background: '#ef4444' }} />
            Past Due <strong>{metrics?.pastDueCount ?? 0}</strong>
          </button>
          {wfStatuses.map(status => {
            const color = statusColors[status] || '#94a3b8';
            const count = metrics?.statusCounts?.[status] ?? 0;
            return (
              <button
                key={status}
                className={`eng-stat${filters.statuses.includes(status) ? ' active' : ''}`}
                onClick={() => handleStatChipClick(status)}
              >
                <span className="eng-stat-dot" style={{ background: color }} />
                {status} <strong>{count}</strong>
              </button>
            );
          })}
        </div>
        <div className="eng-toolbar-actions">
          <Button variant="secondary" onClick={() => setView(view === 'calendar' ? 'list' : 'calendar')}>
            {view === 'calendar' ? <List size={18} /> : <Calendar size={18} />}
            {view === 'calendar' ? 'List View' : 'Calendar View'}
          </Button>
          <Button variant="primary" onClick={handleCreateClick}>
            <Plus size={18} /> Create Assessment
          </Button>
        </div>
      </div>

      {error && (
        <div className="alert alert-danger alert-dismissible fade show" role="alert">
          {error}
          <button type="button" className="btn-close" onClick={() => setError('')}></button>
        </div>
      )}

      {view === 'calendar' ? (
        <AssessmentCalendar
          assessments={assessments.filter(a => {
            if (filters.pastDue && !a.isPastDue) return false;
            if (filters.statuses.length > 0) return filters.statuses.includes(a.status);
            return true;
          })}
          statusColors={statusColors}
          loading={loading}
          onEventClick={handleEventClick}
          onEventDrop={handleEventDrop}
          onEventResize={handleEventDrop}
        />
      ) : (
        <>
          <DataTable
            columns={columns}
            data={assessments}
            loading={loading}
            pagination={pagination}
            onPageChange={(page) => setPagination({ ...pagination, page })}
            onPageSizeChange={(pageSize) => setPagination({ ...pagination, pageSize, page: 0 })}
            initialSearch={filters.name}
            onSearchChange={(q) => applyInline({ name: q })}
            searchPlaceholder="Search by assessment or application"
            idAccessor="id"
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
                  <FormLabel>Planned End</FormLabel>
                  <div className="filter-field-range">
                    <Input type="date" value={draft.endDateFrom}
                      onChange={(e) => setDraft({ ...draft, endDateFrom: e.target.value })} />
                    <Input type="date" value={draft.endDateTo}
                      onChange={(e) => setDraft({ ...draft, endDateTo: e.target.value })} />
                  </div>
                </div>
                <div className="filter-field">
                  <FormLabel>Completed</FormLabel>
                  <div className="filter-field-range">
                    <Input type="date" value={draft.completedDateFrom}
                      onChange={(e) => setDraft({ ...draft, completedDateFrom: e.target.value })} />
                    <Input type="date" value={draft.completedDateTo}
                      onChange={(e) => setDraft({ ...draft, completedDateTo: e.target.value })} />
                  </div>
                </div>
              </>
            }
            headerChildren={
              <div className="ss-filter-bar">
                <SearchableSelect
                  value={filters.applicationId}
                  onChange={(v) => applyInline({ applicationId: v })}
                  options={appOptions}
                  placeholder="All Applications"
                />
                <MultiSelect
                  selected={filters.assessmentTypeIds}
                  onChange={(vals) => applyInline({ assessmentTypeIds: vals })}
                  options={typeOptions}
                  placeholder="All Types"
                  searchable={false}
                />
                <Button variant="secondary" icon={Download} onClick={handleExportCsv} disabled={exporting}>
                  {exporting ? 'Exporting…' : 'Export CSV'}
                </Button>
              </div>
            }
            sort={sort}
            onSortChange={(next) => {
              // Re-sorting reshuffles the whole result set, so the current page
              // number is meaningless afterwards — go back to the first page.
              setSort(next);
              setPagination((prev) => ({ ...prev, page: 0 }));
            }}
          />
        </>
      )}

      {/* Delete Assessment Confirmation Dialog */}
      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleConfirmDelete}
        title="Delete Assessment"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This will permanently delete the assessment and all associated vulnerabilities. This action cannot be undone.`}
        confirmText="Delete"
        cancelText="Cancel"
        variant="danger"
        isLoading={deleting}
      />

      {/* Date Change Confirmation Dialog */}
      <ConfirmDialog
        isOpen={showDateChangeConfirm}
        onClose={handleCancelDateChange}
        onConfirm={handleConfirmDateChange}
        title="Confirm Date Change"
        message={
          pendingDateChange
            ? `Do you want to save the new dates for "${pendingDateChange.assessmentName}"?\n\nNew Start: ${pendingDateChange.newStart.toLocaleDateString()}\nNew End: ${pendingDateChange.newEnd.toLocaleDateString()}`
            : ''
        }
        confirmText="Save Changes"
        cancelText="Cancel"
        variant="info"
      />
    </Page>
  );
}
