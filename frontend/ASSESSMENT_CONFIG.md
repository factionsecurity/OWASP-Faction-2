# Assessment Configuration Frontend Implementation

## Overview
Created a comprehensive frontend UI for managing Assessment Types with full CRUD operations, active/inactive filtering, and the ability to re-enable disabled assessment types.

## Files Created/Modified

### 1. Type Definitions (`src/types.ts`)
Added AssessmentType interfaces:
- `AssessmentType` - Main interface with id, name, description, active status, and timestamps
- `CreateAssessmentTypeRequest` - Request DTO for creating new assessment types
- `UpdateAssessmentTypeRequest` - Request DTO for updating assessment types

### 2. API Client (`src/api.ts`)
Added `assessmentTypesApi` with methods:
- `getAll(page, size, sort)` - Get paginated list of assessment types
- `getById(id)` - Get single assessment type by ID
- `create(assessmentType)` - Create new assessment type
- `update(id, assessmentType)` - Update existing assessment type
- `delete(id)` - Delete/deactivate assessment type

### 3. Navigation (`src/components/DashboardLayout.tsx`)
- Added `Sliders` icon import
- Created new submenu item "Assessment Config" under Administration
- Path: `/assessment-config`

### 4. Assessment Config Page (`src/pages/AssessmentConfig.tsx`)
Created comprehensive page component with:

#### Features
- **Table Display**: Shows all assessment types with DataTable component
- **Pagination**: 10, 25, 50, 100 items per page
- **Search**: Built-in search functionality through DataTable
- **Create**: Modal form to add new assessment types
- **Edit**: Modal form to update existing assessment types
- **Delete**: Confirms before deletion (deactivates if in use)
- **Toggle Active**: Enable/disable assessment types with confirmation
- **Show/Hide Inactive**: Toggle to filter active vs inactive types

#### Columns
1. **Name** - Assessment type name
2. **Description** - Assessment type description
3. **Status** - Active/Inactive badge with color coding
4. **Created** - Creation date (formatted)
5. **Actions** - Edit, Toggle Active, Delete buttons

#### Modal Form Fields
- **Name** (required) - Text input
- **Description** (required) - Textarea
- **Active** (checkbox) - Toggle active status

#### State Management
- `assessmentTypes` - List of assessment types
- `loading` - Loading state for API calls
- `error` - Error message display
- `showModal` - Modal visibility
- `modalMode` - 'create' or 'edit' mode
- `selectedType` - Currently selected type for editing
- `showInactive` - Toggle for showing inactive types
- `pagination` - Pagination info (page, pageSize, total, totalPages)
- `formData` - Form state (name, description, active)

### 5. Styling (`src/pages/AssessmentConfig.css`)
Comprehensive CSS with:
- Page layout and header styling
- Config section with card design
- Status badges (active/inactive)
- Action buttons with hover effects
- Modal overlay and content
- Form styling with validation indicators
- Responsive design patterns
- Button variants (primary, secondary, icon, danger, warning, success)

### 6. App Routing (`src/App.tsx`)
- Imported `AssessmentConfig` component
- Added route: `/assessment-config`
- Protected with authentication check
- Wrapped in `DashboardLayout`

## User Experience Flow

### Viewing Assessment Types
1. Navigate to Administration → Assessment Config
2. See paginated table of assessment types
3. Search for specific types using search bar
4. Toggle "Show Inactive" to see disabled types
5. Change page size or navigate pages

### Creating Assessment Type
1. Click "Add Assessment Type" button
2. Fill in name and description
3. Choose active status (default: active)
4. Click "Create"
5. Table refreshes with new type

### Editing Assessment Type
1. Click edit icon (pencil) on table row
2. Modal opens with pre-filled data
3. Modify fields as needed
4. Click "Update"
5. Table refreshes with updated data

### Deactivating/Reactivating
1. Click power icon on table row
2. Confirm action in dialog
3. Type status updates immediately
4. Badge color changes (green → gray or vice versa)

### Deleting Assessment Type
1. Click delete icon (trash) on table row
2. Confirm deletion in dialog
3. If type is in use, it's deactivated instead
4. If not in use, it's deleted permanently
5. Table refreshes

## UI Components

### Status Badges
- **Active**: Green badge with "Active" text
- **Inactive**: Gray badge with "Inactive" text

### Action Buttons
- **Edit** (Pencil icon): Opens edit modal
- **Toggle Active** (Power icon):
  - Yellow/warning color when active (deactivate action)
  - Green/success color when inactive (reactivate action)
- **Delete** (Trash icon): Red/danger color

### Show/Hide Inactive Button
- **Show Inactive** (Eye icon): Shows all types including inactive
- **Hide Inactive** (Eye-off icon): Filters to show only active types

## API Integration

### Endpoints Used
- `GET /api/v1/assessment-types` - List with pagination
- `GET /api/v1/assessment-types/{id}` - Get single type
- `POST /api/v1/assessment-types` - Create new type
- `PUT /api/v1/assessment-types/{id}` - Update type
- `DELETE /api/v1/assessment-types/{id}` - Delete/deactivate type

### Error Handling
- Displays error messages in red alert box
- Network errors caught and displayed
- Validation errors from backend shown
- Confirmation dialogs prevent accidental operations

## Design Patterns

### Consistent with Existing Pages
- Follows same layout as Users, Teams, Roles pages
- Uses DataTable component for consistency
- Modal design matches existing modals
- Button styles consistent with design system

### Responsive Design
- Works on desktop and tablet sizes
- Modal adapts to screen size (90% width, max 600px)
- Table scrolls horizontally on small screens
- Action buttons stack appropriately

### Accessibility
- Form labels with required indicators
- Button titles for icon-only buttons
- Keyboard navigation support
- Focus management in modals

## Future Enhancements

### Planned for "Assessment Config" Page
The page is structured to accommodate future configuration sections:
- Assessment templates
- Assessment workflows
- Assessment scoring rules
- Assessment checklist templates
- Assessment report templates
- Custom fields configuration

### Current Structure
```
Assessment Configuration
├── Assessment Types (implemented)
└── [Future sections will be added here]
```

## Testing Checklist

### Manual Testing
- [ ] Navigate to Assessment Config page
- [ ] View assessment types table
- [ ] Search for assessment types
- [ ] Create new assessment type
- [ ] Edit existing assessment type
- [ ] Toggle assessment type active/inactive
- [ ] Delete assessment type
- [ ] Show/hide inactive types
- [ ] Pagination works correctly
- [ ] Change page size
- [ ] Error messages display correctly
- [ ] Modal opens and closes properly
- [ ] Form validation works
- [ ] Confirmation dialogs work

### API Testing
- [ ] GET all assessment types
- [ ] GET single assessment type
- [ ] POST create assessment type
- [ ] PUT update assessment type
- [ ] DELETE assessment type
- [ ] Pagination parameters work
- [ ] Sorting parameters work
- [ ] Error responses handled

## Permission Requirements

### Backend Permissions
- `super_admin` - Full access
- `assessments:create:all` - Create assessment types
- `assessments:edit:all` - Edit assessment types
- `assessments:delete:all` - Delete assessment types

Note: Frontend doesn't currently check permissions. Users without proper permissions will receive 403 Forbidden from backend.

## Technical Details

### Dependencies
- React 18
- TypeScript
- Lucide React (icons)
- React Router (navigation)
- Axios (HTTP client)

### Component Architecture
- Functional components with hooks
- useState for local state
- useEffect for data loading
- Controlled form inputs
- Event-driven updates

### Performance Considerations
- Debounced search (handled by DataTable)
- Pagination to limit data load
- Client-side filtering for inactive toggle
- Efficient re-renders with proper state management

## Build Status
✓ Frontend compiles successfully
✓ TypeScript types validated
✓ No build errors or warnings
✓ Production build created successfully
