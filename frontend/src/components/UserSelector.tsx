import { useState, useEffect } from 'react';
import { User } from '../types';
import { usersApi } from '../api';
import './UserSelector.css';

interface UserSelectorProps {
  selectedUserIds: string[];
  onChange: (userIds: string[]) => void;
  label?: string;
  placeholder?: string;
  disabled?: boolean;
  multiple?: boolean;
  /** Offer staff accounts only (external users are hidden). */
  internalOnly?: boolean;
}

export default function UserSelector({
  selectedUserIds,
  onChange,
  label = 'Select Users',
  placeholder = 'Search users...',
  disabled = false,
  multiple = true,
  internalOnly = false,
}: UserSelectorProps) {
  const [users, setUsers] = useState<User[]>([]);
  const [filteredUsers, setFilteredUsers] = useState<User[]>([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [isOpen, setIsOpen] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    loadUsers();
  }, []);

  useEffect(() => {
    filterUsers();
  }, [searchTerm, users]);

  const loadUsers = async () => {
    setLoading(true);
    try {
      const response = await usersApi.getAll(0, 1000);
      if (response.success && response.data) {
        const offered = internalOnly ? response.data.filter((u) => u.isInternal) : response.data;
        setUsers(offered);
        setFilteredUsers(offered);
      }
    } catch (error) {
      console.error('Failed to load users:', error);
    } finally {
      setLoading(false);
    }
  };

  const filterUsers = () => {
    if (!searchTerm) {
      setFilteredUsers(users);
      return;
    }

    const term = searchTerm.toLowerCase();
    const filtered = users.filter(
      (user) =>
        user.username.toLowerCase().includes(term) ||
        user.email.toLowerCase().includes(term) ||
        `${user.firstName} ${user.lastName}`.toLowerCase().includes(term)
    );
    setFilteredUsers(filtered);
  };

  const handleToggleUser = (userId: string) => {
    if (!multiple) {
      onChange([userId]);
      setIsOpen(false);
      return;
    }

    if (selectedUserIds.includes(userId)) {
      onChange(selectedUserIds.filter((id) => id !== userId));
    } else {
      onChange([...selectedUserIds, userId]);
    }
  };

  const handleRemoveUser = (userId: string) => {
    onChange(selectedUserIds.filter((id) => id !== userId));
  };

  const selectedUsers = users.filter((user) => selectedUserIds.includes(user.id));

  return (
    <div className="user-selector">
      <label className="form-label">{label}</label>

      {/* Selected Users Pills */}
      {selectedUsers.length > 0 && (
        <div className="user-selector-pills">
          {selectedUsers.map((user) => (
            <span key={user.id} className="user-selector-pill">
              {user.firstName} {user.lastName} ({user.username})
              {!disabled && (
                <button
                  type="button"
                  className="user-selector-pill-remove"
                  onClick={() => handleRemoveUser(user.id)}
                  aria-label={`Remove ${user.username}`}
                >
                  ×
                </button>
              )}
            </span>
          ))}
        </div>
      )}

      {/* Search Input */}
      <div className="user-selector-field">
        <input
          type="text"
          className="form-input"
          placeholder={placeholder}
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          onFocus={() => setIsOpen(true)}
          disabled={disabled}
        />

        {/* Dropdown */}
        {isOpen && !disabled && (
          <>
            <div className="user-selector-backdrop" onClick={() => setIsOpen(false)} />
            <div className="user-selector-menu" role="listbox">
              {loading ? (
                <div className="user-selector-empty">Loading...</div>
              ) : filteredUsers.length === 0 ? (
                <div className="user-selector-empty">No users found</div>
              ) : (
                filteredUsers.map((user) => {
                  const isSelected = selectedUserIds.includes(user.id);
                  return (
                    <button
                      key={user.id}
                      type="button"
                      role="option"
                      aria-selected={isSelected}
                      className={`user-selector-option${isSelected ? ' is-selected' : ''}`}
                      onClick={() => handleToggleUser(user.id)}
                    >
                      {multiple && (
                        <input type="checkbox" checked={isSelected} readOnly tabIndex={-1} />
                      )}
                      <span className="user-selector-option-text">
                        <span className="user-selector-option-name">
                          {user.firstName} {user.lastName}
                        </span>
                        <span className="user-selector-option-meta">
                          {user.username} • {user.email}
                        </span>
                      </span>
                    </button>
                  );
                })
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
