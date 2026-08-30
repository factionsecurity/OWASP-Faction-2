/**
 * DualListBox Component
 *
 * A reusable dual-list selection component for managing item selections.
 * Perfect for selecting users, teams, roles, permissions, or any grouped items.
 *
 * Features:
 * - Two-panel layout (available items | selected items)
 * - Search functionality in both panels
 * - Checkbox-based selection
 * - Optional secondary text (email, description, etc.)
 * - Responsive design (stacks on mobile)
 *
 * Usage Examples:
 *
 * 1. User Selection (with email):
 * ```tsx
 * <DualListBox
 *   availableItems={users.map(u => ({
 *     id: u.id,
 *     name: `${u.firstName} ${u.lastName}`,
 *     email: u.email
 *   }))}
 *   selectedIds={selectedUserIds}
 *   onChange={setSelectedUserIds}
 *   availableLabel="Available Users"
 *   selectedLabel="Selected Users"
 * />
 * ```
 *
 * 2. Role/Permission Selection (without email):
 * ```tsx
 * <DualListBox
 *   availableItems={roles.map(r => ({
 *     id: r.id,
 *     name: r.name,
 *     secondaryText: r.description
 *   }))}
 *   selectedIds={selectedRoleIds}
 *   onChange={setSelectedRoleIds}
 *   availableLabel="Available Roles"
 *   selectedLabel="Assigned Roles"
 * />
 * ```
 *
 * 3. Simple Selection (name only):
 * ```tsx
 * <DualListBox
 *   availableItems={items.map(i => ({ id: i.id, name: i.name }))}
 *   selectedIds={selectedIds}
 *   onChange={setSelectedIds}
 * />
 * ```
 */
import { useState } from 'react';
import { Search } from 'lucide-react';
import { Input } from './FormControls';
import './DualListBox.css';

export interface DualListBoxItem {
  id: string;
  name: string;
  email?: string;
  secondaryText?: string;  // Generic secondary text (description, role, etc.)
}

export interface DualListBoxProps {
  availableItems: DualListBoxItem[];
  selectedIds: string[];
  onChange: (selectedIds: string[]) => void;
  availableLabel?: string;
  selectedLabel?: string;
  disabled?: boolean;
  showSearch?: boolean;  // Option to hide search if not needed
}

export default function DualListBox({
  availableItems,
  selectedIds,
  onChange,
  availableLabel = 'Available',
  selectedLabel = 'Selected',
  disabled = false,
  showSearch = true,
}: DualListBoxProps) {
  const [searchLeft, setSearchLeft] = useState('');
  const [searchRight, setSearchRight] = useState('');

  const selectedItems = availableItems.filter((item) => selectedIds.includes(item.id));
  const unselectedItems = availableItems.filter((item) => !selectedIds.includes(item.id));

  const filteredUnselected = unselectedItems.filter((item) => {
    if (!showSearch || !searchLeft) return true;
    const searchTerm = searchLeft.toLowerCase();
    const secondaryText = item.email || item.secondaryText || '';
    return (
      item.name.toLowerCase().includes(searchTerm) ||
      secondaryText.toLowerCase().includes(searchTerm)
    );
  });

  const filteredSelected = selectedItems.filter((item) => {
    if (!showSearch || !searchRight) return true;
    const searchTerm = searchRight.toLowerCase();
    const secondaryText = item.email || item.secondaryText || '';
    return (
      item.name.toLowerCase().includes(searchTerm) ||
      secondaryText.toLowerCase().includes(searchTerm)
    );
  });

  const handleToggle = (itemId: string) => {
    if (disabled) return;

    if (selectedIds.includes(itemId)) {
      // Remove from selected
      onChange(selectedIds.filter((id) => id !== itemId));
    } else {
      // Add to selected
      onChange([...selectedIds, itemId]);
    }
  };

  return (
    <div className={`dual-list-box ${disabled ? 'disabled' : ''}`}>
      {/* Available Items (Left Box) */}
      <div className="dual-list-box-panel">
        <div className="dual-list-box-header">
          <label className="dual-list-box-label">
            {availableLabel} ({filteredUnselected.length})
          </label>
          {showSearch && (
            <div className="dual-list-box-search">
              <Search size={16} className="search-icon" />
              <Input
                type="text"
                placeholder="Search..."
                value={searchLeft}
                onChange={(e) => setSearchLeft(e.target.value)}
                disabled={disabled}
              />
            </div>
          )}
        </div>
        <div className="dual-list-box-list">
          {filteredUnselected.length === 0 ? (
            <div className="dual-list-box-empty">
              {searchLeft ? 'No matching items' : 'All items selected'}
            </div>
          ) : (
            filteredUnselected.map((item) => {
              const secondaryText = item.email || item.secondaryText;
              return (
                <label key={item.id} className="dual-list-box-item">
                  <input
                    type="checkbox"
                    checked={false}
                    onChange={() => handleToggle(item.id)}
                    disabled={disabled}
                  />
                  <div className="dual-list-box-item-content">
                    <div className="dual-list-box-item-name">{item.name}</div>
                    {secondaryText && <div className="dual-list-box-item-email">{secondaryText}</div>}
                  </div>
                </label>
              );
            })
          )}
        </div>
      </div>

      {/* Selected Items (Right Box) */}
      <div className="dual-list-box-panel">
        <div className="dual-list-box-header">
          <label className="dual-list-box-label">
            {selectedLabel} ({filteredSelected.length})
          </label>
          {showSearch && (
            <div className="dual-list-box-search">
              <Search size={16} className="search-icon" />
              <Input
                type="text"
                placeholder="Search..."
                value={searchRight}
                onChange={(e) => setSearchRight(e.target.value)}
                disabled={disabled}
              />
            </div>
          )}
        </div>
        <div className="dual-list-box-list">
          {filteredSelected.length === 0 ? (
            <div className="dual-list-box-empty">
              {searchRight ? 'No matching items' : 'No items selected'}
            </div>
          ) : (
            filteredSelected.map((item) => {
              const secondaryText = item.email || item.secondaryText;
              return (
                <label key={item.id} className="dual-list-box-item">
                  <input
                    type="checkbox"
                    checked={true}
                    onChange={() => handleToggle(item.id)}
                    disabled={disabled}
                  />
                  <div className="dual-list-box-item-content">
                    <div className="dual-list-box-item-name">{item.name}</div>
                    {secondaryText && <div className="dual-list-box-item-email">{secondaryText}</div>}
                  </div>
                </label>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
