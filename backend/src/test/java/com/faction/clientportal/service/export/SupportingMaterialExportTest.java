package com.faction.clientportal.service.export;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.InlineImage;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Screenshots pasted into a finding's rich text used to vanish from the export: reducing the field
 * to plain text drops the {@code <img>} tag with everything else, and CycloneDX has nowhere in a
 * text field to put an image anyway. They belong in {@code proofOfConcept.supportingMaterial}.
 *
 * <p>Uses the real storage service against the test container rather than a mock, because the part
 * worth proving is that the bytes make the whole round trip — editor HTML, to object storage, to
 * base64 in the document — and come back byte-identical.
 */
@SpringBootTest
@ActiveProfiles("test")
class SupportingMaterialExportTest extends TestContainersConfig {

    @Autowired private VulnerabilityExportService exportService;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private InlineImageRepository inlineImageRepository;
    @Autowired private StorageService storageService;
    @Autowired private ObjectMapper objectMapper;

    /** A one-pixel PNG — real bytes, small enough to assert on exactly. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private Assessment assessment;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        inlineImageRepository.deleteAll();
        assessmentRepository.deleteAll();

        assessment = assessmentRepository.save(Assessment.builder()
                .name("Evidence Test").applicationId("app-1").organizationId("org-1")
                .assessmentTypeId("t").status("COMPLETED")
                .createdAt(LocalDateTime.now()).build());

        auth = new UsernamePasswordAuthenticationToken("tester", null,
                List.of(new SimpleGrantedAuthority("super_admin")));

        // The export service is a singleton and one test below narrows this budget, so reset it
        // here rather than leaving the rest of the class dependent on execution order.
        ReflectionTestUtils.setField(exportService, "maxSupportingMaterialBytes", 26_214_400L);
    }

    @Test
    void aStoredScreenshotIsEmbeddedAsBase64() throws Exception {
        String imageId = storeImage(PNG, "image/png");
        saveFinding("Stored XSS", "<p>See below.</p><img src=\"/api/v1/inline-images/" + imageId + "\">", null);

        JsonNode material = supportingMaterialOf("Stored XSS");

        assertThat(material).hasSize(1);
        assertThat(material.get(0).path("contentType").asText()).isEqualTo("image/png");
        assertThat(material.get(0).path("encoding").asText()).isEqualTo("base64");
        // The bytes survive the round trip through storage intact.
        assertThat(Base64.getDecoder().decode(material.get(0).path("content").asText()))
                .isEqualTo(PNG);
    }

    @Test
    void anAttachmentCarriesOnlyTheThreeFieldsTheSchemaAllows() throws Exception {
        String imageId = storeImage(PNG, "image/png");
        saveFinding("Stored XSS", "<img src='/api/v1/inline-images/" + imageId + "'>", null);

        JsonNode attachment = supportingMaterialOf("Stored XSS").get(0);

        // attachment has additionalProperties:false, so a filename or caption here would make the
        // document fail validation however useful it would be.
        assertThat(attachment.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("contentType", "encoding", "content");
    }

    @Test
    void aPastedDataUriIsUnwrappedRatherThanFetched() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(PNG);
        saveFinding("Pasted evidence",
                "<p>Proof:</p><img src=\"data:image/png;base64," + base64 + "\" alt=\"screenshot\">", null);

        JsonNode material = supportingMaterialOf("Pasted evidence");

        assertThat(material).hasSize(1);
        assertThat(material.get(0).path("contentType").asText()).isEqualTo("image/png");
        assertThat(material.get(0).path("content").asText()).isEqualTo(base64);
    }

    @Test
    void screenshotsAreCollectedFromEveryRichTextFieldAndDeduplicated() throws Exception {
        String first = storeImage(PNG, "image/png");
        String second = storeImage("second".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        Vulnerability v = saveFinding("Multi",
                "<img src=\"/api/v1/inline-images/" + first + "\">",
                "<img src=\"/api/v1/inline-images/" + second + "\">");
        // The same screenshot referenced twice is one attachment, not two copies of the bytes.
        v.setRecommendation("<img src=\"/api/v1/inline-images/" + first + "\">");
        vulnerabilityRepository.save(v);

        JsonNode material = supportingMaterialOf("Multi");

        assertThat(material).hasSize(2);
        assertThat(material.get(0).path("contentType").asText()).isEqualTo("image/png");
        assertThat(material.get(1).path("contentType").asText()).isEqualTo("image/jpeg");
    }

    @Test
    void anImageHostedElsewhereIsLeftAlone() throws Exception {
        saveFinding("Remote", "<img src=\"https://evil.example.com/pixel.png\">", null);

        // Fetching arbitrary URLs while rendering an export would be a request-forgery primitive.
        assertThat(nodeFor("Remote").has("proofOfConcept")).isFalse();
    }

    @Test
    void aMissingImageDoesNotSinkTheExport() throws Exception {
        saveFinding("Dangling", "<p>Broken</p><img src=\"/api/v1/inline-images/nosuchimage\">", null);

        // Most of an export beats none of it — the finding is still there, just without evidence.
        JsonNode node = nodeFor("Dangling");
        assertThat(node.path("description").asText()).isEqualTo("Dangling");
        assertThat(node.has("proofOfConcept")).isFalse();
    }

    @Test
    void theTextFieldsKeepTheirWordsWhenAnImageIsPulledOut() throws Exception {
        String imageId = storeImage(PNG, "image/png");
        saveFinding("Readable",
                "<p>The login form reflects input.</p><img src=\"/api/v1/inline-images/" + imageId + "\">",
                "<p>Submit the payload.</p>");

        JsonNode node = nodeFor("Readable");

        assertThat(node.path("detail").asText()).isEqualTo("The login form reflects input.");
        assertThat(node.path("proofOfConcept").path("reproductionSteps").asText())
                .isEqualTo("Submit the payload.");
        assertThat(node.path("proofOfConcept").path("supportingMaterial")).hasSize(1);
    }

    @Test
    void theBudgetStopsRunawayEvidenceAndSaysSo() throws Exception {
        // A budget that fits the first screenshot and nothing after it.
        ReflectionTestUtils.setField(exportService, "maxSupportingMaterialBytes", (long) PNG.length);

        String first = storeImage(PNG, "image/png");
        String second = storeImage(PNG, "image/png");
        saveFinding("Heavy",
                "<img src=\"/api/v1/inline-images/" + first + "\">"
                        + "<img src=\"/api/v1/inline-images/" + second + "\">", null);

        JsonNode node = nodeFor("Heavy");

        assertThat(node.path("proofOfConcept").path("supportingMaterial")).hasSize(1);
        // Silently truncating evidence would be worse than the truncation itself.
        assertThat(propertyOf(node, "faction:vulnerability:omittedScreenshots")).isEqualTo("1");
    }

    @Test
    void sarifIsUnaffectedByAnyOfThis() throws Exception {
        String imageId = storeImage(PNG, "image/png");
        saveFinding("Stored XSS", "<p>Reflected.</p><img src=\"/api/v1/inline-images/" + imageId + "\">", null);

        JsonNode sarif = objectMapper.readTree(exportService.export(
                assessment.getId(), VulnerabilityExportService.Format.SARIF, auth).content());

        // No base64 leaks into the SARIF message — the img tag is simply gone, as before.
        String message = sarif.path("runs").get(0).path("results").get(0)
                .path("message").path("text").asText();
        assertThat(message).isEqualTo("Reflected.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JsonNode supportingMaterialOf(String name) throws Exception {
        return nodeFor(name).path("proofOfConcept").path("supportingMaterial");
    }

    private JsonNode nodeFor(String name) throws Exception {
        JsonNode bom = objectMapper.readTree(exportService.export(
                assessment.getId(), VulnerabilityExportService.Format.CYCLONEDX, auth).content());
        for (JsonNode v : bom.path("vulnerabilities")) {
            if (name.equals(v.path("description").asText())) return v;
        }
        throw new AssertionError("No vulnerability named " + name);
    }

    private static String propertyOf(JsonNode vulnerability, String name) {
        for (JsonNode p : vulnerability.path("properties")) {
            if (name.equals(p.path("name").asText())) return p.path("value").asText();
        }
        throw new AssertionError("No property " + name);
    }

    private String storeImage(byte[] bytes, String contentType) {
        String id = java.util.UUID.randomUUID().toString().replace("-", "");
        String key = "inline-images/" + assessment.getId() + "/" + id;
        storageService.uploadStream(key, new java.io.ByteArrayInputStream(bytes), bytes.length, contentType);
        inlineImageRepository.save(InlineImage.builder()
                .id(id).assessmentId(assessment.getId()).storageKey(key)
                .originalFileName("shot.png").contentType(contentType)
                .fileSize((long) bytes.length).uploadedBy("tester")
                .uploadedAt(LocalDateTime.now()).build());
        return id;
    }

    private Vulnerability saveFinding(String name, String description, String details) {
        return vulnerabilityRepository.save(Vulnerability.builder()
                .name(name).severity(VulnerabilitySeverity.HIGH)
                .assessmentId(assessment.getId()).order(0).status("Open")
                .description(description).details(details)
                .createdBy("tester").lastUpdatedBy("tester")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }
}
