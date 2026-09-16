import type {
  RemediationStage,
  UpdateWorkflowRequest,
  VulnerabilitySla,
  Workflow,
  WorkflowRenameInProgress,
} from '../../types';

/** A renamable status row. `key` stays the same while the editor is open, so roles and colours follow a rename. */
export interface DraftRow {
  key: string;
  /** The name when the editor loaded; null for a row added since. */
  originalName: string | null;
  name: string;
}

/** Everything the workflow editor changes, held until Save. */
export interface WorkflowDraft {
  name: string;
  statuses: DraftRow[];
  /** Row keys of the role statuses; '' when none is chosen. */
  newAssessmentKey: string;
  inProgressKey: string;
  completedKey: string;
  /** Colours by row key. */
  colors: Record<string, string>;
  slas: VulnerabilitySla[];
  vulnerabilityStatuses: DraftRow[];
  stages: RemediationStage[];
  allowSelfPeerReview: boolean;
}

export function newRow(name: string): DraftRow {
  return { key: crypto.randomUUID(), originalName: null, name };
}

function loadedRow(name: string): DraftRow {
  return { key: crypto.randomUUID(), originalName: name, name };
}

export function draftFromWorkflow(workflow: Workflow): WorkflowDraft {
  const statuses = (workflow.statuses ?? []).map(loadedRow);
  const keyOf = (name: string) => statuses.find((row) => row.name === name)?.key ?? '';
  const colors: Record<string, string> = {};
  for (const row of statuses) {
    const color = workflow.statusColors?.[row.name];
    if (color) colors[row.key] = color;
  }
  return {
    name: workflow.name,
    statuses,
    newAssessmentKey: keyOf(workflow.newAssessmentStatus),
    inProgressKey: keyOf(workflow.inProgressStatus),
    completedKey: keyOf(workflow.completedStatus),
    colors,
    slas: workflow.vulnerabilitySlas ?? [],
    vulnerabilityStatuses: (workflow.vulnerabilityStatuses ?? []).map(loadedRow),
    stages: workflow.remediationStages ?? [],
    allowSelfPeerReview: !!workflow.allowSelfPeerReview,
  };
}

/** The PUT body: names trimmed, with roles and colours given by each row's current name. */
export function requestFromDraft(draft: WorkflowDraft): UpdateWorkflowRequest {
  const nameOf = (key: string) => draft.statuses.find((row) => row.key === key)?.name.trim() ?? '';
  const statusColors: Record<string, string> = {};
  for (const row of draft.statuses) {
    const color = draft.colors[row.key];
    if (color) statusColors[row.name.trim()] = color;
  }
  return {
    name: draft.name.trim(),
    statuses: draft.statuses.map((row) => ({ originalName: row.originalName, name: row.name.trim() })),
    newAssessmentStatus: nameOf(draft.newAssessmentKey),
    inProgressStatus: nameOf(draft.inProgressKey),
    completedStatus: nameOf(draft.completedKey),
    statusColors,
    vulnerabilitySlas: draft.slas,
    vulnerabilityStatuses: draft.vulnerabilityStatuses.map((row) => ({ originalName: row.originalName, name: row.name.trim() })),
    remediationStages: draft.stages.map((stage) => ({ ...stage, name: stage.name.trim() })),
    allowSelfPeerReview: draft.allowSelfPeerReview,
  };
}

/**
 * What a request says the workflow's settings are, leaving out the rename anchors. Two requests with
 * the same signature save the same thing, however each row's name was arrived at. Autosave measures
 * "anything left to save?" against the signature of the last save the server accepted.
 */
export function settingsSignature(request: UpdateWorkflowRequest): string {
  return JSON.stringify(withSortedKeys({
    ...request,
    statuses: request.statuses.map((row) => row.name),
    vulnerabilityStatuses: request.vulnerabilityStatuses.map((row) => row.name),
  }));
}

/**
 * The same value with every object's keys in a fixed order, so two equal settings always produce the
 * same text. What the server sends back is ordered differently from what the editor sent — colours
 * come out of a hash map, and an SLA's fields are declared in another order — and without this the
 * editor would read its own saved settings as somebody else's change. List order is left alone: the
 * order of statuses and stages is a setting in its own right.
 */
function withSortedKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(withSortedKeys);
  if (value === null || typeof value !== 'object') return value;
  const source = value as Record<string, unknown>;
  return Object.fromEntries(Object.keys(source).sort().map((key) => [key, withSortedKeys(source[key])]));
}

/**
 * After a save, every row that was sent is anchored to the name it was saved under, so the next rename
 * is tracked from there rather than from the name the editor first loaded. Anything typed while the
 * save was in flight is left alone.
 */
export function rebaseRows(current: DraftRow[], sent: DraftRow[]): DraftRow[] {
  const sentNameByKey = new Map(sent.map((row) => [row.key, row.name.trim()]));
  return current.map((row) => {
    const sentName = sentNameByKey.get(row.key);
    return sentName === undefined ? row : { ...row, originalName: sentName };
  });
}

function namingProblems(rows: DraftRow[], kind: string): string[] {
  const problems = new Set<string>();
  const seen = new Set<string>();
  for (const row of rows) {
    const name = row.name.trim();
    if (!name) {
      problems.add(`Every ${kind} needs a name.`);
    } else if (seen.has(name)) {
      problems.add(`Duplicate ${kind}: "${name}".`);
    }
    seen.add(name);
  }
  return [...problems];
}

/**
 * What the server would refuse, caught before saving. The server checks all of this again. Refusals
 * that need counts, like a status still in use, only the server can report.
 */
export function draftProblems(draft: WorkflowDraft, builtInVulnerabilityStatuses: string[]): string[] {
  const problems: string[] = [];
  if (!draft.name.trim()) problems.push('The workflow needs a name.');
  if (draft.statuses.length === 0) problems.push('Add at least one assessment status.');
  problems.push(...namingProblems(draft.statuses, 'assessment status'));
  const roles: [string, string][] = [
    [draft.newAssessmentKey, 'New Assessment'],
    [draft.inProgressKey, 'In Progress'],
    [draft.completedKey, 'Completed'],
  ];
  for (const [key, role] of roles) {
    if (!draft.statuses.some((row) => row.key === key)) problems.push(`Choose the ${role} status.`);
  }
  problems.push(...namingProblems(draft.vulnerabilityStatuses, 'vulnerability status'));
  for (const row of draft.vulnerabilityStatuses) {
    const name = row.name.trim();
    if (builtInVulnerabilityStatuses.includes(name)) {
      problems.push(`"${name}" is a built-in vulnerability status.`);
    }
  }
  if (draft.stages.length === 0) problems.push('Add at least one remediation stage.');
  if (draft.stages.some((stage) => !stage.name.trim())) problems.push('Every remediation stage needs a name.');
  return problems;
}

/** The background rename a vulnerability status row is part of, if any. Such a row can't be edited or removed yet. */
export function renameInProgressFor(row: DraftRow, workflow: Workflow): WorkflowRenameInProgress | undefined {
  if (row.originalName === null) return undefined;
  return (workflow.renamesInProgress ?? []).find(
    (rename) => rename.toName === row.originalName || rename.fromName === row.originalName,
  );
}
