# Reusable Components Guide

This guide explains how to use the standardized components across the application. All components follow consistent design patterns for a unified user experience.

## Modal Component

The Modal component provides a standardized dialog with header, scrollable body, and footer.

### Basic Usage

```tsx
import { Modal, Button } from '../components';

function MyPage() {
  const [showModal, setShowModal] = useState(false);

  return (
    <>
      <Button onClick={() => setShowModal(true)}>Open Modal</Button>

      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title="My Modal Title"
        size="md"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowModal(false)}>
              Cancel
            </Button>
            <Button variant="primary" onClick={handleSave}>
              Save
            </Button>
          </>
        }
      >
        <p>Modal content goes here</p>
      </Modal>
    </>
  );
}
```

### With Form

```tsx
<Modal
  isOpen={showModal}
  onClose={() => setShowModal(false)}
  title="Create User"
  size="lg"
  onSubmit={handleSubmit}
  footer={
    <>
      <Button variant="secondary" onClick={() => setShowModal(false)}>
        Cancel
      </Button>
      <Button type="submit" variant="primary">
        Create
      </Button>
    </>
  }
>
  <FormGroup>
    <FormLabel required>Username</FormLabel>
    <Input
      type="text"
      value={formData.username}
      onChange={(e) => setFormData({ ...formData, username: e.target.value })}
      required
    />
  </FormGroup>
</Modal>
```

### Props

- `isOpen` (boolean): Controls modal visibility
- `onClose` (function): Called when modal should close
- `title` (string): Modal header title
- `children` (ReactNode): Modal body content
- `footer` (ReactNode, optional): Footer content (usually buttons)
- `size` ('sm' | 'md' | 'lg' | 'xl', default: 'md'): Modal width
- `onSubmit` (function, optional): Form submit handler

### Sizes

- `sm`: 400px - Small modals for simple confirmations
- `md`: 600px - Default size for most forms
- `lg`: 800px - Larger forms with multiple sections
- `xl`: 1200px - Wide modals for complex interfaces

---

## Badge Component

The Badge component displays status indicators and labels.

### Usage

```tsx
import { Badge } from '../components';

// Basic badge
<Badge>Active</Badge>

// With variant
<Badge variant="success">Active</Badge>
<Badge variant="danger">Inactive</Badge>
<Badge variant="warning">Pending</Badge>
<Badge variant="info">Draft</Badge>

// With size
<Badge size="sm" variant="primary">Small</Badge>
<Badge size="lg" variant="success">Large</Badge>
```

### Props

- `children` (ReactNode): Badge content
- `variant` ('primary' | 'secondary' | 'success' | 'danger' | 'warning' | 'info', default: 'primary'): Color scheme
- `size` ('sm' | 'md' | 'lg', default: 'md'): Badge size

### Variants

- `primary`: Blue - Default, general purpose
- `secondary`: Gray - Neutral information
- `success`: Green - Successful states, active items
- `danger`: Red - Errors, inactive/deleted items
- `warning`: Yellow - Warnings, pending states
- `info`: Blue - Informational messages

---

## Button Components

### Standard Button

```tsx
import { Button } from '../components';
import { Plus } from 'lucide-react';

// Primary button
<Button variant="primary" onClick={handleClick}>
  Save Changes
</Button>

// With icon
<Button variant="primary" icon={Plus} onClick={handleCreate}>
  Add New
</Button>

// Secondary button
<Button variant="secondary" onClick={handleCancel}>
  Cancel
</Button>

// Danger button
<Button variant="danger" onClick={handleDelete}>
  Delete
</Button>

// Disabled button
<Button variant="primary" disabled>
  Loading...
</Button>

// Submit button
<Button type="submit" variant="primary">
  Submit Form
</Button>
```

### Icon Button (for table actions)

```tsx
import { IconButton, ActionButtons } from '../components';
import { Edit2, Trash2, Eye } from 'lucide-react';

// In table columns
{
  header: 'Actions',
  render: (item) => (
    <ActionButtons>
      <IconButton
        icon={Edit2}
        variant="edit"
        title="Edit"
        onClick={() => handleEdit(item)}
      />
      <IconButton
        icon={Eye}
        variant="info"
        title="View"
        onClick={() => handleView(item)}
      />
      <IconButton
        icon={Trash2}
        variant="delete"
        title="Delete"
        onClick={() => handleDelete(item)}
      />
    </ActionButtons>
  ),
}
```

### Button Props

**Button:**
- `children` (ReactNode): Button text
- `onClick` (function): Click handler
- `variant` ('primary' | 'secondary' | 'danger' | 'warning' | 'success', default: 'primary')
- `size` ('sm' | 'md' | 'lg', default: 'md')
- `disabled` (boolean): Disabled state
- `type` ('button' | 'submit' | 'reset', default: 'button')
- `icon` (LucideIcon): Optional icon

**IconButton:**
- `icon` (LucideIcon, required): Icon to display
- `onClick` (function): Click handler
- `variant` ('default' | 'edit' | 'delete' | 'warning' | 'success' | 'info', default: 'default')
- `title` (string): Tooltip text
- `disabled` (boolean): Disabled state
- `type` ('button' | 'submit' | 'reset', default: 'button')

---

## Form Controls

All form controls have consistent heights (42px) and styling.

### Input

```tsx
import { FormGroup, FormLabel, Input } from '../components';

<FormGroup>
  <FormLabel required>Username</FormLabel>
  <Input
    type="text"
    value={formData.username}
    onChange={(e) => setFormData({ ...formData, username: e.target.value })}
    placeholder="Enter username"
    required
  />
</FormGroup>

// With error
<FormGroup>
  <FormLabel required>Email</FormLabel>
  <Input
    type="email"
    value={formData.email}
    onChange={(e) => setFormData({ ...formData, email: e.target.value })}
    error={errors.email}
  />
</FormGroup>

// Disabled
<FormGroup>
  <FormLabel>Created At</FormLabel>
  <Input
    type="text"
    value={item.createdAt}
    disabled
  />
</FormGroup>
```

### Textarea

```tsx
import { FormGroup, FormLabel, Textarea } from '../components';

<FormGroup>
  <FormLabel required>Description</FormLabel>
  <Textarea
    value={formData.description}
    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
    rows={4}
    placeholder="Enter description"
    required
  />
</FormGroup>
```

### Select

```tsx
import { FormGroup, FormLabel, Select } from '../components';

<FormGroup>
  <FormLabel>Status</FormLabel>
  <Select
    value={formData.status}
    onChange={(e) => setFormData({ ...formData, status: e.target.value })}
  >
    <option value="">Select status</option>
    <option value="active">Active</option>
    <option value="inactive">Inactive</option>
  </Select>
</FormGroup>
```

### Checkbox

```tsx
import { Checkbox } from '../components';

<Checkbox
  label="Active"
  checked={formData.active}
  onChange={(e) => setFormData({ ...formData, active: e.target.checked })}
/>

<Checkbox
  label="Send notification email"
  checked={formData.notify}
  onChange={(e) => setFormData({ ...formData, notify: e.target.checked })}
/>
```

### Form Row (Multiple columns)

```tsx
import { FormRow, FormGroup, FormLabel, Input } from '../components';

// Two columns
<FormRow columns={2}>
  <FormGroup>
    <FormLabel required>First Name</FormLabel>
    <Input type="text" value={formData.firstName} onChange={...} />
  </FormGroup>
  <FormGroup>
    <FormLabel required>Last Name</FormLabel>
    <Input type="text" value={formData.lastName} onChange={...} />
  </FormGroup>
</FormRow>

// Three columns
<FormRow columns={3}>
  <FormGroup>
    <FormLabel>City</FormLabel>
    <Input type="text" value={formData.city} onChange={...} />
  </FormGroup>
  <FormGroup>
    <FormLabel>State</FormLabel>
    <Input type="text" value={formData.state} onChange={...} />
  </FormGroup>
  <FormGroup>
    <FormLabel>ZIP</FormLabel>
    <Input type="text" value={formData.zip} onChange={...} />
  </FormGroup>
</FormRow>
```

### Form Hint

```tsx
import { FormGroup, FormLabel, Input, FormHint } from '../components';

<FormGroup>
  <FormLabel>API Key</FormLabel>
  <Input type="text" value={formData.apiKey} onChange={...} />
  <FormHint>This key will be used to authenticate API requests</FormHint>
</FormGroup>
```

### Error Message

```tsx
import { ErrorMessage } from '../components';

{error && <ErrorMessage>{error}</ErrorMessage>}
```

---

## Complete Example: Create Modal with Form

```tsx
import { useState } from 'react';
import {
  Modal,
  Button,
  FormGroup,
  FormLabel,
  FormRow,
  Input,
  Textarea,
  Select,
  Checkbox,
  ErrorMessage,
} from '../components';

function CreateUserModal() {
  const [showModal, setShowModal] = useState(false);
  const [error, setError] = useState('');
  const [formData, setFormData] = useState({
    username: '',
    email: '',
    firstName: '',
    lastName: '',
    role: '',
    active: true,
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    try {
      await api.createUser(formData);
      setShowModal(false);
      // Refresh data...
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to create user');
    }
  };

  return (
    <>
      <Button variant="primary" onClick={() => setShowModal(true)}>
        Create User
      </Button>

      <Modal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        title="Create New User"
        size="lg"
        onSubmit={handleSubmit}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowModal(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="primary">
              Create
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
            />
          </FormGroup>

          <FormGroup>
            <FormLabel required>Email</FormLabel>
            <Input
              type="email"
              value={formData.email}
              onChange={(e) => setFormData({ ...formData, email: e.target.value })}
              required
            />
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
            />
          </FormGroup>

          <FormGroup>
            <FormLabel required>Last Name</FormLabel>
            <Input
              type="text"
              value={formData.lastName}
              onChange={(e) => setFormData({ ...formData, lastName: e.target.value })}
              required
            />
          </FormGroup>
        </FormRow>

        <FormGroup>
          <FormLabel required>Role</FormLabel>
          <Select
            value={formData.role}
            onChange={(e) => setFormData({ ...formData, role: e.target.value })}
            required
          >
            <option value="">Select a role</option>
            <option value="admin">Admin</option>
            <option value="user">User</option>
          </Select>
        </FormGroup>

        <Checkbox
          label="Active"
          checked={formData.active}
          onChange={(e) => setFormData({ ...formData, active: e.target.checked })}
        />
      </Modal>
    </>
  );
}
```

---

## Design Principles

### Consistency

All components follow these principles:
- **Height**: All inputs, selects, and buttons have consistent heights (42px for inputs)
- **Spacing**: Consistent padding and margins throughout
- **Colors**: Use CSS variables for theming
- **Typography**: Consistent font sizes and weights
- **Borders**: Consistent border radius and colors

### Accessibility

- Form labels are properly associated with inputs
- Required fields are clearly marked with asterisks
- Error messages are displayed below inputs
- Buttons have proper hover and focus states
- Icon buttons have title attributes for tooltips

### Responsive Design

- Forms adapt to mobile screens (columns stack on small screens)
- Modals take full screen on mobile
- Touch-friendly button and input sizes

---

## Migration from Old Code

When updating existing pages to use these components:

### Before:
```tsx
<div className="modal-overlay" onClick={() => setShowModal(false)}>
  <div className="modal" onClick={(e) => e.stopPropagation()}>
    <div className="modal-header">
      <h3 className="modal-title">My Modal</h3>
      <button className="modal-close" onClick={() => setShowModal(false)}>
        <X size={20} />
      </button>
    </div>
    <form onSubmit={handleSubmit}>
      <div className="modal-body">
        <div className="form-group">
          <label className="form-label">Name *</label>
          <input type="text" className="form-input" value={name} onChange={...} />
        </div>
      </div>
      <div className="modal-footer">
        <button type="button" className="btn btn-secondary" onClick={() => setShowModal(false)}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary">
          Save
        </button>
      </div>
    </form>
  </div>
</div>
```

### After:
```tsx
import { Modal, Button, FormGroup, FormLabel, Input } from '../components';

<Modal
  isOpen={showModal}
  onClose={() => setShowModal(false)}
  title="My Modal"
  onSubmit={handleSubmit}
  footer={
    <>
      <Button variant="secondary" onClick={() => setShowModal(false)}>
        Cancel
      </Button>
      <Button type="submit" variant="primary">
        Save
      </Button>
    </>
  }
>
  <FormGroup>
    <FormLabel required>Name</FormLabel>
    <Input type="text" value={name} onChange={...} />
  </FormGroup>
</Modal>
```

---

## CSS Variables

The components use the following CSS variables (defined in your global styles):

- `--primary-color`: Primary brand color
- `--accent-color`: Accent color for gradients
- `--secondary-bg`: Secondary background color
- `--tertiary-bg`: Tertiary background color
- `--border-color`: Border color
- `--text-primary`: Primary text color
- `--text-secondary`: Secondary text color
- `--text-muted`: Muted text color
- `--hover-bg`: Hover background color
- `--info-color`: Info color (blue)
- `--danger-color`: Danger color (red)
- `--radius-sm`: Small border radius
- `--radius-md`: Medium border radius
- `--radius-xl`: Extra large border radius
- `--shadow-lg`: Large box shadow
