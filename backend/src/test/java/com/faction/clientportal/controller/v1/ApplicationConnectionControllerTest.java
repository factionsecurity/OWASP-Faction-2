package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationConnectionRepository;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application connection map — which system depends on, calls or authenticates against which —
 * had no test touching any of its nine endpoints.
 *
 * <p>The direction of a connection is the thing worth pinning: outgoing and incoming are different
 * questions, and a map that answers them the wrong way round misdescribes the blast radius of an
 * application being compromised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationConnectionControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationConnectionRepository connectionRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;

    private String token;
    private String readOnlyToken;
    private Application web;
    private Application api;
    private Application db;

    @BeforeEach
    void setUp() {
        connectionRepository.deleteAll();
        applicationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        token = tokenFor("admin", "super_admin");
        readOnlyToken = tokenFor("reader", "applications:read:all");

        web = saveApp("Web Front End");
        api = saveApp("Payments API");
        db = saveApp("Ledger DB");
    }

    private String connect(String source, String target, String type) throws Exception {
        String body = mockMvc.perform(post("/api/v1/application-connections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"sourceApplicationId":"%s","targetApplicationId":"%s",
                                 "type":"%s","description":"d","critical":true,
                                 "dataSensitivity":"HIGH"}""", source, target, type)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void aConnectionIsCreatedWithBothApplicationNamesResolved() throws Exception {
        mockMvc.perform(post("/api/v1/application-connections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"sourceApplicationId":"%s","targetApplicationId":"%s",
                                 "type":"USES_API","description":"checkout","critical":true,
                                 "dataSensitivity":"HIGH"}""", web.getId(), api.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sourceApplicationName").value("Web Front End"))
                .andExpect(jsonPath("$.data.targetApplicationName").value("Payments API"));
    }

    @Test
    void outgoingAndIncomingAreOppositeEndsOfTheSameConnection() throws Exception {
        connect(web.getId(), api.getId(), "USES_API");

        mockMvc.perform(get("/api/v1/application-connections/application/{id}/outgoing", web.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/application-connections/application/{id}/incoming", web.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(0));

        // …and the mirror image from the other end.
        mockMvc.perform(get("/api/v1/application-connections/application/{id}/incoming", api.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/application-connections/application/{id}/outgoing", api.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void theAllViewCombinesBothDirections() throws Exception {
        connect(web.getId(), api.getId(), "USES_API");
        connect(db.getId(), api.getId(), "CONSUMES_DATA");

        mockMvc.perform(get("/api/v1/application-connections/application/{id}/all", api.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void theSamePairCannotBeConnectedTwiceInTheSameDirection() throws Exception {
        connect(web.getId(), api.getId(), "USES_API");

        mockMvc.perform(post("/api/v1/application-connections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"sourceApplicationId":"%s","targetApplicationId":"%s",
                                 "type":"DEPENDS_ON","description":"again"}""",
                                web.getId(), api.getId())))
                .andExpect(status().isBadRequest());

        assertThat(connectionRepository.count()).isEqualTo(1);
    }

    @Test
    void theReverseDirectionIsADifferentConnection() throws Exception {
        connect(web.getId(), api.getId(), "USES_API");

        // A depends on B and B depends on A are both meaningful, and both may be true.
        connect(api.getId(), web.getId(), "CONSUMES_DATA");

        assertThat(connectionRepository.count()).isEqualTo(2);
    }

    @Test
    void connectingToAnApplicationThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(post("/api/v1/application-connections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"sourceApplicationId":"%s","targetApplicationId":"ghost",
                                 "type":"USES_API"}""", web.getId())))
                .andExpect(status().isNotFound());

        assertThat(connectionRepository.count()).isZero();
    }

    @Test
    void aConnectionCanBeUpdatedAndDeleted() throws Exception {
        String id = connect(web.getId(), api.getId(), "USES_API");

        mockMvc.perform(put("/api/v1/application-connections/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DEPENDS_ON","description":"revised","critical":false,
                                 "dataSensitivity":"LOW"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("revised"))
                .andExpect(jsonPath("$.data.type").value("DEPENDS_ON"));

        mockMvc.perform(delete("/api/v1/application-connections/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // A hard delete here, unlike most of the app — the row is gone, not flagged.
        assertThat(connectionRepository.findById(id)).isEmpty();
        mockMvc.perform(get("/api/v1/application-connections/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownConnectionIs404OnEveryVerb() throws Exception {
        mockMvc.perform(get("/api/v1/application-connections/{id}", "ghost")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // A complete body, or validation rejects it as a 400 before the lookup ever runs.
        mockMvc.perform(put("/api/v1/application-connections/{id}", "ghost")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"USES_API\",\"description\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/application-connections/{id}", "ghost")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void theUpdateIsAFullReplaceAndTypeIsMandatory() throws Exception {
        String id = connect(web.getId(), api.getId(), "USES_API");

        // Omitting type is refused outright…
        mockMvc.perform(put("/api/v1/application-connections/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"only this\"}"))
                .andExpect(status().isBadRequest());

        // …but the other fields are replaced, not merged. A caller sending a partial body clears
        // whatever it left out, which is what PUT means and is easy to get wrong from a form.
        mockMvc.perform(put("/api/v1/application-connections/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"USES_API\"}"))
                .andExpect(status().isOk());

        var stored = connectionRepository.findById(id).orElseThrow();
        assertThat(stored.getDescription()).isNull();
        assertThat(stored.getCritical()).isNull();
        assertThat(stored.getDataSensitivity()).isNull();
    }

    @Test
    void readPermissionDoesNotCarryWritePermission() throws Exception {
        String id = connect(web.getId(), api.getId(), "USES_API");

        mockMvc.perform(get("/api/v1/application-connections")
                        .header("Authorization", "Bearer " + readOnlyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(delete("/api/v1/application-connections/{id}", id)
                        .header("Authorization", "Bearer " + readOnlyToken))
                .andExpect(status().isForbidden());

        assertThat(connectionRepository.findById(id)).isPresent();
    }

    private Application saveApp(String name) {
        return applicationRepository.save(Application.builder()
                .name(name).description("d").organizationId("org-1")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private String tokenFor(String username, String authority) {
        Role role = roleRepository.save(Role.builder()
                .name(username + "-role").permissions(List.of(authority)).build());
        User user = userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE).roleIds(List.of(role.getId()))
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        return jwtService.generateToken(user.getUsername(), List.of(new SimpleGrantedAuthority(authority)));
    }
}
