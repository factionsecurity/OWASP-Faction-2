import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, GripVertical, Lock, Plus, X } from 'lucide-react';
import { workflowsApi } from '../../api';
import type { Workflow } from '../../types';
import { Button, Checkbox, FormGroup, FormHint, FormLabel, Input, Select } from '../../components';
import { useTerminology } from '../../context/TerminologyContext';
import { DEFAULT_VULN_STATUSES } from '../../utils/vulnStatus';
import { VULNERABILITY_SEVERITIES } from '../../utils/vulnSeverity';
import { workflowErrorMessages } from '../../utils/workflowErrors';
import {
  draftFromWorkflow,
  draftProblems,
  newRow,
  rebaseRows,
  renameInProgressFor,
  requestFromDraft,
  settingsSignature,
  type WorkflowDraft,
} from './workflowDraft';
import '../AssessmentConfig.css';
import './WorkflowEditor.css';

/** How long after the last change the workflow is saved. */
const AUTOSAVE_MS = 1000;

/** Status colour presets; the colour input picks anything else. */
const SWATCHES = ['#ef4444', '#f97316', '#eab308', '#22c55e', '#14b8a6', '#3b82f6', '#8b5cf6', '#ec4899', '#6b7280'];

type RoleField = 'newAssessmentKey' | 'inProgressKey' | 'completedKey';

interface Props {
  workflow: Workflow;
  /**
   * Called with the workflow after every accepted save. `stillOpen` says whether this editor is still
   * the one on screen: a save can land after its editor has gone, and its result must not be applied
   * to whatever replaced it — not even to a second session of the same workflow.
   */
  onSaved: (saved: Workflow, stillOpen: boolean) => void;
  /** Reports unsaved changes so the host can guard switching away. Pass a stable function. */
  onDirtyChange: (dirty: boolean) => void;
}

/**
 * Edits one workflow, saving a second after the last change. Each save sends the whole workflow, with
 * every status row's name as it was last saved, so renames are tracked rather than read as a removal
 * plus an addition. Refusals from the server — a status still in use, a rename still running — are
 * listed above the footer, and the same refused settings are not sent again until something changes.
 */
export default function WorkflowEditor({ workflow, onSaved, onDirtyChange }: Props) {
  const { severityLabel } = useTerminology();
  const [draft, setDraft] = useState<WorkflowDraft>(() => draftFromWorkflow(workflow));
  const [errors, setErrors] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [savedOnce, setSavedOnce] = useState(false);
  /** Signature of the last save the server accepted; what "anything left to save?" is measured against. */
  const lastSavedRef = useRef(settingsSignature(requestFromDraft(draftFromWorkflow(workflow))));
  /** A signature the server refused. It is not sent again until something changes, so a refusal can't loop. */
  const refusedRef = useRef<string | null>(null);
  const savingRef = useRef(false);
  const loadedIdRef = useRef(workflow.id);
  /** Bumped when a save finishes, so anything typed while it ran is saved straight after. */
  const [saveTick, setSaveTick] = useState(0);
  const onSavedRef = useRef(onSaved);
  useEffect(() => { onSavedRef.current = onSaved; });
  const mountedRef = useRef(true);
  useEffect(() => () => { mountedRef.current = false; }, []);

  const [newStatus, setNewStatus] = useState('');
  const [newVulnStatus, setNewVulnStatus] = useState('');
  const [newStage, setNewStage] = useState('');
  const [slaForm, setSlaForm] = useState({ severity: '', warningDays: '', pastDueDays: '' });
  const [slaError, setSlaError] = useState('');
  // Rows drag only from their handle, so the name input inside a row can still be clicked and selected.
  const [armedKey, setArmedKey] = useState<string | null>(null);
  const [dragKey, setDragKey] = useState<string | null>(null);
  const [colorMenu, setColorMenu] = useState<{ key: string; x: number; y: number } | null>(null);

  // Another workflow, or one re-read from the server, starts again from what it holds. The echo of our
  // own save is not a reset: that would throw away whatever has been typed since it was sent.
  useEffect(() => {
    const incoming = draftFromWorkflow(workflow);
    const signature = settingsSignature(requestFromDraft(incoming));
    if (workflow.id === loadedIdRef.current && signature === lastSavedRef.current) return;
    loadedIdRef.current = workflow.id;
    lastSavedRef.current = signature;
    refusedRef.current = null;
    setDraft(incoming);
    setErrors([]);
    setSavedOnce(false);
  }, [workflow]);

  useEffect(() => {
    if (!colorMenu) return;
    const close = () => setColorMenu(null);
    document.addEventListener('click', close);
    return () => document.removeEventListener('click', close);
  }, [colorMenu]);

  const builtIns = useMemo(
    () => (workflow.builtInVulnerabilityStatuses?.length ? workflow.builtInVulnerabilityStatuses : DEFAULT_VULN_STATUSES),
    [workflow.builtInVulnerabilityStatuses],
  );

  // ── Assessment statuses ────────────────────────────────────────────────────
  const addStatus = () => {
    const name = newStatus.trim();
    if (!name || draft.statuses.some((row) => row.name.trim() === name)) return;
    setDraft((prev) => ({ ...prev, statuses: [...prev.statuses, newRow(name)] }));
    setNewStatus('');
  };

  const renameStatus = (key: string, name: string) =>
    setDraft((prev) => ({ ...prev, statuses: prev.statuses.map((row) => (row.key === key ? { ...row, name } : row)) }));

  const removeStatus = (key: string) =>
    setDraft((prev) => {
      const colors = { ...prev.colors };
      delete colors[key];
      return {
        ...prev,
        statuses: prev.statuses.filter((row) => row.key !== key),
        colors,
        newAssessmentKey: prev.newAssessmentKey === key ? '' : prev.newAssessmentKey,
        inProgressKey: prev.inProgressKey === key ? '' : prev.inProgressKey,
        completedKey: prev.completedKey === key ? '' : prev.completedKey,
      };
    });

  const dragOverStatus = (e: React.DragEvent, overKey: string) => {
    e.preventDefault();
    if (!dragKey || dragKey === overKey) return;
    setDraft((prev) => {
      const from = prev.statuses.findIndex((row) => row.key === dragKey);
      const to = prev.statuses.findIndex((row) => row.key === overKey);
      if (from < 0 || to < 0) return prev;
      const statuses = [...prev.statuses];
      const [moved] = statuses.splice(from, 1);
      statuses.splice(to, 0, moved);
      return { ...prev, statuses };
    });
  };

  const setRole = (field: RoleField, key: string) =>
    setDraft((prev) => {
      const next = { ...prev };
      next[field] = key;
      return next;
    });

  const setColor = (key: string, color: string | null) => {
    setDraft((prev) => {
      const colors = { ...prev.colors };
      if (color === null) delete colors[key];
      else colors[key] = color;
      return { ...prev, colors };
    });
    setColorMenu(null);
  };

  // ── SLAs ───────────────────────────────────────────────────────────────────
  const addSla = () => {
    setSlaError('');
    const warn = parseInt(slaForm.warningDays);
    const due = parseInt(slaForm.pastDueDays);
    if (!slaForm.severity) return setSlaError('Select a severity.');
    if (isNaN(warn) || isNaN(due)) return setSlaError('Enter valid day values.');
    if (warn <= 0 || due <= 0) return setSlaError('Days must be greater than 0.');
    if (warn >= due) return setSlaError('Warning days must be less than past due days.');
    const severity = slaForm.severity;
    setDraft((prev) => ({ ...prev, slas: [...prev.slas, { severity, warningDays: warn, pastDueDays: due }] }));
    setSlaForm({ severity: '', warningDays: '', pastDueDays: '' });
  };

  // ── Remediation stages ─────────────────────────────────────────────────────
  const addStage = () => {
    const name = newStage.trim();
    if (!name) return;
    setDraft((prev) => ({ ...prev, stages: [...prev.stages, { id: crypto.randomUUID(), name }] }));
    setNewStage('');
  };

  const moveStage = (index: number, delta: -1 | 1) =>
    setDraft((prev) => {
      const stages = [...prev.stages];
      [stages[index], stages[index + delta]] = [stages[index + delta], stages[index]];
      return { ...prev, stages };
    });

  // ── Vulnerability statuses ─────────────────────────────────────────────────
  const addVulnStatus = () => {
    const name = newVulnStatus.trim();
    if (!name || builtIns.includes(name) || draft.vulnerabilityStatuses.some((row) => row.name.trim() === name)) return;
    setDraft((prev) => ({ ...prev, vulnerabilityStatuses: [...prev.vulnerabilityStatuses, newRow(name)] }));
    setNewVulnStatus('');
  };

  const renameVulnStatus = (key: string, name: string) =>
    setDraft((prev) => ({
      ...prev,
      vulnerabilityStatuses: prev.vulnerabilityStatuses.map((row) => (row.key === key ? { ...row, name } : row)),
    }));

  const removeVulnStatus = (key: string) =>
    setDraft((prev) => ({ ...prev, vulnerabilityStatuses: prev.vulnerabilityStatuses.filter((row) => row.key !== key) }));

  // ── Autosave ───────────────────────────────────────────────────────────────
  const signature = settingsSignature(requestFromDraft(draft));
  const unsaved = signature !== lastSavedRef.current;

  useEffect(() => { onDirtyChange(unsaved || saving); }, [unsaved, saving, onDirtyChange]);
  useEffect(() => () => onDirtyChange(false), [onDirtyChange]);

  // Changes are saved a moment after the last one, the way this editor worked before workflows became
  // a list. An invalid draft is never sent, and a refused one is not sent again until it changes.
  useEffect(() => {
    if (!unsaved || signature === refusedRef.current) return;
    const problems = draftProblems(draft, builtIns);
    if (problems.length > 0) {
      setErrors(problems);
      return;
    }
    setErrors([]);
    const timer = setTimeout(async () => {
      if (savingRef.current) return;
      savingRef.current = true;
      setSaving(true);
      const sent = draft;
      try {
        const res = await workflowsApi.update(workflow.id, requestFromDraft(sent));
        // The tab hears about the save even when this editor has gone (the workflow was closed or
        // deleted mid-save), so the list still shows the saved name — but it is told not to apply the
        // result to whatever is on screen now.
        const stillOpen = mountedRef.current;
        if (res.data) onSavedRef.current(res.data, stillOpen);
        if (!stillOpen) return;
        lastSavedRef.current = signature;
        refusedRef.current = null;
        // The names just saved are what the next rename is tracked from.
        setDraft((prev) => ({
          ...prev,
          statuses: rebaseRows(prev.statuses, sent.statuses),
          vulnerabilityStatuses: rebaseRows(prev.vulnerabilityStatuses, sent.vulnerabilityStatuses),
        }));
        setSavedOnce(true);
      } catch (err) {
        if (!mountedRef.current) return;
        refusedRef.current = signature;
        setErrors(workflowErrorMessages(err, 'Failed to save the workflow'));
      } finally {
        savingRef.current = false;
        if (mountedRef.current) {
          setSaving(false);
          setSaveTick((tick) => tick + 1);
        }
      }
    }, AUTOSAVE_MS);
    return () => clearTimeout(timer);
  }, [draft, signature, unsaved, saveTick, builtIns, workflow.id]);

  const roleSelect = (field: RoleField) => (
    <Select value={draft[field]} onChange={(e) => setRole(field, e.target.value)}>
      <option value="">— Select —</option>
      {draft.statuses.map((row) => (
        <option key={row.key} value={row.key}>{row.name.trim() || '(unnamed)'}</option>
      ))}
    </Select>
  );

  const usedSeverities = new Set<string>(draft.slas.map((sla) => sla.severity));
  const availableSeverities = VULNERABILITY_SEVERITIES.filter((severity) => !usedSeverities.has(severity));

  return (
    <div className="workflow-editor">
      <FormGroup className="workflow-section">
        <h4 className="workflow-section-title">Name</h4>
        <Input
          aria-label="Workflow name"
          value={draft.name}
          onChange={(e) => {
            const name = e.target.value;
            setDraft((prev) => ({ ...prev, name }));
          }}
        />
      </FormGroup>

      {/* ── Assessment statuses ─────────────────────────────────────────── */}
      <FormGroup className="workflow-section">
        <h4 className="workflow-section-title">Status List</h4>
        <FormHint>
          Drag the handle to reorder. Edit a name to rename it: assessments in that status keep it under
          the new name. Right-click a status to set its color.
        </FormHint>
        <div className="wf-status-list">
          {draft.statuses.map((row) => (
            <div
              key={row.key}
              className={`wf-status-item${dragKey === row.key ? ' dragging' : ''}`}
              draggable={armedKey === row.key}
              onDragStart={() => setDragKey(row.key)}
              onDragOver={(e) => dragOverStatus(e, row.key)}
              onDragEnd={() => { setDragKey(null); setArmedKey(null); }}
              onContextMenu={(e) => {
                e.preventDefault();
                setColorMenu({ key: row.key, x: e.clientX, y: e.clientY });
              }}
            >
              <span
                className="wf-drag-handle"
                onMouseDown={() => setArmedKey(row.key)}
                onMouseUp={() => setArmedKey(null)}
              >
                <GripVertical size={14} />
              </span>
              <span
                className="wf-status-color-dot"
                style={draft.colors[row.key] ? { backgroundColor: draft.colors[row.key] } : undefined}
              />
              <input
                className="wf-status-name-input"
                value={row.name}
                size={Math.max(row.name.length, 4)}
                aria-label="Status name"
                onChange={(e) => renameStatus(row.key, e.target.value)}
              />
              {row.originalName !== null && row.name.trim() !== row.originalName && (
                <span className="wf-renamed-from">was {row.originalName}</span>
              )}
              <button type="button" className="wf-status-remove" onClick={() => removeStatus(row.key)} title="Remove">
                <X size={12} />
              </button>
            </div>
          ))}
        </div>
        <div className="wf-add-status">
          <Input
            type="text"
            value={newStatus}
            onChange={(e) => setNewStatus(e.target.value)}
            placeholder="New status name…"
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addStatus(); } }}
          />
          <Button variant="secondary" icon={Plus} onClick={addStatus} disabled={!newStatus.trim()}>
            Add
          </Button>
        </div>
      </FormGroup>

      <div className="workflow-section">
        <h4 className="workflow-section-title">Automatic Statuses</h4>
        <div className="workflow-roles">
          <FormGroup>
            <FormLabel>New Assessment</FormLabel>
            <FormHint>Status assigned when a new assessment is created.</FormHint>
            {roleSelect('newAssessmentKey')}
          </FormGroup>

          <FormGroup>
            <FormLabel>In Progress</FormLabel>
            <FormHint>Status automatically applied when an assessment is within its scheduled date range.</FormHint>
            {roleSelect('inProgressKey')}
          </FormGroup>

          <FormGroup>
            <FormLabel>Completed</FormLabel>
            <FormHint>Status applied when an assessment is finalized. Assessments in this status cannot be modified.</FormHint>
            {roleSelect('completedKey')}
          </FormGroup>
        </div>
      </div>

      {/* ── Vulnerability SLAs ──────────────────────────────────────────── */}
      <FormGroup className="workflow-section">
        <h4 className="workflow-section-title">Vulnerability SLAs</h4>
        <FormHint>
          Set deadlines for opened vulnerabilities by severity. Warning days must be less than past due days.
          Severities without an SLA will not be tracked. Saving a change recalculates the due dates of this
          workflow&apos;s open findings.
        </FormHint>

        {draft.slas.length > 0 && (
          <table className="sla-table">
            <thead>
              <tr>
                <th>Severity</th>
                <th>Warning (days)</th>
                <th>Past Due (days)</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {draft.slas.map((sla) => (
                <tr key={sla.severity}>
                  <td>
                    <span className={`sla-severity sla-severity--${sla.severity.toLowerCase()}`}>
                      {severityLabel(sla.severity)}
                    </span>
                  </td>
                  <td>{sla.warningDays}</td>
                  <td>{sla.pastDueDays}</td>
                  <td>
                    <button
                      type="button"
                      className="sla-remove"
                      onClick={() => setDraft((prev) => ({ ...prev, slas: prev.slas.filter((s) => s.severity !== sla.severity) }))}
                      title="Remove"
                    >
                      <X size={13} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {slaError && <div className="sla-error">{slaError}</div>}

        {availableSeverities.length > 0 && (
          <div className="sla-add-row">
            <Select value={slaForm.severity} onChange={(e) => setSlaForm((f) => ({ ...f, severity: e.target.value }))}>
              <option value="">Severity…</option>
              {availableSeverities.map((severity) => <option key={severity} value={severity}>{severity}</option>)}
            </Select>
            <Input
              type="number"
              min={1}
              value={slaForm.warningDays}
              onChange={(e) => setSlaForm((f) => ({ ...f, warningDays: e.target.value }))}
              placeholder="Warning days"
            />
            <Input
              type="number"
              min={1}
              value={slaForm.pastDueDays}
              onChange={(e) => setSlaForm((f) => ({ ...f, pastDueDays: e.target.value }))}
              placeholder="Past due days"
            />
            <Button variant="secondary" icon={Plus} onClick={addSla}>Add</Button>
          </div>
        )}
      </FormGroup>

      {/* ── Remediation stages ──────────────────────────────────────────── */}
      <FormGroup className="workflow-section">
        <h4 className="workflow-section-title">Remediation Stages</h4>
        <FormHint>
          Ordered environments a fix moves through (e.g. Development, Staging, Production).
          Closing a vulnerability in the last stage closes the finding outright; earlier stages
          record the date but leave it open. Stages can be completed in any order and never
          affect the SLA clock.
        </FormHint>

        <div className="rs-list">
          {draft.stages.map((stage, i) => (
            <div key={stage.id} className="rs-item">
              <span className="rs-order">{i + 1}</span>
              <input
                className="rs-name-input"
                value={stage.name}
                aria-label="Remediation stage name"
                onChange={(e) => {
                  const name = e.target.value;
                  setDraft((prev) => ({ ...prev, stages: prev.stages.map((s, idx) => (idx === i ? { ...s, name } : s)) }));
                }}
              />
              {i === draft.stages.length - 1 && <span className="rs-terminal-badge">closes the finding</span>}
              <button type="button" className="rs-move" disabled={i === 0} title="Move up" onClick={() => moveStage(i, -1)}>
                <ChevronUp size={14} />
              </button>
              <button
                type="button"
                className="rs-move"
                disabled={i === draft.stages.length - 1}
                title="Move down"
                onClick={() => moveStage(i, 1)}
              >
                <ChevronDown size={14} />
              </button>
              <button
                type="button"
                className="rs-remove"
                disabled={draft.stages.length === 1}
                title={draft.stages.length === 1 ? 'At least one stage is required' : 'Remove stage'}
                onClick={() => setDraft((prev) => ({ ...prev, stages: prev.stages.filter((_, idx) => idx !== i) }))}
              >
                <X size={13} />
              </button>
            </div>
          ))}
        </div>

        <div className="rs-add-row">
          <Input
            value={newStage}
            onChange={(e) => setNewStage(e.target.value)}
            placeholder="New stage name (e.g. QA)"
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addStage(); } }}
          />
          <Button variant="secondary" icon={Plus} onClick={addStage}>Add</Button>
        </div>
      </FormGroup>

      {/* ── Vulnerability statuses ──────────────────────────────────────── */}
      <FormGroup className="workflow-section">
        <h4 className="workflow-section-title">Vulnerability Statuses</h4>
        <FormHint>
          Built-in statuses are shared by every workflow and can&apos;t be changed. That includes the retest
          statuses (In Retest, Passed Retest, Failed Retest) the retest workflow sets. Custom statuses can be
          renamed in place, and a rename updates existing findings in the background.
        </FormHint>

        <div className="vs-list">
          {builtIns.map((status) => (
            <div key={status} className="vs-item vs-item--locked">
              <span className="vs-lock-icon"><Lock size={12} /></span>
              <span className="vs-label">{status}</span>
              <span className="vs-badge">default</span>
            </div>
          ))}
          {draft.vulnerabilityStatuses.map((row) => {
            const rename = renameInProgressFor(row, workflow);
            return (
              <div key={row.key} className="vs-item">
                <input
                  className="rs-name-input"
                  value={row.name}
                  aria-label="Vulnerability status name"
                  disabled={!!rename}
                  onChange={(e) => renameVulnStatus(row.key, e.target.value)}
                />
                {rename && (
                  <span className="vs-badge vs-badge--renaming">
                    Renaming… {rename.processed.toLocaleString()} updated
                  </span>
                )}
                {!rename && row.originalName !== null && row.name.trim() !== row.originalName && (
                  <span className="wf-renamed-from">was {row.originalName}</span>
                )}
                <button
                  type="button"
                  className="vs-remove"
                  disabled={!!rename}
                  title={rename ? 'Still being renamed' : 'Remove'}
                  onClick={() => removeVulnStatus(row.key)}
                >
                  <X size={13} />
                </button>
              </div>
            );
          })}
        </div>

        <div className="vs-add-row">
          <Input
            type="text"
            value={newVulnStatus}
            onChange={(e) => setNewVulnStatus(e.target.value)}
            placeholder="New status name…"
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addVulnStatus(); } }}
          />
          <Button variant="secondary" icon={Plus} disabled={!newVulnStatus.trim()} onClick={addVulnStatus}>
            Add
          </Button>
        </div>
      </FormGroup>

      {/* ── Peer review ─────────────────────────────────────────────────── */}
      <FormGroup className="workflow-section">
        <h4 className="workflow-section-title">Peer Review</h4>
        <Checkbox
          label="Allow reviewing your own submissions"
          checked={draft.allowSelfPeerReview}
          onChange={(e) => {
            const allow = e.target.checked;
            setDraft((prev) => ({ ...prev, allowSelfPeerReview: allow }));
          }}
        />
        <FormHint>
          Off by default: whoever submits an assessment for peer review cannot also review it.
          Turn this on for small teams where a second reviewer isn&apos;t always available.
          Accepting or rejecting a completed review is always done by the submitter.
        </FormHint>
      </FormGroup>

      {errors.length > 0 && (
        <div className="workflow-errors" role="alert">
          <strong>Not saved:</strong>
          <ul>
            {errors.map((message, i) => <li key={i}>{message}</li>)}
          </ul>
        </div>
      )}

      <div className="workflow-editor-footer">
        <span className="workflow-editor-state">
          {saving ? 'Saving…'
            : errors.length > 0 ? 'Not saved'
            : unsaved ? 'Saving shortly…'
            : savedOnce ? 'Saved' : 'No unsaved changes'}
        </span>
      </div>

      {colorMenu && (
        <div
          className="wf-color-picker"
          style={{ top: colorMenu.y, left: colorMenu.x }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="wf-color-swatches">
            {SWATCHES.map((color) => (
              <button
                key={color}
                type="button"
                className="wf-color-swatch"
                style={{ backgroundColor: color }}
                onClick={() => setColor(colorMenu.key, color)}
                title={color}
              />
            ))}
          </div>
          <div className="wf-color-custom-row">
            <label className="wf-color-custom-label">Custom</label>
            <input
              type="color"
              className="wf-color-input"
              value={draft.colors[colorMenu.key] || '#3b82f6'}
              onChange={(e) => setColor(colorMenu.key, e.target.value)}
            />
          </div>
          <button type="button" className="wf-color-reset" onClick={() => setColor(colorMenu.key, null)}>
            Remove color
          </button>
        </div>
      )}
    </div>
  );
}
