import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, Check, X } from 'lucide-react';
import { subOrganizationsApi } from '../api';
import type { SubOrganization } from '../types';
import { Button, IconButton, Input } from './index';
import ConfirmDialog from './ConfirmDialog';
import './SubOrganizationsPanel.css';
import { useTerminology } from '../context/TerminologyContext';

export interface SubOrganizationsPanelProps {
  organizationId: string;
  /** False renders the list read-only — no add, rename or delete. */
  canWrite: boolean;
}

/**
 * Manages an organization's divisions. A sub-organization is an attribution applied to
 * applications, not an access boundary, so this is plain CRUD gated on the same permission as
 * editing the organization itself.
 */
export default function SubOrganizationsPanel({ organizationId, canWrite }: SubOrganizationsPanelProps) {
  const { subOrganizationPlural } = useTerminology();
  const [subs, setSubs] = useState<SubOrganization[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [newName, setNewName] = useState('');
  const [adding, setAdding] = useState(false);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const [savingEdit, setSavingEdit] = useState(false);

  const [pendingDelete, setPendingDelete] = useState<SubOrganization | null>(null);
  const [deleting, setDeleting] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await subOrganizationsApi.list(organizationId);
      setSubs(res.data ?? []);
    } catch {
      setSubs([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [organizationId]);

  const handleAdd = async () => {
    const name = newName.trim();
    if (!name) return;
    setAdding(true);
    setError(null);
    try {
      await subOrganizationsApi.create(organizationId, { name });
      setNewName('');
      await load();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to add sub-organization');
    } finally {
      setAdding(false);
    }
  };

  const handleRename = async (sub: SubOrganization) => {
    const name = editName.trim();
    if (!name) return;
    setSavingEdit(true);
    setError(null);
    try {
      await subOrganizationsApi.update(organizationId, sub.id, { name, description: sub.description });
      setEditingId(null);
      await load();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to rename sub-organization');
    } finally {
      setSavingEdit(false);
    }
  };

  const handleDelete = async () => {
    if (!pendingDelete) return;
    setDeleting(true);
    setError(null);
    try {
      await subOrganizationsApi.delete(organizationId, pendingDelete.id);
      setPendingDelete(null);
      await load();
    } catch (err: any) {
      // The server refuses while applications still point here, and says how many.
      setError(err.response?.data?.message || 'Failed to delete sub-organization');
      setPendingDelete(null);
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="form-panel sub-orgs-panel">
      <h3 className="form-section-title">{subOrganizationPlural}</h3>
      <p className="sub-orgs-intro">
        Divisions within this organization — business units, subsidiaries or regions. Applications
        can be attributed to one. This does not change who can see the application.
      </p>

      {error && <div className="sub-orgs-error">{error}</div>}

      {loading ? (
        <div className="sub-orgs-empty">Loading…</div>
      ) : subs.length === 0 ? (
        <div className="sub-orgs-empty">No sub-organizations yet.</div>
      ) : (
        <ul className="sub-orgs-list">
          {subs.map(sub => (
            <li key={sub.id} className="sub-orgs-item">
              {editingId === sub.id ? (
                <>
                  <Input
                    value={editName}
                    onChange={e => setEditName(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') handleRename(sub); }}
                    autoFocus
                  />
                  <IconButton icon={Check} variant="edit" title="Save"
                    onClick={() => handleRename(sub)} disabled={savingEdit} />
                  <IconButton icon={X} variant="default" title="Cancel"
                    onClick={() => setEditingId(null)} disabled={savingEdit} />
                </>
              ) : (
                <>
                  <span className="sub-orgs-name">{sub.name}</span>
                  <span className="sub-orgs-count">
                    {sub.applicationCount} application{sub.applicationCount === 1 ? '' : 's'}
                  </span>
                  {canWrite && (
                    <>
                      <IconButton icon={Pencil} variant="edit" title="Rename"
                        onClick={() => { setEditingId(sub.id); setEditName(sub.name); }} />
                      <IconButton icon={Trash2} variant="delete" title="Delete"
                        onClick={() => setPendingDelete(sub)} />
                    </>
                  )}
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      {canWrite && (
        <div className="sub-orgs-add">
          <Input
            value={newName}
            onChange={e => setNewName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); handleAdd(); } }}
            placeholder="New sub-organization name"
          />
          <Button type="button" variant="secondary" size="sm" onClick={handleAdd} disabled={adding || !newName.trim()}>
            <Plus size={14} />
            {adding ? 'Adding…' : 'Add'}
          </Button>
        </div>
      )}

      <ConfirmDialog
        isOpen={!!pendingDelete}
        onClose={() => setPendingDelete(null)}
        onConfirm={handleDelete}
        title="Delete Sub-Organization"
        message={pendingDelete?.applicationCount
          ? `"${pendingDelete.name}" still has ${pendingDelete.applicationCount} application`
            + `${pendingDelete.applicationCount === 1 ? '' : 's'} assigned to it. Reassign them first — `
            + 'this delete will be refused.'
          : `Delete "${pendingDelete?.name}"? This cannot be undone.`}
        confirmText="Delete"
        variant="danger"
        isLoading={deleting}
      />
    </div>
  );
}
