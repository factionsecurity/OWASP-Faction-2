import { useCallback, useEffect, useRef, useState } from 'react';
import { Archive, ArchiveRestore, Plus, Trash2 } from 'lucide-react';
import { workflowsApi } from '../../api';
import type { Workflow, WorkflowUsage } from '../../types';
import {
  Badge,
  Button,
  ConfirmDialog,
  ErrorMessage,
  FormGroup,
  FormHint,
  FormLabel,
  Input,
  Modal,
  Select,
  Toast,
} from '../../components';
import { PaidBadge } from '../../components/PaidFeature';
import { useEdition } from '../../context/EditionContext';
import { DEFAULT_WORKFLOW_ID, useWorkflows } from '../../hooks/useWorkflow';
import { plural, workflowErrorMessages } from '../../utils/workflowErrors';
import WorkflowEditor from './WorkflowEditor';
import './WorkflowsTab.css';

/** How often a workflow with renames still running is re-read, so their progress shows. */
const RENAME_POLL_MS = 5000;

interface Props {
  /** Called after anything that changes the workflow list: save, create, archive, unarchive, delete. */
  onWorkflowsChanged?: () => void;
  /** Whether this tab is the one currently shown. The tab stays mounted while hidden, so usage counts
   * and the workflow list can go stale if a type's workflow changes elsewhere while this is hidden. */
  active?: boolean;
}

/**
 * Assessment Config → Workflows. The list on the left: Default Workflow first, then active workflows,
 * then the archived group, each with how many types and assessments use it. The selected workflow's
 * editor is on the right.
 */
export default function WorkflowsTab({ onWorkflowsChanged, active: isActive }: Props) {
  const canCreate = useEdition().hasFeature('custom_workflows');
  const { workflows, loading, error: loadError, reload } = useWorkflows(true);
  const [usage, setUsage] = useState<Record<string, WorkflowUsage>>({});
  const [selectedId, setSelectedId] = useState<string>(DEFAULT_WORKFLOW_ID);
  const [selected, setSelected] = useState<Workflow | null>(null);
  const [dirty, setDirty] = useState(false);
  const [actionErrors, setActionErrors] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [newName, setNewName] = useState('');
  const [newSourceId, setNewSourceId] = useState<string>(DEFAULT_WORKFLOW_ID);
  const [newErrors, setNewErrors] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const [toast, setToast] = useState<{ key: number; message: string } | null>(null);

  const latestSelectRef = useRef<string>(DEFAULT_WORKFLOW_ID);
  const dirtyRef = useRef(false);
  useEffect(() => { dirtyRef.current = dirty; }, [dirty]);

  const notify = (message: string) => setToast({ key: Date.now(), message });

  const loadUsage = useCallback(async () => {
    try {
      const res = await workflowsApi.usage();
      setUsage(Object.fromEntries((res.data ?? []).map((u) => [u.workflowId, u])));
    } catch {
      // Counts are informational; without them Delete stays hidden.
    }
  }, []);

  useEffect(() => { loadUsage(); }, [loadUsage]);

  // Mount already loads both the list and usage. If a type's workflow changes elsewhere (e.g. the
  // Types tab) while this tab is hidden, refresh both when it becomes active again so Archive/Delete
  // reflect current usage.
  const activeMountedRef = useRef(false);
  useEffect(() => {
    if (!activeMountedRef.current) {
      activeMountedRef.current = true;
      return;
    }
    if (isActive) {
      loadUsage();
      reload();
    }
  }, [isActive, loadUsage, reload]);

  // The editor works on its own fresh copy of the workflow, not the list entry, so a save or a
  // re-read replaces exactly what it shows.
  const loadSelected = useCallback(async (id: string, opts?: { fromPoll?: boolean }) => {
    latestSelectRef.current = id;
    try {
      const res = await workflowsApi.get(id);
      if (latestSelectRef.current === id) {
        if (opts?.fromPoll && dirtyRef.current) {
          // The admin started editing while this re-read was in flight; applying it would wipe
          // their unsaved changes when WorkflowEditor resets its draft to the new baseline.
          return;
        }
        setSelected(res.data ?? null);
      }
    } catch (err) {
      if (latestSelectRef.current === id) {
        setActionErrors(workflowErrorMessages(err, 'Failed to load the workflow'));
      }
    }
  }, []);

  useEffect(() => {
    setSelected(null);
    setActionErrors([]);
    loadSelected(selectedId);
  }, [selectedId, loadSelected]);

  // While vulnerability status renames run, re-read the workflow so their progress shows. Never re-read
  // over unsaved edits: that would reset the editor.
  useEffect(() => {
    if (!selected || selected.renamesInProgress.length === 0 || dirty) return;
    const timer = setTimeout(() => loadSelected(selected.id, { fromPoll: true }), RENAME_POLL_MS);
    return () => clearTimeout(timer);
  }, [selected, dirty, loadSelected]);

  const select = (id: string) => {
    if (id !== selectedId) setSelectedId(id);
  };

  const listChanged = async () => {
    await Promise.all([reload(), loadUsage()]);
    onWorkflowsChanged?.();
  };

  const handleSaved = (saved: Workflow, stillOpen: boolean) => {
    // A save can land after its editor has gone: another workflow is open now, or a second session of
    // the same one. Only an editor still on screen takes the result; the list refreshes either way.
    if (stillOpen) setSelected((prev) => (prev && prev.id === saved.id ? saved : prev));
    listChanged();
  };

  const setArchived = async (archive: boolean) => {
    if (!selected) return;
    const id = selected.id;
    setBusy(true);
    setActionErrors([]);
    try {
      if (archive) await workflowsApi.archive(id);
      else await workflowsApi.unarchive(id);
      await Promise.all([listChanged(), loadSelected(id)]);
      notify(archive ? 'Archived' : 'Unarchived');
    } catch (err) {
      setActionErrors(workflowErrorMessages(err, archive ? 'Failed to archive the workflow' : 'Failed to unarchive the workflow'));
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!selected) return;
    setDeleting(true);
    setActionErrors([]);
    try {
      await workflowsApi.delete(selected.id);
      setConfirmDelete(false);
      setSelectedId(DEFAULT_WORKFLOW_ID);
      await listChanged();
      notify('Deleted');
    } catch (err) {
      setConfirmDelete(false);
      setActionErrors(workflowErrorMessages(err, 'Failed to delete the workflow'));
    } finally {
      setDeleting(false);
    }
  };

  const openNew = () => {
    setNewName('');
    setNewSourceId(selectedId);
    setNewErrors([]);
    setShowNew(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    const name = newName.trim();
    if (!name) {
      setNewErrors(['Enter a name.']);
      return;
    }
    setCreating(true);
    setNewErrors([]);
    try {
      const res = await workflowsApi.create({ sourceWorkflowId: newSourceId, name });
      setShowNew(false);
      await listChanged();
      if (res.data) setSelectedId(res.data.id);
      notify('Workflow created');
    } catch (err) {
      setNewErrors(workflowErrorMessages(err, 'Failed to create the workflow'));
    } finally {
      setCreating(false);
    }
  };

  const isDefault = (w: Workflow) => w.defaultWorkflow || w.id === DEFAULT_WORKFLOW_ID;
  const active = workflows.filter((w) => !w.archived);
  const archived = workflows.filter((w) => w.archived);
  const selectedUsage = selected ? usage[selected.id] : undefined;
  const typeCount = selectedUsage?.assessmentTypeCount ?? 0;
  const canDelete = !!selected && !isDefault(selected) && !!selectedUsage
    && selectedUsage.assessmentTypeCount === 0 && selectedUsage.assessmentCount === 0;

  const listItem = (w: Workflow) => {
    const u = usage[w.id];
    return (
      <button
        type="button"
        key={w.id}
        className={`workflow-list-item${w.id === selectedId ? ' active' : ''}`}
        onClick={() => select(w.id)}
      >
        <span className="workflow-list-name">
          {w.name}
          {isDefault(w) && <Badge variant="primary" size="sm">Default</Badge>}
        </span>
        {u && (
          <span className="workflow-list-usage">
            {plural(u.assessmentTypeCount, 'type')} · {plural(u.assessmentCount, 'assessment')}
          </span>
        )}
      </button>
    );
  };

  return (
    <div className="config-section workflow-config-section">
      <div className="section-header">
        <h2>Workflows</h2>
        <div className="section-actions">
          {!canCreate && <PaidBadge />}
          <Button
            variant="primary"
            icon={Plus}
            onClick={openNew}
            disabled={!canCreate}
            title={!canCreate ? 'Not in this edition' : undefined}
          >
            New Workflow
          </Button>
        </div>
      </div>

      {loadError && <ErrorMessage>{loadError}</ErrorMessage>}

      <div className="workflows-layout">
        <nav className="workflow-list" aria-label="Workflows">
          {loading && workflows.length === 0
            ? <div className="workflow-list-empty">Loading…</div>
            : active.map(listItem)}
          {archived.length > 0 && (
            <>
              <div className="workflow-list-group">Archived</div>
              {archived.map(listItem)}
            </>
          )}
        </nav>

        <div className="workflow-detail">
          {actionErrors.length > 0 && (
            <div className="workflow-errors" role="alert">
              <ul>
                {actionErrors.map((message, i) => <li key={i}>{message}</li>)}
              </ul>
            </div>
          )}

          {selected && (
            <>
              <div className="workflow-detail-header">
                <h3>{selected.name}</h3>
                {isDefault(selected) && <Badge variant="primary" size="sm">Default</Badge>}
                {selected.archived && <Badge variant="secondary" size="sm">Archived</Badge>}
                <div className="section-actions">
                  {!isDefault(selected) && (selected.archived ? (
                    <Button
                      variant="secondary"
                      size="sm"
                      icon={ArchiveRestore}
                      onClick={() => setArchived(false)}
                      disabled={busy}
                    >
                      Unarchive
                    </Button>
                  ) : (
                    <Button
                      variant="secondary"
                      size="sm"
                      icon={Archive}
                      onClick={() => setArchived(true)}
                      disabled={busy || typeCount > 0}
                      title={typeCount > 0
                        ? `Used by ${plural(typeCount, 'assessment type')}; give them another workflow first`
                        : undefined}
                    >
                      Archive
                    </Button>
                  ))}
                  {canDelete && (
                    <Button
                      variant="danger"
                      size="sm"
                      icon={Trash2}
                      onClick={() => setConfirmDelete(true)}
                      disabled={busy}
                    >
                      Delete
                    </Button>
                  )}
                </div>
              </div>

              <WorkflowEditor workflow={selected} onSaved={handleSaved} onDirtyChange={setDirty} />
            </>
          )}
        </div>
      </div>

      <Modal
        isOpen={showNew}
        onClose={() => setShowNew(false)}
        title="New Workflow"
        size="sm"
        closeOnOverlayClick={false}
        onSubmit={handleCreate}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowNew(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={creating}>
              {creating ? 'Creating…' : 'Create'}
            </Button>
          </>
        }
      >
        {newErrors.length > 0 && <ErrorMessage>{newErrors.join(' ')}</ErrorMessage>}
        <FormGroup>
          <FormLabel required>Name</FormLabel>
          <Input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="e.g. PCI Assessments" autoFocus />
        </FormGroup>
        <FormGroup>
          <FormLabel>Copy settings from</FormLabel>
          <Select value={newSourceId} onChange={(e) => setNewSourceId(e.target.value)}>
            {workflows.map((w) => (
              <option key={w.id} value={w.id}>{w.name}{w.archived ? ' (archived)' : ''}</option>
            ))}
          </Select>
          <FormHint>
            The new workflow starts with a copy of its statuses, SLAs, vulnerability statuses and remediation stages.
          </FormHint>
        </FormGroup>
      </Modal>

      <ConfirmDialog
        isOpen={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onConfirm={handleDelete}
        title="Delete Workflow"
        message={`Delete "${selected?.name ?? ''}"? No assessment type or assessment uses it. This cannot be undone.`}
        confirmText="Delete"
        variant="danger"
        isLoading={deleting}
      />

      {toast && <Toast key={toast.key} message={toast.message} onDone={() => setToast(null)} />}
    </div>
  );
}
