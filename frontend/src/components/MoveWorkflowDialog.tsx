import { useEffect, useState } from 'react';
import { assessmentsApi } from '../api';
import type { Assessment, WorkflowMovePreview } from '../types';
import Modal from './Modal';
import { Button } from './Button';
import { FormGroup, FormHint, FormLabel, Select } from './FormControls';
import { PaidBadge } from './PaidFeature';
import { useEdition } from '../context/EditionContext';
import { DEFAULT_WORKFLOW_ID, useWorkflows } from '../hooks/useWorkflow';
import { plural, workflowErrorMessages } from '../utils/workflowErrors';
import './MoveWorkflowDialog.css';

interface Props {
  assessment: Assessment;
  onClose: () => void;
  /** Called with the re-read assessment once the move is applied. */
  onMoved: (updated: Assessment) => void;
}

/**
 * Moves one assessment, with its findings, to another workflow. Choosing a target runs a dry run, and
 * Move is enabled only once that preview is on screen. Render this only while it is open, because it
 * loads the workflow list when it mounts.
 */
export default function MoveWorkflowDialog({ assessment, onClose, onMoved }: Props) {
  const gated = !useEdition().hasFeature('custom_workflows');
  const { workflows, loading } = useWorkflows(true);
  const currentId = assessment.workflowId || DEFAULT_WORKFLOW_ID;
  const [targetId, setTargetId] = useState('');
  const [preview, setPreview] = useState<WorkflowMovePreview | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [moving, setMoving] = useState(false);
  const [moved, setMoved] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);

  useEffect(() => {
    if (moved) return;
    setPreview(null);
    setErrors([]);
    if (!targetId) return;
    let cancelled = false;
    setPreviewing(true);
    assessmentsApi.moveWorkflow(assessment.id, targetId, true)
      .then((res) => { if (!cancelled) setPreview(res.data ?? null); })
      .catch((err) => { if (!cancelled) setErrors(workflowErrorMessages(err, 'Failed to preview the move')); })
      .finally(() => { if (!cancelled) setPreviewing(false); });
    return () => { cancelled = true; };
  }, [assessment.id, targetId, moved]);

  const handleMove = async () => {
    setMoving(true);
    setErrors([]);
    try {
      await assessmentsApi.moveWorkflow(assessment.id, targetId, false);
    } catch (err) {
      setErrors(workflowErrorMessages(err, 'Failed to move the assessment'));
      setMoving(false);
      return;
    }
    setMoved(true);
    try {
      const res = await assessmentsApi.getById(assessment.id);
      if (res.data) onMoved(res.data);
      onClose();
    } catch {
      setErrors(['The assessment was moved, but this page could not be refreshed. Reload the page to see its new workflow.']);
      setMoving(false);
    }
  };

  const nameOf = (id: string) => workflows.find((w) => w.id === id)?.name ?? (id === DEFAULT_WORKFLOW_ID ? 'Default Workflow' : id);
  const targets = workflows.filter((w) => w.id !== currentId && !w.archived);
  const statusChanges = (preview?.findingStatusChanges ?? []).filter((change) => change.from !== change.to);

  return (
    <Modal
      isOpen
      onClose={onClose}
      title="Move to Workflow"
      size="md"
      closeOnOverlayClick={false}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={moving}>Cancel</Button>
          <Button variant="primary" onClick={handleMove} disabled={!preview || previewing || moving || moved}>
            {moving ? 'Moving…' : 'Move'}
          </Button>
        </>
      }
    >
      <FormGroup>
        <FormLabel>Current workflow</FormLabel>
        <div className="move-workflow-current">{nameOf(currentId)}</div>
      </FormGroup>

      <FormGroup>
        <FormLabel required>Move to</FormLabel>
        {!loading && targets.length === 0 ? (
          <div className="move-workflow-note">There is no other workflow to move this assessment to.</div>
        ) : (
          <Select value={targetId} onChange={(e) => setTargetId(e.target.value)} disabled={moving || moved}>
            <option value="">Select a workflow…</option>
            {targets.map((w) => (
              <option key={w.id} value={w.id} disabled={gated && w.id !== DEFAULT_WORKFLOW_ID}>{w.name}</option>
            ))}
          </Select>
        )}
        <FormHint>
          The assessment and its findings take the new workflow&apos;s statuses (matched by name), SLAs and
          remediation stages.
          {gated && <>{' '}<PaidBadge label="Other workflows: not in this edition" /></>}
        </FormHint>
      </FormGroup>

      {previewing && <div className="move-workflow-note">Checking what the move changes…</div>}

      {errors.length > 0 && (
        <div className="move-workflow-errors" role="alert">
          {errors.map((message, i) => <div key={i}>{message}</div>)}
        </div>
      )}

      {preview && !previewing && (
        <div className="move-workflow-preview">
          <div className="move-workflow-preview-title">Moving to {nameOf(preview.toWorkflowId)} will:</div>
          <ul>
            <li>
              {preview.fromStatus === preview.toStatus
                ? `Keep the assessment's status "${preview.toStatus}".`
                : `Change the assessment's status from "${preview.fromStatus}" to "${preview.toStatus}".`}
            </li>
            <li>
              Move {plural(preview.findingCount, 'finding')}
              {statusChanges.length === 0 ? ', keeping their statuses.' : ':'}
              {statusChanges.length > 0 && (
                <ul>
                  {statusChanges.map((change) => (
                    <li key={`${change.from}→${change.to}`}>
                      &ldquo;{change.from}&rdquo; → &ldquo;{change.to}&rdquo;: {plural(change.count, 'finding')}
                    </li>
                  ))}
                </ul>
              )}
            </li>
            {preview.dueDateChanges > 0 && (
              <li>Recalculate {plural(preview.dueDateChanges, 'due date')} under the new SLAs.</li>
            )}
            {preview.remappedStageCompletions > 0 && (
              <li>Carry {plural(preview.remappedStageCompletions, 'remediation stage completion')} to same-named stages.</li>
            )}
            {preview.unmappedStageCompletions > 0 && (
              <li className="move-workflow-warning">
                Keep {plural(preview.unmappedStageCompletions, 'stage completion')} with no same-named stage on
                the new workflow. They stay recorded but won&apos;t show unless the assessment moves back.
              </li>
            )}
          </ul>
        </div>
      )}
    </Modal>
  );
}
