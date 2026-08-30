import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { retestApi, vulnerabilitiesApi, reportsApi, applicationsApi, workflowConfigApi } from '../api';
import type { Application, Assessment, Retest, RetestClosure, RemediationStage, Vulnerability } from '../types';
import ReportPreviewDrawer from '../components/ReportPreviewDrawer';
import { Copy, Check } from 'lucide-react';
import { usePageTitle } from '../context/PageTitleContext';
import RichTextEditor from '../components/RichTextEditor';
import ConfirmDialog from '../components/ConfirmDialog';
import Page from '../components/Page';
import { SeverityBadge, Select } from '../components';
import { SEVERITY_OPTIONS } from '../utils/vulnSeverity';
import { usePermissions } from '../utils/permissions';
import './RetestDetailPage.css';

/**
 * The three ratings a retest can revise. Severity is the closed VulnerabilitySeverity enum;
 * likelihood and impact are free-form strings that happen to use the same scale (matching
 * LIKELIHOOD_IMPACT_OPTIONS in VulnerabilityDetailDrawer), so they can also be cleared.
 */
type RatingKey = 'severity' | 'likelihood' | 'impact';
type RatingValues = Record<RatingKey, string>;

const RATING_FIELDS: { key: RatingKey; label: string; clearable: boolean }[] = [
  { key: 'severity', label: 'Severity', clearable: false },
  { key: 'likelihood', label: 'Likelihood', clearable: true },
  { key: 'impact', label: 'Impact', clearable: true },
];

/**
 * What a passing retest closes. The options come from the configured remediation stages: only the
 * terminal (last) stage closes the finding; earlier stages record that the fix is confirmed there
 * while it stays open, and "retest only" simply ends the retest and leaves the vulnerability in
 * the remediation queue.
 */
function closureOptions(stages: RemediationStage[]): { value: RetestClosure; label: string; hint: string }[] {
  // Stage labels are the configured names verbatim — nothing prepended, since names are often
  // already phrases like "Closed in Dev".
  return [
    { value: 'RETEST_ONLY', label: 'Close retest only',
      hint: 'The finding stays open and remains in the remediation queue.' },
    ...stages.map((s, i) => i === stages.length - 1
      ? { value: s.id, label: s.name, hint: 'Closes the vulnerability.' }
      : { value: s.id, label: s.name,
          hint: "Records this stage's remediation date. The finding stays open." }),
  ];
}

import '../components/VulnerabilityDetailDrawer.css';

const STATUS_COLORS: Record<string, string> = {
  REQUESTED: '#f59e0b',
  SCHEDULED: '#3b82f6',
  IN_PROGRESS: '#f97316',
  PASSED: '#22c55e',
  FAILED: '#ef4444',
  CANCELLED: '#6b7280',
};

export default function RetestDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { permissions: userPerms } = usePermissions();
  // Internal schedulers only — app owners can request but not schedule
  // canScheduleRetests is staff-only, so the request-only correction it used to carry is gone.
  const permsCanSchedule = userPerms.canScheduleRetests;

  const { setBreadcrumbs } = usePageTitle();
  const [retest, setRetest] = useState<Retest | null>(null);
  const [vuln, setVuln] = useState<Vulnerability | null>(null);
  const [application, setApplication] = useState<Application | null>(null);
  const [loading, setLoading] = useState(true);
  const [downloadError, setDownloadError] = useState('');
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [copiedEmail, setCopiedEmail] = useState<string | null>(null);

  useEffect(() => {
    setBreadcrumbs([
      { label: 'Retests', to: '/retests' },
      { label: vuln?.name || retest?.vulnerabilityName || 'Retest' },
    ]);
    return () => setBreadcrumbs(null);
  }, [vuln?.name, retest?.vulnerabilityName]);

  // Result form state
  const [selectedResult, setSelectedResult] = useState<'PASS' | 'FAIL' | ''>('');
  const [commentDraft, setCommentDraft] = useState('');
  // The retest can re-rate the finding; the vulnerability's severity is the source of truth and is
  // only written on save. `originalSeverity` is what it was when the page loaded, so the note
  // always contrasts against the recorded severity rather than the previous unsaved pick.
  const [closure, setClosure] = useState<RetestClosure>('RETEST_ONLY');
  const [remediationStages, setRemediationStages] = useState<RemediationStage[]>([]);
  useEffect(() => {
    workflowConfigApi.getConfig()
      .then(res => setRemediationStages(res.data?.remediationStages ?? []))
      .catch(() => setRemediationStages([]));
  }, []);
  const [ratingDrafts, setRatingDrafts] = useState<RatingValues>({ severity: '', likelihood: '', impact: '' });
  const [originalRatings, setOriginalRatings] = useState<RatingValues>({ severity: '', likelihood: '', impact: '' });
  const [saving, setSaving] = useState(false);
  const [completing, setCompleting] = useState(false);

  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (!id) return;
    loadData(id);
  }, [id]);

  const loadData = async (retestId: string) => {
    setLoading(true);
    try {
      const retestRes = await retestApi.getById(retestId);
      if (retestRes.success && retestRes.data) {
        const r = retestRes.data;
        setRetest(r);
        setCommentDraft(r.comment || '');
        setSelectedResult((r.result as 'PASS' | 'FAIL') || '');

        // Load the linked vulnerability
        try {
          const vulnRes = await vulnerabilitiesApi.getById(r.assessmentId, r.vulnerabilityId);
          if (vulnRes.success && vulnRes.data) {
            setVuln(vulnRes.data);
            const current: RatingValues = {
              severity: vulnRes.data.severity ? String(vulnRes.data.severity) : '',
              likelihood: vulnRes.data.likelihood ?? '',
              impact: vulnRes.data.impact ?? '',
            };
            setRatingDrafts(current);
            setOriginalRatings(current);
          }
        } catch {
          // ignore
        }

        // Load application for stakeholders
        if (r.applicationId) {
          try {
            const appRes = await applicationsApi.getById(r.applicationId);
            if (appRes.success && appRes.data) setApplication(appRes.data);
          } catch {
            // ignore
          }
        }

      }
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  const handleRatingChange = (key: RatingKey, next: string) => {
    setRatingDrafts(prev => ({ ...prev, [key]: next }));
  };

  // Only an in-flight retest re-rates: REQUESTED has no assessor working it yet, and a completed
  // or cancelled retest is history.
  const canRerate = !!retest && !!vuln && userPerms.canEditVulnerabilities
    && retest.status !== 'REQUESTED' && retest.status !== 'PASSED'
    && retest.status !== 'FAILED' && retest.status !== 'CANCELLED';

  /** A rating: a picker while the retest is in flight, the recorded badge once it's history. */
  const renderRating = (key: RatingKey, current: string) => {
    if (!canRerate) {
      return current
        ? <SeverityBadge severity={current} />
        : <span className="vuln-drawer-field-value">—</span>;
    }
    const field = RATING_FIELDS.find(f => f.key === key)!;
    return (
      <Select
        className="retest-rating-select"
        value={ratingDrafts[key]}
        onChange={(e) => handleRatingChange(key, e.target.value)}
      >
        {(field.clearable || !ratingDrafts[key]) && <option value="">—</option>}
        {SEVERITY_OPTIONS.map(o => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </Select>
    );
  };

  /**
   * Ratings the assessor changed, as a retest-request payload fragment. Sent with the retest save:
   * the vulnerability API refuses to modify a finalized assessment, and a retest always runs on
   * one, so the backend applies these and records the change comment on the finding.
   */
  /** Re-read the finding after a save so the panel shows what the server actually stored. */
  const reloadVulnerability = async () => {
    if (!retest) return;
    try {
      const res = await vulnerabilitiesApi.getById(retest.assessmentId, retest.vulnerabilityId);
      if (res.success && res.data) setVuln(res.data);
    } catch { /* the ratings saved; a stale panel is refreshed on the next load */ }
  };

  const changedRatings = () => {
    const payload: { severity?: string; likelihood?: string; impact?: string } = {};
    for (const f of RATING_FIELDS) {
      if (ratingDrafts[f.key] !== originalRatings[f.key]) payload[f.key] = ratingDrafts[f.key];
    }
    return payload;
  };

  const handleSave = async () => {
    if (!retest) return;
    setSaving(true);
    try {
      const res = await retestApi.update(retest.id, { comment: commentDraft, ...changedRatings() });
      if (res.success && res.data) {
        setRetest(res.data);
        setOriginalRatings(ratingDrafts);
        await reloadVulnerability();
      }
    } catch {
      // ignore
    } finally {
      setSaving(false);
    }
  };

  const handleSaveAndClose = async () => {
    if (!retest || !selectedResult) return;
    setCompleting(true);
    try {
      const res = await retestApi.complete(retest.id, {
        ...changedRatings(),
        result: selectedResult,
        comment: commentDraft,
        // Only meaningful on a pass; the server ignores it on a fail.
        ...(selectedResult === 'PASS' ? { closure } : {}),
      });
      if (res.success && res.data) {
        navigate('/retests');
      }
    } catch {
      // ignore
    } finally {
      setCompleting(false);
    }
  };

  const handleDownloadReport = () => {
    if (!retest) return;
    setDownloadError('');
    window.open(reportsApi.getDownloadUrl(retest.assessmentId), '_blank');
  };

  const handleCopyEmail = (email: string) => {
    navigator.clipboard.writeText(email).then(() => {
      setCopiedEmail(email);
      setTimeout(() => setCopiedEmail(null), 2000);
    });
  };

  const handleDelete = async () => {
    if (!retest) return;
    setDeleting(true);
    try {
      await retestApi.cancel(retest.id);
      navigate('/retests');
    } catch {
      // ignore
    } finally {
      setDeleting(false);
      setShowDeleteConfirm(false);
    }
  };

  if (loading) {
    return (
      <div className="retest-detail-page">
        <div className="retest-detail-header">
          <h2>Loading…</h2>
        </div>
      </div>
    );
  }

  if (!retest) {
    return (
      <div className="retest-detail-page">
        <div className="retest-detail-header">
          <h2>Retest not found</h2>
        </div>
      </div>
    );
  }

  const statusColor = STATUS_COLORS[retest.status] || '#6b7280';

  return (
    <Page fill className="retest-detail-page">
      <div className="retest-detail-header">
        <h2>
          {vuln?.name || retest.vulnerabilityName || 'Retest Detail'}
        </h2>
        <span
          style={{
            display: 'inline-block',
            padding: '0.2rem 0.6rem',
            borderRadius: '999px',
            background: statusColor + '22',
            color: statusColor,
            fontSize: '0.75rem',
            fontWeight: 600,
            textTransform: 'uppercase',
          }}
        >
          {retest.status.replace('_', ' ')}
        </span>
      </div>

      <div className="retest-detail-content">
        {/* Left: vulnerability details */}
        <div className="retest-detail-vuln-panel">
          {vuln ? (
            <>
              {/* Core fields grid — same order as VulnerabilityDetailDrawer */}
              <section className="vuln-drawer-section" style={{ marginBottom: '1.5rem' }}>
                <div className="vuln-drawer-grid">

                  <div className="vuln-drawer-field">
                    <span className="vuln-drawer-field-label">Name</span>
                    <span className="vuln-drawer-field-value">{vuln.name}</span>
                  </div>

                  <div className="vuln-drawer-field">
                    <span className="vuln-drawer-field-label">Severity</span>
                    {renderRating('severity', vuln.severity ? String(vuln.severity) : '')}
                  </div>

                  <div className="vuln-drawer-field">
                    <span className="vuln-drawer-field-label">Asset / Location</span>
                    <span className="vuln-drawer-field-value">{vuln.assetLocation || '—'}</span>
                  </div>

                  <div className="vuln-drawer-field">
                    <span className="vuln-drawer-field-label">Likelihood</span>
                    {renderRating('likelihood', vuln.likelihood ?? '')}
                  </div>

                  <div className="vuln-drawer-field">
                    <span className="vuln-drawer-field-label">CVSS Score</span>
                    <span className="vuln-drawer-field-value">
                      {vuln.cvssScore != null ? vuln.cvssScore.toFixed(1) : '—'}
                    </span>
                  </div>

                  <div className="vuln-drawer-field">
                    <span className="vuln-drawer-field-label">Impact</span>
                    {renderRating('impact', vuln.impact ?? '')}
                  </div>

                  <div className="vuln-drawer-field vuln-drawer-field--cvss">
                    <span className="vuln-drawer-field-label">CVSS Vector</span>
                    <span className="vuln-drawer-field-value vuln-monospace">{vuln.cvssString || '—'}</span>
                  </div>

                  <div className="vuln-drawer-field">
                    <span className="vuln-drawer-field-label">Tracking ID</span>
                    <span className="vuln-drawer-field-value">{vuln.trackingId || '—'}</span>
                  </div>

                  {vuln.openedAt && (
                    <div className="vuln-drawer-field">
                      <span className="vuln-drawer-field-label">Opened</span>
                      <span className="vuln-drawer-field-value">
                        {new Date(vuln.openedAt).toLocaleDateString()}
                      </span>
                    </div>
                  )}

                </div>
              </section>

              {/* Rich text sections — always shown */}
              {(['description', 'recommendation', 'details'] as const).map(field => {
                const titles = { description: 'Description', recommendation: 'Recommendation', details: 'Technical Details' };
                const value = vuln[field];
                return (
                  <section key={field} className="vuln-drawer-section" style={{ marginBottom: '1.5rem' }}>
                    <h3 className="vuln-drawer-section-title">{titles[field]}</h3>
                    {value
                      ? <RichTextEditor value={value} disabled />
                      : <span className="vuln-drawer-field-value" style={{ color: 'var(--text-muted)' }}>—</span>}
                  </section>
                );
              })}

              {/* User-defined fields */}
              {vuln.fieldDefinitions?.length > 0 && (
                <section className="vuln-drawer-section" style={{ marginBottom: '1.5rem' }}>
                  <h3 className="vuln-drawer-section-title">Additional Fields</h3>
                  <div className="vuln-drawer-grid">
                    {vuln.fieldDefinitions.map(fd => (
                      <div key={fd.id} className="vuln-drawer-field">
                        <span className="vuln-drawer-field-label">{fd.displayName}</span>
                        <span className="vuln-drawer-field-value">
                          {vuln.fieldValues?.[fd.id] || '—'}
                        </span>
                      </div>
                    ))}
                  </div>
                </section>
              )}
            </>
          ) : (
            <p style={{ color: 'var(--text-muted)' }}>Vulnerability details unavailable.</p>
          )}
        </div>

        {/* Right: retest details */}
        <div className="retest-detail-panel">
          <div className="retest-detail-card">
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem' }}>
              <h3 style={{ margin: 0 }}>Retest Details</h3>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem', alignItems: 'stretch' }}>
                <button
                  type="button"
                  className="retest-btn retest-btn--secondary"
                  style={{ fontSize: '0.8rem', padding: '0.3rem 0.75rem' }}
                  onClick={handleDownloadReport}
                >
                  Download Report
                </button>
                <button
                  type="button"
                  className="retest-btn retest-btn--secondary"
                  style={{ fontSize: '0.8rem', padding: '0.3rem 0.75rem' }}
                  onClick={() => setPreviewOpen(true)}
                  disabled={previewLoading}
                >
                  {previewLoading ? 'Loading Preview…' : 'Preview Report'}
                </button>
              </div>
            </div>
            {downloadError && (
              <p style={{ fontSize: '0.8rem', color: '#ef4444', margin: '0 0 0.75rem 0' }}>{downloadError}</p>
            )}

            <div className="retest-detail-field">
              <div className="retest-detail-field-label">Assessment</div>
              <div className="retest-detail-field-value">{retest.assessmentName || retest.assessmentId}</div>
            </div>

            <div className="retest-detail-field">
              <div className="retest-detail-field-label">Scheduled Start</div>
              <div className="retest-detail-field-value">
                {retest.scheduledStartDate ? new Date(retest.scheduledStartDate).toLocaleDateString() : '-'}
              </div>
            </div>

            <div className="retest-detail-field">
              <div className="retest-detail-field-label">Scheduled End</div>
              <div className="retest-detail-field-value">
                {retest.scheduledEndDate ? new Date(retest.scheduledEndDate).toLocaleDateString() : '-'}
              </div>
            </div>

            {retest.closedDate && (
              <div className="retest-detail-field">
                <div className="retest-detail-field-label">Closed</div>
                <div className="retest-detail-field-value">
                  {new Date(retest.closedDate).toLocaleDateString()}
                </div>
              </div>
            )}

            {retest.assignedAssessorNames && retest.assignedAssessorNames.length > 0 && (
              <div className="retest-detail-field">
                <div className="retest-detail-field-label">Assessors</div>
                <div className="retest-assessors-list">
                  {retest.assignedAssessorNames.map((name, i) => (
                    <span key={i} className="retest-assessor-item">{name}</span>
                  ))}
                </div>
              </div>
            )}

            {retest.scope && (
              <div className="retest-detail-field">
                <div className="retest-detail-field-label">Scope</div>
                <div className="retest-detail-field-value">
                  <RichTextEditor value={retest.scope} disabled />
                </div>
              </div>
            )}

            {application && (application.stakeHolders?.length || application.appOwner?.email) && (
              <div className="retest-detail-field">
                <div className="retest-detail-field-label">Stakeholders</div>
                <div className="retest-assessors-list">
                  {application.appOwner?.email && (
                    <div className="retest-stakeholder-row">
                      <span className="retest-assessor-item">
                        {application.appOwner.fullName || application.appOwner.email} — App Owner
                      </span>
                      <button type="button" className="retest-copy-btn" onClick={() => handleCopyEmail(application.appOwner!.email)} title={`Copy ${application.appOwner.email}`}>
                        {copiedEmail === application.appOwner.email ? <Check size={13} /> : <Copy size={13} />}
                      </button>
                    </div>
                  )}
                  {application.stakeHolders?.map((s, i) => (
                    <div key={i} className="retest-stakeholder-row">
                      <span className="retest-assessor-item">
                        {s.name}{s.role ? ` — ${s.role}` : ''}
                      </span>
                      <button type="button" className="retest-copy-btn" onClick={() => handleCopyEmail(s.email)} title={`Copy ${s.email}`}>
                        {copiedEmail === s.email ? <Check size={13} /> : <Copy size={13} />}
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

          </div>

          {/* Requested retests must be scheduled (dates + assessors) before completion */}
          {retest.status === 'REQUESTED' && (
            <div className="retest-detail-card">
              <h3>Retest Requested</h3>
              <p style={{ margin: '0 0 0.75rem', color: 'var(--text-muted)', fontSize: '0.875rem' }}>
                This retest was requested by the application owner and is awaiting scheduling.
              </p>
              {permsCanSchedule && (
                <button
                  type="button"
                  className="retest-btn retest-btn--primary"
                  onClick={() => navigate('/retests/schedule', {
                    state: {
                      retestId: retest.id,
                      existingRetest: retest,
                      assessmentId: retest.assessmentId,
                      vulnerabilityIds: [retest.vulnerabilityId],
                      vulnerabilities: vuln ? [vuln] : [],
                    },
                  })}
                >
                  Schedule Retest
                </button>
              )}
            </div>
          )}

          {/* Result form */}
          {retest.status !== 'REQUESTED' && retest.status !== 'PASSED' && retest.status !== 'FAILED' && retest.status !== 'CANCELLED' && (
            <div className="retest-detail-card">
              <h3>Complete Retest</h3>
              <div className="retest-result-form">
                <div className="retest-detail-field-label" style={{ marginBottom: '0.5rem' }}>Result</div>
                <div className="retest-result-options">
                  <button
                    type="button"
                    className={`retest-result-option retest-result-option--pass${selectedResult === 'PASS' ? ' selected' : ''}`}
                    onClick={() => setSelectedResult('PASS')}
                  >
                    Pass
                  </button>
                  <button
                    type="button"
                    className={`retest-result-option retest-result-option--fail${selectedResult === 'FAIL' ? ' selected' : ''}`}
                    onClick={() => setSelectedResult('FAIL')}
                  >
                    Fail
                  </button>
                </div>

                {selectedResult === 'PASS' && (
                  <div className="retest-closure">
                    <div className="retest-detail-field-label" style={{ marginBottom: '0.5rem' }}>
                      What does this close?
                    </div>
                    <div className="retest-closure-options">
                      {closureOptions(remediationStages).map(o => (
                        <label
                          key={o.value}
                          className={`retest-closure-option${closure === o.value ? ' selected' : ''}`}
                        >
                          <input
                            type="radio"
                            name="retest-closure"
                            value={o.value}
                            checked={closure === o.value}
                            onChange={() => setClosure(o.value)}
                          />
                          <span className="retest-closure-label">{o.label}</span>
                          <span className="retest-closure-hint">{o.hint}</span>
                        </label>
                      ))}
                    </div>
                  </div>
                )}

                <div className="retest-detail-field-label" style={{ marginBottom: '0.5rem' }}>Comment</div>
                <RichTextEditor
                  value={commentDraft}
                  onChange={setCommentDraft}
                  placeholder="Add a comment about this retest…"
                />

                <div className="retest-result-actions">
                  <button
                    type="button"
                    className="retest-btn retest-btn--secondary"
                    onClick={handleSave}
                    disabled={saving}
                  >
                    {saving ? 'Saving…' : 'Save'}
                  </button>
                  <button
                    type="button"
                    className="retest-btn retest-btn--primary"
                    onClick={handleSaveAndClose}
                    disabled={completing || !selectedResult}
                  >
                    {completing ? 'Completing…' : 'Save & Close'}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Show result if completed */}
          {(retest.status === 'PASSED' || retest.status === 'FAILED') && (
            <div className="retest-detail-card">
              <h3>Result</h3>
              <div className="retest-detail-field">
                <div className="retest-detail-field-label">Outcome</div>
                <div className="retest-detail-field-value" style={{
                  color: retest.result === 'PASS' ? '#22c55e' : '#ef4444',
                  fontWeight: 600,
                }}>
                  {retest.result}
                </div>
              </div>
              {retest.comment && (
                <div className="retest-detail-field">
                  <div className="retest-detail-field-label">Comment</div>
                  <div className="retest-detail-field-value">
                    <RichTextEditor value={retest.comment} disabled />
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Cancel retest — only while it's still open; a completed retest is a record */}
          {retest.status !== 'PASSED' && retest.status !== 'FAILED' && retest.status !== 'CANCELLED' && (
            <button
              type="button"
              className="retest-btn"
              style={{ background: 'rgba(239,68,68,0.1)', color: '#ef4444', border: 'none', marginTop: '0.5rem' }}
              onClick={() => setShowDeleteConfirm(true)}
            >
              Cancel Retest
            </button>
          )}
        </div>
      </div>

      <ConfirmDialog
        isOpen={showDeleteConfirm}
        onClose={() => setShowDeleteConfirm(false)}
        onConfirm={handleDelete}
        title="Cancel Retest"
        message="Are you sure you want to cancel this retest? This cannot be undone."
        confirmText="Cancel Retest"
        variant="danger"
        isLoading={deleting}
      />

      <ReportPreviewDrawer
        assessment={previewOpen && retest ? ({
          id: retest.assessmentId,
          name: retest.assessmentName || 'Assessment Report',
        } as Assessment) : null}
        onClose={() => setPreviewOpen(false)}
        onLoadingChange={setPreviewLoading}
      />
    </Page>
  );
}
