package com.faction.clientportal.service.ai;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.UpdateAiAnonymizationConfigRequest;
import com.faction.clientportal.repository.AiAnonymizationConfigRepository;
import com.faction.clientportal.service.AiAnonymizationConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AnonymizationServiceTest extends TestContainersConfig {

    @Autowired private AnonymizationService anonymizationService;
    @Autowired private AiAnonymizationConfigService configService;
    @Autowired private AiAnonymizationConfigRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private void enable() {
        UpdateAiAnonymizationConfigRequest req = new UpdateAiAnonymizationConfigRequest();
        req.setEnabled(true);
        // No presidioUrl → built-in secret patterns only, no external dependency
        configService.updateConfig(req);
    }

    @Test
    void disabled_passesTextThroughUnchanged() {
        AnonymizationService.Session s = anonymizationService.newSession();
        String text = "AWS key AKIAIOSFODNN7EXAMPLE and password: hunter2secret";
        assertThat(anonymizationService.mask(text, s)).isEqualTo(text);
    }

    @Test
    void masksSecretsAndRestoresThem() {
        enable();
        AnonymizationService.Session s = anonymizationService.newSession();
        String text = "The endpoint accepted AKIAIOSFODNN7EXAMPLE as an access key.";

        String masked = anonymizationService.mask(text, s);
        assertThat(masked).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(masked).contains("[[SECRET_1]]");

        // The model would echo the placeholder; restore puts the real value back
        String modelOutput = "<p>The finding shows [[SECRET_1]] was exposed.</p>";
        assertThat(anonymizationService.restore(modelOutput, s))
                .isEqualTo("<p>The finding shows AKIAIOSFODNN7EXAMPLE was exposed.</p>");
    }

    @Test
    void masksCredentialAssignmentsAndEmails() {
        enable();
        AnonymizationService.Session s = anonymizationService.newSession();
        String masked = anonymizationService.mask(
                "Login with password=Sup3rS3cret! and contact admin@corp.example.com", s);

        assertThat(masked).doesNotContain("Sup3rS3cret!");
        assertThat(masked).doesNotContain("admin@corp.example.com");
        assertThat(masked).contains("[[SECRET_1]]");
        assertThat(masked).contains("[[EMAIL_1]]");
    }

    @Test
    void reusesSamePlaceholderForRepeatedValueAcrossCalls() {
        enable();
        AnonymizationService.Session s = anonymizationService.newSession();
        String key = "AKIAIOSFODNN7EXAMPLE";

        String first = anonymizationService.mask("Prompt references " + key, s);
        String second = anonymizationService.mask("Tool result also has " + key, s);

        // Same value → same placeholder in both the prompt and the tool result
        assertThat(first).contains("[[SECRET_1]]");
        assertThat(second).contains("[[SECRET_1]]");
    }

    @Test
    void doesNotMaskOrdinaryTextOrUuids() {
        enable();
        AnonymizationService.Session s = anonymizationService.newSession();
        String text = "The vulnerability id is 3f2504e0-4f89-41d3-9a0c-0305e82c3301 in the login form.";
        assertThat(anonymizationService.mask(text, s)).isEqualTo(text);
    }

    @Test
    void failsClosedWhenPresidioConfiguredButUnreachable() {
        UpdateAiAnonymizationConfigRequest req = new UpdateAiAnonymizationConfigRequest();
        req.setEnabled(true);
        req.setPresidioUrl("http://127.0.0.1:1"); // nothing listening
        configService.updateConfig(req);

        AnonymizationService.Session s = anonymizationService.newSession();
        org.junit.jupiter.api.Assertions.assertThrows(
                AnonymizationService.AnonymizationUnavailableException.class,
                () -> anonymizationService.mask("password=secret123", s));
    }
}
