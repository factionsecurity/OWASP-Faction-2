import type { AssessmentType, Workflow } from '../types';
import { DEFAULT_WORKFLOW_ID } from '../hooks/useWorkflow';

/** The workflow a row belongs to, falling back to Default Workflow exactly as the backend does. */
export function workflowFor(workflows: Workflow[], workflowId?: string | null): Workflow | undefined {
  const wanted = workflowId || DEFAULT_WORKFLOW_ID;
  return workflows.find((w) => w.id === wanted)
    ?? workflows.find((w) => w.id === DEFAULT_WORKFLOW_ID);
}

/**
 * Whether a row sits in its own workflow's completed status — the backend's
 * AssessmentWorkflows.isCompleted. Everything else, including a missing status, is active.
 */
export function isCompleted(workflows: Workflow[], workflowId: string | null | undefined, status: string | null | undefined): boolean {
  return !!status && status === workflowFor(workflows, workflowId)?.completedStatus;
}

/** The colour this workflow paints this status, or undefined to let the caller's fallback apply. */
export function colorFor(workflows: Workflow[], workflowId: string | null | undefined, status: string): string | undefined {
  return workflowFor(workflows, workflowId)?.statusColors?.[status];
}

/**
 * A status name is ambiguous when more than one workflow IN THE GIVEN SET lists it and they
 * don't all render it the same way. Same name and same colour is not a collision — it is the
 * ordinary case where two workflows simply agree. A workflow that lists the status but has no
 * colour for it still counts as an appearance (mapped to a distinct sentinel): its badge falls
 * back to the grey `secondary` variant, which visibly differs from another workflow's coloured
 * badge even though `statusColors` alone can't tell the two apart. A workflow that doesn't have
 * the status at all contributes nothing — it never renders it, so it can't collide.
 */
export function isAmbiguous(workflows: Workflow[], status: string): boolean {
  const appearances = new Set(
    workflows
      .filter((w) => w.statuses?.includes(status))
      .map((w) => w.statusColors?.[status] ?? ' uncoloured')
  );
  return appearances.size > 1;
}

/** "In Review" normally; "In Review (PCI)" only where the name collides on colour. */
export function statusLabel(workflows: Workflow[], workflowId: string | null | undefined, status: string): string {
  if (!isAmbiguous(workflows, status)) return status;
  const name = workflowFor(workflows, workflowId)?.name;
  return name ? `${status} (${name})` : status;
}

/** Distinct assessment status names across the given workflows, in first-seen order. */
export function mergedStatusNames(workflows: Workflow[]): string[] {
  const seen: string[] = [];
  for (const workflow of workflows) {
    for (const status of workflow.statuses ?? []) {
      if (!seen.includes(status)) seen.push(status);
    }
  }
  return seen;
}

/** Distinct vulnerability status names across the given workflows, in first-seen order. */
export function mergedVulnerabilityStatuses(workflows: Workflow[]): string[] {
  const seen: string[] = [];
  for (const workflow of workflows) {
    for (const status of workflow.vulnerabilityStatuses ?? []) {
      if (!seen.includes(status)) seen.push(status);
    }
  }
  return seen;
}

/**
 * Distinct non-terminal remediation stage names across the given workflows, in first-seen order —
 * the column set for a stage table. The terminal stage is excluded: a completion is never stored
 * against it (it closes the finding instead), so its column could never fill.
 */
export function mergedStageNames(workflows: Workflow[]): string[] {
  const seen: string[] = [];
  for (const workflow of workflows) {
    const stages = workflow.remediationStages;
    stages.slice(0, Math.max(0, stages.length - 1)).forEach((stage) => {
      if (!seen.includes(stage.name)) seen.push(stage.name);
    });
  }
  return seen;
}

/** The stage id this workflow uses for a column's name, or undefined when it has no such stage. */
export function stageIdForName(workflows: Workflow[], workflowId: string | null | undefined, stageName: string): string | undefined {
  return workflowFor(workflows, workflowId)?.remediationStages.find((stage) => stage.name === stageName)?.id;
}

/**
 * The workflows whose statuses a status filter should offer, given the assessment types selected
 * beside it. With no type selected nothing narrows and `whenNoneSelected` is returned, because each
 * screen decides that list itself (most offer active workflows; one has an "include archived"
 * toggle). With types selected, each contributes the workflow it runs on: a type with no
 * `workflowId` runs on Default Workflow, as the backend resolves it, and a type on an archived
 * workflow still contributes it, because the user picked that type explicitly.
 *
 * Also returns `whenNoneSelected` when no selected id matches a loaded type. Types load
 * asynchronously and a selection restored from storage arrives before them; narrowing to nothing in
 * that window would empty the status list and let a caller's prune wipe a saved filter on reload.
 */
export function workflowsForSelectedTypes(
  workflows: Workflow[],
  types: Pick<AssessmentType, 'id' | 'workflowId'>[],
  selectedTypeIds: string[],
  whenNoneSelected: Workflow[],
): Workflow[] {
  if (selectedTypeIds.length === 0) return whenNoneSelected;
  const selected = types.filter((t) => selectedTypeIds.includes(t.id));
  if (selected.length === 0) return whenNoneSelected;
  const offered: Workflow[] = [];
  for (const type of selected) {
    const workflow = workflowFor(workflows, type.workflowId);
    if (workflow && !offered.includes(workflow)) offered.push(workflow);
  }
  return offered;
}
