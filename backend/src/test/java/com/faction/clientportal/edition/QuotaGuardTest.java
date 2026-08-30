package com.faction.clientportal.edition;

import com.faction.clientportal.dto.SaveAiPromptTemplateRequest;
import com.faction.clientportal.dto.SaveAiProviderConfigRequest;
import com.faction.clientportal.model.AiPromptScope;
import com.faction.clientportal.model.AiProviderType;
import com.faction.clientportal.repository.AiPromptTemplateRepository;
import com.faction.clientportal.repository.AiProviderConfigRepository;
import com.faction.clientportal.repository.ExtensionRepository;
import com.faction.clientportal.service.AiPromptTemplateService;
import com.faction.clientportal.service.AiProviderConfigService;
import com.faction.clientportal.service.extension.ExtensionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Each capped resource refuses the write that would cross its limit.
 *
 * <p>Asserts two things per quota: that the guard is consulted at all, and that it runs
 * <em>before</em> the repository write. The second is what actually matters — a guard
 * placed after the save still throws, still returns 402, and still leaves the extra row
 * behind, so the cap would leak one every time somebody retried.
 *
 * <p>The limit values themselves live in {@link EditionPolicyTest}; here the policy is a
 * mock, so these stay true whatever the numbers become.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaGuardTest {

    @Mock private EditionPolicy editionPolicy;
    @Mock private QuotaUsageService quotaUsageService;

    private void atCapacity(Quota quota, long current, int limit) {
        when(quotaUsageService.current(quota)).thenReturn(current);
        doThrow(new QuotaExceededException(quota, limit))
                .when(editionPolicy).requireHeadroom(quota, current);
    }

    // ── AI providers ─────────────────────────────────────────────────────────

    @Mock private AiProviderConfigRepository aiProviderConfigRepository;
    @Mock private com.faction.clientportal.service.EncryptionService encryptionService;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @InjectMocks private AiProviderConfigService aiProviderConfigService;

    @Test
    void refusesASecondAiProvider() {
        atCapacity(Quota.AI_PROVIDERS, 1L, 1);

        SaveAiProviderConfigRequest request = new SaveAiProviderConfigRequest();
        request.setName("Second");
        request.setProviderType(AiProviderType.OPENAI);

        assertThatThrownBy(() -> aiProviderConfigService.createProvider(request))
                .isInstanceOf(QuotaExceededException.class);

        verify(aiProviderConfigRepository, never()).save(any());
    }

    // ── AI prompts ───────────────────────────────────────────────────────────

    @Mock private AiPromptTemplateRepository aiPromptTemplateRepository;
    @InjectMocks private AiPromptTemplateService aiPromptTemplateService;

    /**
     * The guard sits ahead of the field validation, so an operator at the cap is told
     * about the cap rather than being sent to fix a prompt they cannot save anyway.
     */
    @Test
    void refusesAFifthPromptBeforeValidatingIt() {
        atCapacity(Quota.AI_PROMPTS, 4L, 4);

        SaveAiPromptTemplateRequest request = new SaveAiPromptTemplateRequest();
        request.setName("Fifth");
        request.setScope(AiPromptScope.VULNERABILITY);
        request.setPrompt("Summarise this finding.");

        assertThatThrownBy(() -> aiPromptTemplateService.createPrompt(request))
                .isInstanceOf(QuotaExceededException.class);

        verify(aiPromptTemplateRepository, never()).save(any());
    }

    // ── Extensions ───────────────────────────────────────────────────────────

    @Mock private ExtensionRepository extensionRepository;
    @Mock private com.faction.clientportal.repository.ExtensionLogRepository extensionLogRepository;
    @Mock private com.faction.clientportal.service.extension.ExtensionJarParser jarParser;
    @Mock private com.faction.clientportal.service.extension.ExtensionConfigCodec configCodec;
    @Mock private com.faction.clientportal.service.extension.ExtensionClassLoaderFactory classLoaderFactory;
    @Mock private com.faction.clientportal.service.extension.ExtensionRegistry registry;
    @Mock private com.faction.clientportal.service.StorageService storageService;
    @InjectMocks private ExtensionService extensionService;

    /**
     * The guard runs before the JAR is even parsed, so a third install is refused without
     * the bytes ever being unpacked or uploaded to object storage.
     */
    @Test
    void refusesAThirdExtensionWithoutParsingTheJar() {
        atCapacity(Quota.EXTENSIONS, 2L, 2);

        assertThatThrownBy(() -> extensionService.install(new byte[]{1, 2, 3}, "user-1"))
                .isInstanceOf(QuotaExceededException.class);

        verify(jarParser, never()).parse(any());
        verify(storageService, never()).uploadBytes(any(), any(), any());
        verify(extensionRepository, never()).save(any());
    }
}
