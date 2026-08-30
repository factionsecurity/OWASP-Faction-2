# Assessments Feature Documentation

## Overview

The Assessments page provides a comprehensive view of all assessments with advanced search and filtering capabilities. Users can see assessments assigned to them, or view all assessments if they have the appropriate permissions.

## Implementation Summary

### Files Created

1. **`/frontend/src/pages/Assessments.tsx`**
   - Main assessments table page
   - Advanced filtering and search
   - Permission-based view control

2. **`/frontend/src/pages/AssessmentDetail.tsx`**
   - Assessment detail view (placeholder)
   - Will be expanded with findings and vulnerabilities

### Files Modified

1. **`/frontend/src/types.ts`**
   - Added `VulnerabilitySummary` interface
   - Extended `Assessment` interface with:
     - `applicationName` (for display)
     - `assessmentTypeName` (for display)
     - `assessorNames[]` (for display)
     - `vulnerabilitySummary` (vulnerability counts)

2. **`/frontend/src/api.ts`**
   - Added `assessmentsApi.search()` method
   - Supports combined search and multiple filters

3. **`/frontend/src/App.tsx`**
   - Replaced placeholder with `Assessments` component
   - Added `/assessments/:id` route for detail view

## Features

### 1. Assessment Table

Displays assessments with the following columns:

| Column | Description |
|--------|-------------|
| **Assessment Name** | Name with "Past Due" badge if applicable |
| **Application** | Application name |
| **Assessment Type** | Type of assessment |
| **Start Date** | Assessment start date |
| **End Date** | Planned end date |
| **Status** | Current status with color-coded badge |
| **Assessors** | List of assessors (truncated if more than 2) |
| **Vulnerabilities** | Summary in format: `C:3, H:2, M:0, L:1` |
| **Actions** | View button to see details |

### 2. Search and Filters

#### Combined Search
- Single search box that searches across:
  - Assessment name
  - Application name
  - Assessor names
  - Status

#### Date Range Filters
- **Start Date From/To**: Filter by assessment start date range
- **End Date From/To**: Filter by planned end date range

#### Standard Filters
- **Application**: Filter by specific application
- **Assessment Type**: Filter by assessment type
- **Status**: Filter by assessment status
- **Past Due Only**: Checkbox to show only overdue assessments

#### Assignment Filter
- **Show only assessments assigned to me**:
  - Default: checked (shows only user's assessments)
  - When unchecked: shows all accessible assessments
  - Only visible if user has `assessments:read:all` or `assessments:read:team`

### 3. Permission-Based Access

The page respects the following permissions:

- **View Permission**: `assessments:read:*` (any read permission)
- **View All Option**: Only shown if user has:
  - `assessments:read:all` OR
  - `assessments:read:team`

### 4. Row Actions

- **Click on Row**: Opens assessment detail page
- **View Button**: Same as clicking row, opens detail page

### 5. Export

- **Export CSV**: Downloads assessments matching current filters

## Backend API Requirements

The implementation expects a backend endpoint:

```
GET /api/v1/assessments/search
```

### Query Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `page` | number | Page number (0-indexed) |
| `size` | number | Page size |
| `search` | string | Combined search across multiple fields |
| `startDateFrom` | ISO date | Start date range begin |
| `startDateTo` | ISO date | Start date range end |
| `endDateFrom` | ISO date | End date range begin |
| `endDateTo` | ISO date | End date range end |
| `pastDue` | boolean | Filter for past due assessments |
| `assignedToMe` | boolean | Filter for user's assessments |
| `status` | string | Filter by status |
| `applicationId` | string | Filter by application |
| `assessmentTypeId` | string | Filter by assessment type |
| `sort` | string | Sort field and direction |

### Expected Response

```typescript
{
  success: boolean;
  data: Assessment[];
  pagination: {
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    first: boolean;
    last: boolean;
    numberOfElements: number;
    empty: boolean;
  };
}
```

### Assessment Object Enhancements

The backend should populate these additional fields in the response:

```typescript
{
  // ... existing fields
  applicationName: string; // Joined from Application
  assessmentTypeName: string; // Joined from AssessmentType
  assessorNames: string[]; // Resolved from assessorIds
  vulnerabilitySummary: {
    critical: number;
    high: number;
    medium: number;
    low: number;
  }
}
```

## Assessment Detail Page

Currently a placeholder showing:
- Assessment overview (dates, status, team)
- Vulnerability summary
- Scope
- Placeholder notice for future implementation

### Future Enhancements
- Findings list
- Vulnerability details
- Report generation
- Edit assessment
- Status transitions
- Comments/notes
- File attachments

## Usage Examples

### 1. View My Assessments
By default, the page shows only assessments assigned to the current user.

### 2. View All Assessments
1. User must have `assessments:read:all` or `assessments:read:team`
2. Uncheck "Show only assessments assigned to me"
3. Table refreshes to show all accessible assessments

### 3. Search for Assessments
Type in search box to find assessments by:
- Assessment name: "Security Review"
- Application: "Web Portal"
- Assessor: "John Doe"
- Status: "In Progress"

### 4. Filter Past Due Assessments
1. Check "Past Due Only" checkbox
2. Optionally set date ranges
3. Table shows only overdue assessments with red badges

### 5. Filter by Date Range
1. Set Start Date From/To to filter by start dates
2. Set End Date From/To to filter by end dates
3. Combine with other filters for precise results

### 6. View Assessment Details
1. Click on any row in the table
2. OR click the "View" button
3. Opens assessment detail page with full information

### 7. Export Assessments
1. Apply desired filters
2. Click "Export CSV" button
3. Downloads CSV file with filtered assessments

## Data Flow

```
User Action
    ↓
Assessments.tsx (filters state)
    ↓
assessmentsApi.search(filters)
    ↓
Backend /api/v1/assessments/search
    ↓
Query Database with filters
    ↓
Join with Applications, Types, Users
    ↓
Calculate vulnerability summary
    ↓
Return paginated results
    ↓
Assessments.tsx (displays table)
```

## Permission Matrix

| Permission | Can View Page | Can See "View All" Option | Sees |
|-----------|--------------|---------------------------|------|
| None | ❌ No | ❌ No | Nothing |
| `assessments:read:team` | ✅ Yes | ✅ Yes | Team assessments |
| `assessments:read:all` | ✅ Yes | ✅ Yes | All assessments |
| `super_admin` | ✅ Yes | ✅ Yes | Everything |

## Testing Checklist

### Functionality
- [ ] Table loads with assigned assessments by default
- [ ] Search works across all specified fields
- [ ] Date range filters work correctly
- [ ] Past due filter shows only overdue assessments
- [ ] Application filter works
- [ ] Assessment type filter works
- [ ] Status filter works
- [ ] "Assigned to me" toggle works (if user has permission)
- [ ] Pagination works
- [ ] Page size change works
- [ ] Row click opens detail page
- [ ] View button opens detail page
- [ ] Export CSV downloads file

### Permissions
- [ ] Users without `assessments:read` cannot access page
- [ ] "Assigned to me" checkbox only shows for users with read:all/team
- [ ] Users only see assessments they have access to

### UI/UX
- [ ] Filters are intuitive and well-labeled
- [ ] Past due assessments show red badge
- [ ] Status badges have correct colors
- [ ] Vulnerability summary is readable (C:3, H:2, M:0, L:1)
- [ ] Assessor list truncates gracefully (+N more)
- [ ] Date formats are consistent
- [ ] Loading states work correctly
- [ ] Error messages are clear

### Detail Page
- [ ] Assessment detail loads correctly
- [ ] Back button returns to assessments list
- [ ] All information displays correctly
- [ ] Placeholder notice is visible

## Future Enhancements

1. **Bulk Actions**
   - Select multiple assessments
   - Bulk status updates
   - Bulk export

2. **Advanced Filters**
   - Filter by assessor
   - Filter by organization
   - Filter by created date
   - Custom filter combinations

3. **Sorting**
   - Sort by any column
   - Multi-column sort
   - Remember sort preferences

4. **Views/Presets**
   - Save filter combinations
   - Quick access to common views
   - Share views with team

5. **Assessment Detail Enhancements**
   - Full findings management
   - Inline editing
   - Status workflow
   - Activity timeline
   - Comments and collaboration

6. **Reporting**
   - Generate reports from detail page
   - Download multiple formats
   - Schedule automated reports

7. **Dashboard Integration**
   - Widget showing recent assessments
   - Quick stats on dashboard
   - Notifications for past due items
