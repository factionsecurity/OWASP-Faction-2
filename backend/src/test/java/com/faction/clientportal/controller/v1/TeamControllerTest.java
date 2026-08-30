package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.Team;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TeamControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        // Clear teams
        teamRepository.deleteAll();

        // Create a dedicated super-admin for this class instead of logging in as the
        // bootstrapped "admin" user — other test classes wipe the users table, so
        // relying on bootstrap state makes this class order-dependent. The membership
        // tests below add/remove this user from teams, so it must exist in the DB;
        // the token is still minted directly rather than via login.
        Role adminRole = roleRepository.findByName("TeamTestSuperAdmin")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name("TeamTestSuperAdmin")
                        .permissions(List.of("super_admin"))
                        .build()));

        User admin = userRepository.findByUsername("team-test-admin")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("team-test-admin")
                        .firstName("Team")
                        .lastName("Admin")
                        .email("team-admin@test.com")
                        .password(passwordEncoder.encode("password"))
                        .loginOption(LoginOption.NATIVE)
                        .roleIds(List.of(adminRole.getId()))
                        .isInternal(true)
                        .createdAt(LocalDateTime.now())
                        .failedLoginAttempts(0)
                        .build()));

        adminToken = jwtService.generateToken(admin.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));
    }

    @Test
    void listTeams_WithUsersReadAll_ReturnsTeams() throws Exception {
        // Team reads now accept users:read:all / users:read:team (previously super_admin-only),
        // matching the "teams use user permissions" convention — reachable by a read-only
        // super-admin token via the read-universe expansion.
        String token = jwtService.generateToken(
                "team-reader",
                List.of(new SimpleGrantedAuthority("users:read:all")));

        mockMvc.perform(get("/api/v1/teams")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
  
    }

    @Test
    void listTeams_WithUsersReadTeam_ReturnsTeams() throws Exception {
        // A team-scoped scheduler holds users:read:team, never users:read:all. They still have to
        // pick the team an assessment belongs to — the field every team scope resolves against —
        // so the team reads accept the team tier as well.
        String token = jwtService.generateToken(
                "team-scoped-reader",
                List.of(new SimpleGrantedAuthority("users:read:team")));

        mockMvc.perform(get("/api/v1/teams")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void createTeam_WithoutSuperAdmin_IsForbidden() throws Exception {
        // Widening the reads must not widen the writes: managing teams stays super-admin only.
        String token = jwtService.generateToken(
                "team-scoped-reader",
                List.of(new SimpleGrantedAuthority("users:read:team")));

        mockMvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\",\"description\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createTeam_WithValidData_ReturnsCreatedTeam() throws Exception {
        mockMvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Engineering Team\",\"description\":\"Software engineering team\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("Engineering Team"))
                .andExpect(jsonPath("$.data.description").value("Software engineering team"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    void createTeam_WithDuplicateName_ReturnsBadRequest() throws Exception {
        // Create first team
        Team team = Team.builder()
                .name("Security Team")
                .description("Security team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        teamRepository.save(team);

        // Try to create team with same name
        mockMvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Security Team\",\"description\":\"Another security team\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Team with name 'Security Team' already exists"));
    }

    @Test
    void createTeam_WithoutName_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/teams")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Team without name\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAllTeams_ReturnsAllTeams() throws Exception {
        // Create multiple teams
        Team team1 = Team.builder()
                .name("Team Alpha")
                .description("First team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team team2 = Team.builder()
                .name("Team Beta")
                .description("Second team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        teamRepository.save(team1);
        teamRepository.save(team2);

        mockMvc.perform(get("/api/v1/teams")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("Team Alpha", "Team Beta")));
    }

    @Test
    void getTeamById_WithValidId_ReturnsTeam() throws Exception {
        // Create a team
        Team team = Team.builder()
                .name("Development Team")
                .description("Dev team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        mockMvc.perform(get("/api/v1/teams/" + savedTeam.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(savedTeam.getId()))
                .andExpect(jsonPath("$.data.name").value("Development Team"))
                .andExpect(jsonPath("$.data.description").value("Dev team"));
    }

    @Test
    void getTeamById_WithInvalidId_ReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/teams/nonexistent-id")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Team not found with id: nonexistent-id"));
    }

    @Test
    void updateTeam_WithValidData_ReturnsUpdatedTeam() throws Exception {
        // Create a team
        Team team = Team.builder()
                .name("Original Name")
                .description("Original description")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        mockMvc.perform(put("/api/v1/teams/" + savedTeam.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\",\"description\":\"Updated description\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(savedTeam.getId()))
                .andExpect(jsonPath("$.data.name").value("Updated Name"))
                .andExpect(jsonPath("$.data.description").value("Updated description"));
    }

    @Test
    void updateTeam_WithDuplicateName_ReturnsBadRequest() throws Exception {
        // Create two teams
        Team team1 = Team.builder()
                .name("Team One")
                .description("First team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team team2 = Team.builder()
                .name("Team Two")
                .description("Second team")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        teamRepository.save(team1);
        Team savedTeam2 = teamRepository.save(team2);

        // Try to update team2 with team1's name
        mockMvc.perform(put("/api/v1/teams/" + savedTeam2.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Team One\",\"description\":\"Updated description\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Team with name 'Team One' already exists"));
    }

    @Test
    void updateTeam_WithInvalidId_ReturnsNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/teams/nonexistent-id")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\",\"description\":\"Updated description\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Team not found with id: nonexistent-id"));
    }

    @Test
    void deleteTeam_WithValidId_DeletesTeam() throws Exception {
        // Create a team
        Team team = Team.builder()
                .name("Team to Delete")
                .description("This team will be deleted")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        mockMvc.perform(delete("/api/v1/teams/" + savedTeam.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Verify team is deleted
        mockMvc.perform(get("/api/v1/teams/" + savedTeam.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTeam_RemovesTeamFromUsers() throws Exception {
        // Create a team
        Team team = Team.builder()
                .name("Team with Users")
                .description("Team that has users")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        // Create a user with this team
        User user = userRepository.findByUsername("team-test-admin").orElseThrow();
        List<String> teamIds = new ArrayList<>();
        teamIds.add(savedTeam.getId());
        user.setTeamIds(teamIds);
        userRepository.save(user);

        // Delete the team
        mockMvc.perform(delete("/api/v1/teams/" + savedTeam.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Verify team was removed from user
        User updatedUser = userRepository.findByUsername("team-test-admin").orElseThrow();
        assert !updatedUser.getTeamIds().contains(savedTeam.getId());
    }

    @Test
    void deleteTeam_WithInvalidId_ReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/teams/nonexistent-id")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Team not found with id: nonexistent-id"));
    }

    @Test
    void addUserToTeam_WithValidIds_AddsUserToTeam() throws Exception {
        // Create a team
        Team team = Team.builder()
                .name("Target Team")
                .description("Team to add user to")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        // Get admin user
        User user = userRepository.findByUsername("team-test-admin").orElseThrow();

        mockMvc.perform(post("/api/v1/teams/" + savedTeam.getId() + "/users/" + user.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User added to team successfully"));

        // Verify user has team in teamIds
        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assert updatedUser.getTeamIds().contains(savedTeam.getId());
    }

    @Test
    void addUserToTeam_WhenAlreadyMember_ReturnsBadRequest() throws Exception {
        // Create a team
        Team team = Team.builder()
                .name("Existing Team")
                .description("Team with existing member")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        // Get admin user and add team
        User user = userRepository.findByUsername("team-test-admin").orElseThrow();
        List<String> teamIds = new ArrayList<>();
        teamIds.add(savedTeam.getId());
        user.setTeamIds(teamIds);
        userRepository.save(user);

        // Try to add user again
        mockMvc.perform(post("/api/v1/teams/" + savedTeam.getId() + "/users/" + user.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("User is already a member of this team"));
    }

    @Test
    void addUserToTeam_WithInvalidTeamId_ReturnsNotFound() throws Exception {
        User user = userRepository.findByUsername("team-test-admin").orElseThrow();

        mockMvc.perform(post("/api/v1/teams/nonexistent-team/users/" + user.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Team not found with id: nonexistent-team"));
    }

    @Test
    void addUserToTeam_WithInvalidUserId_ReturnsNotFound() throws Exception {
        Team team = Team.builder()
                .name("Valid Team")
                .description("Team with valid ID")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        mockMvc.perform(post("/api/v1/teams/" + savedTeam.getId() + "/users/nonexistent-user")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("User not found with id: nonexistent-user"));
    }

    @Test
    void removeUserFromTeam_WithValidIds_RemovesUserFromTeam() throws Exception {
        // Create a team
        Team team = Team.builder()
                .name("Team to Leave")
                .description("Team user will leave")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        // Get admin user and add team
        User user = userRepository.findByUsername("team-test-admin").orElseThrow();
        List<String> teamIds = new ArrayList<>();
        teamIds.add(savedTeam.getId());
        user.setTeamIds(teamIds);
        userRepository.save(user);

        mockMvc.perform(delete("/api/v1/teams/" + savedTeam.getId() + "/users/" + user.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User removed from team successfully"));

        // Verify user no longer has team in teamIds
        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assert !updatedUser.getTeamIds().contains(savedTeam.getId());
    }

    @Test
    void removeUserFromTeam_WhenNotMember_ReturnsBadRequest() throws Exception {
        // Create a team
        Team team = Team.builder()
                .name("Non-Member Team")
                .description("Team user is not in")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Team savedTeam = teamRepository.save(team);

        User user = userRepository.findByUsername("team-test-admin").orElseThrow();

        mockMvc.perform(delete("/api/v1/teams/" + savedTeam.getId() + "/users/" + user.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("User is not a member of this team"));
    }

    @Test
    void removeUserFromTeam_WithInvalidTeamId_ReturnsNotFound() throws Exception {
        User user = userRepository.findByUsername("team-test-admin").orElseThrow();

        mockMvc.perform(delete("/api/v1/teams/nonexistent-team/users/" + user.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Team not found with id: nonexistent-team"));
    }

    @Test
    void teamOperations_WithoutAuth_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/teams"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test Team\"}"))
                .andExpect(status().isForbidden());
    }
}
