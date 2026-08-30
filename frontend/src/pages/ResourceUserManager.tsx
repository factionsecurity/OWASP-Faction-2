import { useEffect, useState, useCallback } from 'react';
import { applicationsApi, organizationsApi, usersApi } from '../api';
import type { AssignedUser, User } from '../types';
import { Button } from '../components';
import ConfirmDialog from '../components/ConfirmDialog';
import './ResourceUserManager.css';

interface Props {
  resourceType: 'application' | 'organization';
  resourceId: string;
}

export default function ResourceUserManager({ resourceType, resourceId }: Props) {
  const api = resourceType === 'application' ? applicationsApi : organizationsApi;

  const [assignedUsers, setAssignedUsers] = useState<AssignedUser[]>([]);
  const [showAddForm, setShowAddForm] = useState(false);
  const [userSearchQuery, setUserSearchQuery] = useState('');
  const [userSearchResults, setUserSearchResults] = useState<User[]>([]);
  const [selectedUserId, setSelectedUserId] = useState('');
  const [selectedAccessLevel, setSelectedAccessLevel] = useState<'READ' | 'WRITE'>('WRITE');
  const [userToRemove, setUserToRemove] = useState<string | null>(null);
  const [removingUser, setRemovingUser] = useState(false);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState('');

  const loadAssignedUsers = useCallback(async () => {
    try {
      const res = await api.getAssignedUsers(resourceId);
      if (res.data) {
        setAssignedUsers(res.data);
      }
    } catch {
      // silently fail — permissions may not allow this
    }
  }, [api, resourceId]);

  useEffect(() => {
    loadAssignedUsers();
  }, [loadAssignedUsers]);

  useEffect(() => {
    if (!userSearchQuery.trim()) {
      setUserSearchResults([]);
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const res = await usersApi.getAll(0, 10, userSearchQuery);
        if (res.data) {
          setUserSearchResults(res.data);
        }
      } catch (err: any) {
        setUserSearchResults([]);
        const status = err?.response?.status;
        if (status === 403) {
          setError('You do not have permission to search users. Add users:read:all or users:read:team to your role.');
        } else {
          setError('Failed to search users');
        }
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [userSearchQuery]);

  const handleAddUser = async () => {
    if (!selectedUserId) return;
    setAdding(true);
    setError('');
    try {
      const res = await api.assignUser(resourceId, { userId: selectedUserId, accessLevel: selectedAccessLevel });
      if (res.data) {
        setAssignedUsers((prev) => [...prev, res.data!]);
        setShowAddForm(false);
        setSelectedUserId('');
        setUserSearchQuery('');
        setUserSearchResults([]);
        setSelectedAccessLevel('WRITE');
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to assign user');
    } finally {
      setAdding(false);
    }
  };

  const handleAccessLevelChange = async (userId: string, newLevel: string) => {
    try {
      const res = await api.updateAssignedUser(resourceId, userId, { accessLevel: newLevel });
      if (res.data) {
        setAssignedUsers((prev) =>
          prev.map((u) => (u.userId === userId ? { ...u, accessLevel: res.data!.accessLevel } : u))
        );
      }
    } catch {
      // revert on failure by reloading
      loadAssignedUsers();
    }
  };

  const handleConfirmRemove = async () => {
    if (!userToRemove) return;
    setRemovingUser(true);
    try {
      await api.removeAssignedUser(resourceId, userToRemove);
      setAssignedUsers((prev) => prev.filter((u) => u.userId !== userToRemove));
    } catch {
      // silently reload
      loadAssignedUsers();
    } finally {
      setRemovingUser(false);
      setUserToRemove(null);
    }
  };

  const userToRemoveData = userToRemove ? assignedUsers.find((u) => u.userId === userToRemove) : null;

  return (
    <div className="resource-user-manager">
      <div className="rum-header">
        <h4 className="rum-title">Assigned Users</h4>
        {!showAddForm && (
          <Button size="sm" onClick={() => setShowAddForm(true)}>
            Add
          </Button>
        )}
      </div>

      {assignedUsers.length === 0 && !showAddForm && (
        <p className="rum-empty">No users assigned.</p>
      )}

      {assignedUsers.map((u) => (
        <div key={u.userId} className="rum-user-row">
          <div className="rum-user-info">
            <div className="rum-user-name">{u.displayName}</div>
            <div className="rum-user-email">{u.email}</div>
          </div>
          <div className="rum-user-actions">
            <select
              className="rum-level-select"
              value={u.accessLevel}
              onChange={(e) => handleAccessLevelChange(u.userId, e.target.value)}
            >
              <option value="READ">READ</option>
              <option value="WRITE">WRITE</option>
            </select>
            <button
              type="button"
              className="rum-remove-btn"
              onClick={() => setUserToRemove(u.userId)}
            >
              Remove
            </button>
          </div>
        </div>
      ))}

      {showAddForm && (
        <div className="rum-add-form">
          {error && <div className="rum-error">{error}</div>}
          <div className="rum-search-wrapper">
            <input
              className="rum-search-input"
              type="text"
              placeholder="Search users..."
              value={userSearchQuery}
              onChange={(e) => {
                setUserSearchQuery(e.target.value);
                setSelectedUserId('');
                setError('');
              }}
            />
          {userSearchResults.length > 0 && !selectedUserId && (
            <div className="rum-search-results">
              {userSearchResults.map((u) => (
                <button
                  key={u.id}
                  type="button"
                  className="rum-search-result-item"
                  onClick={() => {
                    setSelectedUserId(u.id);
                    setUserSearchQuery(`${u.firstName || ''} ${u.lastName || ''}`.trim() || u.username);
                    setUserSearchResults([]);
                  }}
                >
                  <span className="rum-result-name">
                    {u.firstName || u.lastName ? `${u.firstName} ${u.lastName}`.trim() : u.username}
                  </span>
                  <span className="rum-result-email">{u.email}</span>
                </button>
              ))}
            </div>
          )}
          </div>
          <div className="rum-add-controls">
            <select
              className="rum-level-select"
              value={selectedAccessLevel}
              onChange={(e) => setSelectedAccessLevel(e.target.value as 'READ' | 'WRITE')}
            >
              <option value="WRITE">WRITE</option>
              <option value="READ">READ</option>
            </select>
            <Button size="sm" onClick={handleAddUser} disabled={!selectedUserId || adding}>
              {adding ? 'Adding...' : 'Assign'}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              onClick={() => {
                setShowAddForm(false);
                setUserSearchQuery('');
                setUserSearchResults([]);
                setSelectedUserId('');
                setError('');
              }}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}

      <ConfirmDialog
        isOpen={!!userToRemove}
        onClose={() => setUserToRemove(null)}
        onConfirm={handleConfirmRemove}
        title="Remove User"
        message={`Remove ${userToRemoveData?.displayName || 'this user'} from the ${resourceType}?`}
        confirmText="Remove"
        variant="danger"
        isLoading={removingUser}
      />
    </div>
  );
}
