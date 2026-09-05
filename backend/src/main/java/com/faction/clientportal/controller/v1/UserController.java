package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.CreateUserRequest;
import com.faction.clientportal.dto.MentionableUserDto;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.dto.SyncUserApplicationAssignmentsRequest;
import com.faction.clientportal.dto.UpdateUserRequest;
import com.faction.clientportal.dto.UserApplicationAssignmentDto;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.ApplicationService;
import com.faction.clientportal.service.UserService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.service.MentionableUserService;
import com.faction.clientportal.security.RequiresPermission;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;
    private final com.faction.clientportal.service.PasswordResetService passwordResetService;

    /**
     * Sortable user columns. Roles and Teams/Organization render names resolved from JSONB id
     * lists on the row, so neither is a column the query can order by.
     */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "username", SortField.text("username"),
            "email", SortField.text("email"),
            "firstName", SortField.text("firstName"),
            "lastName", SortField.text("lastName"),
            "loginOption", SortField.value("loginOption"),
            "isInternal", SortField.value("isInternal"),
            // The Status cell reads Deleted → Disabled → Active off these two timestamps; ordering
            // by disabledAt groups disabled users together, which is what the column sorts by.
            "disabledAt", SortField.value("disabledAt"),
            "lastLogin", SortField.value("lastLogin"),
            "createdAt", SortField.value("createdAt"));

    private static final Sort DEFAULT_SORT = Sort.by("username");
    private final ApplicationService applicationService;
    private final MentionableUserService mentionableUserService;

    /**
     * Who the caller may @mention, for the editor's autocomplete.
     *
     * <p>Login-only rather than permission-gated, because external (portal) users hold no
     * {@code users:read:*} grant and still have to address the people they are working with. The
     * narrowing lives in {@link MentionableUserService}: an external caller sees their own
     * organization's users plus the remediation contact and whoever is already on the thread, and
     * never another organization's users. Internal callers get the directory their
     * {@code users:read:*} scope already allows.
     *
     * <p>Returns only a username and display name — a mention list must not become a way to read
     * colleagues' emails, roles, or organizations.
     */
    @GetMapping("/mentionable")
    @AuthenticatedOnly
    @Operation(
            summary = "Get @mention candidates",
            description = "Users the caller may mention, optionally narrowed to the conversation "
                    + "being written to. External users see their own organization plus the "
                    + "remediation contact and existing thread participants.")
    public ResponseEntity<JsonApiResponse<List<MentionableUserDto>>> getMentionableUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String vulnerabilityId,
            @RequestParam(required = false) String applicationId,
            Authentication authentication) {
        return ResponseUtil.success("Mentionable users retrieved successfully",
                mentionableUserService.find(search, vulnerabilityId, applicationId, authentication));
    }

    @GetMapping
    @RequiresPermission({Permission.USERS_READ_ALL, Permission.USERS_READ_TEAM})
    @Operation(
            summary = "Get all users",
            description = "Retrieve all users with pagination and optional search. " +
                    "Super admins and users with 'users:read:all' can see all users. " +
                    "Users with 'users:read:team' can only see users in their teams.",
            parameters = {
                    @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
                    @Parameter(name = "size", description = "Number of items per page", example = "10"),
                    @Parameter(name = "sort", description = "Sort field and direction (e.g., 'username,asc')", example = "username,asc"),
                    @Parameter(name = "search", description = "Search term to filter users by username, email, first name, or last name (case-insensitive)", example = "john"),
                    @Parameter(name = "roleId", description = "Only users holding this role"),
                    @Parameter(name = "teamId", description = "Only users belonging to this team"),
                    @Parameter(name = "organizationId", description = "Only users in this organization"),
                    @Parameter(name = "type", description = "INTERNAL or EXTERNAL; omit for both", example = "INTERNAL")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully retrieved users",
                            content = @Content(schema = @Schema(implementation = ApiResponse.class))
                    ),
                    @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
            }
    )
    public ResponseEntity<JsonApiResponse<List<UserDto>>> getAllUsers(
            @Parameter(hidden = true) @RequestParam(defaultValue = "0") int page,
            @Parameter(hidden = true) @RequestParam(defaultValue = "10") int size,
            @Parameter(hidden = true) @RequestParam(defaultValue = "username,asc") String sort,
            @Parameter(hidden = true) @RequestParam(required = false) String search,
            @Parameter(hidden = true) @RequestParam(required = false) String roleId,
            @Parameter(hidden = true) @RequestParam(required = false) String teamId,
            @Parameter(hidden = true) @RequestParam(required = false) String organizationId,
            @Parameter(hidden = true) @RequestParam(required = false) String type,
            Authentication authentication) {

        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);
        Page<UserDto> userPage = userService.searchUsersPaginated(
                search, roleId, teamId, organizationId, parseType(type), pageable, authentication);

        return ResponseUtil.paginated("Users retrieved successfully", userPage);
    }

    /** {@code type=INTERNAL|EXTERNAL} → the isInternal flag; absent or blank → no filter. */
    private static Boolean parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return switch (type.trim().toUpperCase()) {
            case "INTERNAL" -> Boolean.TRUE;
            case "EXTERNAL" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(
                    "Unknown user type '" + type + "'. Expected INTERNAL or EXTERNAL.");
        };
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permission.USERS_READ_ALL, Permission.USERS_READ_TEAM})
    @Operation(
            summary = "Get user by ID",
            description = "Retrieve a specific user by their ID. " +
                    "Super admins and users with 'users:read:all' can see any user. " +
                    "Users with 'users:read:team' can only see users in their teams.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved user"),
                    @ApiResponse(responseCode = "404", description = "User not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<UserDto>> getUserById(
            @PathVariable String id,
            Authentication authentication) {
        UserDto user = userService.findUserById(id, authentication);
        return ResponseUtil.success("User retrieved successfully", user);
    }

    @PostMapping
    @RequiresPermission({Permission.USERS_CREATE_ALL, Permission.USERS_CREATE_TEAM})
    @Operation(
            summary = "Create a new user",
            description = "Create a new user with specified details. " +
                    "Super admins and users with 'users:create:all' can create any user. " +
                    "Users with 'users:create:team' can only create users for their teams.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "User created successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input or username already exists"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<UserDto>> createUser(
            @Valid @RequestBody CreateUserRequest request,
            Authentication authentication) {
        UserDto createdUser = userService.createUserDto(request, authentication);
        return ResponseUtil.created("User created successfully", createdUser);
    }

    @PutMapping("/{id}")
    @RequiresPermission({Permission.USERS_EDIT_ALL, Permission.USERS_EDIT_TEAM})
    @Operation(
            summary = "Update an existing user",
            description = "Update an existing user's details. " +
                    "Super admins and users with 'users:edit:all' can update any user. " +
                    "Users with 'users:edit:team' can only update users in their teams.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "User updated successfully"),
                    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input or username already exists"),
                    @ApiResponse(responseCode = "404", description = "User not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<UserDto>> updateUser(
            @PathVariable String id,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication) {
        UserDto updatedUser = userService.updateUserDto(id, request, authentication);
        return ResponseUtil.success("User updated successfully", updatedUser);
    }

    @PostMapping("/{id}/password-reset")
    @RequiresPermission({Permission.USERS_EDIT_ALL, Permission.USERS_EDIT_TEAM})
    @Operation(
            summary = "Send a password reset link to a user",
            description = "Emails a reset link to the user, on an administrator's behalf. Unlike "
                    + "the public forgot-password endpoint, which must answer identically whatever "
                    + "happens so it cannot be used to discover which addresses have accounts, this "
                    + "one reports what actually happened: that the user has no email address, that "
                    + "they sign in through an identity provider, or that email is not configured.",
            responses = {
                @ApiResponse(responseCode = "200", description = "The link was sent"),
                @ApiResponse(responseCode = "400", description = "No email, an SSO account, or email not configured"),
                @ApiResponse(responseCode = "403", description = "Forbidden - requires users:edit"),
                @ApiResponse(responseCode = "404", description = "User not found")
            }
    )
    public ResponseEntity<JsonApiResponse<Map<String, String>>> sendPasswordReset(
            @PathVariable String id) {
        String email = passwordResetService.sendResetLinkFor(id);
        return ResponseUtil.success("Password reset link sent", Map.of("email", email));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({Permission.USERS_DELETE_ALL, Permission.USERS_DELETE_TEAM})
    @Operation(
            summary = "Delete a user",
            description = "Soft delete a user by setting deletedAt timestamp. " +
                    "Super admins and users with 'users:delete:all' can delete any user. " +
                    "Users with 'users:delete:team' can only delete users in their teams.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "User deleted successfully"),
                    @ApiResponse(responseCode = "404", description = "User not found"),
                    @ApiResponse(responseCode = "403", description = "Forbidden"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized")
            }
    )
    public ResponseEntity<JsonApiResponse<Void>> deleteUser(
            @PathVariable String id,
            Authentication authentication) {
        userService.deleteUserById(id, authentication);
        return ResponseUtil.success("User deleted successfully");
    }

    // ==================== APPLICATION ASSIGNMENTS ====================

    @GetMapping("/{id}/application-assignments")
    @RequiresPermission(Permission.APPLICATIONS_EDIT_ALL)
    @Operation(summary = "List the applications a user is assigned to (app-level owner access)")
    public ResponseEntity<JsonApiResponse<List<UserApplicationAssignmentDto>>> getApplicationAssignments(
            @PathVariable String id) {
        return ResponseUtil.success("Application assignments retrieved successfully",
                applicationService.getUserAssignments(id));
    }

    @PutMapping("/{id}/application-assignments")
    @RequiresPermission(Permission.APPLICATIONS_EDIT_ALL)
    @Operation(summary = "Replace the full set of a user's application assignments",
            description = "Adds/updates the listed applications and removes the user from any "
                    + "others. An empty list clears app-level restriction, returning the user "
                    + "to organization-level access (if they have a home organization).")
    public ResponseEntity<JsonApiResponse<List<UserApplicationAssignmentDto>>> syncApplicationAssignments(
            @PathVariable String id,
            @Valid @RequestBody SyncUserApplicationAssignmentsRequest request) {
        return ResponseUtil.success("Application assignments updated successfully",
                applicationService.syncUserAssignments(id, request.getAssignments()));
    }
}
