import { useState, useEffect } from 'react';
import {
  Calendar,
  CheckCircle2,
  RotateCcw,
  Send,
  Clock,
  FileText,
  Flag,
  Mail,
  GitMerge,
  ChevronRight,
  FileJson,
} from 'lucide-react';
import type { Assessment, AssessmentChecklist, PeerReview } from '../types';
import { assessmentsApi, peerReviewsApi, assessmentChecklistsApi, vulnerabilitiesApi } from '../api';
import { Button } from '../components';
import ConfirmDialog from '../components/ConfirmDialog';
import Modal from '../components/Modal';
import ReportDocumentsPanel from '../components/ReportDocumentsPanel';
import PeerReviewDiff from './PeerReviewDiff';
import { peerReviewerLabel } from '../utils/peerReview';
import './AssessmentFinalizeSection.css';

interface Props {
  assessmentId: string;
  assessment: Assessment;
  isFinalized: boolean;
  completedStatus?: string;
  /** Status a reopened assessment returns to; falls back to IN_PROGRESS. */
  inProgressStatus?: string;
  onAssessmentUpdated: (updated: Assessment) => void;
}

/**
 * How long after completion an assessment can still be reopened — mirrors
 * AssessmentService.REOPEN_WINDOW_DAYS, which enforces it and keeps the assessment in the queue
 * for the same period. The server rejects a late reopen regardless of what the UI offers.
 */
const REOPEN_WINDOW_DAYS = 30;

/** Whole days left before a completed assessment can no longer be reopened; 0 once it has lapsed. */
function reopenDaysLeft(completedDate?: string | null): number {
  if (!completedDate) return 0;
  const deadline = new Date(completedDate).getTime() + REOPEN_WINDOW_DAYS * 24 * 60 * 60 * 1000;
  return Math.max(0, Math.ceil((deadline - Date.now()) / (24 * 60 * 60 * 1000)));
}

function formatDate(iso?: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

// Build a list of attendee emails from stakeholders + assessors (deduped)
function buildAttendeeEmails(assessment: Assessment): string[] {
  const emails = new Set<string>();
  assessment.stakeholders?.forEach((s) => {
    if (s.email) emails.add(s.email.trim());
  });
  assessment.assessorEmails?.forEach((e) => {
    if (e) emails.add(e.trim());
  });
  return Array.from(emails);
}

// Format a Date as YYYYMMDDTHHmmssZ for iCal / Google
function toIcalDate(d: Date): string {
  return d.toISOString().replace(/[-:]/g, '').split('.')[0] + 'Z';
}

// Default meeting: tomorrow 10am–11am UTC
function defaultMeetingDates(): { start: Date; end: Date } {
  const start = new Date();
  start.setUTCDate(start.getUTCDate() + 1);
  start.setUTCHours(15, 0, 0, 0); // 10am Eastern ≈ 15:00 UTC
  const end = new Date(start.getTime() + 60 * 60 * 1000);
  return { start, end };
}

function buildGoogleCalendarUrl(assessment: Assessment, emails: string[]): string {
  const { start, end } = defaultMeetingDates();
  const title = encodeURIComponent(`Report Out Meeting for ${assessment.name}`);
  const dates = `${toIcalDate(start)}/${toIcalDate(end)}`;
  // Google Calendar requires a separate &add= param per attendee
  const addParams = emails.map((e) => `add=${encodeURIComponent(e)}`).join('&');
  return `https://calendar.google.com/calendar/render?action=TEMPLATE&text=${title}&dates=${dates}${addParams ? `&${addParams}` : ''}`;
}

function buildOutlookUrl(base: string, assessment: Assessment, emails: string[]): string {
  const { start, end } = defaultMeetingDates();
  const title = encodeURIComponent(`Report Out Meeting for ${assessment.name}`);
  const startdt = encodeURIComponent(start.toISOString().split('.')[0]);
  const enddt = encodeURIComponent(end.toISOString().split('.')[0]);
  // Outlook uses ; as separator — encode each address individually but keep the ; literal
  const to = emails.map((e) => encodeURIComponent(e)).join(';');
  return `${base}?subject=${title}&startdt=${startdt}&enddt=${enddt}&to=${to}`;
}

function downloadIcs(assessment: Assessment, emails: string[]) {
  const { start, end } = defaultMeetingDates();
  const title = `Report Out Meeting for ${assessment.name}`;
  const attendeeLines = emails.map((e) => `ATTENDEE;RSVP=TRUE:mailto:${e}`).join('\r\n');
  const ics = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Faction//ClientPortal//EN',
    'BEGIN:VEVENT',
    `DTSTART:${toIcalDate(start)}`,
    `DTEND:${toIcalDate(end)}`,
    `SUMMARY:${title}`,
    `DESCRIPTION:Report Out Meeting for assessment: ${assessment.name}`,
    attendeeLines,
    `UID:${assessment.id}-report-out@faction`,
    'END:VEVENT',
    'END:VCALENDAR',
  ]
    .filter(Boolean)
    .join('\r\n');

  const blob = new Blob([ics], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `report-out-meeting-${assessment.id}.ics`;
  a.click();
  URL.revokeObjectURL(url);
}

interface TimelineStep {
  label: string;
  date?: string | null;
  icon: React.ReactNode;
  done: boolean;
}

export default function AssessmentFinalizeSection({
  assessmentId,
  assessment,
  isFinalized,
  completedStatus,
  inProgressStatus,
  onAssessmentUpdated,
}: Props) {
  const [submittingPeerReview, setSubmittingPeerReview] = useState(false);
  const [submittingFinalize, setSubmittingFinalize] = useState(false);
  const [reopening, setReopening] = useState(false);
  const [showReopenConfirm, setShowReopenConfirm] = useState(false);
  const [showFinalizeConfirm, setShowFinalizeConfirm] = useState(false);
  const [actionError, setActionError] = useState('');
  /** Which format is in flight, so both buttons disable but only one shows progress. */
  const [exporting, setExporting] = useState<'sarif' | 'cyclonedx' | null>(null);
  const [exportError, setExportError] = useState('');
  const [peerReviews, setPeerReviews] = useState<PeerReview[]>([]);
  const [openReview, setOpenReview] = useState<PeerReview | null>(null);
  const [blockingChecklists, setBlockingChecklists] = useState<AssessmentChecklist[]>([]);

  useEffect(() => {
    Promise.all([
      assessmentChecklistsApi.getByAssessment(assessmentId).catch(() => null),
      import('../api').then(m => m.checklistTemplatesApi.getAll(assessment.assessmentTypeId)).catch(() => null),
    ]).then(([clRes, tplRes]) => {
      const checklists = clRes?.data ?? [];
      const templates = tplRes?.data ?? [];
      const preventClosureIds = new Set(templates.filter(t => t.preventClosure).map(t => t.id));
      const blocking = checklists.filter(
        cl => preventClosureIds.has(cl.templateId) && cl.responses?.some(r => r.result === null)
      );
      setBlockingChecklists(blocking);
    });
  }, [assessmentId, assessment.assessmentTypeId]);

  useEffect(() => {
    peerReviewsApi.getByAssessment(assessmentId).then(res => {
      if (res.success && res.data) {
        setPeerReviews(res.data.filter(r => r.status === 'COMPLETED'));
      }
    });
  }, [assessmentId]);

  const attendeeEmails = buildAttendeeEmails(assessment);

  const isPendingReview = assessment.peerReviewStatus === 'IN_PEER_REVIEW'
    || assessment.peerReviewStatus === 'NEEDS_ACCEPTANCE';
  const resolvedCompletedStatus = completedStatus ?? 'COMPLETED';
  const isCompleted = assessment.status === resolvedCompletedStatus
    || ['COMPLETED', 'APPROVED', 'ARCHIVED'].includes(assessment.status);

  const handleSubmitPeerReview = async () => {
    setSubmittingPeerReview(true);
    setActionError('');
    try {
      const res = await peerReviewsApi.submit(assessmentId);
      if (res.success) {
        // Reload the assessment so peerReviewStatus is reflected
        const updated = await assessmentsApi.getById(assessmentId);
        if (updated.success && updated.data) {
          onAssessmentUpdated(updated.data);
        }
      } else {
        setActionError(res.message || 'Failed to submit for peer review');
      }
    } catch {
      setActionError('Failed to submit for peer review');
    } finally {
      setSubmittingPeerReview(false);
    }
  };

  const daysLeftToReopen = isCompleted ? reopenDaysLeft(assessment.completedDate) : 0;
  const canReopen = isCompleted && daysLeftToReopen > 0;

  const handleReopen = async () => {
    setReopening(true);
    setActionError('');
    try {
      const res = await assessmentsApi.updateStatus(assessmentId, inProgressStatus ?? 'IN_PROGRESS');
      if (res.success && res.data) {
        onAssessmentUpdated(res.data);
      } else {
        setActionError(res.message || 'Failed to reopen assessment');
      }
    } catch (err: any) {
      setActionError(err.response?.data?.message || 'Failed to reopen assessment');
    } finally {
      setReopening(false);
      setShowReopenConfirm(false);
    }
  };

  const handleExport = async (format: 'sarif' | 'cyclonedx') => {
    setExporting(format);
    setExportError('');
    try {
      const { blob, filename } = await vulnerabilitiesApi.exportMachineReadable(assessmentId, format);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err: any) {
      // Unlike the CSV exports elsewhere, this one reports rather than swallowing: a silent
      // no-op on a button press reads as a broken build.
      setExportError(err?.response?.data?.message
        || `Could not export ${format === 'sarif' ? 'SARIF' : 'CycloneDX'}. Please try again.`);
    } finally {
      setExporting(null);
    }
  };

  const handleFinalize = async () => {
    setSubmittingFinalize(true);
    setActionError('');
    try {
      const res = await assessmentsApi.updateStatus(assessmentId, resolvedCompletedStatus);
      if (res.success && res.data) {
        onAssessmentUpdated(res.data);
      } else {
        setActionError(res.message || 'Failed to finalize assessment');
      }
    } catch {
      setActionError('Failed to finalize assessment');
    } finally {
      setSubmittingFinalize(false);
    }
  };

  const steps: TimelineStep[] = [
    {
      label: 'Assessment Started',
      date: assessment.startDate,
      icon: <Clock size={16} />,
      done: !!assessment.startDate,
    },
    {
      label: 'Report Generated',
      date: assessment.reportGeneratedAt,
      icon: <FileText size={16} />,
      done: !!assessment.reportGeneratedAt,
    },
    {
      label: 'Submitted for Peer Review',
      date: assessment.peerReviewedAt,
      icon: <Send size={16} />,
      done: !!assessment.peerReviewedAt,
    },
    {
      label: 'Report Out Meeting Scheduled',
      date: null,
      icon: <Calendar size={16} />,
      done: false,
    },
    {
      label: 'Assessment Finalized',
      date: assessment.completedDate,
      icon: <Flag size={16} />,
      done: !!assessment.completedDate,
    },
  ];

  return (
    <section className="finalize-section content-section">
      <div className="section-header">
        <h3>Finalize Assessment</h3>
      </div>

      <div className="finalize-layout">
      {/* Left column: timeline */}
      <div className="finalize-col finalize-col--timeline">
      <div className="finalize-timeline">
        {steps.map((step, i) => {
          // After "Submitted for Peer Review" (index 2), inject peer review iterations
          const isBeforePeerReviews = i === 2;
          const totalItems = steps.length + peerReviews.length;
          const globalIndex = i + (i > 2 ? peerReviews.length : 0);
          const isLast = globalIndex === totalItems - 1;

          return (
            <div key={i}>
              <div className={`finalize-step${step.done ? ' done' : ''}`}>
                <div className="finalize-step-connector">
                  <div className="finalize-step-icon">{step.icon}</div>
                  {!isLast && <div className="finalize-step-line" />}
                </div>
                <div className="finalize-step-body">
                  <div className="finalize-step-label">{step.label}</div>
                  <div className="finalize-step-date">{step.date ? formatDate(step.date) : null}</div>
                </div>
              </div>

              {isBeforePeerReviews && peerReviews.map((review, ri) => {
                const isLastReview = ri === peerReviews.length - 1;
                return (
                  <div key={review.id} className="finalize-step done finalize-step--review">
                    <div className="finalize-step-connector">
                      <div className="finalize-step-icon finalize-step-icon--review">
                        <GitMerge size={15} />
                      </div>
                      {(!isLastReview || steps.length > 3) && <div className="finalize-step-line" />}
                    </div>
                    <div className="finalize-step-body finalize-step-body--review">
                      {/* The diff is far wider than the timeline column, so it opens in a modal
                          rather than expanding in place. */}
                      <button
                        className="finalize-review-toggle"
                        onClick={() => setOpenReview(review)}
                      >
                        <span className="finalize-step-label">
                          Peer Review by {peerReviewerLabel(review)}
                        </span>
                        <span className="finalize-step-date">{formatDate(review.completedAt)}</span>
                        <ChevronRight size={14} />
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>
      </div>

      {/* Right column: panels */}
      <div className="finalize-col finalize-col--panels">

      {/* Report Documents */}
      <ReportDocumentsPanel
        assessmentId={assessmentId}
        onAssessmentUpdated={onAssessmentUpdated}
        readOnly={isCompleted}
      />

      {/* Machine-readable exports — the same findings the report narrates, in the two formats a
          scanner pipeline or an SBOM tool ingests. Available before finalizing too: the file is
          just the current findings, and teams hand one over mid-engagement. */}
      <div className="finalize-actions-card">
        <h4 className="finalize-actions-title">Vulnerability Exports</h4>
        {exportError && <div className="finalize-error">{exportError}</div>}
        <div className="finalize-action-row">
          <div className="finalize-action-info">
            <strong>Machine-readable findings</strong>
          </div>
        </div>
        <div className="finalize-export-btns">
          <Button
            variant="secondary"
            size="sm"
            icon={FileJson}
            onClick={() => handleExport('sarif')}
            disabled={exporting !== null}
          >
            {exporting === 'sarif' ? 'Exporting…' : 'SARIF'}
          </Button>
          <Button
            variant="secondary"
            size="sm"
            icon={FileJson}
            onClick={() => handleExport('cyclonedx')}
            disabled={exporting !== null}
          >
            {exporting === 'cyclonedx' ? 'Exporting…' : 'CycloneDX'}
          </Button>
        </div>
      </div>

      {/* Status Actions */}
      {/* Shown while the assessment is open (peer review / finalize) and once it's completed,
          which is when the reopen action lives here. */}
      {(!isFinalized || isCompleted) && (
        <div className="finalize-actions-card">
          <h4 className="finalize-actions-title">Status Actions</h4>
          {actionError && <div className="finalize-error">{actionError}</div>}
          {!isFinalized && (
          <>
          <div className="finalize-action-row">
            <div className="finalize-action-info">
              <span className="finalize-action-name">Submit for Peer Review</span>
              <span className="finalize-action-desc">
                Marks the assessment as awaiting peer review (status: PENDING REVIEW)
              </span>
            </div>
            <Button
              variant="warning"
              size="sm"
              onClick={handleSubmitPeerReview}
              disabled={submittingPeerReview || isPendingReview || isCompleted}
            >
              <Send size={14} />
              {submittingPeerReview ? 'Submitting…' : isPendingReview ? 'In Review' : 'Submit'}
            </Button>
          </div>
          {blockingChecklists.length > 0 && (
            <div className="finalize-checklist-block">
              <strong>Cannot finalize:</strong> the following checklists have unanswered questions:
              <ul className="finalize-checklist-block-list">
                {blockingChecklists.map(cl => <li key={cl.id}>{cl.templateName}</li>)}
              </ul>
            </div>
          )}
          <div className="finalize-action-row">
            <div className="finalize-action-info">
              <span className="finalize-action-name">Finalize Assessment</span>
              <span className="finalize-action-desc">
                Marks the assessment as completed and locks it for editing
              </span>
            </div>
            <Button
              variant="primary"
              size="sm"
              onClick={() => setShowFinalizeConfirm(true)}
              disabled={submittingFinalize || isCompleted || blockingChecklists.length > 0}
            >
              <CheckCircle2 size={14} />
              {submittingFinalize ? 'Finalizing…' : 'Finalize'}
            </Button>
          </div>
          </>
          )}

          {/* Reopen — offered only inside the window the server enforces. */}
          {isCompleted && (
            <div className="finalize-action-row">
              <div className="finalize-action-info">
                <span className="finalize-action-name">Reopen Assessment</span>
                <span className="finalize-action-desc">
                  {canReopen
                    ? `Returns the assessment to editing. Available for ${daysLeftToReopen} more `
                      + `${daysLeftToReopen === 1 ? 'day' : 'days'}.`
                    : `This assessment was completed more than ${REOPEN_WINDOW_DAYS} days ago `
                      + 'and can no longer be reopened.'}
                </span>
              </div>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setShowReopenConfirm(true)}
                disabled={!canReopen || reopening}
              >
                <RotateCcw size={14} />
                {reopening ? 'Reopening…' : 'Reopen'}
              </Button>
            </div>
          )}
        </div>
      )}

      {/* Calendar Invite */}
      <div className="finalize-calendar-card">
        <h4 className="finalize-actions-title">Schedule Report Out Meeting</h4>
        <p className="finalize-calendar-desc">
          Create a calendar invite titled{' '}
          <strong>&ldquo;Report Out Meeting for {assessment.name}&rdquo;</strong> with all
          stakeholders and pentesters pre-filled as attendees.
        </p>

        {attendeeEmails.length > 0 && (
          <div className="finalize-attendees">
            <span className="finalize-attendees-label">
              <Mail size={13} /> Attendees ({attendeeEmails.length})
            </span>
            <div className="finalize-attendees-list">
              {attendeeEmails.map((e) => (
                <span key={e} className="finalize-attendee-chip">{e}</span>
              ))}
            </div>
          </div>
        )}

        <div className="finalize-calendar-btns">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => window.open(buildGoogleCalendarUrl(assessment, attendeeEmails), '_blank')}
          >
            <Calendar size={14} />
            Google Calendar
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() =>
              window.open(
                buildOutlookUrl(
                  'https://outlook.office.com/calendar/deeplink/compose',
                  assessment,
                  attendeeEmails
                ),
                '_blank'
              )
            }
          >
            <Calendar size={14} />
            Outlook (Work)
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() =>
              window.open(
                buildOutlookUrl(
                  'https://outlook.live.com/calendar/deeplink/compose',
                  assessment,
                  attendeeEmails
                ),
                '_blank'
              )
            }
          >
            <Calendar size={14} />
            Outlook (Live)
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => downloadIcs(assessment, attendeeEmails)}
          >
            <Calendar size={14} />
            Download .ics
          </Button>
        </div>
      </div>
      </div>
      </div>

      <ConfirmDialog
        isOpen={showFinalizeConfirm}
        onClose={() => setShowFinalizeConfirm(false)}
        onConfirm={() => {
          setShowFinalizeConfirm(false);
          handleFinalize();
        }}
        title="Finalize Assessment"
        message="Are you sure you want to finalize this assessment? This will lock it for editing."
        confirmText="Finalize"
        variant="warning"
        isLoading={submittingFinalize}
      />

      <ConfirmDialog
        isOpen={showReopenConfirm}
        onClose={() => setShowReopenConfirm(false)}
        onConfirm={handleReopen}
        title="Reopen Assessment"
        message={`Reopen this assessment for editing? It will return to `
          + `${inProgressStatus ?? 'IN_PROGRESS'} and its completion date will be cleared, `
          + 'so finalizing it again starts a new 30-day window.'}
        confirmText="Reopen"
        variant="warning"
        isLoading={reopening}
      />

      <Modal
        isOpen={!!openReview}
        onClose={() => setOpenReview(null)}
        size="xl"
        title={openReview
          ? `Peer Review by ${peerReviewerLabel(openReview)}`
            + `${openReview.completedAt ? ` — ${formatDate(openReview.completedAt)}` : ''}`
          : 'Peer Review'}
      >
        {openReview && (
          <PeerReviewDiff
            review={openReview}
            assessment={assessment}
            onAccepted={() => {}}
            readOnly
          />
        )}
      </Modal>
    </section>
  );
}
