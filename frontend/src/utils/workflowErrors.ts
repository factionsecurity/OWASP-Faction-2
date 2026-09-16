import type { WorkflowViolation } from '../types';

/** "1 assessment", "3 assessments". */
export function plural(count: number, noun: string): string {
  return `${count.toLocaleString()} ${noun}${count === 1 ? '' : 's'}`;
}

/** One refusal reason, with what to do about it. */
export function describeViolation(violation: WorkflowViolation): string {
  const { name, count } = violation;
  switch (violation.kind) {
    case 'ASSESSMENT_STATUS_IN_USE':
      return `"${name}" is the status of ${plural(count, 'assessment')}. Change their status before removing it.`;
    case 'VULNERABILITY_STATUS_IN_USE':
      return `"${name}" is the status of ${plural(count, 'finding')}. Change their status before removing it.`;
    case 'REMEDIATION_STAGE_IN_USE':
      return `Remediation stage "${name}" has ${plural(count, 'completion')} recorded, so it can't be removed.`;
    case 'RENAME_IN_PROGRESS':
      return `"${name}" is still being renamed on existing findings. Try again when that finishes.`;
    case 'NAME_TAKEN':
      return `Another workflow is already named "${name}".`;
    case 'DEFAULT_WORKFLOW':
      return `"${name}" is the default workflow and can't be archived or deleted.`;
    case 'USED_BY_ASSESSMENT_TYPES':
      return `"${name}" is used by ${plural(count, 'assessment type')}. Give those types another workflow first.`;
    case 'USED_BY_ASSESSMENTS':
      return `"${name}" is used by ${plural(count, 'assessment')}. Move them to another workflow first.`;
    case 'TARGET_ARCHIVED':
      return `"${name}" is archived. Unarchive it first.`;
    default:
      return `${(violation as WorkflowViolation).kind}: ${name}`;
  }
}

/**
 * Why a workflow request failed, one message per entry:
 * - a 409 with violations gives each violation;
 * - otherwise the server's message, otherwise `fallback`;
 * - a 402 gives one short line, because the upgrade dialog already explains it.
 */
export function workflowErrorMessages(err: unknown, fallback: string): string[] {
  const response = (err as {
    response?: { status?: number; data?: { message?: string; violations?: WorkflowViolation[] } };
  })?.response;
  if (response?.status === 402) return ['Not in this edition.'];
  const violations = response?.data?.violations;
  if (violations && violations.length > 0) return violations.map(describeViolation);
  return [response?.data?.message || fallback];
}
