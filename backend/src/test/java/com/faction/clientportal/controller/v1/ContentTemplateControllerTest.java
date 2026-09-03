package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.ContentTemplate;
import com.faction.clientportal.model.ContentTemplateScope;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ContentTemplateRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentTemplateControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ContentTemplateRepository contentTemplateRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String authorToken;
    private String basicToken;

    @BeforeEach
    void setUp() {
        contentTemplateRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = roleRepository.save(Role.builder()
                .name("SuperAdmin").permissions(List.of("super_admin")).build());
        Role authorRole = roleRepository.save(Role.builder()
                .name("TemplateAuthor")
                .permissions(List.of("content-templates:create", "content-templates:edit",
                        "content-templates:delete")).build());
        Role basicRole = roleRepository.save(Role.builder()
                .name("Basic").permissions(List.of("vulnerabilities:read:all")).build());

        User admin = userRepository.save(user("template-admin", adminRole));
        User author = userRepository.save(user("template-author", authorRole));
        User basic = userRepository.save(user("template-basic", basicRole));

        adminToken = jwtService.generateToken(admin.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));
        authorToken = jwtService.generateToken(author.getUsername(),
                List.of(new SimpleGrantedAuthority("content-templates:create"),
                        new SimpleGrantedAuthority("content-templates:edit"),
                        new SimpleGrantedAuthority("content-templates:delete")));
        basicToken = jwtService.generateToken(basic.getUsername(),
                List.of(new SimpleGrantedAuthority("vulnerabilities:read:all")));
    }

    private User user(String username, Role role) {
        return User.builder()
                .username(username)
                .firstName("Test").lastName("User")
                .email(username + "@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
    }

    @Test
    void createTemplate_persistsAllFieldsAndAuthor() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "Standard Methodology",
                "description", "Boilerplate testing methodology",
                "scope", "ASSESSMENT",
                "content", "<p>We performed a manual review.</p>",
                "enabled", true
        );

        mockMvc.perform(post("/api/v1/admin/content-templates")
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Standard Methodology"))
                .andExpect(jsonPath("$.data.scope").value("ASSESSMENT"))
                .andExpect(jsonPath("$.data.content").value("<p>We performed a manual review.</p>"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.createdBy").value("template-author"));

        assertThat(contentTemplateRepository.findAll()).hasSize(1);
    }

    @Test
    void createTemplate_requiresNameScopeAndContent() throws Exception {
        mockMvc.perform(post("/api/v1/admin/content-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "X"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminEndpoints_forbiddenWithoutTemplatePermissions() throws Exception {
        mockMvc.perform(get("/api/v1/admin/content-templates")
                        .header("Authorization", "Bearer " + basicToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/content-templates")
                        .header("Authorization", "Bearer " + basicToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "X", "scope", "ASSESSMENT", "content", "<p>x</p>"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTemplates_adminListIncludesDisabled() throws Exception {
        contentTemplateRepository.save(template("Enabled", ContentTemplateScope.ASSESSMENT, true));
        contentTemplateRepository.save(template("Disabled", ContentTemplateScope.ASSESSMENT, false));

        mockMvc.perform(get("/api/v1/admin/content-templates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void updateTemplate_appliesPartialChanges() throws Exception {
        ContentTemplate saved = contentTemplateRepository.save(
                template("Old Name", ContentTemplateScope.ASSESSMENT, true));

        mockMvc.perform(put("/api/v1/admin/content-templates/" + saved.getId())
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "New Name", "enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.content").value("<p>template body</p>"));
    }

    @Test
    void deleteTemplate_removesTemplate() throws Exception {
        ContentTemplate saved = contentTemplateRepository.save(
                template("Temp", ContentTemplateScope.ASSESSMENT, true));

        mockMvc.perform(delete("/api/v1/admin/content-templates/" + saved.getId())
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk());

        assertThat(contentTemplateRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    void editorList_returnsOnlyEnabledTemplatesForScope() throws Exception {
        contentTemplateRepository.save(template("Vuln Boilerplate", ContentTemplateScope.VULNERABILITY, true));
        contentTemplateRepository.save(template("Retired Boilerplate", ContentTemplateScope.VULNERABILITY, false));
        contentTemplateRepository.save(template("Assessment Boilerplate", ContentTemplateScope.ASSESSMENT, true));

        mockMvc.perform(get("/api/v1/content-templates?scope=VULNERABILITY")
                        .header("Authorization", "Bearer " + basicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Vuln Boilerplate"))
                .andExpect(jsonPath("$.data[0].content").value("<p>template body</p>"));
    }

    @Test
    void editorList_rejectsAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/v1/content-templates?scope=ASSESSMENT"))
                .andExpect(status().isForbidden());
    }

    private ContentTemplate template(String name, ContentTemplateScope scope, boolean enabled) {
        return ContentTemplate.builder()
                .name(name)
                .scope(scope)
                .content("<p>template body</p>")
                .enabled(enabled)
                .build();
    }
}
