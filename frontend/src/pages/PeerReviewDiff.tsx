import { useMemo, useState } from 'react';
import { Check, Columns2, PenLine } from 'lucide-react';
import DiffMatchPatch from 'diff-match-patch';
import type { Assessment, PeerReview, PeerReviewVulnerability, UserDefinedField } from '../types';
import { ASSESSMENT_VARIABLES_NOTES_KEY } from '../types';
import { Button, Toast } from '../components';
import { peerReviewsApi } from '../api';
import { peerReviewerLabel } from '../utils/peerReview';
import TrackChangesResolver from '../components/TrackChangesResolver';
import PlainEditor from '../components/PlainEditor';
import './PeerReviewDiff.css';

interface Props {
  review: PeerReview;
  assessment: Assessment;
  onAccepted: (updated: Assessment) => void;
  readOnly?: boolean;
}

// Strip ICE <ins>/<del> markup, keeping the resolved content as clean HTML.
// (Content is stored as HTML app-wide — the old markdown conversion here
// corrupted rich text on accept.)
function iceResolvedHtml(html: string): string {
  const div = document.createElement('div');
  div.innerHTML = html;
  div.querySelectorAll('del').forEach(el => el.remove());
  div.querySelectorAll('ins').forEach(el => {
    while (el.firstChild) el.parentNode?.insertBefore(el.firstChild, el);
    el.remove();
  });
  return div.innerHTML;
}

// The mirror of iceResolvedHtml: keep what the reviewer struck out, drop what they added,
// which reconstructs the pre-review text straight from the tracked markup. Deriving both sides
// from the same string sidesteps the snapshot being markdown while the revision is HTML.
function iceOriginalHtml(html: string): string {
  const div = document.createElement('div');
  div.innerHTML = html;
  div.querySelectorAll('ins').forEach(el => el.remove());
  div.querySelectorAll('del').forEach(el => {
    while (el.firstChild) el.parentNode?.insertBefore(el.firstChild, el);
    el.remove();
  });
  return div.innerHTML;
}

function hasUnresolved(html: string): boolean {
  return html.includes('<ins ') || html.includes('<del ');
}

function hasTrackedChanges(html?: string | null): boolean {
  return !!html && (html.includes('<ins') || html.includes('<del'));
}

/**
 * Plain text for diffing, with block boundaries kept as newlines — collapsing everything to one
 * line the way visibleText does would make a paragraph-level diff unreadable.
 */
function blockText(html?: string | null): string {
  const div = document.createElement('div');
  div.innerHTML = html || '';
  div.querySelectorAll('br').forEach(br => br.replaceWith('\n'));
  ['p', 'div', 'li', 'tr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6'].forEach(tag =>
    div.querySelectorAll(tag).forEach(el => el.append('\n')));
  return (div.textContent || '')
    .replace(/[ \t]+/g, ' ')
    .split('\n').map(line => line.trim()).join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** The before/after text pair for one field, however its revision was recorded. */
function diffSides(revised: string | null | undefined, snapshot: string | null | undefined) {
  if (hasTrackedChanges(revised)) {
    return {
      original: blockText(iceOriginalHtml(revised!)),
      revised: blockText(iceResolvedHtml(revised!)),
    };
  }
  return { original: blockText(snapshot), revised: blockText(revised ?? snapshot) };
}

/**
 * diff-match-patch's char-level output splits words mid-token ("foun|determine|d"), which is
 * unreadable in prose. This is the library's own lines-to-chars trick applied to words instead:
 * each distinct word/whitespace run maps to one character, the diff runs over those characters,
 * and diff_charsToLines_ expands them back — so every edit lands on a word boundary.
 */
const MAX_DISTINCT_TOKENS = 60000; // one char per token; above this, fall back to a char diff

function wordDiff(original: string, revised: string): [number, string][] {
  const dmp = new DiffMatchPatch();
  const tokens: string[] = [''];
  const index: Record<string, number> = {};
  let overflowed = false;

  const encode = (text: string) => {
    let encoded = '';
    for (const token of text.split(/(\s+)/)) {
      if (token === '') continue;
      let i = index[token];
      if (i === undefined) {
        if (tokens.length >= MAX_DISTINCT_TOKENS) { overflowed = true; return ''; }
        i = tokens.length;
        tokens.push(token);
        index[token] = i;
      }
      encoded += String.fromCharCode(i);
    }
    return encoded;
  };

  const chars1 = encode(original);
  const chars2 = encode(revised);
  if (overflowed) {
    const fallback = dmp.diff_main(original, revised);
    dmp.diff_cleanupSemantic(fallback);
    return fallback;
  }

  const diffs = dmp.diff_main(chars1, chars2, false);
  dmp.diff_charsToLines_(diffs, tokens);
  return diffs;
}

/**
 * Additions and deletions in reading order. Accepting a review rewrites its stored values through
 * iceResolvedHtml, so an accepted review has no <ins>/<del> left to render — this reconstructs
 * that reading from the snapshot, keeping the Updates tab meaningful for the whole history.
 */
function InlineDiff({ original, revised }: { original: string; revised: string }) {
  const diffs = useMemo(() => wordDiff(original, revised), [original, revised]);

  if (original === revised) {
    return <p className="pr-td-same">No text changes — reviewer note only.</p>;
  }

  return (
    <div className="pr-inline-diff">
      {diffs.map(([op, text], i) =>
        op === -1 ? <del key={i} className="pr-td-del">{text}</del>
          : op === 1 ? <ins key={i} className="pr-td-ins">{text}</ins>
            : <span key={i}>{text}</span>)}
    </div>
  );
}

/** Side-by-side text diff: deletions marked on the left, insertions on the right. */
function TextDiff({ original, revised }: { original: string; revised: string }) {
  const diffs = useMemo(() => wordDiff(original, revised), [original, revised]);

  if (original === revised) {
    return <p className="pr-td-same">No text changes — reviewer note only.</p>;
  }

  return (
    <div className="pr-td">
      <div className="pr-td-col">
        <div className="pr-td-head">Original</div>
        <div className="pr-td-body">
          {diffs.map(([op, text], i) => op === 1 ? null : (
            <span key={i} className={op === -1 ? 'pr-td-del' : undefined}>{text}</span>
          ))}
        </div>
      </div>
      <div className="pr-td-col">
        <div className="pr-td-head">Revised</div>
        <div className="pr-td-body">
          {diffs.map(([op, text], i) => op === -1 ? null : (
            <span key={i} className={op === 1 ? 'pr-td-ins' : undefined}>{text}</span>
          ))}
        </div>
      </div>
    </div>
  );
}

function visibleText(html?: string | null): string {
  const div = document.createElement('div');
  div.innerHTML = html || '';
  return (div.textContent || '').replace(/\s+/g, ' ').trim();
}

// A revised value only counts as a change when it carries tracked edits or
// its visible text really differs from the snapshot. A merely-defined (or
// empty) revised value — e.g. an editor that emitted its unchanged content —
// must never be treated as a change: accepting those wiped live data.
function isActualChange(revised: string | null | undefined, snapshot: string | null | undefined): boolean {
  if (revised == null) return false; // null (JSON round-trip) or undefined
  if (revised.includes('<ins') || revised.includes('<del')) return true;
  const revisedText = visibleText(revised);
  return revisedText !== '' && revisedText !== visibleText(snapshot);
}

// The vulnerability fields that genuinely changed in this review.
function vulnChangedFields(v: PeerReviewVulnerability): string[] {
  const changed: string[] = [];
  if (isActualChange(v.revisedDescription, v.description)) changed.push('description');
  if (isActualChange(v.revisedRecommendation, v.recommendation)) changed.push('recommendation');
  if (isActualChange(v.revisedDetails, v.details)) changed.push('details');
  Object.entries(v.revisedFieldValues || {}).forEach(([k, rev]) => {
    if (isActualChange(rev, v.fieldValues?.[k])) changed.push(k);
  });
  return changed;
}

function hasNote(note?: string | null): boolean {
  return visibleText(note) !== '';
}

// Fields to SHOW in the diff: real changes plus note-only fields — a reviewer
// note with no text edit must still reach the assessor. Note-only fields are
// displayed but never applied on accept.
function vulnVisibleFields(v: PeerReviewVulnerability): string[] {
  const visible = new Set(vulnChangedFields(v));
  if (hasNote(v.descriptionNotes)) visible.add('description');
  if (hasNote(v.recommendationNotes)) visible.add('recommendation');
  if (hasNote(v.detailsNotes)) visible.add('details');
  Object.entries(v.fieldNotes || {}).forEach(([k, n]) => {
    if (hasNote(n)) visible.add(k);
  });
  return Array.from(visible);
}

/** One before/after pair per visible field of a vulnerability, for the Diff tab. */
function vulnDiffEntries(v: PeerReviewVulnerability) {
  const known: Record<string, { label: string; revised?: string; snapshot?: string; note?: string }> = {
    description: { label: 'Description', revised: v.revisedDescription, snapshot: v.description, note: v.descriptionNotes },
    recommendation: { label: 'Recommendation', revised: v.revisedRecommendation, snapshot: v.recommendation, note: v.recommendationNotes },
    details: { label: 'Details', revised: v.revisedDetails, snapshot: v.details, note: v.detailsNotes },
  };
  return vulnVisibleFields(v).map(key => {
    const f = known[key] ?? {
      label: key,
      revised: v.revisedFieldValues?.[key],
      snapshot: v.fieldValues?.[key],
      note: v.fieldNotes?.[key],
    };
    return { key, label: f.label, note: f.note, ...diffSides(f.revised, f.snapshot) };
  });
}

/** The reviewer's note on a field, rendered the same way in both tabs. */
function FieldNote({ note }: { note?: string | null }) {
  if (!hasNote(note)) return null;
  return (
    <div className="pr-diff-note">
      <span className="pr-diff-note-label">Reviewer note:</span>
      <div className="pr-diff-note-body" dangerouslySetInnerHTML={{ __html: note! }} />
    </div>
  );
}

export default function PeerReviewDiff({ review, assessment, onAccepted, readOnly = false }: Props) {
  const [tab, setTab] = useState<'updates' | 'diff'>('updates');
  const [resolvedFieldValues, setResolvedFieldValues] = useState<Record<string, string>>({});
  const [resolvedVulnValues, setResolvedVulnValues] = useState<Record<string, Record<string, string>>>({});
  const [submitting, setSubmitting] = useState(false);
  const [toastKey, setToastKey] = useState(0);
  const [toastMessage, setToastMessage] = useState('');
  const [toastVariant, setToastVariant] = useState<'success' | 'warning' | 'danger'>('danger');
  const [showToast, setShowToast] = useState(false);

  const notify = (message: string, variant: 'success' | 'warning' | 'danger' = 'danger') => {
    setToastMessage(message);
    setToastVariant(variant);
    setToastKey(k => k + 1);
    setShowToast(true);
  };

  const fieldDefs: UserDefinedField[] = assessment.fieldDefinitions || [];

  const changedAssessmentFields = fieldDefs.filter(f => {
    const rev = review.revisedFieldValues[f.id];
    const snap = review.snapshotFieldValues[f.id];
    return f.fieldType === 'RICH_TEXT'
      ? isActualChange(rev, snap)
      : rev != null && rev !== snap && rev !== '';
  });

  // Shown in the diff: changed fields plus note-only fields
  const displayAssessmentFields = fieldDefs.filter(f =>
    changedAssessmentFields.includes(f) || hasNote(review.fieldNotes[f.id]));

  const changedVulns = review.vulnerabilities.filter(v => vulnChangedFields(v).length > 0);
  const displayVulns = review.vulnerabilities.filter(v => vulnVisibleFields(v).length > 0);

  const handleComplete = async () => {
    // Validate: every rich-text field must have no remaining <ins>/<del> tags
    const unresolved: string[] = [];

    changedAssessmentFields.forEach(f => {
      if (f.fieldType === 'RICH_TEXT') {
        const html = resolvedFieldValues[f.id] ?? review.revisedFieldValues[f.id] ?? '';
        if (hasUnresolved(html)) unresolved.push(f.displayName);
      }
    });

    changedVulns.forEach(v => {
      const resolved = resolvedVulnValues[v.vulnerabilityId] || {};
      const changed = vulnChangedFields(v);
      const check = (key: string, label: string, original?: string) => {
        const html = resolved[key] ?? original ?? '';
        if (hasUnresolved(html)) unresolved.push(`${v.name} → ${label}`);
      };
      if (changed.includes('description')) check('description', 'Description', v.revisedDescription);
      if (changed.includes('recommendation')) check('recommendation', 'Recommendation', v.revisedRecommendation);
      if (changed.includes('details')) check('details', 'Details', v.revisedDetails);
    });

    if (unresolved.length > 0) {
      notify('Unresolved Changes:\n' + unresolved.map(f => `• ${f}`).join('\n'));
      return;
    }

    setSubmitting(true);
    try {
      // Resolve tracked changes into clean HTML (only for RICH_TEXT fields)
      const cleanedAssessmentFields: Record<string, string> = {};
      changedAssessmentFields.forEach(f => {
        const rev = resolvedFieldValues[f.id] ?? review.revisedFieldValues[f.id] ?? '';
        cleanedAssessmentFields[f.id] = f.fieldType === 'RICH_TEXT' ? iceResolvedHtml(rev) : rev;
      });

      const cleanedVulns = review.vulnerabilities.map(v => {
        const resolved = resolvedVulnValues[v.vulnerabilityId] || {};
        const changed = vulnChangedFields(v);
        const cleaned = { ...v };
        if (changed.includes('description')) {
          cleaned.revisedDescription = iceResolvedHtml(resolved['description'] ?? v.revisedDescription ?? '');
        } else {
          cleaned.revisedDescription = undefined;
        }
        if (changed.includes('recommendation')) {
          cleaned.revisedRecommendation = iceResolvedHtml(resolved['recommendation'] ?? v.revisedRecommendation ?? '');
        } else {
          cleaned.revisedRecommendation = undefined;
        }
        if (changed.includes('details')) {
          cleaned.revisedDetails = iceResolvedHtml(resolved['details'] ?? v.revisedDetails ?? '');
        } else {
          cleaned.revisedDetails = undefined;
        }
        return cleaned;
      });

      // Push cleaned values to the stored review so the backend reads clean markdown
      await peerReviewsApi.update(review.id, {
        revisedFieldValues: { ...review.revisedFieldValues, ...cleanedAssessmentFields },
        fieldNotes: review.fieldNotes,
        vulnerabilities: cleanedVulns,
      });

      // Accept all changed fields
      const acceptedAssessmentFieldIds = changedAssessmentFields.map(f => f.id);

      const acceptedVulnerabilityChanges: Record<string, string[]> = {};
      changedVulns.forEach(v => {
        const accepted = vulnChangedFields(v);
        if (accepted.length > 0) acceptedVulnerabilityChanges[v.vulnerabilityId] = accepted;
      });

      await peerReviewsApi.accept(review.id, {
        acceptedAssessmentFieldIds,
        acceptedVulnerabilityChanges,
      });

      const { assessmentsApi } = await import('../api');
      const res = await assessmentsApi.getById(assessment.id);
      if (res.success && res.data) onAccepted(res.data);
    } catch {
      notify('Failed to complete review. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  const variablesNote = review.fieldNotes[ASSESSMENT_VARIABLES_NOTES_KEY];
  const hasAnyChange = changedAssessmentFields.length > 0 || changedVulns.length > 0;
  const hasAnythingToShow =
    displayAssessmentFields.length > 0 || displayVulns.length > 0 || hasNote(variablesNote);

  return (
    <div className="pr-diff">
      <div className="pr-diff-toolbar">
        <div className="pr-diff-meta">
          <span>Reviewed by <strong>{peerReviewerLabel(review, '—')}</strong></span>
          {review.completedAt && (
            <span> · {new Date(review.completedAt).toLocaleDateString()}</span>
          )}
        </div>
      </div>

      {!hasAnythingToShow && (
        <p className="pr-diff-empty">No field changes in this review.</p>
      )}

      {hasAnythingToShow && (
        <div className="pr-diff-tabs">
          <button
            type="button"
            className={`pr-diff-tab${tab === 'updates' ? ' pr-diff-tab--active' : ''}`}
            onClick={() => setTab('updates')}
          >
            <PenLine size={14} />
            Reviewer Updates
          </button>
          <button
            type="button"
            className={`pr-diff-tab${tab === 'diff' ? ' pr-diff-tab--active' : ''}`}
            onClick={() => setTab('diff')}
          >
            <Columns2 size={14} />
            Diff
          </button>
        </div>
      )}

      {/* Hidden rather than unmounted on the Diff tab: unmounting would throw away every
          accept/reject the assessor has already made in the resolver. */}
      <div className="pr-diff-pane" style={{ display: tab === 'updates' ? undefined : 'none' }}>

      {hasNote(variablesNote) && (
        <div className="pr-diff-field">
          <div className="pr-diff-field-header">
            <span className="pr-diff-field-name">Assessment Variables</span>
          </div>
          <FieldNote note={variablesNote} />
        </div>
      )}

      {displayAssessmentFields.map(field => {
        // Note-only fields show the snapshot content beside the reviewer note
        const rev = review.revisedFieldValues[field.id]
          || review.snapshotFieldValues[field.id] || '';
        const note = review.fieldNotes[field.id];
        const isRich = field.fieldType === 'RICH_TEXT';

        return (
          <div key={field.id} className="pr-diff-field">
            <div className="pr-diff-field-header">
              <span className="pr-diff-field-name">{field.displayName}</span>
            </div>
            {isRich ? (
              <div className="pr-diff-rich-row">
                {readOnly && !hasTrackedChanges(rev) ? (
                  <InlineDiff
                    {...diffSides(review.revisedFieldValues[field.id], review.snapshotFieldValues[field.id])}
                  />
                ) : (
                  <TrackChangesResolver
                    initialValue={rev}
                    onChange={html => setResolvedFieldValues(prev => ({ ...prev, [field.id]: html }))}
                    disabled={readOnly || submitting}
                  />
                )}
                <div className="pr-diff-notes-col">
                  <div className="pr-diff-notes-label">Reviewer note</div>
                  <PlainEditor
                    defaultValue={note || ''}
                    onChange={() => {}}
                    disabled
                  />
                </div>
              </div>
            ) : (
              <>
                <div className="pr-diff-text-change">
                  <span className="pr-diff-original-text">{review.snapshotFieldValues[field.id] || '—'}</span>
                  <span className="pr-diff-arrow">→</span>
                  <span className="pr-diff-revised-text">{rev || '—'}</span>
                </div>
                {note && (
                  <div className="pr-diff-note">
                    <span className="pr-diff-note-label">Reviewer note:</span>
                    <div className="pr-diff-note-body" dangerouslySetInnerHTML={{ __html: note }} />
                  </div>
                )}
              </>
            )}
          </div>
        );
      })}

      {displayVulns.map(vuln => (
        <VulnDiff
          key={vuln.vulnerabilityId}
          vuln={vuln}
          onResolve={(field, html) =>
            setResolvedVulnValues(prev => ({
              ...prev,
              [vuln.vulnerabilityId]: { ...(prev[vuln.vulnerabilityId] || {}), [field]: html },
            }))
          }
          disabled={submitting}
          readOnly={readOnly}
        />
      ))}

      </div>

      {tab === 'diff' && (
        <div className="pr-diff-pane">
          {hasNote(variablesNote) && (
            <div className="pr-diff-field">
              <div className="pr-diff-field-header">
                <span className="pr-diff-field-name">Assessment Variables</span>
              </div>
              <FieldNote note={variablesNote} />
            </div>
          )}

          {displayAssessmentFields.map(field => {
            const sides = diffSides(
              review.revisedFieldValues[field.id], review.snapshotFieldValues[field.id]);
            return (
              <div key={field.id} className="pr-diff-field">
                <div className="pr-diff-field-header">
                  <span className="pr-diff-field-name">{field.displayName}</span>
                </div>
                <TextDiff original={sides.original} revised={sides.revised} />
                <FieldNote note={review.fieldNotes[field.id]} />
              </div>
            );
          })}

          {displayVulns.map(vuln => (
            <div key={vuln.vulnerabilityId} className="pr-diff-vuln">
              <div className="pr-diff-vuln-title">{vuln.name}</div>
              {vulnDiffEntries(vuln).map(entry => (
                <div key={entry.key} className="pr-diff-field">
                  <div className="pr-diff-field-header">
                    <span className="pr-diff-field-name">{entry.label}</span>
                  </div>
                  <TextDiff original={entry.original} revised={entry.revised} />
                  <FieldNote note={entry.note} />
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {!readOnly && (
        <div className="pr-diff-actions">
          <Button variant="primary" size="sm" onClick={handleComplete} disabled={submitting}>
            <Check size={14} />
            {submitting ? 'Completing…'
              : hasAnyChange ? 'Complete Review' : 'Accept & Close Review'}
          </Button>
        </div>
      )}

      {showToast && (
        <Toast
          key={toastKey}
          message={toastMessage}
          variant={toastVariant}
          onDone={() => setShowToast(false)}
        />
      )}
    </div>
  );
}

interface VulnDiffProps {
  vuln: PeerReviewVulnerability;
  onResolve: (field: string, html: string) => void;
  disabled: boolean;
  readOnly?: boolean;
}

function VulnDiff({ vuln, onResolve, disabled, readOnly = false }: VulnDiffProps) {
  type RichField = { key: string; label: string; revised?: string; snapshot?: string; note?: string; isRich: true };
  type PlainField = { key: string; label: string; original?: string; revised?: string; note?: string; isRich: false };
  const fields: Array<RichField | PlainField> = [];

  // Note-only fields (no text change) fall back to the snapshot content so
  // the assessor sees what the note refers to.
  const visible = vulnVisibleFields(vuln);
  if (visible.includes('description')) {
    fields.push({ key: 'description', label: 'Description', revised: vuln.revisedDescription || vuln.description, snapshot: vuln.description, note: vuln.descriptionNotes, isRich: true });
  }
  if (visible.includes('recommendation')) {
    fields.push({ key: 'recommendation', label: 'Recommendation', revised: vuln.revisedRecommendation || vuln.recommendation, snapshot: vuln.recommendation, note: vuln.recommendationNotes, isRich: true });
  }
  if (visible.includes('details')) {
    fields.push({ key: 'details', label: 'Details', revised: vuln.revisedDetails || vuln.details, snapshot: vuln.details, note: vuln.detailsNotes, isRich: true });
  }
  visible
    .filter(k => !['description', 'recommendation', 'details'].includes(k))
    .forEach(k => {
      fields.push({
        key: k, label: k,
        original: vuln.fieldValues?.[k],
        revised: vuln.revisedFieldValues?.[k] ?? vuln.fieldValues?.[k],
        note: vuln.fieldNotes?.[k], isRich: false,
      });
    });

  if (fields.length === 0) return null;

  return (
    <div className="pr-diff-vuln">
      <div className="pr-diff-vuln-title">{vuln.name}</div>
      {fields.map(f => (
        <div key={f.key} className="pr-diff-field">
          <div className="pr-diff-field-header">
            <span className="pr-diff-field-name">{f.label}</span>
          </div>
          {f.isRich ? (
            <div className="pr-diff-rich-row">
              {readOnly && !hasTrackedChanges(f.revised) ? (
                <InlineDiff {...diffSides(f.revised, f.snapshot)} />
              ) : (
                <TrackChangesResolver
                  initialValue={f.revised || ''}
                  onChange={html => onResolve(f.key, html)}
                  disabled={readOnly || disabled}
                />
              )}
              <div className="pr-diff-notes-col">
                <div className="pr-diff-notes-label">Reviewer note</div>
                <PlainEditor
                  defaultValue={f.note || ''}
                  onChange={() => {}}
                  disabled
                />
              </div>
            </div>
          ) : (
            <>
              <div className="pr-diff-text-change">
                <span className="pr-diff-original-text">{f.original || '—'}</span>
                <span className="pr-diff-arrow">→</span>
                <span className="pr-diff-revised-text">{f.revised || '—'}</span>
              </div>
              {f.note && (
                <div className="pr-diff-note">
                  <span className="pr-diff-note-label">Reviewer note:</span>
                  <div className="pr-diff-note-body" dangerouslySetInnerHTML={{ __html: f.note }} />
                </div>
              )}
            </>
          )}
        </div>
      ))}
    </div>
  );
}
