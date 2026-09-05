package com.faction.clientportal.controller.v1;

import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    // Roles
    private Role superAdminRole;
    private Role usersAllRole;
    private Role usersTeamRole;
    private Role pentesterRole;

    // Teams
    private Team teamAlpha;
    private Team teamBeta;
    private Team teamGamma;

    // Users
    private User superAdminUser;
    private User usersAllUser;
    private User teamAlphaManagerUser;
    private User teamBetaManagerUser;
    private User teamAlphaMemberUser;
    private User teamBetaMemberUser;
    private User noTeamManagerUser;
    private User multiTeamUser;
    private User noPermissionsUser;

    private Organization testOrganization;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        teamRepository.deleteAll();
        organizationRepository.deleteAll();

        // Create test organization
        testOrganization = Organization.builder()
                .name("Test Organization")
                .description("Organization for testing")
                .build();
        testOrganization = organizationRepository.save(testOrganization);

        // Create teams
        teamAlpha = Team.builder()
                .name("Team Alpha")
                .description("First test team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        teamAlpha = teamRepository.save(teamAlpha);

        teamBeta = Team.builder()
                .name("Team Beta")
                .description("Second test team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        teamBeta = teamRepository.save(teamBeta);

        teamGamma = Team.builder()
                .name("Team Gamma")
                .description("Third test team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        teamGamma = teamRepository.save(teamGamma);

        // Create roles
        superAdminRole = Role.builder()
                .name("SuperAdmin")
                .description("Super Administrator with full access")
                .permissions(List.of("super_admin"))
                .build();
        superAdminRole = roleRepository.save(superAdminRole);

        usersAllRole = Role.builder()
                .name("UsersAll")
                .description("Full user management permissions")
                .permissions(List.of(
                        "users:read:all",
                        "users:create:all",
                        "users:edit:all",
                        "users:delete:all"
                ))
                .build();
        usersAllRole = roleRepository.save(usersAllRole);

        usersTeamRole = Role.builder()
                .name("UsersTeam")
                .description("Team-scoped user management permissions")
                .permissions(List.of(
                        "users:read:team",
                        "users:create:team",
                        "users:edit:team",
                        "users:delete:team"
                ))
                .build();
        usersTeamRole = roleRepository.save(usersTeamRole);

        pentesterRole = Role.builder()
                .name("Pentester")
                .description("Pentester with no user management permissions")
                .permissions(List.of("assessments:read:team"))
                .build();
        pentesterRole = roleRepository.save(pentesterRole);

        // Create users
        superAdminUser = createUser("superadmin", "superadmin@test.com", "Super", "Admin",
                superAdminRole, null);

        usersAllUser = createUser("usersall", "usersall@test.com", "Users", "All",
                usersAllRole, null);

        teamAlphaManagerUser = createUser("alphamanager", "alphamanager@test.com", "Alpha", "Manager",
                usersTeamRole, List.of(teamAlpha.getId()));

        teamBetaManagerUser = createUser("betamanager", "betamanager@test.com", "Beta", "Manager",
                usersTeamRole, List.of(teamBeta.getId()));

        teamAlphaMemberUser = createUser("alphamember", "alphamember@test.com", "Alpha", "Member",
                pentesterRole, List.of(teamAlpha.getId()));

        teamBetaMemberUser = createUser("betamember", "betamember@test.com", "Beta", "Member",
                pentesterRole, List.of(teamBeta.getId()));

        noTeamManagerUser = createUser("noteammanager", "noteammanager@test.com", "NoTeam", "Manager",
                usersTeamRole, null);

        multiTeamUser = createUser("multiteam", "multiteam@test.com", "Multi", "Team",
                usersTeamRole, List.of(teamAlpha.getId(), teamBeta.getId()));

        noPermissionsUser = createUser("nopermissions", "nopermissions@test.com", "No", "Permissions",
                pentesterRole, null);
    }

    private User createUser(String username, String email, String firstName, String lastName,
                            Role role, List<String> teamIds) {
        User user = User.builder()
                .username(username)
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .teamIds(teamIds != null ? teamIds : new ArrayList<>())
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        return userRepository.save(user);
    }

    // ==================== GET ALL USERS TESTS ====================

    @Test
    void getAllUsers_AsSuperAdmin_ReturnsAllUsers() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(9)); // All users created in setup
    }

    @Test
    void getAllUsers_WithUsersReadAll_ReturnsAllUsers() throws Exception {
        String token = generateToken(usersAllUser, List.of("users:read:all"));

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(9));
    }

    // ── Filters ────────────────────────────────────────────────────────────────

    @Test
    void getAllUsers_FilteredByRole_ReturnsOnlyRoleHolders() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(get("/api/v1/users")
                        .param("roleId", usersTeamRole.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].username", containsInAnyOrder(
                        "alphamanager", "betamanager", "noteammanager", "multiteam")));
    }

    @Test
    void getAllUsers_FilteredByTeam_ReturnsOnlyTeamMembers() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(get("/api/v1/users")
                        .param("teamId", teamAlpha.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].username", containsInAnyOrder(
                        "alphamanager", "alphamember", "multiteam")));

        // A team nobody belongs to yields an empty page, not everyone.
        mockMvc.perform(get("/api/v1/users")
                        .param("teamId", teamGamma.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.pagination.totalElements").value(0));
    }

    @Test
    void getAllUsers_RoleAndTeamFiltersIntersect() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        // Team Alpha holds alphamanager, alphamember and multiteam; only two of those are managers.
        mockMvc.perform(get("/api/v1/users")
                        .param("roleId", usersTeamRole.getId())
                        .param("teamId", teamAlpha.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].username", containsInAnyOrder(
                        "alphamanager", "multiteam")));
    }

    @Test
    void getAllUsers_FilteredByOrganizationAndType() throws Exception {
        User external = userRepository.save(User.builder()
                .username("externaluser").email("external@test.com")
                .firstName("Ext").lastName("User")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(pentesterRole.getId())).teamIds(new ArrayList<>())
                .isInternal(false).organizationId(testOrganization.getId())
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0)
                .build());
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(get("/api/v1/users")
                        .param("organizationId", testOrganization.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value(external.getUsername()));

        mockMvc.perform(get("/api/v1/users")
                        .param("type", "external")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("externaluser"));

        mockMvc.perform(get("/api/v1/users")
                        .param("type", "INTERNAL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(9))
                .andExpect(jsonPath("$.data[*].username", not(hasItem("externaluser"))));
    }

    @Test
    void getAllUsers_FiltersCombineWithSearchAndSorting() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(get("/api/v1/users")
                        .param("teamId", teamAlpha.getId())
                        .param("search", "alpha")
                        .param("sort", "username,desc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].username").value("alphamember"))
                .andExpect(jsonPath("$.data[1].username").value("alphamanager"));
    }

    @Test
    void getAllUsers_UnknownTypeIsRejected() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(get("/api/v1/users")
                        .param("type", "contractor")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAllUsers_FiltersApplyWithinTeamScope() throws Exception {
        // A team-scoped admin filtering by a team they cannot see still sees only their own users.
        String token = generateToken(teamAlphaManagerUser, List.of("users:read:team"));

        mockMvc.perform(get("/api/v1/users")
                        .param("teamId", teamBeta.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].username", everyItem(is("multiteam"))));
    }

    @Test
    void getAllUsers_WithUsersReadTeam_ReturnsOnlyTeamUsers() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:read:team"));

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3)) // alphamanager, alphamember, multiteam
                .andExpect(jsonPath("$.data[*].username", containsInAnyOrder(
                        "alphamanager", "alphamember", "multiteam"
                )));
    }

    @Test
    void getAllUsers_WithUsersReadTeam_MultipleTeams_ReturnsUsersFromAllTeams() throws Exception {
        String token = generateToken(multiTeamUser, List.of("users:read:team"));

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(5)) // alpha manager/member, beta manager/member, multiteam
                .andExpect(jsonPath("$.data[*].username", containsInAnyOrder(
                        "alphamanager", "alphamember", "betamanager", "betamember", "multiteam"
                )));
    }

    @Test
    void getAllUsers_WithUsersReadTeam_NoTeams_ReturnsEmptyList() throws Exception {
        String token = generateToken(noTeamManagerUser, List.of("users:read:team"));

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getAllUsers_WithNoPermissions_ReturnsForbidden() throws Exception {
        String token = generateToken(noPermissionsUser, List.of("assessments:read:team"));

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllUsers_WithoutAuth_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isForbidden());
    }

    // ==================== GET USER BY ID TESTS ====================

    @Test
    void getUserById_AsSuperAdmin_ReturnsAnyUser() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(get("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("alphamember"));
    }

    @Test
    void getUserById_WithUsersReadAll_ReturnsAnyUser() throws Exception {
        String token = generateToken(usersAllUser, List.of("users:read:all"));

        mockMvc.perform(get("/api/v1/users/" + teamBetaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("betamember"));
    }

    @Test
    void getUserById_WithUsersReadTeam_SameTeam_ReturnsUser() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:read:team"));

        mockMvc.perform(get("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alphamember"));
    }

    @Test
    void getUserById_WithUsersReadTeam_DifferentTeam_ReturnsForbidden() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:read:team"));

        mockMvc.perform(get("/api/v1/users/" + teamBetaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void getUserById_WithUsersReadTeam_NoTeams_ReturnsForbidden() throws Exception {
        String token = generateToken(noTeamManagerUser, List.of("users:read:team"));

        mockMvc.perform(get("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void getUserById_WithNoPermissions_ReturnsForbidden() throws Exception {
        String token = generateToken(noPermissionsUser, List.of("assessments:read:team"));

        mockMvc.perform(get("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void getUserById_NonExistentId_ReturnsNotFound() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(get("/api/v1/users/nonexistent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ==================== CREATE USER TESTS ====================

    @Test

    @EnterpriseOnly
    void createUser_AsSuperAdmin_CreatesSuccessfully() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        String requestBody = String.format("""
                {
                    "username": "newuser",
                    "email": "newuser@test.com",
                    "firstName": "New",
                    "lastName": "User",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamGamma.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("newuser"))
                .andExpect(jsonPath("$.data.teamIds[0]").value(teamGamma.getId()));
    }

    @Test

    @EnterpriseOnly
    void createUser_WithReservedSystemPrefix_ReturnsBadRequest() throws Exception {
        // "system:" usernames are reserved: they would collide with the synthetic principal the
        // API-key filter assigns to system keys ("system:<key name>").
        String token = generateToken(superAdminUser, List.of("super_admin"));

        String requestBody = String.format("""
                {
                    "username": "System:evil",
                    "email": "reserved@test.com",
                    "firstName": "Reserved",
                    "lastName": "Prefix",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamGamma.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test

    @EnterpriseOnly
    void createUser_WithUsersCreateAll_CreatesSuccessfully() throws Exception {
        String token = generateToken(usersAllUser, List.of("users:create:all"));

        String requestBody = String.format("""
                {
                    "username": "newuser",
                    "email": "newuser@test.com",
                    "firstName": "New",
                    "lastName": "User",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamGamma.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("newuser"));
    }

    @Test

    @EnterpriseOnly
    void createUser_WithUsersCreateTeam_OwnTeam_CreatesSuccessfully() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:create:team"));

        String requestBody = String.format("""
                {
                    "username": "newalphauser",
                    "email": "newalphauser@test.com",
                    "firstName": "New",
                    "lastName": "Alpha",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamAlpha.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("newalphauser"))
                .andExpect(jsonPath("$.data.teamIds[0]").value(teamAlpha.getId()));
    }

    @Test
    void createUser_WithUsersCreateTeam_DifferentTeam_ReturnsForbidden() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:create:team"));

        String requestBody = String.format("""
                {
                    "username": "newbetauser",
                    "email": "newbetauser@test.com",
                    "firstName": "New",
                    "lastName": "Beta",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamBeta.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUser_WithUsersCreateTeam_NoTeams_ReturnsForbidden() throws Exception {
        String token = generateToken(noTeamManagerUser, List.of("users:create:team"));

        String requestBody = String.format("""
                {
                    "username": "newuser",
                    "email": "newuser@test.com",
                    "firstName": "New",
                    "lastName": "User",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamAlpha.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUser_WithUsersCreateTeam_NoTeamIds_ReturnsBadRequest() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:create:team"));

        String requestBody = String.format("""
                {
                    "username": "newuser",
                    "email": "newuser@test.com",
                    "firstName": "New",
                    "lastName": "User",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUser_WithNoPermissions_ReturnsForbidden() throws Exception {
        String token = generateToken(noPermissionsUser, List.of("assessments:read:team"));

        String requestBody = String.format("""
                {
                    "username": "newuser",
                    "email": "newuser@test.com",
                    "firstName": "New",
                    "lastName": "User",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test

    @EnterpriseOnly
    void createUser_WithDuplicateUsername_ReturnsBadRequest() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        String requestBody = String.format("""
                {
                    "username": "alphamember",
                    "email": "duplicate@test.com",
                    "firstName": "Duplicate",
                    "lastName": "User",
                    "password": "password123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username 'alphamember' already exists"));
    }

    // ==================== UPDATE USER TESTS ====================

    @Test
    void updateUser_AsSuperAdmin_UpdatesSuccessfully() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        String requestBody = String.format("""
                {
                    "username": "updatedalpha",
                    "email": "updatedalpha@test.com",
                    "firstName": "Updated",
                    "lastName": "Alpha",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamAlpha.getId());

        mockMvc.perform(put("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("updatedalpha"));
    }

    @Test
    void updateUser_WithUsersEditAll_UpdatesSuccessfully() throws Exception {
        String token = generateToken(usersAllUser, List.of("users:edit:all"));

        String requestBody = String.format("""
                {
                    "username": "updatedbeta",
                    "email": "updatedbeta@test.com",
                    "firstName": "Updated",
                    "lastName": "Beta",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamBeta.getId());

        mockMvc.perform(put("/api/v1/users/" + teamBetaMemberUser.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("updatedbeta"));
    }

    @Test
    void updateUser_WithUsersEditTeam_SameTeam_UpdatesSuccessfully() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:edit:team"));

        String requestBody = String.format("""
                {
                    "username": "updatedalpha",
                    "email": "updatedalpha@test.com",
                    "firstName": "Updated",
                    "lastName": "Alpha",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamAlpha.getId());

        mockMvc.perform(put("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("updatedalpha"));
    }

    @Test
    void updateUser_WithUsersEditTeam_DifferentTeam_ReturnsForbidden() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:edit:team"));

        String requestBody = String.format("""
                {
                    "username": "updatedbeta",
                    "email": "updatedbeta@test.com",
                    "firstName": "Updated",
                    "lastName": "Beta",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamBeta.getId());

        mockMvc.perform(put("/api/v1/users/" + teamBetaMemberUser.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateUser_WithUsersEditTeam_AssigningToDifferentTeam_ReturnsForbidden() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:edit:team"));

        String requestBody = String.format("""
                {
                    "username": "alphamember",
                    "email": "alphamember@test.com",
                    "firstName": "Alpha",
                    "lastName": "Member",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamBeta.getId());

        mockMvc.perform(put("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateUser_WithNoPermissions_ReturnsForbidden() throws Exception {
        String token = generateToken(noPermissionsUser, List.of("assessments:read:team"));

        String requestBody = String.format("""
                {
                    "username": "updated",
                    "email": "updated@test.com",
                    "firstName": "Updated",
                    "lastName": "User",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId());

        mockMvc.perform(put("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateUser_NonExistentId_ReturnsNotFound() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        String requestBody = String.format("""
                {
                    "username": "updated",
                    "email": "updated@test.com",
                    "firstName": "Updated",
                    "lastName": "User",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId());

        mockMvc.perform(put("/api/v1/users/nonexistent-id")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isNotFound());
    }

    // ==================== DELETE USER TESTS ====================

    @Test
    void deleteUser_AsSuperAdmin_DeletesSuccessfully() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(delete("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Verify soft delete
        User deletedUser = userRepository.findById(teamAlphaMemberUser.getId()).orElseThrow();
        assertThat(deletedUser.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteUser_WithUsersDeleteAll_DeletesSuccessfully() throws Exception {
        String token = generateToken(usersAllUser, List.of("users:delete:all"));

        mockMvc.perform(delete("/api/v1/users/" + teamBetaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User deletedUser = userRepository.findById(teamBetaMemberUser.getId()).orElseThrow();
        assertThat(deletedUser.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteUser_WithUsersDeleteTeam_SameTeam_DeletesSuccessfully() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:delete:team"));

        mockMvc.perform(delete("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User deletedUser = userRepository.findById(teamAlphaMemberUser.getId()).orElseThrow();
        assertThat(deletedUser.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteUser_WithUsersDeleteTeam_DifferentTeam_ReturnsForbidden() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:delete:team"));

        mockMvc.perform(delete("/api/v1/users/" + teamBetaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_WithUsersDeleteTeam_NoTeams_ReturnsForbidden() throws Exception {
        String token = generateToken(noTeamManagerUser, List.of("users:delete:team"));

        mockMvc.perform(delete("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_WithNoPermissions_ReturnsForbidden() throws Exception {
        String token = generateToken(noPermissionsUser, List.of("assessments:read:team"));

        mockMvc.perform(delete("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_NonExistentId_ReturnsNotFound() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));

        mockMvc.perform(delete("/api/v1/users/nonexistent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ==================== ADDITIONAL VALIDATION TESTS ====================

    @Test

    @EnterpriseOnly
    void createUser_WithExternalUserWithoutOrganization_Succeeds() throws Exception {
        // External users may have no organization (e.g. app-level assignment only)
        String token = generateToken(superAdminUser, List.of("super_admin"));

        String requestBody = String.format("""
                {
                    "username": "externaluser",
                    "email": "external@test.com",
                    "firstName": "External",
                    "lastName": "User",
                    "password": "password123",
                    "loginOption": "SAML2",
                    "roleIds": ["%s"],
                    "isInternal": false
                }
                """, pentesterRole.getId());

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isInternal").value(false));
    }

    @Test
    void updateUser_WithPassword_UpdatesPassword() throws Exception {
        String token = generateToken(superAdminUser, List.of("super_admin"));
        String oldPasswordHash = teamAlphaMemberUser.getPassword();

        String requestBody = String.format("""
                {
                    "username": "alphamember",
                    "email": "alphamember@test.com",
                    "firstName": "Alpha",
                    "lastName": "Member",
                    "password": "Newpassword123",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": ["%s"],
                    "isInternal": true
                }
                """, pentesterRole.getId(), teamAlpha.getId());

        mockMvc.perform(put("/api/v1/users/" + teamAlphaMemberUser.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk());

        User updatedUser = userRepository.findById(teamAlphaMemberUser.getId()).orElseThrow();
        assertThat(updatedUser.getPassword()).isNotEqualTo(oldPasswordHash);
        assertThat(passwordEncoder.matches("Newpassword123", updatedUser.getPassword())).isTrue();
    }

    @Test
    void searchUsers_WithUsersReadTeam_FiltersResults() throws Exception {
        String token = generateToken(teamAlphaManagerUser, List.of("users:read:team"));

        mockMvc.perform(get("/api/v1/users")
                        .param("search", "member")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].username", hasItem("alphamember")))
                .andExpect(jsonPath("$.data[*].username", not(hasItem("betamember"))));
    }

    // ==================== DISABLE / RE-ENABLE ====================

    /** A full-replace update body for the given user, with the extra fields appended verbatim. */
    private String updateBodyFor(User user, String extraJson) {
        return String.format("""
                {
                    "username": "%s",
                    "email": "%s",
                    "firstName": "%s",
                    "lastName": "%s",
                    "loginOption": "NATIVE",
                    "roleIds": ["%s"],
                    "teamIds": [],
                    "isInternal": true%s
                }
                """, user.getUsername(), user.getEmail(), user.getFirstName(), user.getLastName(),
                pentesterRole.getId(), extraJson);
    }

    @Test
    void updateUser_withDisabledTrue_switchesTheAccountOff() throws Exception {
        String token = generateToken(usersAllUser, List.of("users:edit:all"));

        mockMvc.perform(put("/api/v1/users/" + teamBetaMemberUser.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBodyFor(teamBetaMemberUser, ",\n    \"disabled\": true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabledAt").isNotEmpty());

        assertThat(userRepository.findById(teamBetaMemberUser.getId()).orElseThrow().getDisabledAt())
                .isNotNull();
        // Disabling is not deleting: the account is still there, and still listed.
        assertThat(userRepository.findById(teamBetaMemberUser.getId()).orElseThrow().getDeletedAt())
                .isNull();
    }

    @Test
    void updateUser_withDisabledFalse_reEnablesAndClearsTheLockoutCounter() throws Exception {
        // Standing in for a password lockout: disabled, with the failed count that caused it.
        User locked = userRepository.findById(teamBetaMemberUser.getId()).orElseThrow();
        locked.setDisabledAt(LocalDateTime.now());
        locked.setFailedLoginAttempts(5);
        userRepository.save(locked);

        String token = generateToken(usersAllUser, List.of("users:edit:all"));
        mockMvc.perform(put("/api/v1/users/" + locked.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBodyFor(locked, ",\n    \"disabled\": false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabledAt").doesNotExist());

        User reEnabled = userRepository.findById(locked.getId()).orElseThrow();
        assertThat(reEnabled.getDisabledAt()).isNull();
        // Without this they would be locked straight back out on the next typo.
        assertThat(reEnabled.getFailedLoginAttempts()).isZero();
    }

    @Test
    void updateUser_omittingDisabled_leavesADisabledAccountDisabled() throws Exception {
        User locked = userRepository.findById(teamBetaMemberUser.getId()).orElseThrow();
        locked.setDisabledAt(LocalDateTime.now());
        userRepository.save(locked);

        // An ordinary edit — renaming someone — must not quietly let them back in.
        String token = generateToken(usersAllUser, List.of("users:edit:all"));
        mockMvc.perform(put("/api/v1/users/" + locked.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBodyFor(locked, "")))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(locked.getId()).orElseThrow().getDisabledAt()).isNotNull();
    }

    // ==================== HELPER METHODS ====================

    private String generateToken(User user, List<String> permissions) {
        return jwtService.generateToken(
                user.getUsername(),
                permissions.stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );
    }
}
