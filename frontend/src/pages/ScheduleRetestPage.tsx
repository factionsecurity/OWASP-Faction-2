import { useEffect, useState } from 'react';
import { Eye } from 'lucide-react';
import { SeverityBadge, IconButton } from '../components';
import VulnerabilityDetailDrawer from '../components/VulnerabilityDetailDrawer';
import { useNavigate, useLocation } from 'react-router-dom';
import { usePageTitle } from '../context/PageTitleContext';
import { retestApi, usersApi, assessmentsApi, vulnerabilitiesApi } from '../api';
import type { Assessment, Retest, User, Vulnerability } from '../types';
import { FormLabel, Input, DualListBox } from '../components';
import RichTextEditor from '../components/RichTextEditor';
import AssessmentCalendar from '../components/AssessmentCalendar';
import Page from '../components/Page';
import './ScheduleRetestPage.css';

/** Mirrors RetestService.OPEN_STATUSES — a finding may carry only one retest in these. */
const OPEN_RETEST_STATUSES = ['REQUESTED', 'SCHEDULED', 'IN_PROGRESS'];

const RETEST_STATUS_COLORS: Record<string, string> = {
  RETEST_SCHEDULED: '#0891b2',
  RETEST_IN_PROGRESS: '#7c3aed',
  RETEST_PASSED: '#16a34a',
  RETEST_FAILED: '#dc2626',
  RETEST_CANCELLED: '#6b7280',
};

/**
 * A yyyy-mm-dd input value as the zone-less ISO datetime the API uses for these fields (they
 * are `LocalDateTime` server-side). Deliberately not `new Date(value).toISOString()`: that
 * reads the bare date as UTC midnight and stamps a `Z`, so the value lands a day earlier for
 * anyone west of Greenwich. A scheduled date has no time of day and no timezone.
 */
const toApiDate = (isoDate: string): string => `${isoDate}T00:00:00`;

/** The calendar date part of an API value, dropping the always-midnight time. */
const dateOnly = (value?: string): string => (value ? value.split('T')[0] : '');

/** Read a Date the calendar hands back as its local calendar date, never via UTC. */
const localDate = (dt: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`;
};

function retestToCalendarItem(r: Retest): Assessment {
  return {
    id: r.id,
    name: `[Retest] ${r.vulnerabilityName || r.vulnerabilityId}`,
    applicationId: r.applicationId || '',
    assessmentTypeId: '',
    organizationId: '',
    reportTemplateId: '',
    reportTemplateVersion: 0,
    templateName: '',
    fieldDefinitions: [],
    fieldValues: {},
    status: `RETEST_${r.status}` as string,
    startDate: r.scheduledStartDate,
    plannedEndDate: r.scheduledEndDate,
    assessorIds: r.assignedAssessorIds,
  } as unknown as Assessment;
}

interface LocationState {
  vulnerabilityIds: string[];
  assessmentId: string;
  vulnerabilities?: Vulnerability[];
  // Edit mode
  retestId?: string;
  existingRetest?: Retest;
}

export default function ScheduleRetestPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const state = (location.state || {}) as LocationState;

  const {
    vulnerabilityIds = [],
    assessmentId = '',
    vulnerabilities: passedVulns = [],
    retestId,
    existingRetest,
  } = state;

  const isEditMode = !!retestId;

  const { setBreadcrumbs } = usePageTitle();
  useEffect(() => {
    setBreadcrumbs([
      { label: 'Retests', to: '/retests' },
      { label: isEditMode ? 'Edit Retest' : 'Schedule Retest' },
    ]);
    return () => setBreadcrumbs(null);
  }, [isEditMode]);

  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [assessorIds, setAssessorIds] = useState<string[]>([]);
  const [scope, setScope] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const [previewVuln, setPreviewVuln] = useState<Vulnerability | null>(null);
  /** Retests already open on the selected findings — one per finding is the server's rule. */
  const [blockingRetests, setBlockingRetests] = useState<Retest[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [teamAssessments, setTeamAssessments] = useState<Assessment[]>([]);
  const [calendarRetests, setCalendarRetests] = useState<Retest[]>([]);
  const [conflicts, setConflicts] = useState<Assessment[]>([]);

  useEffect(() => {
    // Pre-populate form when editing an existing retest
    if (existingRetest) {
      const sd = dateOnly(existingRetest.scheduledStartDate);
      const ed = dateOnly(existingRetest.scheduledEndDate);
      setStartDate(sd);
      setEndDate(ed);
      setAssessorIds(existingRetest.assignedAssessorIds || []);
      if (existingRetest.scope) setScope(existingRetest.scope);
    }
    loadData();
  }, []);

  useEffect(() => {
    if (assessorIds.length > 0 && startDate && endDate) {
      checkConflicts();
    } else {
      setConflicts([]);
    }
  }, [assessorIds, startDate, endDate]);

  const loadData = async () => {
    const calStart = new Date(Date.now() - 90 * 86400000).toISOString().split('T')[0];
    const calEnd = new Date(Date.now() + 180 * 86400000).toISOString().split('T')[0];
    try {
      const [usersRes, asmtRes, teamRes, retestsRes, openRetestsRes] = await Promise.all([
        usersApi.getAll(0, 500).catch(() => ({ data: [] as User[], success: false })),
        assessmentId ? assessmentsApi.getById(assessmentId).catch(() => null) : Promise.resolve(null),
        assessmentsApi.getCalendarView(calStart, calEnd).catch(() => ({ data: [] as Assessment[], success: false })),
        retestApi.getCalendar(calStart, calEnd).catch(() => ({ data: [] as Retest[], success: false })),
        assessmentId
          ? retestApi.getByAssessment(assessmentId).catch(() => ({ data: [] as Retest[], success: false }))
          : Promise.resolve(null),
      ]);

      if (usersRes.success && usersRes.data) {
        setUsers(Array.isArray(usersRes.data) ? usersRes.data : []);
      }

      if (asmtRes && asmtRes.success && asmtRes.data) {
        const a = asmtRes.data;
        setAssessment(a);
        if (a.scope) setScope(a.scope);
      }

      if (teamRes.success && teamRes.data) {
        setTeamAssessments(Array.isArray(teamRes.data) ? teamRes.data : []);
      }

      if (retestsRes.success && retestsRes.data) {
        setCalendarRetests(Array.isArray(retestsRes.data) ? retestsRes.data : []);
      }

      if (openRetestsRes && openRetestsRes.success && openRetestsRes.data) {
        applyBlockingRetests(openRetestsRes.data);
      }
    } catch {
      // ignore
    }
  };

  /**
   * The server allows one live retest per finding. Catching that here keeps a multi-vulnerability
   * selection from failing half-way through, and names the retest already in the way.
   */
  const applyBlockingRetests = (assessmentRetests: Retest[]) => {
    const selected = new Set(vulnerabilityIds);
    setBlockingRetests(assessmentRetests.filter(r =>
      selected.has(r.vulnerabilityId)
      && r.id !== retestId
      && OPEN_RETEST_STATUSES.includes(r.status)));
  };

  const checkConflicts = async () => {
    if (!startDate || !endDate || assessorIds.length === 0) return;
    try {
      const res = await assessmentsApi.checkConflicts(
        assessmentId || null,
        assessorIds,
        toApiDate(startDate),
        toApiDate(endDate)
      );
      if (res.success && res.data) setConflicts(res.data);
    } catch {
      setConflicts([]);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!startDate || !endDate) {
      setError('Please select start and end dates.');
      return;
    }
    // Mirrors the server rule: a scheduled retest needs someone to carry it out. (Requesting a
    // retest, which app owners do from the vulnerability drawer, still needs neither.)
    if (assessorIds.length === 0) {
      setError('Please assign at least one assessor before scheduling.');
      return;
    }

    if (!isEditMode && blockingRetests.length > 0) {
      setError('One of the selected findings already has an open retest. Reschedule or cancel it '
        + 'instead of creating another.');
      return;
    }

    setSubmitting(true);
    setError('');

    try {
      if (isEditMode && retestId) {
        await retestApi.update(retestId, {
          scheduledStartDate: toApiDate(startDate),
          scheduledEndDate: toApiDate(endDate),
          assignedAssessorIds: assessorIds,
          scope: scope || undefined,
        });
        navigate(-1);
      } else {
        if (vulnerabilityIds.length === 0) {
          setError('No vulnerabilities selected for retest.');
          setSubmitting(false);
          return;
        }
        await Promise.all(
          vulnerabilityIds.map(vulnId =>
            retestApi.create(assessmentId, {
              vulnerabilityId: vulnId,
              scheduledStartDate: toApiDate(startDate),
              scheduledEndDate: toApiDate(endDate),
              assignedAssessorIds: assessorIds,
              scope: scope || undefined,
            })
          )
        );
        navigate('/retests');
      }
    } catch (err: any) {
      // The server enforces one open retest per finding; show what it said rather than a
      // generic failure, since the fix is to go and reschedule the existing one.
      setError(err?.response?.data?.message
        || (isEditMode ? 'Failed to update retest. Please try again.' : 'Failed to schedule retest(s). Please try again.'));
      // A multi-vulnerability schedule can fail part-way, leaving some findings retested and some
      // not. Re-read so a retry sees which ones are now spoken for.
      if (!isEditMode && assessmentId) {
        try {
          const res = await retestApi.getByAssessment(assessmentId);
          if (res.success && res.data) applyBlockingRetests(res.data);
        } catch { /* the banner already says what went wrong */ }
      }
    } finally {
      setSubmitting(false);
    }
  };

  // Build calendar preview assessment
  const calendarPreview: Assessment | null = startDate && endDate ? {
    id: 'retest-preview',
    name: `Retest (${vulnerabilityIds.length} vuln${vulnerabilityIds.length !== 1 ? 's' : ''})`,
    applicationId: assessment?.applicationId || '',
    assessmentTypeId: '',
    organizationId: '',
    reportTemplateId: '',
    reportTemplateVersion: 0,
    templateName: '',
    fieldDefinitions: [],
    fieldValues: {},
    status: 'SCHEDULED',
    startDate: toApiDate(startDate),
    plannedEndDate: toApiDate(endDate),
    assessorIds: assessorIds,
  } as unknown as Assessment : null;

  const internalUsers = users.filter(u => u.isInternal);

  return (
    <Page fill className="schedule-retest-page">
      <div className="schedule-retest-content">
        {assessment && (
          <div className="page-header">
            <p style={{ color: 'var(--text-muted)', margin: 0, fontSize: '0.9rem' }}>
              Assessment: {assessment.name}
            </p>
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="split-view">
            {/* Left: form */}
            <div className="form-panel">
              <div className="form-section">
                <h3 className="section-title">Vulnerabilities Being Retested</h3>
                <div className="vuln-retest-list">
                  {passedVulns.length > 0 ? (
                    passedVulns.map(v => (
                      <div key={v.id} className="vuln-retest-item">
                        <span className="vuln-retest-item-name">{v.name}</span>
                        {v.severity && <SeverityBadge severity={String(v.severity)} />}
                        <IconButton
                          icon={Eye}
                          variant="info"
                          title="Preview vulnerability"
                          onClick={async () => {
                            // Fetch the full vulnerability for the drawer — callers may pass a
                            // lightweight list row that lacks description/fields/comments.
                            try {
                              const res = await vulnerabilitiesApi.getById(v.assessmentId ?? assessmentId, v.id);
                              if (res.data) setPreviewVuln(res.data);
                            } catch { /* preview unavailable */ }
                          }}
                        />
                      </div>
                    ))
                  ) : (
                    <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>
                      {vulnerabilityIds.length} vulnerabilit{vulnerabilityIds.length !== 1 ? 'ies' : 'y'} selected.
                    </p>
                  )}
                </div>

                {!isEditMode && blockingRetests.length > 0 && (
                  <div className="conflict-warning" style={{ background: 'rgba(239,68,68,0.1)', borderColor: 'rgba(239,68,68,0.3)', color: '#ef4444' }}>
                    <strong>
                      {blockingRetests.length === 1
                        ? 'This finding already has an open retest:'
                        : 'These findings already have an open retest:'}
                    </strong>
                    <ul style={{ margin: '0.5rem 0 0 0', paddingLeft: '1.25rem' }}>
                      {blockingRetests.map(r => (
                        <li key={r.id}>
                          <button
                            type="button"
                            className="schedule-retest-existing-link"
                            onClick={() => navigate(`/retests/${r.id}`)}
                          >
                            {r.vulnerabilityName || r.vulnerabilityId}
                          </button>
                          {' — '}{r.status.replace('_', ' ').toLowerCase()}
                        </li>
                      ))}
                    </ul>
                    <p style={{ margin: '0.5rem 0 0 0' }}>
                      Reschedule or cancel the existing retest instead of creating another.
                    </p>
                  </div>
                )}
              </div>

              <div className="form-section">
                <h3 className="section-title">Schedule</h3>

                <div style={{ display: 'flex', gap: '1rem' }}>
                  <div className="form-group" style={{ flex: 1 }}>
                    <FormLabel required>Start Date</FormLabel>
                    <Input
                      type="date"
                      value={startDate}
                      onChange={e => setStartDate(e.target.value)}
                      required
                    />
                  </div>
                  <div className="form-group" style={{ flex: 1 }}>
                    <FormLabel required>End Date</FormLabel>
                    <Input
                      type="date"
                      value={endDate}
                      onChange={e => setEndDate(e.target.value)}
                      required
                    />
                  </div>
                </div>
              </div>

              <div className="form-section">
                <h3 className="section-title">Assessors</h3>
                <div className="form-group">
                  <DualListBox
                    availableItems={internalUsers.map(u => ({
                      id: u.id,
                      name: `${u.firstName} ${u.lastName}`,
                      email: u.email,
                    }))}
                    selectedIds={assessorIds}
                    onChange={setAssessorIds}
                    availableLabel="Available Assessors"
                    selectedLabel="Selected Assessors"
                  />
                </div>

                {conflicts.length > 0 && (
                  <div className="conflict-warning">
                    <strong>Scheduling Conflicts Detected:</strong>
                    <ul style={{ margin: '0.5rem 0 0 0', paddingLeft: '1.25rem' }}>
                      {conflicts.map(c => (
                        <li key={c.id}>
                          {c.name} ({c.status}) —{' '}
                          {c.startDate && new Date(c.startDate).toLocaleDateString()} to{' '}
                          {c.plannedEndDate && new Date(c.plannedEndDate).toLocaleDateString()}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>

              <div className="form-section">
                <h3 className="section-title">Scope</h3>
                <div className="form-group">
                  <RichTextEditor
                    value={scope}
                    onChange={setScope}
                    placeholder="Describe the scope of this retest…"
                  />
                </div>
              </div>

              {error && (
                <div className="conflict-warning" style={{ background: 'rgba(239,68,68,0.1)', borderColor: 'rgba(239,68,68,0.3)', color: '#ef4444' }}>
                  {error}
                </div>
              )}

              <button
                type="submit"
                className="schedule-retest-submit-btn"
                disabled={submitting || !startDate || !endDate || assessorIds.length === 0
                  || (!isEditMode && blockingRetests.length > 0)}
              >
                {submitting
                  ? (isEditMode ? 'Saving…' : 'Scheduling…')
                  : isEditMode
                    ? 'Save Changes'
                    : `Schedule Retest${vulnerabilityIds.length > 1 ? 's' : ''}`}
              </button>
            </div>

            {/* Right: calendar */}
            <div className="calendar-panel">
              <h3 className="section-title">Calendar</h3>
              <AssessmentCalendar
                key={startDate ? startDate.substring(0, 7) : 'default'}
                initialDate={startDate || undefined}
                assessments={[
                  ...(calendarPreview ? [calendarPreview] : []),
                  ...teamAssessments,
                  ...calendarRetests.map(retestToCalendarItem),
                ]}
                loading={false}
                onEventClick={() => {}}
                currentAssessmentId={calendarPreview ? 'retest-preview' : undefined}
                statusColors={RETEST_STATUS_COLORS}
                onEventDrop={(id, newStart, newEnd) => {
                  if (id === 'retest-preview') {
                    setStartDate(localDate(newStart));
                    setEndDate(localDate(newEnd));
                  }
                }}
                onEventResize={(id, newStart, newEnd) => {
                  if (id === 'retest-preview') {
                    setStartDate(localDate(newStart));
                    setEndDate(localDate(newEnd));
                  }
                }}
              />
              {!calendarPreview && (
                <p style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '0.5rem', fontSize: '0.875rem' }}>
                  Fill in the dates above to preview this retest on the calendar
                </p>
              )}
            </div>
          </div>
        </form>
      </div>

      <VulnerabilityDetailDrawer
        vulnerability={previewVuln}
        assessment={assessment}
        onClose={() => setPreviewVuln(null)}
      />
    </Page>
  );
}
