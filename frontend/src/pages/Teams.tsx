import { useEffect, useState, useCallback } from 'react';
import { Edit2, Trash2, Plus, X, Users as UsersIcon, Search } from 'lucide-react';
import { teamsApi, usersApi } from '../api';
import type { Team, User, CreateTeamRequest, UpdateTeamRequest } from '../types';
import DataTable, { Column, PaginationInfo, SortState, sortParam } from '../components/DataTable';
import {
  Modal,
  Button,
  IconButton,
  ActionButtons,
  Badge,
  FormGroup,
  FormLabel,
  Input,
  Textarea,
  ErrorMessage,
} from '../components';
import Page from '../components/Page';
import './Teams.css';

export default function Teams() {
  const [teams, setTeams] = useState<Team[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showTeamModal, setShowTeamModal] = useState(false);
  const [showUsersModal, setShowUsersModal] = useState(false);
  const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
  const [selectedTeam, setSelectedTeam] = useState<Team | null>(null);
  const [teamUsers, setTeamUsers] = useState<User[]>([]);
  const [selectedUserIds, setSelectedUserIds] = useState<string[]>([]);
  const [usersLoading, setUsersLoading] = useState(false);
  const [userSearchQuery, setUserSearchQuery] = useState('');

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  const [searchQuery, setSearchQuery] = useState('');
  const [sort, setSort] = useState<SortState | null>(null);

  const [formData, setFormData] = useState({
    name: '',
    description: '',
  });

  useEffect(() => {
    loadUsers();
  }, []);

  useEffect(() => {
    loadTeams();
  }, [pagination.page, pagination.pageSize, searchQuery, sort]);

  const loadTeams = async () => {
    try {
      setLoading(true);
      const response = await teamsApi.getAll(
        pagination.page, pagination.pageSize, searchQuery, sortParam(sort));
      if (response.data) {
        setTeams(response.data);
        setPagination({
          page: response.pagination?.page || 0,
          pageSize: response.pagination?.size || 10,
          total: response.pagination?.totalElements || 0,
          totalPages: response.pagination?.totalPages || 0,
        });
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load teams');
    } finally {
      setLoading(false);
    }
  };

  const loadUsers = async () => {
    try {
      const response = await usersApi.getAll(0, 1000);
      if (response.data) {
        setUsers(response.data);
      }
    } catch (err) {
      console.error('Failed to load users:', err);
    }
  };

  const loadTeamUsers = async (teamId: string) => {
    try {
      setUsersLoading(true);
      const response = await teamsApi.getUsersInTeam(teamId);
      if (response.data) {
        setTeamUsers(response.data);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load team users');
    } finally {
      setUsersLoading(false);
    }
  };

  const handleCreate = () => {
    setModalMode('create');
    setSelectedTeam(null);
    setFormData({
      name: '',
      description: '',
    });
    setError('');
    setShowTeamModal(true);
  };

  const handleEdit = (team: Team) => {
    setModalMode('edit');
    setSelectedTeam(team);
    setFormData({
      name: team.name,
      description: team.description,
    });
    setError('');
    setShowTeamModal(true);
  };

  const handleDelete = async (teamId: string) => {
    if (!confirm('Are you sure you want to delete this team? It will be removed from all users.')) return;

    try {
      await teamsApi.delete(teamId);
      await loadTeams();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to delete team');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    try {
      if (modalMode === 'create') {
        const createData: CreateTeamRequest = {
          name: formData.name,
          description: formData.description,
        };
        await teamsApi.create(createData);
      } else if (selectedTeam) {
        const updateData: UpdateTeamRequest = {
          name: formData.name,
          description: formData.description,
        };
        await teamsApi.update(selectedTeam.id, updateData);
      }

      setShowTeamModal(false);
      await loadTeams();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to save team');
    }
  };

  const handleManageUsers = async (team: Team) => {
    setSelectedTeam(team);
    setSelectedUserIds([]);
    setUserSearchQuery('');
    await loadTeamUsers(team.id);
    setShowUsersModal(true);
  };

  const handleAddUsers = async () => {
    if (!selectedTeam || selectedUserIds.length === 0) return;

    try {
      setUsersLoading(true);
      for (const userId of selectedUserIds) {
        await teamsApi.addUserToTeam(selectedTeam.id, userId);
      }
      setSelectedUserIds([]);
      await loadTeamUsers(selectedTeam.id);
      await loadUsers();
      await loadTeams();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to add users to team');
    } finally {
      setUsersLoading(false);
    }
  };

  const handleRemoveUser = async (userId: string) => {
    if (!selectedTeam) return;

    try {
      setUsersLoading(true);
      await teamsApi.removeUserFromTeam(selectedTeam.id, userId);
      await loadTeamUsers(selectedTeam.id);
      await loadUsers();
      await loadTeams();
    } catch (err: any) {
      alert(err.response?.data?.message || 'Failed to remove user from team');
    } finally {
      setUsersLoading(false);
    }
  };

  const getUserCount = (teamId: string): number => {
    return users.filter(user => user.teamIds && user.teamIds.includes(teamId)).length;
  };

  const getAvailableUsers = (): User[] => {
    const teamUserIds = teamUsers.map(u => u.id);
    let availableUsers = users.filter(u => !teamUserIds.includes(u.id));

    if (userSearchQuery.trim()) {
      const query = userSearchQuery.toLowerCase();
      availableUsers = availableUsers.filter(u => {
        const firstName = (u.firstName || '').toLowerCase();
        const lastName = (u.lastName || '').toLowerCase();
        const username = (u.username || '').toLowerCase();
        const email = (u.email || '').toLowerCase();

        return firstName.includes(query) ||
               lastName.includes(query) ||
               username.includes(query) ||
               email.includes(query);
      });
    }

    return availableUsers;
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

  const columns: Column<Team>[] = [
    {
      header: 'Name',
      sortKey: 'name',
      accessor: 'name',
      render: (team) => (
        <div>
          <div className="font-medium">{team.name}</div>
        </div>
      ),
    },
    {
      header: 'Description',
      sortKey: 'description',
      accessor: 'description',
      render: (team) => <span className="text-sm text-muted">{team.description}</span>,
    },
    {
      header: 'Users',
      render: (team) => (
        <Badge variant="info">{getUserCount(team.id)} members</Badge>
      ),
    },
    {
      header: 'Created',
      sortKey: 'createdAt',
      render: (team) => (
        <span className="text-sm text-muted">
          {new Date(team.createdAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      header: 'Actions',
      width: '150px',
      render: (team) => (
        <ActionButtons>
          <IconButton
            icon={Edit2}
            variant="edit"
            title="Edit"
            onClick={() => handleEdit(team)}
          />
          <IconButton
            icon={UsersIcon}
            variant="info"
            title="Manage Users"
            onClick={() => handleManageUsers(team)}
          />
          <IconButton
            icon={Trash2}
            variant="delete"
            title="Delete"
            onClick={() => handleDelete(team.id)}
          />
        </ActionButtons>
      ),
    },
  ];

  return (
    <Page className="teams-page">
      <div className="page-header">
        <div />
        <Button variant="primary" icon={Plus} onClick={handleCreate}>
          Create Team
        </Button>
      </div>

      <DataTable
        columns={columns}
        data={teams}
        loading={loading}
        pagination={pagination}
        onPageChange={handlePageChange}
        onPageSizeChange={handlePageSizeChange}
        onSearchChange={handleSearchChange}
        searchPlaceholder="Search teams"
        emptyMessage="No teams found"
        idAccessor="id"
        sort={sort}
        onSortChange={handleSortChange}
      />

      <Modal
        isOpen={showTeamModal}
        onClose={() => setShowTeamModal(false)}
        title={modalMode === 'create' ? 'Create New Team' : 'Edit Team'}
        size="md"
        closeOnOverlayClick={false}
        onSubmit={handleSubmit}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowTeamModal(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="primary">
              {modalMode === 'create' ? 'Create' : 'Save Changes'}
            </Button>
          </>
        }
      >
        {error && <ErrorMessage>{error}</ErrorMessage>}

        <FormGroup>
          <FormLabel required>Team Name</FormLabel>
          <Input
            type="text"
            value={formData.name}
            onChange={(e) => setFormData({ ...formData, name: e.target.value })}
            required
            placeholder="Enter team name"
          />
        </FormGroup>

        <FormGroup>
          <FormLabel required>Description</FormLabel>
          <Textarea
            value={formData.description}
            onChange={(e) => setFormData({ ...formData, description: e.target.value })}
            required
            placeholder="Enter team description"
            rows={4}
          />
        </FormGroup>
      </Modal>

      <Modal
        isOpen={showUsersModal}
        onClose={() => setShowUsersModal(false)}
        title={`Manage Users - ${selectedTeam?.name || ''}`}
        size="lg"
        closeOnOverlayClick={false}
        footer={
          <Button variant="secondary" onClick={() => setShowUsersModal(false)}>
            Close
          </Button>
        }
      >
        {usersLoading && <div className="loading-inline">Loading...</div>}

        <div className="users-section">
          <h4 className="section-title">Current Members ({teamUsers.length})</h4>
          {teamUsers.length === 0 ? (
            <div className="empty-state">No users in this team</div>
          ) : (
            <div className="user-list">
              {teamUsers.map((user) => (
                <div key={user.id} className="user-item">
                  <div className="user-info">
                    <div className="user-name">{user.firstName} {user.lastName}</div>
                    <div className="user-meta">{user.username} • {user.email}</div>
                  </div>
                  <button
                    onClick={() => handleRemoveUser(user.id)}
                    className="btn-remove"
                    disabled={usersLoading}
                  >
                    <X size={16} />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="users-section">
          <h4 className="section-title">Add Members</h4>
          <FormGroup>
            <FormLabel>Search for users to add</FormLabel>
            <div className="search-input-wrapper">
              <Search size={18} className="search-icon" />
              <Input
                type="text"
                className="search-input"
                placeholder="Search by name, username, or email..."
                value={userSearchQuery}
                onChange={(e) => setUserSearchQuery(e.target.value)}
              />
              {userSearchQuery && (
                <button
                  className="clear-search"
                  onClick={() => setUserSearchQuery('')}
                  type="button"
                >
                  <X size={16} />
                </button>
              )}
            </div>
          </FormGroup>
          <div className="form-group">
            <div className="user-select-group">
              {!userSearchQuery.trim() ? (
                <div className="empty-state">
                  Start typing to search for users to add to this team
                </div>
              ) : getAvailableUsers().length === 0 ? (
                <div className="empty-state">
                  No users found matching "{userSearchQuery}"
                </div>
              ) : (
                getAvailableUsers().map((user) => (
                  <label key={user.id} className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={selectedUserIds.includes(user.id)}
                      onChange={(e) => {
                        if (e.target.checked) {
                          setSelectedUserIds([...selectedUserIds, user.id]);
                        } else {
                          setSelectedUserIds(selectedUserIds.filter((id) => id !== user.id));
                        }
                      }}
                      disabled={usersLoading}
                    />
                    <span>
                      {user.firstName} {user.lastName} ({user.username})
                    </span>
                  </label>
                ))
              )}
            </div>
          </div>
          {selectedUserIds.length > 0 && (
            <Button
              onClick={handleAddUsers}
              variant="primary"
              disabled={usersLoading}
            >
              Add {selectedUserIds.length} User{selectedUserIds.length > 1 ? 's' : ''}
            </Button>
          )}
        </div>
      </Modal>
    </Page>
  );
}
