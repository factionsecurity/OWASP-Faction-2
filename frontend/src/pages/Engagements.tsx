import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Edit2, Trash2, Plus, Calendar, List, Download, Upload, Eye, Users } from 'lucide-react';
import { assessmentsApi, applicationsApi, assessmentTypesApi, availabilityApi, teamsApi, usersApi, vulnerabilitiesApi } from '../api';
import type {
  Assessment,
  AssessmentMetrics,
  Application,
  AssessmentType,
  Team,
  TimelineSpan,
  Unavailability,
  User,
  Vulnerability,
  HolidayEntry,
  ScheduleBlock,
} from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam, FilterChip } from '../components/DataTable';
import SearchableSelect, { MultiSelect, SelectOption } from '../components/SearchableSelect';
import { Button, Badge, ConfirmDialog, IconButton, ActionButtons, FormLabel, Input } from '../components';
import { findUnavailability, UnavailabilityList } from '../components/UnavailabilityWarning';
import AssessmentCalendar from '../components/AssessmentCalendar';
import { AssessorTimeline } from '@enterprise';
import { PaidFeature } from '../components/PaidFeature';
import DiamondIcon from '../components/DiamondIcon';
import { useEdition } from '../context/EditionContext';
import AssessmentImportModal from '../components/AssessmentImportModal';
import Page from '../components/Page';
import { usePersistedState } from '../hooks/usePersistedState';
import { usePermissions } from '../utils/permissions';
import { useWorkflowsContext } from '../context/WorkflowsContext';
import { colorFor, isAmbiguous, mergedStatusNames, statusLabel, workflowsForSelectedTypes } from '../utils/workflowLookup';
import './Engagements.css';

/** A calendar Date as the zone-less ISO datetime the API uses for these date-only fields. */
const toApiDate = (dt: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}T00:00:00`;
};

/** The calendar views' initial fetch: last month through the end of next month. */
const defaultCalendarWindow = (): { start: string; end: string } => {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return {
    start: iso(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
    end: iso(new Date(now.getFullYear(), now.getMonth() + 2, 0)),
  };
};

// localStorage key for the list view's saved search, filters, sort and paging.
const TABLE_KEY = 'scheduling';

export default function Engagements() {
  const navigate = useNavigate();
  // The View action opens the assessment detail page, which sits behind its own permission —
  // scheduling access alone does not imply it.
  const { permissions } = usePermissions();
  const { workflows } = useWorkflowsContext();
  // The By User timeline is paid; the open source build keeps the button and shows why it's locked.
  const { hasFeature } = useEdition();
  const hasTeamScheduling = hasFeature('team_scheduling');
  const [assessments, setAssessments] = useState<Assessment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [view, setView] = useState<'calendar' | 'list' | 'people'>(() => {
    const saved = localStorage.getItem('engagements-view');
    return (saved === 'list' || saved === 'calendar' || saved === 'people') ? saved : 'list';
  });
  // The By User timeline's range and team filter survive leaving the page, like the view itself.
  const [timelineSpan, setTimelineSpan] = usePersistedState<TimelineSpan>('engagements-timeline', 'span', 'month');
  const [timelineTeamId, setTimelineTeamId] = usePersistedState<string>('engagements-timeline', 'teamId', '');
  const [timelineActiveOnly, setTimelineActiveOnly] = usePersistedState<boolean>('engagements-timeline', 'activeAssessmentsOnly', true);
  // The timeline's rows: the user directory and teams, when the viewer may read them.
  const [timelineUsers, setTimelineUsers] = useState<User[] | null>(null);
  const [timelineTeams, setTimelineTeams] = useState<Team[]>([]);
  // The date window the calendar views have fetched, as inclusive YYYY-MM-DD bounds. Navigating
  // past it widens it and refetches; kept in a ref so reloads never shrink it back.
  const calendarWindow = useRef(defaultCalendarWindow());
  // The calendar's currently VISIBLE range (unpadded), as last reported by onRangeChange. Unlike
  // calendarWindow above, this never accumulates — it just tracks what's on screen right now, so
  // availability overlays (padded a month either side of it) stay well under the API's 366-day
  // range cap no matter how far the cumulative assessment window has grown.
  const visibleRange = useRef(defaultCalendarWindow());
  const [metrics, setMetrics] = useState<AssessmentMetrics | null>(null);
  // Unavailability bands for the By User timeline, fetched for the visible range (padded, see
  // above). Guarded by a request counter: the visible range can change quietly while an earlier
  // fetch is still in flight, and that stale response must not clobber a newer one.
  const [timelineUnavailability, setTimelineUnavailability] = useState<Unavailability[]>([]);
  const unavailabilityRequestId = useRef(0);
  // The main Calendar view's org-wide overlay: default-region holidays and scheduling blocks
  // (never personal time off — that's per-user and stays on the By User timeline only).
  const [calendarOrgHolidays, setCalendarOrgHolidays] = useState<HolidayEntry[]>([]);
  const [calendarBlocks, setCalendarBlocks] = useState<ScheduleBlock[]>([]);

  // Reference data
  const [applications, setApplications] = useState<Application[]>([]);
  const [assessmentTypes, setAssessmentTypes] = useState<AssessmentType[]>([]);

  // Archived workflows still colour their own past assessments elsewhere, but they don't
  // contribute new stat pills or filter options.
  const activeWorkflows = useMemo(() => workflows.filter((w) => !w.archived), [workflows]);

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

  // The workflows the stat pills describe: those the selected types run on, or every active workflow
  // when no type is selected. Names, counts, dot colours and collisions are all judged within this
  // set, so a pill counting only PCI assessments never wears another workflow's colour.
  const pillWorkflows = useMemo(
    () => workflowsForSelectedTypes(workflows, assessmentTypes, filters.assessmentTypeIds, activeWorkflows),
    [workflows, assessmentTypes, filters.assessmentTypeIds, activeWorkflows]);

  // One pill per distinct status name across those workflows — the pills are this page's status
  // filter. A name defined in several of them collapses to one pill, and the server's statusCounts
  // combine assessments by name the same way.
  const mergedNames = useMemo(() => mergedStatusNames(pillWorkflows), [pillWorkflows]);

  // Deselect a status pill the selected types no longer offer, so the calendar and list are never
  // filtered by a pill that has disappeared. Waits for types and workflows: a persisted selection is
  // restored before either loads, and clearing it in that window would wipe a saved filter on reload.
  useEffect(() => {
    if (assessmentTypes.length === 0 || workflows.length === 0) return;
    const offered = new Set(mergedNames);
    if (filters.statuses.every((s) => offered.has(s))) return;
    applyInline({ statuses: filters.statuses.filter((s) => offered.has(s)) });
  }, [mergedNames, filters.statuses, assessmentTypes.length, workflows.length]);

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
  const [showImport, setShowImport] = useState(false);

  const [showDateChangeConfirm, setShowDateChangeConfirm] = useState(false);
  const [pendingDateChange, setPendingDateChange] = useState<{
    assessmentId: string;
    assessmentName: string;
    newStart: Date;
    newEnd: Date;
    revert: () => void;
    unavailable: Unavailability[];
  } | null>(null);

  useEffect(() => {
    loadReferenceData();
  }, []);

  // Counts are scoped to the toolbar's type filter, so reload them whenever the selection changes.
  // This also covers the first load, which is why the mount effect above no longer fetches them.
  // Keyed on the joined ids rather than the array, so only a real change in selection refetches.
  const selectedTypesKey = filters.assessmentTypeIds.join(',');
  useEffect(() => {
    loadMetrics();
  }, [selectedTypesKey]);

  // Persist view preference
  useEffect(() => {
    localStorage.setItem('engagements-view', view);
  }, [view]);

  useEffect(() => {
    if (view === 'list') {
      loadAssessments();
    } else {
      loadCalendarData();
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
    if (view === 'list') {
      loadAssessments();
    } else {
      loadCalendarData();
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

  /** An inclusive YYYY-MM-DD range, padded a whole calendar month either side. */
  const padMonths = (start: string, end: string): { start: string; end: string } => {
    const pad = (n: number) => String(n).padStart(2, '0');
    const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const [sy, sm] = start.split('-').map(Number);
    const [ey, em] = end.split('-').map(Number);
    return {
      start: iso(new Date(sy, sm - 2, 1)),
      end: iso(new Date(ey, em + 1, 0)),
    };
  };

  /**
   * Availability overlays — org holidays, scheduling blocks, and By User unavailability — fetched
   * for the currently visible range (padded a month either side), not the cumulative
   * calendarWindow. calendarWindow only grows, and once it passes 366 days the /org-calendar and
   * /calendar endpoints reject the request outright, silently blanking every overlay. The visible
   * range stays roughly one screen wide no matter how far the user has paginated, so the padded
   * range stays well under that cap.
   */
  const loadAvailabilityOverlays = () => {
    const { start, end } = padMonths(visibleRange.current.start, visibleRange.current.end);

    // Unavailability bands are only needed for the By User view, and only when the viewer can
    // read users (the calendar endpoint requires users:read). A request counter guards against
    // a stale response landing after a newer one — the visible range can change quietly while an
    // earlier fetch is still in flight.
    const requestId = ++unavailabilityRequestId.current;
    if (view === 'people' && hasTeamScheduling && permissions.canViewUsers) {
      availabilityApi.calendar(start, end)
        .then((r) => { if (requestId === unavailabilityRequestId.current) setTimelineUnavailability(r.data ?? []); })
        .catch(() => { if (requestId === unavailabilityRequestId.current) setTimelineUnavailability([]); });
    } else {
      setTimelineUnavailability([]);
    }

    // The main Calendar view's org-wide overlay. Unlike /calendar (users:read-gated), both
    // /org-calendar and /blocks are @AuthenticatedOnly, so no permissions.canViewUsers check.
    // /blocks itself takes no date range (it always returns every not-yet-ended block), so it
    // never risks the 366-day cap and doesn't need the padded range.
    if (view === 'calendar' && hasTeamScheduling) {
      availabilityApi.orgCalendar(start, end)
        .then((r) => { if (requestId === unavailabilityRequestId.current) setCalendarOrgHolidays(r.data ?? []); })
        .catch(() => { if (requestId === unavailabilityRequestId.current) setCalendarOrgHolidays([]); });
      availabilityApi.blocks()
        .then((r) => { if (requestId === unavailabilityRequestId.current) setCalendarBlocks(r.data ?? []); })
        .catch(() => { if (requestId === unavailabilityRequestId.current) setCalendarBlocks([]); });
    } else {
      setCalendarOrgHolidays([]);
      setCalendarBlocks([]);
    }
  };

  /** `quiet` refetches in place, without the spinner that would reset the calendar's position. */
  const loadCalendarData = async (quiet = false) => {
    if (!quiet) setLoading(true);
    setError('');
    try {
      const { start, end } = calendarWindow.current;
      const response = await assessmentsApi.getCalendarView(start, end, 0, 1000);

      if (response.success && response.data) {
        setAssessments(response.data);
      }

      loadAvailabilityOverlays();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load calendar data');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Widen the fetched assessment window to cover a newly visible range, a month either side, and
   * refetch — and always record the visible range and refresh the (separately windowed)
   * availability overlays, even when the assessment window itself didn't need to grow.
   */
  const ensureCalendarRange = (start: string, end: string) => {
    visibleRange.current = { start, end };
    loadAvailabilityOverlays();

    const current = calendarWindow.current;
    if (start >= current.start && end <= current.end) return;
    const pad = (n: number) => String(n).padStart(2, '0');
    const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const [sy, sm] = start.split('-').map(Number);
    const [ey, em] = end.split('-').map(Number);
    calendarWindow.current = {
      start: start < current.start ? iso(new Date(sy, sm - 2, 1)) : current.start,
      end: end > current.end ? iso(new Date(ey, em + 1, 0)) : current.end,
    };
    loadCalendarData(true);
  };

  // The By User rows and team filter read the user directory, so load them only for viewers who
  // may browse it; everyone else gets rows built from the assessments' own assessors.
  useEffect(() => {
    if (view !== 'people' || !hasTeamScheduling || !permissions.canViewUsers || timelineUsers) return;
    Promise.all([
      usersApi.getAll(0, 1000, '', '', { type: 'INTERNAL' }).catch(() => null),
      teamsApi.getAll(0, 1000).catch(() => null),
    ]).then(([usersResponse, teamsResponse]) => {
      if (usersResponse?.success && usersResponse.data) setTimelineUsers(usersResponse.data);
      if (teamsResponse?.success && teamsResponse.data) setTimelineTeams(teamsResponse.data);
    });
  }, [view]);

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
  };

  const loadMetrics = async () => {
    try {
      // Counts follow the toolbar's type filter, so each pill matches the calendar and list below it.
      const response = await assessmentsApi.getMetrics(undefined, filters.assessmentTypeIds);
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

    const unavailable = await findUnavailability(
      assessmentId, assessment.assessorIds ?? [], toApiDate(newStart), toApiDate(newEnd));

    // Show confirmation dialog
    setPendingDateChange({
      assessmentId,
      assessmentName: assessment.name,
      newStart,
      newEnd,
      revert,
      unavailable,
    });
    setShowDateChangeConfirm(true);
  };

  /** Names from the assessment itself: Engagements has no user directory in calendar view. */
  const assessorNamesById = (assessmentId: string): Record<string, string> => {
    const a = assessments.find((x) => x.id === assessmentId);
    return Object.fromEntries((a?.assessorIds ?? []).map((id, i) => [id, a?.assessorNames?.[i] ?? 'Unknown user']));
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
        const custom = colorFor(workflows, assessment.workflowId, assessment.status);
        return (
          <Badge variant={custom ? undefined : 'secondary'} customColor={custom}>
            {statusLabel(workflows, assessment.workflowId, assessment.status)}
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

  // The calendar fetch takes only a date range, so the toolbar's type filter applies here
  // alongside status and past-due, keeping both calendar views in step with the pills above them.
  const calendarAssessments = assessments.filter(a => {
    if (filters.assessmentTypeIds.length > 0 && !filters.assessmentTypeIds.includes(a.assessmentTypeId)) return false;
    if (filters.pastDue && !a.isPastDue) return false;
    if (filters.statuses.length > 0) return filters.statuses.includes(a.status);
    return true;
  });

  return (
    <Page className="engagements-page">
      <div className="eng-toolbar">
        {/* Sits beside the pills it narrows and applies in both views — the list's own filter bar
            is list-only, so a type filter there could never reach the calendar or the counts. */}
        <div className="eng-type-filter">
          <MultiSelect
            selected={filters.assessmentTypeIds}
            onChange={(vals) => applyInline({ assessmentTypeIds: vals })}
            options={typeOptions}
            placeholder="All Types"
            searchable={false}
          />
        </div>
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
          {mergedNames.map(status => {
            // A pill aggregates this name's count across every workflow the pills describe
            // (pillWorkflows) that defines it. When those workflows disagree on colour
            // (isAmbiguous), painting the dot from just one of them would assert an ownership
            // the pill doesn't have — and silently, unlike the legend/labels, which make such
            // collisions visible. So an ambiguous name gets the same neutral dot as Total/Past
            // Due instead of a pick.
            const color = isAmbiguous(pillWorkflows, status)
              ? '#94a3b8'
              : pillWorkflows.find((w) => w.statusColors?.[status])?.statusColors?.[status] ?? '#94a3b8';
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
          <div className="eng-view-toggle" role="group" aria-label="View">
            <button className={view === 'list' ? 'active' : ''} onClick={() => setView('list')}>
              <List size={16} /> List View
            </button>
            <button className={view === 'calendar' ? 'active' : ''} onClick={() => setView('calendar')}>
              <Calendar size={16} /> Calendar View
            </button>
            <button className={view === 'people' ? 'active' : ''} onClick={() => setView('people')}>
              <Users size={16} /> By User
              {!hasTeamScheduling && (
                <span className="paid-badge" title="Not in this edition"><DiamondIcon className="paid-badge__diamond" /></span>
              )}
            </button>
          </div>
          {permissions.canImportAssessments && (
            <Button variant="secondary" onClick={() => setShowImport(true)}>
              <Upload size={18} /> Import CSV
            </Button>
          )}
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
          assessments={calendarAssessments}
          workflows={workflows}
          loading={loading}
          onEventClick={handleEventClick}
          onEventDrop={handleEventDrop}
          onEventResize={handleEventDrop}
          onRangeChange={ensureCalendarRange}
          orgHolidays={calendarOrgHolidays}
          blocks={calendarBlocks}
        />
      ) : view === 'people' ? (
        <PaidFeature
          feature="team_scheduling"
          title="By User Timeline"
          description="One row per person with their assessments laid across the days, to see who is booked and who is free."
        >
        <AssessorTimeline
          assessments={calendarAssessments}
          workflows={workflows}
          users={timelineUsers}
          teams={timelineTeams}
          teamId={timelineTeamId}
          onTeamChange={setTimelineTeamId}
          activeOnly={timelineActiveOnly}
          onActiveOnlyChange={setTimelineActiveOnly}
          span={timelineSpan}
          onSpanChange={setTimelineSpan}
          loading={loading}
          onEventClick={handleEventClick}
          onRangeChange={ensureCalendarRange}
          unavailability={timelineUnavailability}
        />
        </PaidFeature>
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
        title={pendingDateChange?.unavailable.length ? 'Assessors Unavailable' : 'Confirm Date Change'}
        message={
          pendingDateChange ? (
            <>
              {`Save the new dates for "${pendingDateChange.assessmentName}"?\nNew Start: ${pendingDateChange.newStart.toLocaleDateString()}\nNew End: ${pendingDateChange.newEnd.toLocaleDateString()}`}
              {pendingDateChange.unavailable.length > 0 && (
                <UnavailabilityList entries={pendingDateChange.unavailable} names={assessorNamesById(pendingDateChange.assessmentId)} />
              )}
            </>
          ) : ''
        }
        confirmText={pendingDateChange?.unavailable.length ? 'Save Anyway' : 'Save Changes'}
        cancelText="Cancel"
        variant={pendingDateChange?.unavailable.length ? 'warning' : 'info'}
      />

      <AssessmentImportModal
        isOpen={showImport}
        onClose={() => setShowImport(false)}
        onImported={() => { loadData(); loadMetrics(); }}
      />
    </Page>
  );
}
