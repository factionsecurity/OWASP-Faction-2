import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { CheckCircle2, ClipboardList, Eye, FileText, FileType2, PenLine } from 'lucide-react';
import { marked } from 'marked';
import { peerReviewsApi, assessmentsApi, reportsApi } from '../api';
import type { Assessment, FieldLockInfo, PeerReview, PeerReviewVulnerability, ReportDocumentInfo, ReportDocumentType, ScoringType, UserDefinedField, VulnerabilitySeverity } from '../types';
import { ASSESSMENT_VARIABLES_NOTES_KEY } from '../types';
import { Button, Badge } from '../components';
import TrackChangesEditor from '../components/TrackChangesEditor';
import PlainEditor from '../components/PlainEditor';
import ReportPreviewDrawer from '../components/ReportPreviewDrawer';
import AssessmentChecklistsView from '../components/AssessmentChecklistsView';
import { usePageTitle } from '../context/PageTitleContext';
import Page from '../components/Page';
import { peerReviewerNames } from '../utils/peerReview';
import { createSseParser } from '../utils/sse';
import { SEVERITY_COLORS } from '../utils/vulnSeverity';
import './PeerReviewEditor.css';
import { useTerminology } from '../context/TerminologyContext';

function ratingVariant(value: string): 'danger' | 'warning' | 'info' | 'success' | 'secondary' {
  switch (value.toLowerCase()) {
    case 'critical':
    case 'very high': return 'danger';
    case 'high': return 'warning';
    case 'medium':
    case 'moderate': return 'info';
    case 'low': return 'success';
    default: return 'secondary';
  }
}

function PrCvssScoreBadge({ score, severity }: { score?: number; severity: VulnerabilitySeverity }) {
  const { severityLabel } = useTerminology();
  const color = SEVERITY_COLORS[severity] ?? '#9ca3af';
  const label = severityLabel(severity);
  return (
    <div className="pr-cvss-badge">
      <div className="pr-cvss-badge-value" style={{ color }}>
        {score != null ? score.toFixed(1) : '—'}
      </div>
      <div className="pr-cvss-badge-label" style={{ background: color }}>
        {label}
      </div>
    </div>
  );
}

/**
 * A top-bar action. The title sits on a wrapper because a disabled button never fires hover,
 * so the reason it is unavailable would otherwise be invisible.
 */
function HeaderAction({ icon: Icon, label, title, disabled, onClick }: {
  icon: typeof FileText;
  label: string;
  title: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <span className="pr-editor-action" title={title}>
      <Button variant="secondary" size="sm" onClick={onClick} disabled={disabled}>
        <Icon size={14} />
        {label}
      </Button>
    </span>
  );
}

/**
 * The identity of one editable region, as the lock server sees it. A peer review is a page of
 * independent editors, so the lock has to be per-editor: two reviewers on different
 * vulnerabilities must not block each other, and neither must a reviewer's note block the content
 * beside it. The server treats this as an opaque string.
 */
function lockKey(...parts: string[]): string {
  return parts.join(':');
}

/** Re-stamp at most this often during a typing burst. Well inside the server's 10s TTL. */
const LOCK_KEEPALIVE_MS = 3000;
/** ...and once this long after the last keystroke, so the TTL counts from the real last edit. */
const LOCK_TRAILING_MS = 1000;
/** Mirrors the server's lock TTL; how long after typing we still treat a region as ours. */
const LOCK_TTL_MS = 10000;

/** Vulnerability fields that carry a reviewer's text and so sync between clients. */
const VULN_SYNCED_FIELDS = [
  'revisedDescription', 'revisedRecommendation', 'revisedDetails',
  'descriptionNotes', 'recommendationNotes', 'detailsNotes',
] as const;

/** The editable payload another reviewer's save pushes to everyone else on the review. */
interface RemoteEdits {
  revisedFieldValues?: Record<string, string>;
  fieldNotes?: Record<string, string>;
  vulnerabilities?: PeerReviewVulnerability[];
}

// Convert a markdown snapshot to HTML for initial SunEditor load.
// If revisedFieldValues already has content (HTML from a previous session), use that directly.
function getInitialHtml(snapshot: string | undefined, revised?: string): string {
  if (revised) return revised;
  return String(marked.parse(snapshot || ''));
}

export default function PeerReviewEditor() {
  const { reviewId } = useParams<{ reviewId: string }>();
  const navigate = useNavigate();
  const { setBreadcrumbs } = usePageTitle();

  const [review, setReview] = useState<PeerReview | null>(null);
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [completing, setCompleting] = useState(false);
  const [saveIndicator, setSaveIndicator] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');

  const [tab, setTab] = useState<'review' | 'checklists'>('review');
  // The report is reference material the reviewer reads alongside their edits, so it opens in the
  // side drawer rather than displacing the track-changes editors.
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [reportDocs, setReportDocs] = useState<ReportDocumentInfo[]>([]);
  const [fieldLocks, setFieldLocks] = useState<Record<string, FieldLockInfo | undefined>>({});

  const [revisedFieldValues, setRevisedFieldValues] = useState<Record<string, string>>({});
  const [fieldNotes, setFieldNotes] = useState<Record<string, string>>({});
  const [vulnEdits, setVulnEdits] = useState<PeerReviewVulnerability[]>([]);

  const saveTimer = useRef<ReturnType<typeof setTimeout>>();
  /** Last time each region's lock was stamped, so typing doesn't post on every keystroke. */
  const lockStamped = useRef<Record<string, number>>({});
  const lockTrailing = useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  const user = JSON.parse(localStorage.getItem('user') || '{}');
  const currentUsername = String(user.username || '');

  /**
   * Hold the lock on a region while its editor is being typed into. The server's TTL runs from
   * the last stamp, so this re-stamps at most every few seconds during a burst and once more
   * after typing stops — which is what makes the countdown run from the final keystroke rather
   * than from the first.
   */
  const touchLock = useCallback((key: string) => {
    if (!reviewId) return;
    const stamp = () => {
      lockStamped.current[key] = Date.now();
      peerReviewsApi.acquireLock(reviewId, key).catch(() => {});
    };
    if (Date.now() - (lockStamped.current[key] ?? 0) > LOCK_KEEPALIVE_MS) stamp();
    clearTimeout(lockTrailing.current[key]);
    lockTrailing.current[key] = setTimeout(stamp, LOCK_TRAILING_MS);
  }, [reviewId]);

  /** Whoever else is in this region right now, or undefined when it's free (or ours). */
  const heldByOther = (key: string): string | undefined => {
    const lock = fieldLocks[key];
    if (!lock || lock.username === currentUsername) return undefined;
    return lock.displayName || lock.username;
  };

  /**
   * Who is in the review right now, one entry per person however many regions they hold.
   *
   * <p>Derived from the locks, so it names whoever has *typed* recently — a reviewer reading
   * without editing isn't shown, and a name drops off once their last lock lapses. That is the
   * only presence signal the stream carries, hence the "Editing now" label rather than a claim
   * about who has the page open.
   */
  const reviewerNames = review ? peerReviewerNames(review) : [];

  const activeEditors = useMemo(() => {
    const byUser = new Map<string, string>();
    Object.values(fieldLocks).forEach(lock => {
      if (lock) byUser.set(lock.username, lock.displayName || lock.username);
    });
    return Array.from(byUser, ([username, name]) => ({ username, name }));
  }, [fieldLocks]);

  const fieldLocksRef = useRef(fieldLocks);
  fieldLocksRef.current = fieldLocks;

  /**
   * True while this region is ours to write. Checks our own recent typing as well as the server's
   * view, because the first keystroke lands before the lock round-trip returns — without that,
   * a save arriving in the gap could overwrite the character just typed.
   */
  const heldByMe = useCallback((key: string) =>
    fieldLocksRef.current[key]?.username === currentUsername
    || Date.now() - (lockStamped.current[key] ?? 0) < LOCK_TTL_MS,
  [currentUsername]);

  /**
   * Fold another reviewer's saved text into ours, region by region. A review saves as one
   * document, so the payload covers the whole thing and this side decides what it may take:
   * everything except the regions we hold. That is what keeps a locked editor live instead of
   * frozen, without ever overwriting what the local reviewer is working on.
   */
  const applyRemoteEdits = useCallback((remote: RemoteEdits) => {
    setRevisedFieldValues(prev => {
      const next = { ...prev };
      Object.entries(remote.revisedFieldValues ?? {}).forEach(([id, val]) => {
        if (!heldByMe(lockKey('field', id))) next[id] = val;
      });
      return next;
    });

    setFieldNotes(prev => {
      const next = { ...prev };
      Object.entries(remote.fieldNotes ?? {}).forEach(([id, val]) => {
        const key = id === ASSESSMENT_VARIABLES_NOTES_KEY
          ? lockKey('vars', 'notes')
          : lockKey('field', id, 'notes');
        if (!heldByMe(key)) next[id] = val;
      });
      return next;
    });

    setVulnEdits(prev => prev.map(local => {
      const incoming = (remote.vulnerabilities ?? [])
        .find(v => v.vulnerabilityId === local.vulnerabilityId);
      if (!incoming) return local;

      const merged: PeerReviewVulnerability = { ...local };
      VULN_SYNCED_FIELDS.forEach(f => {
        // Every entry in VULN_SYNCED_FIELDS is an optional string field, so the assignment is
        // sound; TypeScript just can't narrow a union key to a single property.
        if (!heldByMe(lockKey('vuln', local.vulnerabilityId, f))) merged[f] = incoming[f];
      });

      const revised = { ...(local.revisedFieldValues ?? {}) };
      Object.entries(incoming.revisedFieldValues ?? {}).forEach(([fid, val]) => {
        if (!heldByMe(lockKey('vuln', local.vulnerabilityId, 'field', fid))) revised[fid] = val;
      });
      merged.revisedFieldValues = revised;

      const notes = { ...(local.fieldNotes ?? {}) };
      Object.entries(incoming.fieldNotes ?? {}).forEach(([fid, val]) => {
        if (!heldByMe(lockKey('vuln', local.vulnerabilityId, 'field', fid, 'notes'))) notes[fid] = val;
      });
      merged.fieldNotes = notes;

      return merged;
    }));
  }, [heldByMe]);

  useEffect(() => {
    setBreadcrumbs([
      { label: 'Peer Review', to: '/peer-review' },
      { label: 'Editor' },
    ]);
    return () => setBreadcrumbs(null);
  }, []);

  useEffect(() => {
    if (reviewId) loadData(reviewId);
  }, [reviewId]);

  const loadData = async (id: string) => {
    setLoading(true);
    setError('');
    try {
      const res = await peerReviewsApi.getById(id);
      if (!res.success || !res.data) {
        setError('Peer review not found');
        return;
      }
      const r = res.data;
      setReview(r);
      setRevisedFieldValues(r.revisedFieldValues || {});
      setFieldNotes(r.fieldNotes || {});
      setVulnEdits(r.vulnerabilities || []);

      // Drives the top-bar download buttons; a failure here just leaves them unavailable.
      reportsApi.getDocuments(r.assessmentId)
        .then(res => setReportDocs(res.success && res.data ? res.data.documents ?? [] : []))
        .catch(() => setReportDocs([]));

      const aRes = await assessmentsApi.getById(r.assessmentId);
      if (aRes.success && aRes.data) {
        setAssessment(aRes.data);
        setBreadcrumbs([
          { label: 'Peer Review', to: '/peer-review' },
          { label: aRes.data.name },
        ]);
      }

      if (r.status === 'PENDING') {
        const startRes = await peerReviewsApi.start(id);
        if (startRes.success && startRes.data) setReview(startRes.data);
      }
    } catch {
      setError('Failed to load peer review');
    } finally {
      setLoading(false);
    }
  };

  // Live lock state for this review. Mirrors the assessment page's stream; the only difference is
  // that the region ids are composite keys rather than field ids.
  useEffect(() => {
    if (!reviewId || loading) return;
    const controller = new AbortController();

    const clientId = sessionStorage.getItem('sseClientId') ?? (() => {
      const cid = Math.random().toString(36).slice(2);
      sessionStorage.setItem('sseClientId', cid);
      return cid;
    })();

    const handleEvent = (type: string, data: string) => {
      if (type === 'current_locks') {
        const { locks } = JSON.parse(data) as { locks: FieldLockInfo[] };
        const map: Record<string, FieldLockInfo> = {};
        locks.forEach(l => { map[l.fieldId] = l; });
        setFieldLocks(map);
      } else if (type === 'field_locked') {
        const lock = JSON.parse(data) as FieldLockInfo;
        setFieldLocks(prev => ({ ...prev, [lock.fieldId]: lock }));
      } else if (type === 'field_unlocked') {
        const { fieldId } = JSON.parse(data) as { fieldId: string };
        setFieldLocks(prev => { const n = { ...prev }; delete n[fieldId]; return n; });
      } else if (type === 'review_updated') {
        applyRemoteEdits(JSON.parse(data) as RemoteEdits);
      }
    };

    const connect = async () => {
      let errorCount = 0;
      while (!controller.signal.aborted) {
        try {
          const token = localStorage.getItem('token') ?? '';
          const response = await fetch(
            `/api/v1/peer-reviews/${reviewId}/events?clientId=${clientId}`,
            {
              headers: {
                Authorization: `Bearer ${token}`,
                Accept: 'text/event-stream',
                'Cache-Control': 'no-cache',
              },
              signal: controller.signal,
            }
          );
          if (!response.ok || !response.body) throw new Error(`SSE ${response.status}`);

          errorCount = 0;
          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          const feed = createSseParser(handleEvent);

          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            feed(decoder.decode(value, { stream: true }));
          }
        } catch {
          if (controller.signal.aborted) break;
          errorCount++;
          // Give up after three failures: without the stream every editor simply stays
          // editable, which is the old single-user behaviour rather than a broken page.
          if (errorCount >= 3) break;
          await new Promise(r => setTimeout(r, 3000));
        }
      }
    };

    connect();
    return () => controller.abort();
  }, [reviewId, loading, applyRemoteEdits]);

  // Pending trailing lock stamps must not fire after the page is gone.
  useEffect(() => () => {
    Object.values(lockTrailing.current).forEach(clearTimeout);
  }, []);

  /** Read at flush time, so the debounced save never writes a snapshot from 1.5s ago. */
  const editsRef = useRef({ revisedFieldValues, fieldNotes, vulnEdits });
  editsRef.current = { revisedFieldValues, fieldNotes, vulnEdits };

  const scheduleSave = useCallback(() => {
    if (!reviewId) return;
    clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(async () => {
      setSaveIndicator('saving');
      try {
        const edits = editsRef.current;
        await peerReviewsApi.update(reviewId, {
          revisedFieldValues: edits.revisedFieldValues,
          fieldNotes: edits.fieldNotes,
          vulnerabilities: edits.vulnEdits,
        });
        setSaveIndicator('saved');
        setTimeout(() => setSaveIndicator('idle'), 2000);
      } catch {
        setSaveIndicator('error');
      }
    }, 1500);
  }, [reviewId]);

  /**
   * One edit's worth of bookkeeping: hold the region's lock, and queue a save.
   *
   * <p>Saves are driven from the edit handlers rather than from an effect watching the edit state,
   * because that state now also changes when another reviewer's text arrives — an effect could not
   * tell the two apart, and would echo every incoming edit straight back to the server, which then
   * broadcast it again.
   */
  const onLocalEdit = useCallback((key: string) => {
    touchLock(key);
    scheduleSave();
  }, [touchLock, scheduleSave]);

  const handleComplete = async () => {
    if (!reviewId) return;
    setCompleting(true);
    try {
      clearTimeout(saveTimer.current);
      await peerReviewsApi.update(reviewId, { revisedFieldValues, fieldNotes, vulnerabilities: vulnEdits });
      await peerReviewsApi.complete(reviewId);
      navigate('/peer-review');
    } catch {
      setError('Failed to complete review');
    } finally {
      setCompleting(false);
    }
  };

  const updateVulnRichField = (vulnId: string, field: keyof PeerReviewVulnerability, value: string) => {
    setVulnEdits(prev => prev.map(v =>
      v.vulnerabilityId === vulnId ? { ...v, [field]: value } : v
    ));
  };

  const updateVulnNoteField = (vulnId: string, noteKey: string, value: string) => {
    setVulnEdits(prev => prev.map(v => {
      if (v.vulnerabilityId !== vulnId) return v;
      return { ...v, fieldNotes: { ...(v.fieldNotes || {}), [noteKey]: value } };
    }));
  };

  const updateVulnRevisedCustomField = (vulnId: string, fieldId: string, value: string) => {
    setVulnEdits(prev => prev.map(v => {
      if (v.vulnerabilityId !== vulnId) return v;
      return { ...v, revisedFieldValues: { ...(v.revisedFieldValues || {}), [fieldId]: value } };
    }));
  };

  if (loading) {
    return (
      <div className="pr-editor-loading">
        <div className="spinner-border text-primary" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  if (error || !review || !assessment) {
    return (
      <div className="pr-editor-error">
        <div className="alert alert-danger">{error || 'Not found'}</div>
        <Button variant="secondary" onClick={() => navigate('/peer-review')}>
          Go to Peer Review Queue
        </Button>
      </div>
    );
  }

  const docAvailable = (type: ReportDocumentType) =>
    reportDocs.some(d => d.type === type && d.available);

  // Same streaming endpoint the report panel uses; the media cookie authorizes it.
  const downloadReport = (type: ReportDocumentType) =>
    window.open(reportsApi.getDownloadUrl(review.assessmentId, type), '_blank', 'noopener,noreferrer');

  const isReadOnly = review.status === 'COMPLETED';
  const assessmentFields = assessment.fieldDefinitions || [];
  const richTextFields = assessmentFields.filter(f => f.fieldType === 'RICH_TEXT');
  const stringDropdownFields = assessmentFields.filter(f => f.fieldType !== 'RICH_TEXT');

  const scoringType: ScoringType = assessment.scoringType ?? 'NATIVE';
  const cvssVersion = scoringType === 'CVSS_31' ? '3.1' : scoringType === 'CVSS_40' ? '4.0' : null;

  const fieldLabel = (f: UserDefinedField) => f.displayName;

  return (
    <Page fill className="pr-editor">
      {/* Header */}
      <div className="pr-editor-header">
        <div className="pr-editor-title">
          <div className="pr-editor-title-row">
            <h2>Peer Review: {assessment.name}</h2>
            <Badge variant={review.status === 'COMPLETED' ? 'success' : review.status === 'IN_REVIEW' ? 'info' : 'warning'}>
              {review.status.replace('_', ' ')}
            </Badge>
          </div>
          <div className="pr-editor-people">
            {review.submittedByName && (
              <span>Submitted by <strong>{review.submittedByName}</strong></span>
            )}
            {reviewerNames.length > 0 && (
              <span>
                {reviewerNames.length > 1 ? 'Reviewers ' : 'Reviewer '}
                <strong>{reviewerNames.join(', ')}</strong>
              </span>
            )}
            {activeEditors.length > 0 && (
              <span className="pr-editor-present">
                <span className="pr-editor-present-label">Editing now</span>
                {activeEditors.map(editor => (
                  <span
                    key={editor.username}
                    className={`pr-editor-chip${editor.username === currentUsername ? ' pr-editor-chip--me' : ''}`}
                  >
                    <span className="pr-editor-dot" />
                    {editor.username === currentUsername ? 'You' : editor.name}
                  </span>
                ))}
              </span>
            )}
          </div>
        </div>
        <div className="pr-editor-actions">
          <span className={`pr-save-indicator pr-save-indicator--${saveIndicator}`}>
            {saveIndicator === 'saving' && 'Saving…'}
            {saveIndicator === 'saved' && 'Saved ✓'}
            {saveIndicator === 'error' && 'Save error'}
          </span>
          <HeaderAction
            icon={Eye}
            label="Preview"
            title={assessment.generatedReportFileId
              ? 'Preview the generated report'
              : 'No report has been generated for this assessment yet'}
            disabled={!assessment.generatedReportFileId}
            onClick={() => setDrawerOpen(true)}
          />
          <HeaderAction
            icon={FileType2}
            label="DOCX"
            title={docAvailable('DOCX')
              ? 'Download the editable Word report'
              : 'No Word report has been generated for this assessment yet'}
            disabled={!docAvailable('DOCX')}
            onClick={() => downloadReport('DOCX')}
          />
          <HeaderAction
            icon={FileText}
            label="PDF"
            title={docAvailable('PDF')
              ? 'Download the PDF report'
              : 'No PDF report has been generated for this assessment yet'}
            disabled={!docAvailable('PDF')}
            onClick={() => downloadReport('PDF')}
          />
          {!isReadOnly && (
            <Button variant="primary" size="sm" onClick={handleComplete} disabled={completing}>
              <CheckCircle2 size={14} />
              {completing ? 'Completing…' : 'Complete Review'}
            </Button>
          )}
        </div>
      </div>

      <div className="pr-editor-tabs">
        <button
          type="button"
          className={`pr-editor-tab${tab === 'review' ? ' pr-editor-tab--active' : ''}`}
          onClick={() => setTab('review')}
        >
          <PenLine size={14} />
          Review
        </button>
        <button
          type="button"
          className={`pr-editor-tab${tab === 'checklists' ? ' pr-editor-tab--active' : ''}`}
          onClick={() => setTab('checklists')}
        >
          <ClipboardList size={14} />
          Checklists
        </button>
      </div>

      {tab === 'checklists' && (
        <div className="pr-editor-body">
          <div className="pr-editor-checklists">
            <AssessmentChecklistsView assessmentId={review.assessmentId} />
          </div>
        </div>
      )}

      {/* Hidden rather than unmounted: the track-changes editors hold their content in the DOM,
          so remounting them on every tab switch would lose unsaved edits. */}
      <div className="pr-editor-body" style={{ display: tab === 'review' ? undefined : 'none' }}>
        {/* String/Dropdown assessment fields */}
        {stringDropdownFields.length > 0 && (
          <section className="pr-editor-section">
            <h3 className="pr-section-title">Assessment Variables</h3>
            <div className="pr-field-with-notes">
              <div className="pr-vars-grid">
                {stringDropdownFields.map(field => (
                  <div key={field.id} className="pr-var-cell">
                    <div className="pr-field-label">{fieldLabel(field)}</div>
                    <div className="pr-var-value">{review.snapshotFieldValues[field.id] || '—'}</div>
                  </div>
                ))}
              </div>
              <div className="pr-field-notes-side">
                <div className="pr-ice-label">{isReadOnly ? '' : 'Reviewer Notes'}</div>
                <PlainEditor
                  defaultValue={fieldNotes[ASSESSMENT_VARIABLES_NOTES_KEY] || ''}
                  onChange={val => {
                    onLocalEdit(lockKey('vars', 'notes'));
                    setFieldNotes(prev => ({ ...prev, [ASSESSMENT_VARIABLES_NOTES_KEY]: val }));
                  }}
                  disabled={isReadOnly}
                  lockedBy={heldByOther(lockKey('vars', 'notes'))}
                />
              </div>
            </div>
          </section>
        )}

        {/* Rich text assessment fields with track-changes editor */}
        {richTextFields.map(field => (
          <section key={field.id} className="pr-editor-section">
            <h3 className="pr-section-title">{fieldLabel(field)}</h3>
            <div className="pr-field-with-notes">
              <div className="pr-ice-editor-block">
                {!isReadOnly && (
                  <div className="pr-ice-label">
                    Track changes enabled — additions appear green, deletions are struck through
                  </div>
                )}
                <TrackChangesEditor
                  key={field.id}
                  defaultValue={getInitialHtml(review.snapshotFieldValues[field.id], revisedFieldValues[field.id])}
                  onChange={val => {
                    onLocalEdit(lockKey('field', field.id));
                    setRevisedFieldValues(prev => ({ ...prev, [field.id]: val }));
                  }}
                  userId={String(user.id || '')}
                  userName={String(user.username || '')}
                  disabled={isReadOnly}
                  lockedBy={heldByOther(lockKey('field', field.id))}
                />
              </div>
              <div className="pr-field-notes-side">
                <div className="pr-ice-label">{isReadOnly ? '' : 'Reviewer Notes'}</div>
                <PlainEditor
                  defaultValue={fieldNotes[field.id] || ''}
                  onChange={val => {
                    onLocalEdit(lockKey('field', field.id, 'notes'));
                    setFieldNotes(prev => ({ ...prev, [field.id]: val }));
                  }}
                  disabled={isReadOnly}
                  lockedBy={heldByOther(lockKey('field', field.id, 'notes'))}
                />
              </div>
            </div>
          </section>
        ))}

        {/* Vulnerabilities */}
        {vulnEdits.length > 0 && (
          <section className="pr-editor-section">
            <h3 className="pr-section-title">Vulnerabilities ({vulnEdits.length})</h3>
            {vulnEdits.map(vuln => (
              <div key={vuln.vulnerabilityId} className="pr-vuln-block">
                <div className="pr-vuln-header">
                  <strong>{vuln.name}</strong>
                  <Badge variant={
                    vuln.severity === 'CRITICAL' ? 'danger' :
                    vuln.severity === 'HIGH' ? 'warning' :
                    vuln.severity === 'MEDIUM' ? 'info' : 'secondary'
                  }>
                    {vuln.severity}
                  </Badge>
                  {vuln.likelihood && (
                    <span className="pr-vuln-rated-field">
                      <span className="pr-vuln-rated-label">Likelihood</span>
                      <Badge variant={ratingVariant(vuln.likelihood)}>{vuln.likelihood}</Badge>
                    </span>
                  )}
                  {vuln.impact && (
                    <span className="pr-vuln-rated-field">
                      <span className="pr-vuln-rated-label">Impact</span>
                      <Badge variant={ratingVariant(vuln.impact)}>{vuln.impact}</Badge>
                    </span>
                  )}
                </div>
                {scoringType !== 'NATIVE' && (
                  <div className="pr-vuln-scoring">
                    <PrCvssScoreBadge score={vuln.cvssScore} severity={vuln.severity} />
                    <div className="pr-vuln-cvss-meta">
                      <span className="pr-vuln-meta-label">CVSS {cvssVersion} Vector</span>
                      <span className="pr-vuln-cvss-string">{vuln.cvssString || '—'}</span>
                    </div>
                  </div>
                )}

                {/* Description */}
                <div className="pr-field-block">
                  <div className="pr-col-header">
                    <div className="pr-field-label">Description</div>
                    <span className="pr-notes-col-label">Notes</span>
                  </div>
                  <div className="pr-field-with-notes">
                    <TrackChangesEditor
                      key={`${vuln.vulnerabilityId}-desc`}
                      defaultValue={getInitialHtml(vuln.description, vuln.revisedDescription)}
                      onChange={val => {
                        onLocalEdit(lockKey('vuln', vuln.vulnerabilityId, 'revisedDescription'));
                        updateVulnRichField(vuln.vulnerabilityId, 'revisedDescription', val);
                      }}
                      userId={String(user.id || '')}
                      userName={String(user.username || '')}
                      disabled={isReadOnly}
                      lockedBy={heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'revisedDescription'))}
                    />
                    <PlainEditor
                      defaultValue={vuln.descriptionNotes || ''}
                      onChange={val => {
                        onLocalEdit(lockKey('vuln', vuln.vulnerabilityId, 'descriptionNotes'));
                        updateVulnRichField(vuln.vulnerabilityId, 'descriptionNotes', val);
                      }}
                      disabled={isReadOnly}
                      lockedBy={heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'descriptionNotes'))}
                    />
                  </div>
                </div>

                {/* Recommendation */}
                <div className="pr-field-block">
                  <div className="pr-col-header">
                    <div className="pr-field-label">Recommendation</div>
                    <span className="pr-notes-col-label">Notes</span>
                  </div>
                  <div className="pr-field-with-notes">
                    <TrackChangesEditor
                      key={`${vuln.vulnerabilityId}-rec`}
                      defaultValue={getInitialHtml(vuln.recommendation, vuln.revisedRecommendation)}
                      onChange={val => {
                        onLocalEdit(lockKey('vuln', vuln.vulnerabilityId, 'revisedRecommendation'));
                        updateVulnRichField(vuln.vulnerabilityId, 'revisedRecommendation', val);
                      }}
                      userId={String(user.id || '')}
                      userName={String(user.username || '')}
                      disabled={isReadOnly}
                      lockedBy={heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'revisedRecommendation'))}
                    />
                    <PlainEditor
                      defaultValue={vuln.recommendationNotes || ''}
                      onChange={val => {
                        onLocalEdit(lockKey('vuln', vuln.vulnerabilityId, 'recommendationNotes'));
                        updateVulnRichField(vuln.vulnerabilityId, 'recommendationNotes', val);
                      }}
                      disabled={isReadOnly}
                      lockedBy={heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'recommendationNotes'))}
                    />
                  </div>
                </div>

                {/* Details */}
                {(vuln.details || !isReadOnly) && (
                  <div className="pr-field-block">
                    <div className="pr-col-header">
                      <div className="pr-field-label">Details</div>
                      <span className="pr-notes-col-label">Notes</span>
                    </div>
                    <div className="pr-field-with-notes">
                      <TrackChangesEditor
                        key={`${vuln.vulnerabilityId}-details`}
                        defaultValue={getInitialHtml(vuln.details, vuln.revisedDetails)}
                        onChange={val => {
                          onLocalEdit(lockKey('vuln', vuln.vulnerabilityId, 'revisedDetails'));
                          updateVulnRichField(vuln.vulnerabilityId, 'revisedDetails', val);
                        }}
                        userId={String(user.id || '')}
                        userName={String(user.username || '')}
                        disabled={isReadOnly}
                        lockedBy={heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'revisedDetails'))}
                      />
                      <PlainEditor
                        defaultValue={vuln.detailsNotes || ''}
                        onChange={val => {
                          onLocalEdit(lockKey('vuln', vuln.vulnerabilityId, 'detailsNotes'));
                          updateVulnRichField(vuln.vulnerabilityId, 'detailsNotes', val);
                        }}
                        disabled={isReadOnly}
                        lockedBy={heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'detailsNotes'))}
                      />
                    </div>
                  </div>
                )}

                {/* Custom vulnerability fields */}
                {Object.entries(vuln.fieldValues || {}).map(([fieldId, original]) => (
                  <div key={fieldId} className="pr-field-block">
                    <div className="pr-field-label">Field: {fieldId}</div>
                    <div className="pr-field-original">
                      <span className="pr-field-badge">Original</span>
                      <span>{original || '—'}</span>
                    </div>
                    <div className="pr-field-revised">
                      <span className="pr-field-badge pr-field-badge--revised">Revised</span>
                      <input
                        type="text"
                        className="pr-text-input"
                        value={vuln.revisedFieldValues?.[fieldId] || ''}
                        onChange={e => {
                          onLocalEdit(lockKey('vuln', vuln.vulnerabilityId, 'field', fieldId));
                          updateVulnRevisedCustomField(vuln.vulnerabilityId, fieldId, e.target.value);
                        }}
                        disabled={isReadOnly || !!heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'field', fieldId))}
                        title={heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'field', fieldId))
                          ? `${heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'field', fieldId))} is editing`
                          : undefined}
                      />
                    </div>
                    <div className="pr-field-notes">
                      <span className="pr-field-badge pr-field-badge--notes">Notes</span>
                      <textarea
                        className="pr-textarea"
                        value={vuln.fieldNotes?.[fieldId] || ''}
                        onChange={e => {
                          onLocalEdit(lockKey('vuln', vuln.vulnerabilityId, 'field', fieldId, 'notes'));
                          updateVulnNoteField(vuln.vulnerabilityId, fieldId, e.target.value);
                        }}
                        disabled={isReadOnly || !!heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'field', fieldId, 'notes'))}
                        title={heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'field', fieldId, 'notes'))
                          ? `${heldByOther(lockKey('vuln', vuln.vulnerabilityId, 'field', fieldId, 'notes'))} is editing`
                          : undefined}
                        rows={2}
                      />
                    </div>
                  </div>
                ))}
              </div>
            ))}
          </section>
        )}
      </div>

      <ReportPreviewDrawer
        assessment={drawerOpen ? assessment : null}
        onClose={() => setDrawerOpen(false)}
      />
    </Page>
  );
}
