package com.faction.clientportal.service;

import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.dto.CreateUserRequest;
import com.faction.clientportal.dto.UpdateUserRequest;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final ApiKeyService apiKeyService;
    private final EditionPolicy editionPolicy;

    public User createUser(User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public UserDto findDtoByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    /**
     * The {@code system:} prefix is reserved — the API-key authentication filter uses
     * {@code system:<name>} as the synthetic principal for ownerless system keys, so a real
     * username with that prefix could collide with (and be mistaken for) a system-key principal.
     */
    private static void validateUsername(String username) {
        if (username != null && username.toLowerCase().startsWith("system:")) {
            throw new IllegalArgumentException("Usernames may not start with 'system:' (reserved prefix)");
        }
    }

    public Optional<User> findById(String id) {
        return userRepository.findById(id);
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public User updateUser(User user) {
        return userRepository.save(user);
    }

    public void deleteUser(String id) {
        // Revoke the user's API keys before removing them, so no active-but-orphaned keys linger.
        apiKeyService.revokeAllForUser(id);
        userRepository.deleteById(id);
    }

    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public long count() {
        return userRepository.count();
    }

    // ==================== HELPER METHODS ====================

    /**
     * Whether the caller may reach every user, rather than only their teammates.
     *
     * <p>Matches the {@code users:*:all} grants exactly. It used to accept any authority
     * <em>containing</em> {@code ":all"}, which meant an unrelated grant — {@code applications:read:all}
     * on a pentester role, say — silently turned {@code users:read:team} into unrestricted access to
     * every user in the system. Scope is per resource: only a users-level "all" widens users.
     */
    private static final Set<String> USERS_ALL_SCOPES = Set.of(
            Permission.USERS_READ_ALL.getPermission(),
            Permission.USERS_EDIT_ALL.getPermission(),
            Permission.USERS_CREATE_ALL.getPermission(),
            Permission.USERS_DELETE_ALL.getPermission());

    private boolean hasAllScopeOrSuperAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(auth -> auth.equals("super_admin") || USERS_ALL_SCOPES.contains(auth));
    }

    /**
     * Get the current user from authentication
     */
    private User getCurrentUser(Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication required");
        }

        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found: " + username));
    }

    /**
     * Check if a target user shares any team with the current user
     */
    private boolean sharesTeam(User currentUser, User targetUser) {
        if (currentUser.getTeamIds() == null || currentUser.getTeamIds().isEmpty()) {
            return false;
        }
        if (targetUser.getTeamIds() == null || targetUser.getTeamIds().isEmpty()) {
            return false;
        }

        return currentUser.getTeamIds().stream()
                .anyMatch(teamId -> targetUser.getTeamIds().contains(teamId));
    }

    /**
     * Validate that the current user can access the target user
     * - Super admins and users with 'all' scope can access any user
     * - Users with 'team' scope can only access users in their teams
     */
    private void validateUserAccess(User targetUser, Authentication authentication) {
        if (hasAllScopeOrSuperAdmin(authentication)) {
            return; // Has full access
        }

        User currentUser = getCurrentUser(authentication);
        if (!sharesTeam(currentUser, targetUser)) {
            throw new AccessDeniedException("You can only access users in your teams");
        }
    }

    /**
     * Validate that team IDs in the request match current user's teams for team-scoped users
     */
    private void validateTeamScope(CreateUserRequest request, Authentication authentication) {
        if (hasAllScopeOrSuperAdmin(authentication)) {
            return; // Can create users for any team
        }

        User currentUser = getCurrentUser(authentication);
        if (currentUser.getTeamIds() == null || currentUser.getTeamIds().isEmpty()) {
            throw new AccessDeniedException("You must be assigned to at least one team to create users");
        }

        if (request.getTeamIds() == null || request.getTeamIds().isEmpty()) {
            throw new IllegalArgumentException("You must assign the new user to at least one of your teams");
        }

        // Verify all requested teams are in current user's teams
        boolean allTeamsValid = request.getTeamIds().stream()
                .allMatch(teamId -> currentUser.getTeamIds().contains(teamId));

        if (!allTeamsValid) {
            throw new AccessDeniedException("You can only create users for your own teams");
        }
    }

    /**
     * Validate that team IDs in the update request match current user's teams for team-scoped users
     */
    private void validateTeamScope(UpdateUserRequest request, Authentication authentication) {
        if (hasAllScopeOrSuperAdmin(authentication)) {
            return; // Can update users for any team
        }

        User currentUser = getCurrentUser(authentication);
        if (currentUser.getTeamIds() == null || currentUser.getTeamIds().isEmpty()) {
            throw new AccessDeniedException("You must be assigned to at least one team to update users");
        }

        if (request.getTeamIds() == null || request.getTeamIds().isEmpty()) {
            throw new IllegalArgumentException("You must assign the user to at least one of your teams");
        }

        // Verify all requested teams are in current user's teams
        boolean allTeamsValid = request.getTeamIds().stream()
                .allMatch(teamId -> currentUser.getTeamIds().contains(teamId));

        if (!allTeamsValid) {
            throw new AccessDeniedException("You can only assign users to your own teams");
        }
    }

    // ==================== NEW CRUD METHODS ====================

    public UserDto createUserDto(CreateUserRequest request, Authentication authentication) {
        // Validate team scope
        validateTeamScope(request, authentication);

        return createUserDto(request);
    }

    private UserDto createUserDto(CreateUserRequest request) {
        // An external account is a portal user, which is the external-owner feature. The
        // form hides the choice where it does not apply, but the form is not the gate.
        if (Boolean.FALSE.equals(request.getIsInternal())) {
            editionPolicy.require(Feature.EXTERNAL_OWNERS);
        }

        // Check if username already exists
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username '" + request.getUsername() + "' already exists");
        }

        // Check if email already exists
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new IllegalArgumentException("Email '" + request.getEmail() + "' already exists");
        }

        // Validate roles exist
        List<Role> roles = roleRepository.findAllById(request.getRoleIds());
        if (roles.size() != request.getRoleIds().size()) {
            throw new IllegalArgumentException("One or more role IDs are invalid");
        }

        // Validate organization exists if provided
        if (request.getOrganizationId() != null && !request.getOrganizationId().isEmpty()) {
            organizationRepository.findById(request.getOrganizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + request.getOrganizationId()));
        }


        validateUsername(request.getUsername());

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .password(passwordEncoder.encode(request.getPassword()))
                .loginOption(request.getLoginOption())
                .roleIds(request.getRoleIds())
                .teamIds(request.getTeamIds() != null ? request.getTeamIds() : new ArrayList<>())
                .isInternal(request.getIsInternal())
                .organizationId(request.getOrganizationId())
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .disabledAt(Boolean.TRUE.equals(request.getDisabled()) ? LocalDateTime.now() : null)
                .build();

        User savedUser = userRepository.save(user);
        return toDto(savedUser);
    }

    public UserDto updateUserDto(String id, UpdateUserRequest request, Authentication authentication) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        // Validate access to this user
        validateUserAccess(user, authentication);

        // Validate team scope
        validateTeamScope(request, authentication);

        return updateUserDto(id, request);
    }

    private UserDto updateUserDto(String id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        // Turning an existing account external is the same decision as creating one that
        // way. Accounts already external stay editable, so a downgraded install can still
        // manage the users it has.
        if (Boolean.FALSE.equals(request.getIsInternal()) && !Boolean.FALSE.equals(user.getIsInternal())) {
            editionPolicy.require(Feature.EXTERNAL_OWNERS);
        }

        // Check if updating to a username that already exists (and it's not the same user)
        if (!user.getUsername().equals(request.getUsername()) && userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username '" + request.getUsername() + "' already exists");
        }

        // Check if updating to an email that already exists (and it's not the same user)
        if ((user.getEmail() == null || !user.getEmail().equalsIgnoreCase(request.getEmail()))
                && userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new IllegalArgumentException("Email '" + request.getEmail() + "' already exists");
        }

        // Resolve valid role IDs (silently drops any stale IDs for deleted roles)
        List<Role> roles = roleRepository.findAllById(request.getRoleIds());
        List<String> validRoleIds = roles.stream().map(Role::getId).collect(Collectors.toList());

        // Validate organization exists if provided
        if (request.getOrganizationId() != null && !request.getOrganizationId().isEmpty()) {
            organizationRepository.findById(request.getOrganizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + request.getOrganizationId()));
        }


        validateUsername(request.getUsername());
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());

        // Only update password if provided
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        user.setLoginOption(request.getLoginOption());
        user.setRoleIds(validRoleIds);
        user.setTeamIds(request.getTeamIds() != null ? request.getTeamIds() : new ArrayList<>());
        user.setIsInternal(request.getIsInternal());
        user.setOrganizationId(request.getOrganizationId());

        // Null means "leave as is" so an ordinary edit can't re-enable an account by omission.
        // Re-enabling also clears the failed-attempt count, which would otherwise keep a
        // lockout in force against someone who was just let back in.
        if (request.getDisabled() != null) {
            if (request.getDisabled()) {
                if (user.getDisabledAt() == null) {
                    user.setDisabledAt(LocalDateTime.now());
                }
            } else {
                user.setDisabledAt(null);
                user.setFailedLoginAttempts(0);
            }
        }

        User updatedUser = userRepository.save(user);
        return toDto(updatedUser);
    }

    public void deleteUserById(String id, Authentication authentication) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        // Validate access to this user
        validateUserAccess(user, authentication);

        // Soft delete the user
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);

        // Revoke the user's API keys. The live owner-check already makes them inert while deleted,
        // but revocation is permanent — so restoring this soft-deleted user cannot resurrect
        // credentials that may have leaked while the account was gone.
        apiKeyService.revokeAllForUser(id);
    }

    public UserDto findUserById(String id, Authentication authentication) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        // Validate access to this user
        validateUserAccess(user, authentication);

        return toDto(user);
    }

    public Page<UserDto> findAllUsersPaginated(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toDto);
    }

    public Page<UserDto> searchUsersPaginated(String search, Pageable pageable, Authentication authentication) {
        return searchUsersPaginated(search, null, null, null, null, pageable, authentication);
    }

    /**
     * The user list: free-text search plus the optional role / team / organization / type filters
     * the Users page exposes. Every filter is ANDed, and a null one is ignored.
     *
     * <p>Role and team membership live in jsonb columns, so those two are resolved to a set of
     * user ids first and applied as an {@code IN} — which keeps the list query itself JPQL, so the
     * table's column sorting still works. Two of them intersect, so "role X and team Y" means
     * users who are both.
     *
     * @param isInternal true for internal users, false for external, null for both
     */
    public Page<UserDto> searchUsersPaginated(String search, String roleId, String teamId,
                                              String organizationId, Boolean isInternal,
                                              Pageable pageable, Authentication authentication) {
        String pattern = (search == null || search.trim().isEmpty())
                ? null : search.trim().toLowerCase() + "%";
        String org = (organizationId == null || organizationId.isBlank()) ? null : organizationId;

        Set<String> ids = null;
        if (roleId != null && !roleId.isBlank()) {
            ids = new HashSet<>(userRepository.findIdsByRoleId(roleId));
        }
        if (teamId != null && !teamId.isBlank()) {
            List<String> teamMembers = userRepository.findIdsByTeamId(teamId);
            ids = ids == null ? new HashSet<>(teamMembers)
                    : ids.stream().filter(new HashSet<>(teamMembers)::contains)
                            .collect(Collectors.toSet());
        }
        // No one matches: skip the query rather than send an empty IN list.
        if (ids != null && ids.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        Page<User> userPage = userRepository.searchFiltered(
                pattern, org, isInternal,
                ids != null,
                // The IN list is unused when filterByIds is false, but must still be non-empty for
                // the query to parse.
                ids != null ? ids : Set.of(""),
                pageable);

        // Filter by team if user has team scope
        if (!hasAllScopeOrSuperAdmin(authentication)) {
            User currentUser = getCurrentUser(authentication);
            if (currentUser.getTeamIds() == null || currentUser.getTeamIds().isEmpty()) {
                // User has no teams, return empty page
                return new PageImpl<>(List.of(), pageable, 0);
            }

            // Filter users to only those who share at least one team
            List<UserDto> filteredUsers = userPage.getContent().stream()
                    .filter(user -> sharesTeam(currentUser, user))
                    .map(this::toDto)
                    .collect(Collectors.toList());

            return new PageImpl<>(filteredUsers, pageable, filteredUsers.size());
        }

        return userPage.map(this::toDto);
    }

    private UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .loginOption(user.getLoginOption())
                .roleIds(user.getRoleIds())
                .teamIds(user.getTeamIds())
                .isInternal(user.getIsInternal())
                .organizationId(user.getOrganizationId())
                .createdAt(user.getCreatedAt())
                .deletedAt(user.getDeletedAt())
                .disabledAt(user.getDisabledAt())
                .failedLoginAttempts(user.getFailedLoginAttempts())
                .lastLogin(user.getLastLogin())
                .profileImageId(user.getProfileImageId())
                .build();
    }
}
