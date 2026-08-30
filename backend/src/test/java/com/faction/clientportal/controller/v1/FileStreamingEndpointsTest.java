package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.InlineImage;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.StorageService;
import com.faction.clientportal.util.StoredObjects;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the streaming file path that replaced presigned URLs:
 * bodies flow through the application, and the endpoints that a browser reaches
 * from {@code <img>}/{@code <a href>} are authenticated rather than public.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileStreamingEndpointsTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private InlineImageRepository inlineImageRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @MockBean private StorageService storageService;

    private String jwtToken;
    private Assessment assessment;

    @BeforeEach
    void setUp() {
        inlineImageRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        // buildKey lives on the mocked bean, so keep it producing real keys.
        when(storageService.buildKey(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "/" + inv.getArgument(1)
                        + "/" + inv.getArgument(2));

        Role role = roleRepository.save(Role.builder()
                .name("Pentester")
                .permissions(List.of("assessments:read:all", "assessments:edit:all"))
                .build());

        User user = userRepository.save(User.builder()
                .username("stream-user")
                .firstName("Stream").lastName("Tester")
                .email("stream@test.com")
                .password(passwordEncoder.encode("password1"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build());

        jwtToken = jwtService.generateToken(user.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:read:all"),
                        new SimpleGrantedAuthority("assessments:edit:all")));

        assessment = assessmentRepository.save(Assessment.builder()
                .name("Streaming Assessment")
                .status("IN_PROGRESS")
                .createdAt(LocalDateTime.now())
                .build());
    }

    // ── Assessment attachments ───────────────────────────────────────────────

    @Test
    @DisplayName("prepare returns a backend upload target, never a storage URL")
    void prepareReturnsBackendTarget() throws Exception {
        mockMvc.perform(post("/api/v1/assessments/{id}/files/prepare", assessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fileName", "evidence.pdf",
                                "contentType", "application/pdf",
                                "fileSize", 512))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileId").isNotEmpty())
                .andExpect(jsonPath("$.data.uploadUrl").value(
                        org.hamcrest.Matchers.startsWith("/api/v1/assessments/" + assessment.getId())))
                .andExpect(jsonPath("$.data.uploadUrl").value(
                        org.hamcrest.Matchers.endsWith("/content")));
    }

    @Test
    @DisplayName("an uploaded body is streamed into storage with its exact length")
    void uploadStreamsBodyIntoStorage() throws Exception {
        String fileId = prepareUpload("evidence.pdf");
        byte[] body = "pdf-file-body".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(put("/api/v1/assessments/{id}/files/{fileId}/content",
                        assessment.getId(), fileId)
                        .param("fileName", "evidence.pdf")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<Long> length = ArgumentCaptor.forClass(Long.class);
        verify(storageService).uploadStream(
                org.mockito.ArgumentMatchers.contains("evidence.pdf"),
                org.mockito.ArgumentMatchers.any(),
                length.capture(),
                org.mockito.ArgumentMatchers.eq(MediaType.APPLICATION_PDF_VALUE));
        assertThat(length.getValue()).isEqualTo(body.length);
    }

    @Test
    @DisplayName("a download streams the bytes back as an attachment")
    void downloadStreamsAttachment() throws Exception {
        String fileId = prepareUpload("evidence.pdf");
        confirmUpload(fileId, "evidence.pdf");
        when(storageService.openStream(anyString()))
                .thenReturn(StoredObjects.of("pdf-file-body"));

        mockMvc.perform(get("/api/v1/assessments/{id}/files/{fileId}/content",
                        assessment.getId(), fileId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(content().string("pdf-file-body"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("evidence.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("an anonymous caller cannot download an attachment")
    void downloadRequiresAuthentication() throws Exception {
        String fileId = prepareUpload("evidence.pdf");
        confirmUpload(fileId, "evidence.pdf");

        mockMvc.perform(get("/api/v1/assessments/{id}/files/{fileId}/content",
                        assessment.getId(), fileId))
                .andExpect(status().isForbidden());
    }

    // ── Inline images (screenshots of findings) ──────────────────────────────

    @Test
    @DisplayName("inline images are no longer public — an unguessable id is not enough")
    void inlineImageRejectsAnonymousCallers() throws Exception {
        saveInlineImage("img-1");

        mockMvc.perform(get("/api/v1/inline-images/{id}", "img-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("inline images stream to an authenticated caller")
    void inlineImageStreamsForAuthenticatedCaller() throws Exception {
        saveInlineImage("img-1");
        when(storageService.openStream(anyString()))
                .thenReturn(StoredObjects.of("png-bytes".getBytes(StandardCharsets.UTF_8), "image/png"));

        mockMvc.perform(get("/api/v1/inline-images/{id}", "img-1")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(content().string("png-bytes"))
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    @DisplayName("an <img> tag authenticates with the media cookie instead of a header")
    void inlineImageAcceptsMediaCookie() throws Exception {
        saveInlineImage("img-1");
        when(storageService.openStream(anyString()))
                .thenReturn(StoredObjects.of("png-bytes".getBytes(StandardCharsets.UTF_8), "image/png"));

        mockMvc.perform(get("/api/v1/inline-images/{id}", "img-1")
                        .cookie(new Cookie("media_access", jwtToken)))
                .andExpect(status().isOk())
                .andExpect(content().string("png-bytes"));
    }

    @Test
    @DisplayName("a header-authenticated session with no cookie yet is issued one")
    void mediaCookieIsToppedUpForExistingSessions() throws Exception {
        // Sessions that predate the cookie would otherwise 403 on every image
        // until the user signed in again.
        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("media_access="),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("SameSite=Strict"))));
    }

    @Test
    @DisplayName("a request that already carries the cookie is not re-issued one")
    void mediaCookieNotReissuedWhenPresent() throws Exception {
        mockMvc.perform(get("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .cookie(new Cookie("media_access", jwtToken)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("the media cookie is rejected on endpoints outside the media allowlist")
    void mediaCookieDoesNotAuthenticateOrdinaryEndpoints() throws Exception {
        // The cookie's safety rests on being useless anywhere but media reads.
        mockMvc.perform(get("/api/v1/assessments")
                        .cookie(new Cookie("media_access", jwtToken)))
                .andExpect(status().isForbidden());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private String prepareUpload(String fileName) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/assessments/{id}/files/prepare", assessment.getId())
                                .header("Authorization", "Bearer " + jwtToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "fileName", fileName,
                                        "contentType", "application/pdf",
                                        "fileSize", 512))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("fileId").asText();
    }

    private void confirmUpload(String fileId, String fileName) throws Exception {
        mockMvc.perform(post("/api/v1/assessments/{id}/files", assessment.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fileId", fileId,
                                "fileName", fileName,
                                "contentType", "application/pdf",
                                "fileSize", 512))))
                .andExpect(status().isOk());
    }

    private void saveInlineImage(String imageId) {
        inlineImageRepository.save(InlineImage.builder()
                .id(imageId)
                .assessmentId(assessment.getId())
                .storageKey("inline-images/" + assessment.getId() + "/" + imageId + "/shot.png")
                .originalFileName("shot.png")
                .contentType("image/png")
                .fileSize(9L)
                .uploadedBy("stream-user")
                .uploadedAt(LocalDateTime.now())
                .build());
    }
}
