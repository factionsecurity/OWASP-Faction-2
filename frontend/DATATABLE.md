# DataTable Component

A reusable React component for displaying tabular data with server-side pagination and search functionality.

## Features

- **Server-side Pagination**: Efficiently handles large datasets by fetching only the data needed for the current page
- **Server-side Search**: Debounced search input (500ms) to minimize API calls
- **Customizable Columns**: Support for custom rendering, column widths, and accessors
- **Responsive Design**: Mobile-friendly layout with proper breakpoints
- **Loading States**: Built-in loading indicators during data fetching
- **Empty States**: Configurable empty state messages
- **Page Size Selection**: Users can choose to display 10, 25, 50, or 100 items per page
- **Modern UI**: Consistent with the dark theme design system

## Usage

### Basic Example

```tsx
import DataTable, { Column, PaginationInfo } from '../components/DataTable';
import type { User } from '../types';

const columns: Column<User>[] = [
  {
    header: 'Username',
    accessor: 'username',
  },
  {
    header: 'Email',
    accessor: 'email',
  },
  {
    header: 'Actions',
    width: '120px',
    render: (user) => (
      <button onClick={() => handleEdit(user)}>Edit</button>
    ),
  },
];

const [pagination, setPagination] = useState<PaginationInfo>({
  page: 0,
  pageSize: 10,
  total: 0,
  totalPages: 0,
});

<DataTable
  columns={columns}
  data={users}
  loading={loading}
  pagination={pagination}
  onPageChange={(page) => setPagination((prev) => ({ ...prev, page }))}
  onPageSizeChange={(pageSize) => setPagination((prev) => ({ ...prev, page: 0, pageSize }))}
  onSearchChange={(search) => setSearchQuery(search)}
  searchPlaceholder="Search users..."
  emptyMessage="No users found"
  idAccessor="id"
/>
```

## Props

### DataTableProps<T>

| Prop | Type | Required | Description |
|------|------|----------|-------------|
| `columns` | `Column<T>[]` | Yes | Array of column definitions |
| `data` | `T[]` | Yes | Array of data items to display |
| `loading` | `boolean` | Yes | Loading state indicator |
| `pagination` | `PaginationInfo` | Yes | Pagination state object |
| `onPageChange` | `(page: number) => void` | Yes | Callback when page changes |
| `onPageSizeChange` | `(pageSize: number) => void` | Yes | Callback when page size changes |
| `onSearchChange` | `(search: string) => void` | Yes | Callback when search query changes (debounced) |
| `searchPlaceholder` | `string` | No | Placeholder text for search input (default: "Search...") |
| `emptyMessage` | `string` | No | Message to display when no data is found (default: "No data found") |
| `idAccessor` | `keyof T` | Yes | Key of the unique identifier property in your data |

### Column<T>

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `header` | `string` | Yes | Column header text |
| `accessor` | `keyof T` | No | Property key to access data (used if render is not provided) |
| `render` | `(item: T) => ReactNode` | No | Custom render function for cell content |
| `width` | `string` | No | CSS width value (e.g., "120px", "20%") |

### PaginationInfo

| Property | Type | Description |
|----------|------|-------------|
| `page` | `number` | Current page number (0-indexed) |
| `pageSize` | `number` | Number of items per page |
| `total` | `number` | Total number of items |
| `totalPages` | `number` | Total number of pages |

## Backend Requirements

The backend API endpoint must support the following query parameters:

- `page`: Page number (0-indexed)
- `size`: Number of items per page
- `search`: Search query string (optional)

The backend response must include pagination metadata:

```json
{
  "success": true,
  "data": [...],
  "page": 0,
  "pageSize": 10,
  "total": 42,
  "totalPages": 5
}
```

## Search Behavior

- Search input is debounced with a 500ms delay to avoid excessive API calls
- When the user types in the search box, the component waits 500ms before triggering `onSearchChange`
- The parent component should reset the page to 0 when the search query changes
- Empty or whitespace-only searches should return all results

## Example with Full State Management

```tsx
import { useEffect, useState, useCallback } from 'react';
import DataTable, { Column, PaginationInfo } from '../components/DataTable';
import { usersApi } from '../api';
import type { User } from '../types';

export default function Users() {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');

  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 0,
    pageSize: 10,
    total: 0,
    totalPages: 0,
  });

  // Load users whenever pagination or search changes
  useEffect(() => {
    loadUsers();
  }, [pagination.page, pagination.pageSize, searchQuery]);

  const loadUsers = async () => {
    try {
      setLoading(true);
      const response = await usersApi.getAll(
        pagination.page,
        pagination.pageSize,
        searchQuery
      );

      if (response.data) {
        setUsers(response.data);
        setPagination({
          page: response.page || 0,
          pageSize: response.pageSize || 10,
          total: response.total || 0,
          totalPages: response.totalPages || 0,
        });
      }
    } catch (err) {
      console.error('Failed to load users:', err);
    } finally {
      setLoading(false);
    }
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

  const columns: Column<User>[] = [
    {
      header: 'Username',
      accessor: 'username',
    },
    {
      header: 'Email',
      accessor: 'email',
    },
    {
      header: 'Status',
      render: (user) => (
        <span className={`badge ${user.active ? 'badge-success' : 'badge-danger'}`}>
          {user.active ? 'Active' : 'Inactive'}
        </span>
      ),
    },
  ];

  return (
    <DataTable
      columns={columns}
      data={users}
      loading={loading}
      pagination={pagination}
      onPageChange={handlePageChange}
      onPageSizeChange={handlePageSizeChange}
      onSearchChange={handleSearchChange}
      searchPlaceholder="Search users..."
      emptyMessage="No users found"
      idAccessor="id"
    />
  );
}
```

## Styling

The DataTable component uses the following CSS custom properties from your theme:

- `--secondary-bg`: Card background
- `--tertiary-bg`: Header and hover backgrounds
- `--border-color`: Borders
- `--border-light`: Hover borders
- `--text-primary`: Primary text color
- `--text-secondary`: Secondary text color
- `--text-muted`: Muted text color
- `--primary-color`: Active states
- `--radius-sm`, `--radius-md`, `--radius-lg`: Border radius values
- `--shadow-md`: Box shadows

## Notes

- The component is fully typed with TypeScript generics for type safety
- The search functionality uses `useCallback` to ensure stable function references
- Pagination controls automatically disable at boundaries (first/last page)
- The page numbers display a maximum of 5 buttons with smart pagination logic
- All callbacks should be memoized with `useCallback` to prevent unnecessary re-renders
