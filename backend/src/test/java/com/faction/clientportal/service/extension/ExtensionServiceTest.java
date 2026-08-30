package com.faction.clientportal.service.extension;

import com.faction.clientportal.dto.ExtensionDto;
import com.faction.clientportal.edition.UnrestrictedEditionPolicy;
import com.faction.clientportal.dto.UpdateExtensionConfigRequest;
import com.faction.clientportal.dto.UpdateExtensionRequest;
import com.faction.clientportal.model.Extension;
import com.faction.clientportal.repository.ExtensionLogRepository;
import com.faction.clientportal.repository.ExtensionRepository;
import com.faction.clientportal.service.EncryptionService;
import com.faction.clientportal.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExtensionService}: the install / configure / upgrade / uninstall lifecycle.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExtensionServiceTest {

    private static final String CONFIG_JSON = """
            {
              "Jira Host":    { "type": "text",     "value": "https://yourhost.com" },
              "Jira API Key": { "type": "password", "value": "" }
            }
            """;

    @Mock private ExtensionRepository extensionRepository;
    @Mock private ExtensionLogRepository extensionLogRepository;
    @Mock private StorageService storageService;
    @Mock private EncryptionService encryptionService;
    @Mock private ExtensionRegistry registry;
    @Mock private ExtensionClassLoaderFactory classLoaderFactory;

    private ExtensionService service;
    private ExtensionConfigCodec configCodec;

    @BeforeEach
    void setUp() {
        // Encryption is a pass-through so assertions can read the stored document;
        // that it is encrypted at all is asserted separately below.
        when(encryptionService.encrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        when(encryptionService.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        when(extensionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(extensionRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc()).thenReturn(List.of());
        doNothing().when(registry).reload();

        configCodec = new ExtensionConfigCodec(encryptionService);
        service = new ExtensionService(extensionRepository,
                new com.faction.clientportal.edition.UnrestrictedEditionPolicy(),
                org.mockito.Mockito.mock(com.faction.clientportal.edition.QuotaUsageService.class),
                extensionLogRepository,
                new ExtensionJarParser(), configCodec, classLoaderFactory, registry, storageService);
    }

    // ── Install ──────────────────────────────────────────────────────────────

    @Test
    void installStoresTheJarAndSeedsHooksAndConfigFromIt() throws IOException {
        ExtensionDto dto = service.install(jiraJar(), "admin");

        assertThat(dto.getName()).isEqualTo("Faction Jira Extension");
        assertThat(dto.getVersion()).isEqualTo("1.0");
        assertThat(dto.getProvidesAssessment()).isTrue();
        assertThat(dto.getProvidesReport()).isFalse();
        // A declared hook defaults on, so enabling the extension is the only switch to find.
        assertThat(dto.getAssessmentEnabled()).isTrue();
        // ...but the extension itself is installed off: it runs code in Faction's JVM.
        assertThat(dto.getEnabled()).isFalse();

        assertThat(dto.getConfig()).containsKeys("Jira Host", "Jira API Key");
        verify(storageService).uploadBytes(anyString(), any(), anyString());
        verify(registry).reload();
    }

    @Test
    void configIsEncryptedAtRest() throws IOException {
        EncryptionService realEncryption = mock(EncryptionService.class);
        when(realEncryption.encrypt(anyString())).thenReturn("ENCRYPTED-BLOB");
        when(realEncryption.decrypt("ENCRYPTED-BLOB")).thenReturn(CONFIG_JSON);

        ExtensionConfigCodec codec = new ExtensionConfigCodec(realEncryption);
        ExtensionService encrypting = new ExtensionService(extensionRepository,
                new com.faction.clientportal.edition.UnrestrictedEditionPolicy(),
                org.mockito.Mockito.mock(com.faction.clientportal.edition.QuotaUsageService.class),
                extensionLogRepository,
                new ExtensionJarParser(), codec, classLoaderFactory, registry, storageService);

        encrypting.install(jiraJar(), "admin");

        // Config routinely holds live API credentials, so it must never be plaintext.
        verify(extensionRepository).save(org.mockito.ArgumentMatchers.argThat(
                saved -> "ENCRYPTED-BLOB".equals(saved.getEncryptedConfigs())));
    }

    @Test
    void reinstallingAnIdenticalJarIsRejected() throws IOException {
        byte[] jar = jiraJar();
        Extension already = Extension.builder().id("x").name("Faction Jira Extension")
                .version("1.0").build();
        when(extensionRepository.findByHashAndDeletedAtIsNull(anyString()))
                .thenReturn(Optional.of(already));

        assertThatThrownBy(() -> service.install(jar, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already installed");
    }

    // ── Configure ────────────────────────────────────────────────────────────

    @Test
    void configValuesAreSavedAndPasswordsComeBackMasked() throws IOException {
        Extension extension = installedJiraExtension();

        ExtensionDto dto = service.updateConfig(extension.getId(),
                new UpdateExtensionConfigRequest(Map.of(
                        "Jira Host", "https://faction.atlassian.net",
                        "Jira API Key", "super-secret")),
                "admin");

        assertThat(dto.getConfig().get("Jira Host").get("value"))
                .isEqualTo("https://faction.atlassian.net");
        // The UI must never receive a live credential back.
        assertThat(dto.getConfig().get("Jira API Key").get("value"))
                .isEqualTo(ExtensionConfigCodec.MASK);
        assertThat(configCodec.toMap(extension)).containsEntry("Jira API Key", "super-secret");
        verify(registry, org.mockito.Mockito.atLeastOnce()).reload();
    }

    @Test
    void echoingTheMaskBackLeavesTheStoredSecretAlone() throws IOException {
        Extension extension = installedJiraExtension();
        service.updateConfig(extension.getId(),
                new UpdateExtensionConfigRequest(Map.of("Jira API Key", "super-secret")), "admin");

        // The UI only ever holds the mask, so saving an unrelated field must not
        // overwrite the credential with the mask itself.
        service.updateConfig(extension.getId(),
                new UpdateExtensionConfigRequest(Map.of(
                        "Jira Host", "https://changed.example.com",
                        "Jira API Key", ExtensionConfigCodec.MASK)),
                "admin");

        assertThat(configCodec.toMap(extension)).containsEntry("Jira API Key", "super-secret");
        assertThat(configCodec.toMap(extension))
                .containsEntry("Jira Host", "https://changed.example.com");
    }

    @Test
    void undeclaredConfigKeysAreRejected() throws IOException {
        Extension extension = installedJiraExtension();
        UpdateExtensionConfigRequest request =
                new UpdateExtensionConfigRequest(Map.of("Made Up Key", "value"));

        // Storing it would show the operator a setting the extension never reads.
        assertThatThrownBy(() -> service.updateConfig(extension.getId(), request, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a config key declared");
    }

    @Test
    void aHookTheJarDoesNotImplementCannotBeSwitchedOn() throws IOException {
        Extension extension = installedJiraExtension();
        UpdateExtensionRequest request = UpdateExtensionRequest.builder().reportEnabled(true).build();

        assertThatThrownBy(() -> service.update(extension.getId(), request, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement the Report hook");
    }

    @Test
    void togglesAndOrderingAreApplied() throws IOException {
        Extension extension = installedJiraExtension();

        ExtensionDto dto = service.update(extension.getId(), UpdateExtensionRequest.builder()
                .enabled(true).assessmentEnabled(false).assessmentOrder(3).build(), "admin");

        assertThat(dto.getEnabled()).isTrue();
        assertThat(dto.getAssessmentEnabled()).isFalse();
        assertThat(dto.getAssessmentOrder()).isEqualTo(3);
        verify(registry, org.mockito.Mockito.atLeastOnce()).reload();
    }

    // ── Upgrade ──────────────────────────────────────────────────────────────

    @Test
    void upgradePreservesConfiguredValuesAcrossAVersionBump() throws IOException {
        Extension extension = installedJiraExtension();
        service.updateConfig(extension.getId(),
                new UpdateExtensionConfigRequest(Map.of("Jira API Key", "super-secret")), "admin");
        String previousHash = extension.getHash();

        byte[] v2 = new ExtensionJarFixture()
                .manifest("Faction Jira Extension", "Josh Summitt", "2.0", null)
                .configJson("""
                        {
                          "Jira Host":    { "type": "text",     "value": "https://yourhost.com" },
                          "Jira API Key": { "type": "password", "value": "" },
                          "Jira Project": { "type": "text",     "value": "KAN" }
                        }
                        """)
                .service("AssessmentManager", "org.faction.JiraPlugin")
                .build();

        ExtensionDto dto = service.upgrade(extension.getId(), v2, "admin");

        assertThat(dto.getVersion()).isEqualTo("2.0");
        // Bumping a version must not make an operator re-enter their credentials...
        assertThat(configCodec.toMap(extension)).containsEntry("Jira API Key", "super-secret");
        // ...while a newly declared key still arrives with its default.
        assertThat(configCodec.toMap(extension)).containsEntry("Jira Project", "KAN");
        // The old JAR's loader and object are released.
        verify(classLoaderFactory).evict(previousHash);
        verify(storageService).deleteObject(anyString());
    }

    @Test
    void upgradeSwitchesOffAHookTheNewJarDropped() throws IOException {
        Extension extension = installedJiraExtension();
        service.update(extension.getId(),
                UpdateExtensionRequest.builder().enabled(true).assessmentEnabled(true).build(), "admin");

        byte[] reportOnly = new ExtensionJarFixture()
                .manifest("Faction Jira Extension", "Josh Summitt", "3.0", null)
                .service("ReportManager", "org.faction.JiraReport")
                .build();

        ExtensionDto dto = service.upgrade(extension.getId(), reportOnly, "admin");

        assertThat(dto.getProvidesAssessment()).isFalse();
        assertThat(dto.getAssessmentEnabled()).isFalse();
        assertThat(dto.getProvidesReport()).isTrue();
    }

    // ── Uninstall ────────────────────────────────────────────────────────────

    @Test
    void uninstallSoftDeletesAndReleasesEverything() throws IOException {
        Extension extension = installedJiraExtension();

        service.uninstall(extension.getId(), "admin");

        assertThat(extension.getDeletedAt()).isNotNull();
        assertThat(extension.getEnabled()).isFalse();
        verify(classLoaderFactory).evict(extension.getHash());
        verify(storageService).deleteObject(extension.getJarFileId());
        verify(extensionLogRepository).deleteByExtensionId(extension.getId());
        verify(registry, org.mockito.Mockito.atLeastOnce()).reload();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private byte[] jiraJar() throws IOException {
        return new ExtensionJarFixture()
                .manifest("Faction Jira Extension", "Josh Summitt", "1.0",
                          "https://www.factionsecurity.com")
                .description("Sends findings to Jira when an assessment is finalized.")
                .configJson(CONFIG_JSON)
                .service("AssessmentManager", "org.faction.JiraPlugin")
                .build();
    }

    /** Installs the Jira fixture and wires the repository to return the saved row. */
    private Extension installedJiraExtension() throws IOException {
        ExtensionDto dto = service.install(jiraJar(), "admin");
        Extension saved = captureSaved();
        saved.setId(dto.getId() == null ? "ext-1" : dto.getId());
        when(extensionRepository.findByIdAndDeletedAtIsNull(saved.getId()))
                .thenReturn(Optional.of(saved));
        return saved;
    }

    private Extension captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(Extension.class);
        verify(extensionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
