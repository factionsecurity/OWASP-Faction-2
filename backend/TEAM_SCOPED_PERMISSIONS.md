# Team-Scoped User Management Permissions

## Overview
Updated the User management endpoints to support team-scoped permissions, allowing team managers to manage users within their teams.

## Permission Structure

### Available Permissions

#### Read Permissions
- `super_admin` - Full access to all users
- `users:read:all` - View all users in the system
- `users:read:team` - View only users in your teams

#### Create Permissions
- `super_admin` - Create any user
- `users:create:all` - Create any user
- `users:create:team` - Create users only for your teams

#### Edit Permissions
- `super_admin` - Edit any user
- `users:edit:all` - Edit any user
- `users:edit:team` - Edit only users in your teams

#### Delete Permissions
- `super_admin` - Delete any user
- `users:delete:all` - Delete any user
- `users:delete:team` - Delete only users in your teams

## Implementation Details

### Controller Updates (UserController.java)

All endpoints now:
1. Accept `Authentication` parameter to identify the current user
2. Support both `:all` and `:team` scope permissions
3. Pass authentication to service layer for validation

**Updated Endpoints:**
- `GET /api/v1/users` - List users (filtered by team for team-scope)
- `GET /api/v1/users/{id}` - Get user by ID (validates team membership)
- `POST /api/v1/users` - Create user (validates team assignment)
- `PUT /api/v1/users/{id}` - Update user (validates team membership and assignment)
- `DELETE /api/v1/users/{id}` - Delete user (validates team membership)

### Service Updates (UserService.java)

#### New Helper Methods

1. **hasAllScopeOrSuperAdmin(Authentication)**
   - Checks if user has `super_admin` or any `:all` permission
   - Returns true for full access users

2. **getCurrentUser(Authentication)**
   - Retrieves the current user from the database
   - Used to check team memberships

3. **sharesTeam(User currentUser, User targetUser)**
   - Checks if two users share at least one team
   - Returns true if any team IDs overlap

4. **validateUserAccess(User targetUser, Authentication)**
   - Validates that current user can access the target user
   - Super admins and `:all` scope users have full access
   - `:team` scope users can only access users in their teams
   - Throws `AccessDeniedException` if access is denied

5. **validateTeamScope(CreateUserRequest, Authentication)**
   - Validates team assignments for new users
   - `:team` scope users can only create users for their own teams
   - Ensures at least one team is assigned
   - Throws `AccessDeniedException` if teams don't match

6. **validateTeamScope(UpdateUserRequest, Authentication)**
   - Validates team assignments for user updates
   - `:team` scope users can only assign users to their own teams
   - Ensures at least one team is assigned
   - Throws `AccessDeniedException` if teams don't match

#### Updated CRUD Methods

All CRUD methods now accept `Authentication` parameter and perform team-based validation:

- **createUserDto(CreateUserRequest, Authentication)**
  - Validates team scope before creating
  - Team managers must assign users to their own teams

- **updateUserDto(String id, UpdateUserRequest, Authentication)**
  - Validates access to target user
  - Validates team scope for team assignments
  - Team managers can only update users in their teams

- **deleteUserById(String id, Authentication)**
  - Validates access to target user
  - Team managers can only delete users in their teams

- **findUserById(String id, Authentication)**
  - Validates access to target user
  - Team managers can only view users in their teams

- **searchUsersPaginated(String search, Pageable, Authentication)**
  - Filters results by team for team-scope users
  - Returns empty page if user has no teams
  - Returns all users for super admins and `:all` scope users

## Access Control Logic

### Full Access Users
Users with these permissions can manage ALL users:
- `super_admin`
- Any permission ending with `:all` (e.g., `users:read:all`)

### Team-Scoped Users
Users with these permissions can only manage users IN THEIR TEAMS:
- Any permission ending with `:team` (e.g., `users:read:team`)

**Team Membership Rules:**
- Current user must have at least one team assigned
- Target user must share at least one team with current user
- When creating/updating users, team managers can only assign users to their own teams

## Error Handling

### AccessDeniedException
Thrown when:
- Team-scoped user tries to access a user not in their teams
- Team-scoped user has no teams assigned
- Team-scoped user tries to create/update user for teams they're not in

### IllegalArgumentException
Thrown when:
- Team-scoped user tries to create/update user without team assignment
- Other validation errors (duplicate username, invalid role IDs, etc.)

### ResourceNotFoundException
Thrown when:
- User ID not found
- Current user not found in database

## Example Use Cases

### Scenario 1: Team Manager Creating a User
```java
// Team manager has teams: ["team-1", "team-2"]
// Permission: users:create:team

// ✓ ALLOWED - Creating user for their team
POST /api/v1/users
{
  "username": "newuser",
  "teamIds": ["team-1"]
}

// ✗ DENIED - Creating user for different team
POST /api/v1/users
{
  "username": "newuser",
  "teamIds": ["team-3"]
}
```

### Scenario 2: Team Manager Viewing Users
```java
// Team manager has teams: ["team-1"]
// Permission: users:read:team

// GET /api/v1/users
// Returns only users who have "team-1" in their teamIds
```

### Scenario 3: Super Admin
```java
// User has permission: super_admin

// Can access all endpoints
// Can manage all users
// No team restrictions
```

## Testing Recommendations

### Unit Tests Needed
1. Test super_admin can access all users
2. Test users:*:all can access all users
3. Test users:*:team can only access team members
4. Test users:*:team denied when accessing non-team members
5. Test users:create:team must assign to own teams
6. Test users:edit:team can only edit team members
7. Test users:delete:team can only delete team members
8. Test users with no teams get empty results
9. Test users sharing multiple teams

### Integration Tests
1. Create team manager role with team-scoped permissions
2. Assign team manager to specific teams
3. Verify team manager can only manage users in those teams
4. Verify team manager cannot access users outside their teams

## Migration Notes

### Breaking Changes
- All UserService CRUD methods now require `Authentication` parameter
- Controllers must pass authentication to service methods

### Backward Compatibility
- Existing `super_admin` permission works as before
- No database schema changes required
- Existing users and roles continue to work

## Security Considerations

1. **Team Membership Validation**: Always performed before any operation
2. **Cascading Checks**: Both user access AND team scope are validated
3. **Empty Team Lists**: Users with no teams cannot access team-scoped endpoints
4. **Permission Hierarchy**: `super_admin` and `:all` scope bypass team checks
5. **Soft Delete**: Deleted users maintain team associations for audit purposes

## Future Enhancements

1. **Audit Logging**: Track team-scoped operations for compliance
2. **Bulk Operations**: Support bulk user management with team validation
3. **Team Hierarchy**: Support parent/child team relationships
4. **Cross-Team Collaboration**: Allow temporary access to users from other teams
5. **Performance Optimization**: Cache team memberships for faster lookups
