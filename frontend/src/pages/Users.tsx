import { useEffect, useState, useCallback, useRef } from 'react';
import { useEdition } from '../context/EditionContext';
import { Edit2, Trash2, Plus, X, Search, Mail, Check, UserX, UserCheck } from 'lucide-react';
import { usersApi, rolesApi, teamsApi, organizationsApi, applicationsApi, azureUsersApi } from '../api';
import type { User, Role, Team, Organization, Application, CreateUserRequest, UpdateUserRequest, AzureDirectoryUser } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import SearchableSelect, { SelectOption } from '../components/SearchableSelect';
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
  Select,
  Checkbox,
  ErrorMessage,
  ConfirmDialog,
} from '../components';
import Page from '../components/Page';
import './Users.css';
import { useTerminology } from '../context/TerminologyContext';

export default function Users() {
  const { organizationLower, organizationPlural, organizationSingular } = useTerminology();
  const hasExternalOwners = useEdition().hasFeature('external_owners');
  const [users, setUsers] = useState<User[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [applications, setApplications] = useState<Application[]>([]);
  const [accessScope, setAccessScope] = useState<'organization' | 'application'>('organization');
  const [appAssignments, setAppAssignments] = useState<{ applicationId: string; accessLevel: 'READ' | 'WRITE' }[]>([]);
  const [appSearchQuery, setAppSearchQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
  const [selectedUser, setSelectedUser] = useState<User | null>(null);
  const [roleSearchQuery, setRoleSearchQuery] = useState('');
  const [teamSearchQuery, setTeamSearchQuery] = useState('');

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [searchQuery, setSearchQuery] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);
  // Table filters, mirroring the vulnerabilities page: each narrows the server-side query.
  const [filterRoleId, setFilterRoleId] = useState('');
  const [filterTeamId, setFilterTeamId] = useState('');
  const [filterOrganizationId, setFilterOrganizationId] = useState('');
  const [filterType, setFilterType] = useState<'INTERNAL' | 'EXTERNAL' | ''>('');
  const [resetSentId, setResetSentId] = useState<string | null>(null);

  // Azure (Entra ID) directory typeahead — active when Graph lookup is
  // configured in SSO settings (SAML2 tenants)
  const [azureLookupEnabled, setAzureLookupEnabled] = useState(false);
  const [azureSuggestions, setAzureSuggestions] = useState<AzureDirectoryUser[]>([]);
  const [azureOpen, setAzureOpen] = useState(false);
  const appliedAzureEmail = useRef('');
  const [confirmResetUser, setConfirmResetUser] = useState<User | null>(null);
  // Disable / re-enable, and delete — both confirmed, both acting on one row.
  const [confirmToggleUser, setConfirmToggleUser] = useState<User | null>(null);
  const [togglingDisabled, setTogglingDisabled] = useState(false);
  const [confirmDeleteUser, setConfirmDeleteUser] = useState<User | null>(null);
  const [deletingUser, setDeletingUser] = useState(false);
  const [sendingReset, setSendingReset] = useState(false);

  const [formData, setFormData] = useState({
    username: '',
    email: '',
    firstName: '',
    lastName: '',
    password: '',
    loginOption: 'NATIVE' as 'NATIVE' | 'SAML2' | 'OPENID',
    roleIds: [] as string[],
    teamIds: [] as string[],
    isInternal: true,
    organizationId: '',
  });

  useEffect(() => {
    azureUsersApi.enabled()
      .then(res => setAzureLookupEnabled(!!res.data?.enabled))
      .catch(() => setAzureLookupEnabled(false));
  }, []);

  // Debounced directory search as the admin types an email in create mode
  useEffect(() => {
    if (!azureLookupEnabled || modalMode !== 'create' || formData.email.trim().length < 3
        || formData.email === appliedAzureEmail.current) {
      setAzureSuggestions([]);
      setAzureOpen(false);
      return;
    }
    const timer = setTimeout(() => {
      azureUsersApi.search(formData.email.trim())
        .then(res => {
          const users = res.data ?? [];
          setAzureSuggestions(users);
          setAzureOpen(users.length > 0);
        })
        .catch(() => {
          setAzureSuggestions([]);
          setAzureOpen(false);
        });
    }, 300);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [formData.email, azureLookupEnabled, modalMode]);

  const applyAzureSuggestion = (u: AzureDirectoryUser) => {
    appliedAzureEmail.current = u.email || '';
    setFormData(prev => ({
      ...prev,
      email: u.email || prev.email,
      firstName: u.firstName || prev.firstName,
      lastName: u.lastName || prev.lastName,
      username: prev.username || (u.email ? u.email.split('@')[0] : ''),
      loginOption: 'SAML2',
    }));
    setAzureOpen(false);
    setAzureSuggestions([]);
  };

  useEffect(() => {
    loadRoles();
    loadTeams();
    loadOrganizations();
    loadApplications();
  }, []);

  useEffect(() => {
    loadUsers();
  }, [pagination.page, pagination.pageSize, searchQuery, sort,
      filterRoleId, filterTeamId, filterOrganizationId, filterType]);

  const loadUsers = async () => {
    try {
      setLoading(true);
      const response = await usersApi.getAll(
        pagination.page, pagination.pageSize, searchQuery, sortParam(sort),
        {
          roleId: filterRoleId,
          teamId: filterTeamId,
          organizationId: filterOrganizationId,
          type: filterType,
        });
      if (response.data) {
        setUsers(response.data);
        setPagination({
          page: response.pagination?.page || 0,
          pageSize: response.pagination?.size || 10,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        });
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load users');
    } finally {
      setLoading(false);
    }
  };

  const loadRoles = async () => {
    try {
      const response = await rolesApi.getAllUnpaginated();
      if (response.data) {
        setRoles(response.data);
      }
    } catch (err) {
      console.error('Failed to load roles:', err);
    }
  };

  const loadTeams = async () => {
    try {
      const response = await teamsApi.getAll(0, 1000);
      if (response.data) {
        setTeams(response.data);
      }
    } catch (err) {
      console.error('Failed to load teams:', err);
    }
  };

  const loadOrganizations = async () => {
    try {
      const response = await organizationsApi.getAll(0, 1000);
      if (response.data) {
        setOrganizations(response.data);
      }
    } catch (err) {
      console.error('Failed to load organizations:', err);
    }
  };

  const loadApplications = async () => {
    try {
      const response = await applicationsApi.getAll(0, 1000);
      if (response.data) {
        setApplications(response.data);
      }
    } catch (err) {
      console.error('Failed to load applications:', err);
    }
  };

  const handleCreate = () => {
    setModalMode('create');
    setSelectedUser(null);
    setRoleSearchQuery('');
    setTeamSearchQuery('');
    setAppSearchQuery('');
    setAccessScope('organization');
    setAppAssignments([]);
    setFormData({
      username: '',
      email: '',
      firstName: '',
      lastName: '',
      password: '',
      loginOption: 'NATIVE',
      roleIds: [],
      teamIds: [],
      isInternal: true,
      organizationId: '',
    });
    setShowModal(true);
  };

  const handleEdit = (user: User) => {
    setModalMode('edit');
    setSelectedUser(user);
    setRoleSearchQuery('');
    setTeamSearchQuery('');
    setAppSearchQuery('');
    // Load the user's application assignments; any present means app-level scope
    setAccessScope('organization');
    setAppAssignments([]);
    if (!user.isInternal) {
      usersApi.getApplicationAssignments(user.id)
        .then((res) => {
          const assigns = (res.data || []).map(a => ({
            applicationId: a.applicationId,
            accessLevel: a.accessLevel,
          }));
          if (assigns.length > 0) {
            setAppAssignments(assigns);
            setAccessScope('application');
          }
        })
        .catch(() => {});
    }
    setFormData({
      username: user.username,
      email: user.email,
      firstName: user.firstName,
      lastName: user.lastName,
      password: '',
      loginOption: user.loginOption,
      roleIds: user.roleIds,
      teamIds: user.teamIds || [],
      isInternal: user.isInternal,
      organizationId: user.organizationId || '',
    });
    setShowModal(true);
  };

  const handleSendReset = async () => {
    if (!confirmResetUser) return;
    setSendingReset(true);
    setError('');
    try {
      // The admin endpoint, not the public forgot-password one. That one answers 200 whatever
      // happens — deliberately, so it cannot be used to discover which addresses have accounts —
      // which meant an admin sending a link to an SSO user was told it worked when nothing was
      // sent at all.
      await usersApi.sendPasswordReset(confirmResetUser.id);
      setResetSentId(confirmResetUser.id);
      setTimeout(() => setResetSentId(null), 3000);
      setConfirmResetUser(null);
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Could not send the reset link.');
      setConfirmResetUser(null);
    } finally {
      setSendingReset(false);
    }
  };

  /**
   * Flip the account's disabled state. The update endpoint is a full replace, so the row's own
   * values are echoed back with only `disabled` changed — anything left out would be wiped.
   */
  const handleToggleDisabled = async () => {
    const user = confirmToggleUser;
    if (!user) return;
    setTogglingDisabled(true);
    try {
      await usersApi.update(user.id, {
        username: user.username,
        email: user.email,
        firstName: user.firstName,
        lastName: user.lastName,
        loginOption: user.loginOption,
        roleIds: user.roleIds,
        teamIds: user.teamIds || [],
        isInternal: user.isInternal,
        organizationId: user.organizationId || '',
        disabled: !user.disabledAt,
      });
      setConfirmToggleUser(null);
      await loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to update the account');
    } finally {
      setTogglingDisabled(false);
    }
  };

  const handleDelete = async () => {
    if (!confirmDeleteUser) return;
    setDeletingUser(true);
    try {
      await usersApi.delete(confirmDeleteUser.id);
      setConfirmDeleteUser(null);
      await loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to delete user');
    } finally {
      setDeletingUser(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    try {
      let userId = selectedUser?.id;
      // App-level external users have no home organization; org scope uses the
      // selected org (which may be "No organization").
      const effectiveOrgId = (!formData.isInternal && accessScope === 'application')
        ? undefined
        : (formData.organizationId || undefined);

      if (modalMode === 'create') {
        const createData: CreateUserRequest = {
          username: formData.username,
          email: formData.email,
          firstName: formData.firstName,
          lastName: formData.lastName,
          password: formData.password,
          loginOption: formData.loginOption,
          roleIds: formData.roleIds,
          teamIds: formData.teamIds,
          isInternal: formData.isInternal,
          organizationId: effectiveOrgId,
        };
        const created = await usersApi.create(createData);
        userId = created.data?.id;
      } else if (selectedUser) {
        const updateData: UpdateUserRequest = {
          username: formData.username,
          email: formData.email,
          firstName: formData.firstName,
          lastName: formData.lastName,
          loginOption: formData.loginOption,
          roleIds: formData.roleIds,
          teamIds: formData.teamIds,
          isInternal: formData.isInternal,
          organizationId: effectiveOrgId,
        };
        await usersApi.update(selectedUser.id, updateData);
      }

      // Sync application assignments for external users. Application scope sends
      // the chosen apps; organization scope sends an empty list, clearing any
      // prior assignments so the user falls back to organization-level access.
      if (userId && !formData.isInternal) {
        const assignments = accessScope === 'application' ? appAssignments : [];
        await usersApi.syncApplicationAssignments(userId, assignments);
      }

      setShowModal(false);
      await loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save user');
    }
  };

  const getRoleNames = (roleIds: string[]) => {
    return roleIds
      .map(id => roles.find(r => r.id === id)?.name)
      .filter(Boolean)
      .join(', ') || 'No roles';
  };

  const getTeamNames = (teamIds?: string[]) => {
    if (!teamIds || teamIds.length === 0) return 'No teams';
    return teamIds
      .map(id => teams.find(t => t.id === id)?.name)
      .filter(Boolean)
      .join(', ');
  };

  const availableRoles = (): Role[] =>
    formData.isInternal ? roles : roles.filter(r => r.externalRole);

  const getFilteredRoles = (): Role[] => {
    const pool = availableRoles();
    if (!roleSearchQuery.trim()) return pool;
    const query = roleSearchQuery.toLowerCase();
    return pool.filter(r => {
      const name = (r.name || '').toLowerCase();
      const description = (r.description || '').toLowerCase();
      return name.includes(query) || description.includes(query);
    });
  };

  // Applications selectable for app-level assignment (org is cleared in this mode)
  const assignableApplications = (): Application[] => {
    if (!appSearchQuery.trim()) return applications;
    const query = appSearchQuery.toLowerCase();
    return applications.filter(a =>
      (a.name || '').toLowerCase().includes(query) ||
      (a.appId || '').toLowerCase().includes(query));
  };

  const toggleAppAssignment = (applicationId: string, checked: boolean) => {
    setAppAssignments(prev => checked
      ? [...prev, { applicationId, accessLevel: 'READ' }]
      : prev.filter(a => a.applicationId !== applicationId));
  };

  const setAppAccessLevel = (applicationId: string, accessLevel: 'READ' | 'WRITE') => {
    setAppAssignments(prev => prev.map(a =>
      a.applicationId === applicationId ? { ...a, accessLevel } : a));
  };

  const getOrgName = (orgId?: string): string => {
    if (!orgId) return '—';
    return organizations.find(o => o.id === orgId)?.name || orgId;
  };

  const getFilteredTeams = (): Team[] => {
    if (!teamSearchQuery.trim()) {
      return [];
    }

    const query = teamSearchQuery.toLowerCase();
    return teams.filter(t => {
      const name = (t.name || '').toLowerCase();
      const description = (t.description || '').toLowerCase();
      return name.includes(query) || description.includes(query);
    });
  };

  /** "First Last (username)", for confirmation copy that has to name exactly one person. */
  const userLabel = (user: User | null) =>
    user ? `${user.firstName} ${user.lastName} (${user.username})` : '';

  const getStatusBadge = (user: User) => {
    if (user.deletedAt) return <Badge variant="danger">Deleted</Badge>;
    if (user.disabledAt) {
      // Failed attempts survive the lockout precisely so this can say what happened; an admin
      // who switched the account off by hand leaves the counter at 0.
      const attempts = user.failedLoginAttempts ?? 0;
      const why = attempts > 0
        ? `Locked out after ${attempts} failed sign-in attempt${attempts === 1 ? '' : 's'}`
        : 'Disabled by an administrator';
      return <span title={why}><Badge variant="warning">Disabled</Badge></span>;
    }
    return <Badge variant="success">Active</Badge>;
  };

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

  // Filters narrow the query, so changing one invalidates the current page number.
  const resetPage = () => setPagination((prev) => ({ ...prev, page: 0 }));

  const toOptions = (items: { id: string; name: string }[]): SelectOption[] =>
    items.map((i) => ({ value: i.id, label: i.name }));

  const headerFilters = (
    <div className="ss-filter-bar">
      <SearchableSelect
        value={filterRoleId}
        onChange={(v) => { setFilterRoleId(v); resetPage(); }}
        options={toOptions(roles)}
        placeholder="All Roles"
      />
      <SearchableSelect
        value={filterTeamId}
        onChange={(v) => { setFilterTeamId(v); resetPage(); }}
        options={toOptions(teams)}
        placeholder="All Teams"
      />
      <SearchableSelect
        value={filterOrganizationId}
        onChange={(v) => { setFilterOrganizationId(v); resetPage(); }}
        options={toOptions(organizations)}
        placeholder={`All ${organizationPlural}`}
      />
      <SearchableSelect
        value={filterType}
        onChange={(v) => { setFilterType(v as 'INTERNAL' | 'EXTERNAL' | ''); resetPage(); }}
        options={[
          { value: 'INTERNAL', label: 'Internal' },
          { value: 'EXTERNAL', label: 'External' },
        ]}
        searchable={false}
        placeholder="All Types"
      />
    </div>
  );

  const columns: Column<User>[] = [
    {
      header: 'Name',
      sortKey: 'firstName',
      render: (user) => (
        <div>
          <div className="font-medium">{user.firstName} {user.lastName}</div>
          <div className="text-sm text-muted">{user.username}</div>
        </div>
      ),
    },
    {
      header: 'Email',
      accessor: 'email',
      sortKey: 'email',
      render: (user) => <span className="text-sm">{user.email}</span>,
    },
    {
      header: 'Login Method',
      accessor: 'loginOption',
      sortKey: 'loginOption',
      render: (user) => <Badge variant="info">{user.loginOption}</Badge>,
    },
    {
      header: 'Roles',
      render: (user) => (
        <span className="text-sm text-secondary">{getRoleNames(user.roleIds)}</span>
      ),
    },
    {
      header: 'Type',
      sortKey: 'isInternal',
      render: (user) => (
        <Badge variant={user.isInternal ? 'info' : 'warning'}>
          {user.isInternal ? 'Internal' : 'External'}
        </Badge>
      ),
    },
    {
      header: `Teams / ${organizationSingular}`,
      render: (user) => (
        <span className="text-sm text-secondary">
          {user.isInternal ? getTeamNames(user.teamIds) : getOrgName(user.organizationId)}
        </span>
      ),
    },
    {
      header: 'Status',
      sortKey: 'disabledAt',
      render: (user) => getStatusBadge(user),
    },
    {
      header: 'Last Login',
      sortKey: 'lastLogin',
      render: (user) => (
        <span className="text-sm text-muted">
          {user.lastLogin ? new Date(user.lastLogin).toLocaleDateString() : 'Never'}
        </span>
      ),
    },
    {
      header: 'Actions',
      width: '170px',
      render: (user) => (
        <ActionButtons>
          <IconButton
            icon={Edit2}
            variant="edit"
            title="Edit"
            onClick={() => handleEdit(user)}
          />
          {user.loginOption === 'NATIVE' && (
            <IconButton
              icon={resetSentId === user.id ? Check : Mail}
              variant="edit"
              title={resetSentId === user.id ? 'Reset link sent' : 'Send password reset link'}
              onClick={() => setConfirmResetUser(user)}
            />
          )}
          {/* Deleted accounts are shown badged so an admin can see them, but there is nothing
              left to switch on or off — the disable toggle is for live accounts. */}
          {!user.deletedAt && (
            <IconButton
              icon={user.disabledAt ? UserCheck : UserX}
              variant="warning"
              // Amber at rest on a disabled account, so the row reads as "off" from the action
              // column too and not only from the Status badge. Active rows stay neutral —
              // colouring both would make the yellow mean nothing.
              className={user.disabledAt ? 'user-toggle--disabled' : ''}
              title={user.disabledAt ? 'Re-enable account' : 'Disable account'}
              onClick={() => setConfirmToggleUser(user)}
            />
          )}
          <IconButton
            icon={Trash2}
            variant="delete"
            title="Delete"
            onClick={() => setConfirmDeleteUser(user)}
          />
        </ActionButtons>
      ),
    },
  ];

  return (
    <Page className="users-page">
      <div className="page-header">
        <div />
        <Button variant="primary" icon={Plus} onClick={handleCreate}>
          Create User
        </Button>
      </div>

      <DataTable
        columns={columns}
        data={users}
        loading={loading}
        pagination={pagination}
        onPageChange={handlePageChange}
        onPageSizeChange={handlePageSizeChange}
        onSearchChange={handleSearchChange}
        searchPlaceholder="Search users"
        emptyMessage="No users found"
        idAccessor="id"
        headerChildren={headerFilters}
        sort={sort}
        onSortChange={handleSortChange}
      />

      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title={modalMode === 'create' ? 'Create New User' : 'Edit User'}
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
            <FormLabel required>Username</FormLabel>
            <Input
              type="text"
              value={formData.username}
              onChange={(e) => setFormData({ ...formData, username: e.target.value })}
              required
              placeholder="Enter username"
            />
          </FormGroup>

          <FormGroup>
            <FormLabel required>Email</FormLabel>
            <div className="azure-typeahead">
              <Input
                type="email"
                value={formData.email}
                onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                onBlur={() => setTimeout(() => setAzureOpen(false), 150)}
                required
                placeholder={azureLookupEnabled && modalMode === 'create'
                  ? 'Type to search your directory' : 'Enter email'}
              />
              {azureOpen && (
                <ul className="azure-typeahead-list" role="listbox">
                  {azureSuggestions.map((u, i) => (
                    <li key={`${u.email}-${i}`}>
                      <button
                        type="button"
                        className="azure-typeahead-option"
                        onMouseDown={(e) => { e.preventDefault(); applyAzureSuggestion(u); }}
                      >
                        <span className="azure-typeahead-name">{u.displayName || u.email}</span>
                        <span className="azure-typeahead-email">{u.email}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </FormGroup>
        </FormRow>

        <FormRow columns={2}>
          <FormGroup>
            <FormLabel required>First Name</FormLabel>
            <Input
              type="text"
              value={formData.firstName}
              onChange={(e) => setFormData({ ...formData, firstName: e.target.value })}
              required
              placeholder="Enter first name"
            />
          </FormGroup>

          <FormGroup>
            <FormLabel required>Last Name</FormLabel>
            <Input
              type="text"
              value={formData.lastName}
              onChange={(e) => setFormData({ ...formData, lastName: e.target.value })}
              required
              placeholder="Enter last name"
            />
          </FormGroup>
        </FormRow>

        <FormRow columns={2}>
          {modalMode === 'create' && (
            <FormGroup>
              <FormLabel required>Password</FormLabel>
              <Input
                type="password"
                value={formData.password}
                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                required
                placeholder="Enter password"
              />
            </FormGroup>
          )}

          <FormGroup>
            <FormLabel>Login Method</FormLabel>
            <Select
              value={formData.loginOption}
              onChange={(e) =>
                setFormData({
                  ...formData,
                  loginOption: e.target.value as 'NATIVE' | 'SAML2' | 'OPENID',
                })
              }
            >
              <option value="NATIVE">Password</option>
              <option value="SAML2">SAML 2.0</option>
              <option value="OPENID">OpenID Connect</option>
            </Select>
          </FormGroup>
        </FormRow>

        <FormRow columns={2}>
          <FormGroup>
            <FormLabel required>Roles</FormLabel>
                  <div className="search-input-wrapper">
                    <Search size={18} className="search-icon" />
                    <input
                      type="text"
                      className="form-input search-input"
                      placeholder="Search for roles to add..."
                      value={roleSearchQuery}
                      onChange={(e) => setRoleSearchQuery(e.target.value)}
                    />
                    {roleSearchQuery && (
                      <button
                        className="clear-search"
                        onClick={() => setRoleSearchQuery('')}
                        type="button"
                      >
                        <X size={16} />
                      </button>
                    )}
                  </div>
                  <div className="user-select-group">
                    {getFilteredRoles().length === 0 ? (
                      <div className="empty-state">
                        {roleSearchQuery.trim()
                          ? `No roles found matching "${roleSearchQuery}"`
                          : 'No roles available'}
                      </div>
                    ) : (
                      getFilteredRoles().map((role) => (
                        <label key={role.id} className="checkbox-label">
                          <input
                            type="checkbox"
                            checked={formData.roleIds.includes(role.id)}
                            onChange={(e) => {
                              if (e.target.checked) {
                                setFormData({
                                  ...formData,
                                  roleIds: [...formData.roleIds, role.id],
                                });
                              } else {
                                setFormData({
                                  ...formData,
                                  roleIds: formData.roleIds.filter((id) => id !== role.id),
                                });
                              }
                            }}
                          />
                          <span>{role.name}</span>
                        </label>
                      ))
                    )}
                  </div>
          </FormGroup>

          {formData.isInternal ? (
            <FormGroup>
              <FormLabel>Teams</FormLabel>
                    <div className="search-input-wrapper">
                      <Search size={18} className="search-icon" />
                      <input
                        type="text"
                        className="form-input search-input"
                        placeholder="Search for teams to add..."
                        value={teamSearchQuery}
                        onChange={(e) => setTeamSearchQuery(e.target.value)}
                      />
                      {teamSearchQuery && (
                        <button
                          className="clear-search"
                          onClick={() => setTeamSearchQuery('')}
                          type="button"
                        >
                          <X size={16} />
                        </button>
                      )}
                    </div>
                    <div className="user-select-group">
                      {!teamSearchQuery.trim() ? (
                        formData.teamIds.length > 0 ? (
                          teams.filter(t => formData.teamIds.includes(t.id)).map((team) => (
                            <label key={team.id} className="checkbox-label">
                              <input
                                type="checkbox"
                                checked={true}
                                onChange={() => {
                                  setFormData({
                                    ...formData,
                                    teamIds: formData.teamIds.filter((id) => id !== team.id),
                                  });
                                }}
                              />
                              <span>{team.name}</span>
                            </label>
                          ))
                        ) : (
                          <div className="empty-state">
                            Start typing to search for teams
                          </div>
                        )
                      ) : getFilteredTeams().length === 0 ? (
                        <div className="empty-state">
                          No teams found matching "{teamSearchQuery}"
                        </div>
                      ) : (
                        getFilteredTeams().map((team) => (
                          <label key={team.id} className="checkbox-label">
                            <input
                              type="checkbox"
                              checked={formData.teamIds.includes(team.id)}
                              onChange={(e) => {
                                if (e.target.checked) {
                                  setFormData({
                                    ...formData,
                                    teamIds: [...formData.teamIds, team.id],
                                  });
                                } else {
                                  setFormData({
                                    ...formData,
                                    teamIds: formData.teamIds.filter((id) => id !== team.id),
                                  });
                                }
                              }}
                            />
                            <span>{team.name}</span>
                          </label>
                        ))
                      )}
                    </div>
            </FormGroup>
          ) : (
            <FormGroup>
              <FormLabel>Access</FormLabel>
              <div className="access-scope-toggle">
                <label className="radio-label">
                  <input
                    type="radio"
                    name="accessScope"
                    checked={accessScope === 'organization'}
                    onChange={() => setAccessScope('organization')}
                  />
                  <span>Entire {organizationLower}</span>
                </label>
                <label className="radio-label">
                  <input
                    type="radio"
                    name="accessScope"
                    checked={accessScope === 'application'}
                    onChange={() => {
                      setAccessScope('application');
                      setFormData(prev => ({ ...prev, organizationId: '' }));
                    }}
                  />
                  <span>Specific applications</span>
                </label>
              </div>

              {accessScope === 'organization' ? (
                <>
                  <Select
                    value={formData.organizationId}
                    onChange={(e) => setFormData({ ...formData, organizationId: e.target.value })}
                  >
                    <option value="">No {organizationLower}</option>
                    {organizations.map((org) => (
                      <option key={org.id} value={org.id}>{org.name}</option>
                    ))}
                  </Select>
                  <FormHint>
                    {formData.organizationId
                      ? `Full access to everything in the selected ${organizationLower}.`
                      : `No ${organizationLower}-wide access. Assign specific applications instead, or leave with no access.`}
                  </FormHint>
                </>
              ) : (
                <>
                  <div className="search-input-wrapper">
                    <Search size={18} className="search-icon" />
                    <input
                      type="text"
                      className="form-input search-input"
                      placeholder="Search applications..."
                      value={appSearchQuery}
                      onChange={(e) => setAppSearchQuery(e.target.value)}
                    />
                    {appSearchQuery && (
                      <button className="clear-search" onClick={() => setAppSearchQuery('')} type="button">
                        <X size={16} />
                      </button>
                    )}
                  </div>
                  <div className="user-select-group">
                    {assignableApplications().length === 0 ? (
                      <div className="empty-state">
                        {appSearchQuery.trim()
                          ? `No applications found matching "${appSearchQuery}"`
                          : 'No applications available'}
                      </div>
                    ) : (
                      assignableApplications().map((app) => {
                        const assignment = appAssignments.find(a => a.applicationId === app.id);
                        return (
                          <div key={app.id} className="app-assignment-row">
                            <label className="checkbox-label">
                              <input
                                type="checkbox"
                                checked={!!assignment}
                                onChange={(e) => toggleAppAssignment(app.id, e.target.checked)}
                              />
                              <span>{app.name}</span>
                            </label>
                            {assignment && (
                              <Select
                                value={assignment.accessLevel}
                                onChange={(e) => setAppAccessLevel(app.id, e.target.value as 'READ' | 'WRITE')}
                              >
                                <option value="READ">Read</option>
                                <option value="WRITE">Write</option>
                              </Select>
                            )}
                          </div>
                        );
                      })
                    )}
                  </div>
                  <FormHint>Access limited to the applications selected here.</FormHint>
                </>
              )}
            </FormGroup>
          )}
        </FormRow>

        {/* External users are portal accounts, which this build may not have. With no
            checkbox the form default stands and every account created here is internal. */}
        {hasExternalOwners && (
        <Checkbox
          label="Internal User"
          checked={formData.isInternal}
          onChange={(e) => {
            setFormData({
              ...formData,
              isInternal: e.target.checked,
              organizationId: '',
              teamIds: [],
              roleIds: [],
            });
            setAccessScope('organization');
            setAppAssignments([]);
            setAppSearchQuery('');
          }}
        />
        )}
      </Modal>

      <ConfirmDialog
        isOpen={!!confirmResetUser}
        onClose={() => setConfirmResetUser(null)}
        onConfirm={handleSendReset}
        title="Send Password Reset"
        message={`Send a password reset link to ${confirmResetUser?.firstName} ${confirmResetUser?.lastName} (${confirmResetUser?.email})? The link will expire after 1 hour.`}
        confirmText="Send Reset Link"
        variant="info"
        isLoading={sendingReset}
      />

      <ConfirmDialog
        isOpen={!!confirmToggleUser}
        onClose={() => setConfirmToggleUser(null)}
        onConfirm={handleToggleDisabled}
        title={confirmToggleUser?.disabledAt ? 'Re-enable Account' : 'Disable Account'}
        message={confirmToggleUser?.disabledAt
          ? `${userLabel(confirmToggleUser)} will be able to sign in again, and any API keys they own start working. This also clears the failed sign-in counter, so an old lockout won't lock them straight back out.`
          : `${userLabel(confirmToggleUser)} will not be able to sign in and their API keys stop working. They keep their history and can still be mentioned in comments — re-enable them here at any time.`}
        confirmText={confirmToggleUser?.disabledAt ? 'Re-enable' : 'Disable'}
        variant={confirmToggleUser?.disabledAt ? 'info' : 'warning'}
        isLoading={togglingDisabled}
      />

      <ConfirmDialog
        isOpen={!!confirmDeleteUser}
        onClose={() => setConfirmDeleteUser(null)}
        onConfirm={handleDelete}
        title="Delete User"
        message={`Delete ${userLabel(confirmDeleteUser)}? Deletion is for someone who has left — they drop out of mentions and every picker for good. If this is a password lockout or an account that isn't ready yet, disable them instead.`}
        confirmText="Delete"
        variant="danger"
        isLoading={deletingUser}
      />
    </Page>
  );
}
