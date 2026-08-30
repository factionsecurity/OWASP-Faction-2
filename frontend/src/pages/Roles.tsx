import { useEffect, useMemo, useState, useCallback } from 'react';
import { PaidBadge } from '../components/PaidFeature';
import { useEdition } from '../context/EditionContext';
import { Edit2, Trash2, Plus, Copy } from 'lucide-react';
import { rolesApi, permissionsApi } from '../api';
import type { Role, CreateRoleRequest, UpdateRoleRequest, ResourcePermissions, PermissionInfo } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import {
  Modal,
  Button,
  IconButton,
  ActionButtons,
  Badge,
  FormGroup,
  FormHint,
  FormLabel,
  FormRow,
  Input,
  Checkbox,
  ErrorMessage,
} from '../components';
import Page from '../components/Page';
import './Roles.css';

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/**
 * Scope tiers, widest first — the row order inside a permission group, and what each tier
 * actually means at runtime (AccessScopeService / VulnerabilityScopeResolver resolve these).
 */
const SCOPES: Record<string, { label: string; description: string }> = {
  all: { label: 'All', description: 'Every record in the system' },
  org: { label: 'Organization', description: "Records in the user's own organization" },
  team: { label: 'Team', description: "Records belonging to the user's teams" },
  owned: { label: 'Owned', description: 'Applications or organizations assigned to the user' },
  assigned: { label: 'Assigned', description: 'Records the user is assigned to' },
  assessment: { label: 'Assessment', description: 'Records inside an assessment the user can reach' },
  self: { label: 'Self', description: "The user's own records" },
  system: { label: 'System', description: 'System (service-account) records' },
};

/** Column order. The four standard verbs first, then the ones only a few resources use. */
const ACTIONS: Record<string, string> = {
  read: 'Read',
  edit: 'Edit',
  create: 'Create',
  delete: 'Delete',
  write: 'Write',
  download: 'Download',
  comment: 'Comment',
  retest: 'Retest',
  complete: 'Complete',
};
const ACTION_ORDER = Object.keys(ACTIONS);
const SCOPE_ORDER = Object.keys(SCOPES);

/**
 * Splits a permission key into the row/column coordinates of the matrix.
 *
 * Parsed from the right, because the prefix is not always a single segment: "sso:config:read"
 * and "audit:logs:read" are subject "sso:config" / "audit:logs" with no scope, not
 * subject "sso" scoped to "read". A trailing segment counts as a scope only when it is a known
 * tier; otherwise the key is unscoped ("reporting:create", "survey:complete").
 */
function parsePermission(key: string): { subject: string; action: string; scope: string | null } {
  const parts = key.split(':');
  if (parts.length >= 3 && SCOPES[parts[parts.length - 1]]) {
    return {
      subject: parts.slice(0, -2).join(':'),
      action: parts[parts.length - 2],
      scope: parts[parts.length - 1],
    };
  }
  if (parts.length >= 2) {
    return { subject: parts.slice(0, -1).join(':'), action: parts[parts.length - 1], scope: null };
  }
  return { subject: key, action: key, scope: null };
}

/** Words that are acronyms, so "sso:config" reads as "SSO Config" and not "Sso Config". */
const ACRONYMS = new Set(['sso', 'ai', 'api', 'id']);

const humanize = (subject: string) =>
  subject
    .split(':')
    .map(part =>
      part
        .split(/[-_]/)
        .map(word => (ACRONYMS.has(word) ? word.toUpperCase() : cap(word)))
        .join(' ')
    )
    .join(' ');

interface MatrixRow {
  key: string;
  label: string;
  description: string;
  /** action -> the permission that fills that cell; a missing action leaves the cell empty. */
  cells: Record<string, PermissionInfo>;
}

/**
 * Reshapes a resource's flat permission list into rows (scope tiers) x columns (actions).
 * Columns are per group: only the actions that resource actually has get a column, so
 * Reporting doesn't carry four empty verb columns to show one Download.
 */
function buildMatrix(group: ResourcePermissions): { actions: string[]; rows: MatrixRow[] } {
  const rows = new Map<string, MatrixRow>();
  const actions = new Set<string>();

  // The subject most of the group's permissions share (e.g. "assessments") — those rows are
  // labelled by scope alone, and only the odd ones out ("sso:config") name their subject.
  const subjectCounts = new Map<string, number>();
  for (const p of group.permissions) {
    const { subject } = parsePermission(p.permission);
    subjectCounts.set(subject, (subjectCounts.get(subject) ?? 0) + 1);
  }
  // Only when it actually dominates: System Config is five unrelated subjects with no majority,
  // and labelling one of them by scope alone would hide which setting it is.
  const [topSubject, topCount] = [...subjectCounts.entries()].sort((a, b) => b[1] - a[1])[0] ?? [];
  const primarySubject = topCount && topCount > group.permissions.length / 2 ? topSubject : null;

  for (const p of group.permissions) {
    const { subject, action, scope } = parsePermission(p.permission);
    actions.add(action);
    const rowKey = `${subject}|${scope ?? ''}`;
    let row = rows.get(rowKey);
    if (!row) {
      const scopeMeta = scope ? SCOPES[scope] : undefined;
      const isPrimary = subject === primarySubject;
      row = {
        key: rowKey,
        label: isPrimary
          ? (scopeMeta?.label ?? 'General')
          : `${humanize(subject)}${scopeMeta ? ` (${scopeMeta.label})` : ''}`,
        description: scopeMeta?.description ?? '',
        cells: {},
      };
      rows.set(rowKey, row);
    }
    row.cells[action] = p;
  }

  // An unscoped row has no tier to explain; borrow the permission's own description when it is
  // the row's only one, so nothing is described by a sibling permission's text.
  for (const row of rows.values()) {
    const cells = Object.values(row.cells);
    if (!row.description && cells.length === 1) {
      row.description = cells[0].description;
    }
  }

  const ordered = [...rows.values()].sort((a, b) => {
    const scopeA = a.key.split('|')[1];
    const scopeB = b.key.split('|')[1];
    const rank = (scope: string) => (scope ? SCOPE_ORDER.indexOf(scope) : SCOPE_ORDER.length);
    return rank(scopeA) - rank(scopeB) || a.label.localeCompare(b.label);
  });

  return {
    actions: ACTION_ORDER.filter(a => actions.has(a)).concat(
      [...actions].filter(a => !ACTION_ORDER.includes(a)).sort()
    ),
    rows: ordered,
  };
}

export default function Roles() {
  const canCustomiseRoles = useEdition().hasFeature('custom_roles');
  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
  const [selectedRole, setSelectedRole] = useState<Role | null>(null);

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [searchQuery, setSearchQuery] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);

  // Assignable permissions, grouped by resource — served from the backend
  // Permission enum so newly added permissions appear without a UI change.
  const [permissionGroups, setPermissionGroups] = useState<ResourcePermissions[]>([]);

  const [formData, setFormData] = useState({
    name: '',
    description: '',
    permissions: [] as string[],
    externalRole: false,
  });

  useEffect(() => {
    loadRoles();
  }, [pagination.page, pagination.pageSize, searchQuery, sort]);

  useEffect(() => {
    permissionsApi
      .getAll()
      .then(res => setPermissionGroups(res.data || []))
      .catch(() => setError('Failed to load available permissions'));
  }, []);

  const loadRoles = async () => {
    try {
      setLoading(true);
      const response = await rolesApi.getAll(
        pagination.page, pagination.pageSize, searchQuery, sortParam(sort));
      if (response.data) {
        setRoles(response.data);
        setPagination({
          page: response.pagination?.page || 0,
          pageSize: response.pagination?.size || 10,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        });
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load roles');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = () => {
    setModalMode('create');
    setSelectedRole(null);
    setFormData({
      name: '',
      description: '',
      permissions: [],
      externalRole: false,
    });
    setError('');
    setShowModal(true);
  };

  /**
   * Duplicate: opens the create form pre-filled from an existing role, so a new role can start
   * from a known-good permission set. Only permissions in the assignable catalog are carried —
   * the form cannot express anything else (super_admin has no checkbox), and silently minting a
   * role that grants more than the form shows would be a privilege-escalation path.
   */
  const handleCopy = (role: Role) => {
    const assignable = new Set(permissionGroups.flatMap(g => g.permissions.map(p => p.permission)));
    setModalMode('create');
    setSelectedRole(null);
    setFormData({
      name: `${role.name} (Copy)`,
      description: role.description,
      permissions: role.permissions.filter(p => assignable.has(p)),
      externalRole: !!role.externalRole,
    });
    setError('');
    setShowModal(true);
  };

  const handleEdit = (role: Role) => {
    setModalMode('edit');
    setSelectedRole(role);
    setFormData({
      name: role.name,
      description: role.description,
      permissions: role.permissions,
      externalRole: !!role.externalRole,
    });
    setError('');
    setShowModal(true);
  };

  const handleDelete = async (roleId: string) => {
    if (!confirm('Are you sure you want to delete this role?')) return;

    try {
      await rolesApi.delete(roleId);
      await loadRoles();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to delete role');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    try {
      if (modalMode === 'create') {
        const createData: CreateRoleRequest = {
          name: formData.name,
          description: formData.description,
          permissions: formData.permissions,
          externalRole: formData.externalRole,
        };
        await rolesApi.create(createData);
      } else if (selectedRole) {
        // Preserve any permissions the role has that aren't in the assignable list
        // (e.g. super_admin, or everything if the permission fetch failed)
        const knownKeys = new Set(permissionGroups.flatMap(g => g.permissions.map(p => p.permission)));
        const unknownPerms = selectedRole.permissions.filter(p => !knownKeys.has(p));
        const updateData: UpdateRoleRequest = {
          name: formData.name,
          description: formData.description,
          permissions: [...new Set([...formData.permissions, ...unknownPerms])],
          externalRole: formData.externalRole,
        };
        await rolesApi.update(selectedRole.id, updateData);
      }

      setShowModal(false);
      await loadRoles();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save role');
    }
  };

  const togglePermission = (permission: string) => {
    setFormData((prev) => {
      if (prev.permissions.includes(permission)) {
        return {
          ...prev,
          permissions: prev.permissions.filter((p) => p !== permission),
        };
      } else {
        return {
          ...prev,
          permissions: [...prev.permissions, permission],
        };
      }
    });
  };

  const getPermissionCount = (permissions: string[]) => {
    return permissions.length;
  };

  const permissionMatrix = useMemo(
    () => permissionGroups.map(group => ({ group, ...buildMatrix(group) })),
    [permissionGroups]
  );

  const handlePageChange = useCallback((page: number) => {
    setPagination((prev) => ({ ...prev, page }));
  }, []);

  const handlePageSizeChange = useCallback((pageSize: number) => {
    setPagination((prev) => ({ ...prev, page: 0, pageSize }));
  }, []);

  const handleSearchChange = useCallback((search: string) => {
    setSearchQuery(search);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  // Re-sorting reshuffles the whole result set, so the current page number is
  // meaningless afterwards — go back to the first page.
  const handleSortChange = useCallback((next: SortState | null) => {
    setSort(next);
    setPagination((prev) => ({ ...prev, page: 0 }));
  }, []);

  const columns: Column<Role>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      accessor: 'name',
      render: (role) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <span className="font-medium">{role.name}</span>
          {role.externalRole && <Badge variant="warning">External</Badge>}
        </div>
      ),
    },
    {
      header: 'Description',
      sortKey: 'description',
      accessor: 'description',
      render: (role) => <span className="text-sm text-muted">{role.description}</span>,
    },
    {
      header: 'Permissions',
      render: (role) => (
        <Badge variant="info">{getPermissionCount(role.permissions)} permissions</Badge>
      ),
    },
    {
      header: 'Actions',
      width: '160px',
      render: (role) => (
        <ActionButtons>
          <IconButton
            icon={Edit2}
            variant="edit"
            title="Edit"
            onClick={() => handleEdit(role)}
            disabled={!canCustomiseRoles}
          />
          <IconButton
            icon={Copy}
            variant="default"
            title="Duplicate"
            onClick={() => handleCopy(role)}
          />
          <IconButton
            icon={Trash2}
            variant="delete"
            title="Delete"
            onClick={() => handleDelete(role.id)}
            disabled={!canCustomiseRoles}
          />
        </ActionButtons>
      ),
    },
  ];

  return (
    <Page className="roles-page">
      <div className="page-header">
        <div />
        <div className="page-header-actions">
          {!canCustomiseRoles && <PaidBadge pill />}
          <Button
            variant="primary"
            icon={Plus}
            onClick={handleCreate}
            disabled={!canCustomiseRoles}
            title={canCustomiseRoles
              ? undefined
              : 'This edition ships Super Admin and Pentester'}
          >
            Create Role
          </Button>
        </div>
      </div>

      <DataTable
        columns={columns}
        data={roles}
        loading={loading}
        pagination={pagination}
        onPageChange={handlePageChange}
        onPageSizeChange={handlePageSizeChange}
        onSearchChange={handleSearchChange}
        searchPlaceholder="Search roles"
        emptyMessage="No roles found"
        idAccessor="id"
        sort={sort}
        onSortChange={handleSortChange}
      />

      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title={modalMode === 'create' ? 'Create New Role' : 'Edit Role'}
        size="xl"
        closeOnOverlayClick={false}
        onSubmit={handleSubmit}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowModal(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="primary">
              {modalMode === 'create' ? 'Create' : 'Save Changes'}
            </Button>
          </>
        }
      >
        {error && <ErrorMessage>{error}</ErrorMessage>}

        <FormRow columns={2}>
          <FormGroup>
            <FormLabel required>Role Name</FormLabel>
            <Input
              type="text"
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              required
              placeholder="Enter role name"
            />
          </FormGroup>

          <FormGroup>
            <FormLabel required>Description</FormLabel>
            <Input
              type="text"
              value={formData.description}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
              required
              placeholder="Enter role description"
            />
          </FormGroup>
        </FormRow>

        <FormGroup>
          <Checkbox
            label="External role"
            checked={formData.externalRole}
            onChange={(e) => setFormData({ ...formData, externalRole: e.target.checked })}
          />
          <FormHint>
            Makes this role assignable to external (client) users. External roles should
            use only organization- or application-scoped permissions.
          </FormHint>
        </FormGroup>

        <div className="permissions-section">
          <h4 className="section-title">Permissions</h4>

          {permissionGroups.length === 0 && (
            <p className="text-muted">Loading available permissions…</p>
          )}
          {permissionMatrix.map(({ group, actions, rows }) => (
            <div key={group.resource} className="permission-group">
              <h5 className="permission-group-title">{group.displayName || group.resource}</h5>
              <table className="permission-matrix">
                <thead>
                  <tr>
                    <th className="pm-scope-col">Permission</th>
                    {actions.map(action => (
                      <th key={action} className="pm-action-col">{ACTIONS[action] ?? cap(action)}</th>
                    ))}
                    <th className="pm-spacer-col" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map(row => (
                    <tr key={row.key}>
                      <td className="pm-scope-col">
                        <span className="permission-label">{row.label}</span>
                        {row.description && (
                          <span className="permission-description">{row.description}</span>
                        )}
                      </td>
                      {actions.map(action => {
                        const permission = row.cells[action];
                        return (
                          <td key={action} className="pm-action-col">
                            {/* No permission for this scope/action pair — leave the cell empty
                                rather than showing a checkbox that grants nothing. */}
                            {permission && (
                              <input
                                type="checkbox"
                                aria-label={permission.description}
                                title={`${permission.description} (${permission.permission})`}
                                checked={formData.permissions.includes(permission.permission)}
                                onChange={() => togglePermission(permission.permission)}
                              />
                            )}
                          </td>
                        );
                      })}
                      <td className="pm-spacer-col" />
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      </Modal>
    </Page>
  );
}
