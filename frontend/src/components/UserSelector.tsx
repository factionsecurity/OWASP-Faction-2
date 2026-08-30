import { useState, useEffect } from 'react';
import { User } from '../types';
import { usersApi } from '../api';

interface UserSelectorProps {
  selectedUserIds: string[];
  onChange: (userIds: string[]) => void;
  label?: string;
  placeholder?: string;
  disabled?: boolean;
  multiple?: boolean;
}

export default function UserSelector({
  selectedUserIds,
  onChange,
  label = 'Select Users',
  placeholder = 'Search users...',
  disabled = false,
  multiple = true,
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
        setUsers(response.data);
        setFilteredUsers(response.data);
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
        <div className="selected-users mb-2">
          {selectedUsers.map((user) => (
            <span key={user.id} className="badge bg-primary me-2 mb-2">
              {user.firstName} {user.lastName} ({user.username})
              {!disabled && (
                <button
                  type="button"
                  className="btn-close btn-close-white ms-2"
                  style={{ fontSize: '0.6rem' }}
                  onClick={() => handleRemoveUser(user.id)}
                  aria-label="Remove"
                />
              )}
            </span>
          ))}
        </div>
      )}

      {/* Search Input */}
      <div className="position-relative">
        <input
          type="text"
          className="form-control"
          placeholder={placeholder}
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          onFocus={() => setIsOpen(true)}
          disabled={disabled}
        />

        {/* Dropdown */}
        {isOpen && !disabled && (
          <>
            <div
              className="position-fixed top-0 start-0 w-100 h-100"
              style={{ zIndex: 1040 }}
              onClick={() => setIsOpen(false)}
            />
            <div
              className="dropdown-menu show w-100"
              style={{
                maxHeight: '300px',
                overflowY: 'auto',
                position: 'absolute',
                zIndex: 1050,
              }}
            >
              {loading ? (
                <div className="dropdown-item text-muted">Loading...</div>
              ) : filteredUsers.length === 0 ? (
                <div className="dropdown-item text-muted">No users found</div>
              ) : (
                filteredUsers.map((user) => {
                  const isSelected = selectedUserIds.includes(user.id);
                  return (
                    <button
                      key={user.id}
                      type="button"
                      className={`dropdown-item ${isSelected ? 'active' : ''}`}
                      onClick={() => handleToggleUser(user.id)}
                    >
                      <div className="d-flex align-items-center">
                        {multiple && (
                          <input
                            type="checkbox"
                            className="form-check-input me-2"
                            checked={isSelected}
                            readOnly
                          />
                        )}
                        <div>
                          <div>
                            {user.firstName} {user.lastName}
                          </div>
                          <small className="text-muted">
                            {user.username} • {user.email}
                          </small>
                        </div>
                      </div>
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
