import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Edit2, Trash2, Plus, Calendar, List, Download } from 'lucide-react';
import { assessmentsApi, applicationsApi, assessmentTypesApi, workflowConfigApi, vulnerabilitiesApi } from '../api';
import type {
  Assessment,
  AssessmentMetrics,
  Application,
  AssessmentType,
  Vulnerability,
} from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import SearchableSelect, { SelectOption } from '../components/SearchableSelect';
import { Button, Badge, ConfirmDialog, IconButton, ActionButtons } from '../components';
import AssessmentCalendar from '../components/AssessmentCalendar';
import Page from '../components/Page';
import './Engagements.css';

/** A calendar Date as the zone-less ISO datetime the API uses for these date-only fields. */
const toApiDate = (dt: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}T00:00:00`;
};

const STATUS_COLORS: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'secondary'> = {
  DRAFT: 'secondary',
  IN_PROGRESS: 'info',
  ON_HOLD: 'warning',
  PENDING_REVIEW: 'info',
  COMPLETED: 'success',
  APPROVED: 'success',
  ARCHIVED: 'secondary',
};

export default function Engagements() {
  const navigate = useNavigate();
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

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [sort, setSort] = useState<SortState | null>(null);

  const [filters, setFilters] = useState({
    status: '',
    applicationId: '',
    assessmentTypeId: '',
    name: '',
    pastDue: false,
  });
  const [activeStatChip, setActiveStatChip] = useState<string | null>(null);

  // Inline filter options + live-apply. Status is not here — the stat pills own status filtering.
  const appOptions: SelectOption[] = useMemo(
    () => applications.map((a) => ({ value: a.id, label: a.name })), [applications]);
  const typeOptions: SelectOption[] = useMemo(
    () => assessmentTypes.map((t) => ({ value: t.id, label: t.name })), [assessmentTypes]);
  const applyInline = (patch: Partial<typeof filters>) => {
    setFilters((prev) => ({ ...prev, ...patch }));
    setPagination((prev) => ({ ...prev, page: 0 }));
  };

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
      const response = await assessmentsApi.getAll(
        pagination.page,
        pagination.pageSize,
        filters.applicationId || undefined,
        undefined,
        filters.assessmentTypeId || undefined,
        undefined,
        filters.status || undefined,
        filters.name || undefined,
        sortParam(sort) ?? 'createdAt,desc',
        filters.pastDue || undefined
      );

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

  const handleStatChipClick = (chip: string) => {
    if (activeStatChip === chip) {
      // Second click — reset
      setActiveStatChip(null);
      setFilters(prev => ({ ...prev, status: '', pastDue: false }));
    } else {
      setActiveStatChip(chip);
      if (chip === 'pastDue') {
        setFilters(prev => ({ ...prev, status: '', pastDue: true }));
      } else if (chip === 'total') {
        setFilters(prev => ({ ...prev, status: '', pastDue: false }));
      } else {
        setFilters(prev => ({ ...prev, status: chip, pastDue: false }));
      }
      setPagination(prev => ({ ...prev, page: 0 }));
    }
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
      const blob = await assessmentsApi.exportToCsv({
        applicationId: filters.applicationId || undefined,
        assessmentTypeId: filters.assessmentTypeId || undefined,
        status: filters.status || undefined,
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
          <IconButton
            icon={Edit2}
            onClick={() => handleEditClick(assessment)}
            title="Edit"
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
            className={`eng-stat${activeStatChip === 'total' || activeStatChip === null ? ' active' : ''}`}
            onClick={() => handleStatChipClick('total')}
          >
            <span className="eng-stat-dot" style={{ background: '#94a3b8' }} />
            Total <strong>{metrics?.totalCount ?? 0}</strong>
          </button>
          <button
            className={`eng-stat${activeStatChip === 'pastDue' ? ' active' : ''}`}
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
                className={`eng-stat${activeStatChip === status ? ' active' : ''}`}
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
            if (filters.pastDue) return !!a.isPastDue;
            if (filters.status) return a.status === filters.status;
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
            onSearchChange={(q) => applyInline({ name: q })}
            searchPlaceholder="Search assessments"
            idAccessor="id"
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
