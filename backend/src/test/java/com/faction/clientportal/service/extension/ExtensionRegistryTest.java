package com.faction.clientportal.service.extension;

import com.faction.clientportal.model.Extension;
import com.faction.clientportal.repository.ExtensionRepository;
import com.faction.clientportal.service.EncryptionService;
import com.faction.clientportal.service.StorageService;
import com.faction.extender.ReportManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link ExtensionRegistry}: loads real JARs through a real {@link java.net.URLClassLoader}
 * and discovers their hooks with {@link java.util.ServiceLoader}.
 *
 * <p>Uses no mock for the loading path on purpose: service discovery, config
 * injection and resource lookup all run against a JAR that is really built, really
 * staged to disk and really opened.
 *
 * <p>One honest limitation. The stub implementations live on the test classpath, so
 * although their {@code META-INF/services} entries are read from the JAR, the classes
 * themselves resolve through the parent loader. That leaves the interface-identity
 * hazard — a {@code jar-with-dependencies} shipping its own copy of
 * {@code com.faction.extender}, so the loaded class implements a <em>different</em>
 * interface than Faction holds and ServiceLoader silently yields nothing — covered
 * only by the parent-first delegation in
 * {@link ExtensionClassLoaderFactory}, not by an assertion here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExtensionRegistryTest {

    @Mock private ExtensionRepository extensionRepository;
    @Mock private StorageService storageService;
    @Mock private EncryptionService encryptionService;

    private ExtensionRegistry registry;
    private ExtensionConfigCodec configCodec;
    private ExtensionClassLoaderFactory classLoaderFactory;

    private byte[] stubJar;

    @BeforeEach
    void setUp() throws IOException {
        // Encryption is exercised in ExtensionServiceTest; here it is a pass-through
        // so the test stays about discovery.
        when(encryptionService.encrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        when(encryptionService.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));

        configCodec = new ExtensionConfigCodec(encryptionService);
        classLoaderFactory = new ExtensionClassLoaderFactory(storageService);
        registry = new ExtensionRegistry(extensionRepository, classLoaderFactory, configCodec);

        stubJar = new ExtensionJarFixture()
                .manifest("Stub Report Extension", "Tester", "1.0", null)
                .service("ReportManager", StubReportManager.class.getName())
                .classFile(StubReportManager.class)
                .build();
    }

    @Test
    void discoversAHookFromARealJarAndHandsItItsConfigs() {
        Extension extension = installed(true, true);
        configCodec.write(extension, configCodec.parseDeclared(
                "{\"Replacement\": {\"type\": \"text\", \"value\": \"SUBSTITUTED\"}}"));
        stub(extension);

        registry.reload();

        assertThat(registry.isExtended(ExtensionRegistry.EventType.REPORT_MANAGER)).isTrue();
        List<ExtensionRegistry.LoadedExtension<ReportManager>> loaded =
                registry.get(ExtensionRegistry.EventType.REPORT_MANAGER);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getExtensionName()).isEqualTo("Stub Report Extension");

        // The config declared in the JAR reached the instance.
        String result = loaded.get(0).getInstance()
                .reportCreate(null, List.of(), "before " + StubReportManager.TOKEN + " after");
        assertThat(result).isEqualTo("before SUBSTITUTED after");
    }

    @Test
    void theMasterSwitchSuppressesEveryHook() {
        Extension extension = installed(false, true);
        stub(extension);

        registry.reload();

        assertThat(registry.isExtended(ExtensionRegistry.EventType.REPORT_MANAGER)).isFalse();
        assertThat(registry.get(ExtensionRegistry.EventType.REPORT_MANAGER)).isEmpty();
    }

    @Test
    void aDisabledHookIsNotLoadedEvenWhenTheExtensionIsEnabled() {
        Extension extension = installed(true, false);
        stub(extension);

        registry.reload();

        assertThat(registry.isExtended(ExtensionRegistry.EventType.REPORT_MANAGER)).isFalse();
    }

    @Test
    void hooksTheJarDoesNotDeclareStayEmpty() {
        Extension extension = installed(true, true);
        stub(extension);

        registry.reload();

        assertThat(registry.isExtended(ExtensionRegistry.EventType.ASMT_MANAGER)).isFalse();
        assertThat(registry.isExtended(ExtensionRegistry.EventType.VULN_MANAGER)).isFalse();
        assertThat(registry.isExtended(ExtensionRegistry.EventType.VER_MANAGER)).isFalse();
        assertThat(registry.isExtended(ExtensionRegistry.EventType.INVENTORY)).isFalse();
    }

    @Test
    void hookOrderFollowsThatHooksOwnOrderColumn() {
        Extension first = installed(true, true);
        first.setName("Runs second");
        first.setReportOrder(10);
        Extension second = installed(true, true);
        second.setName("Runs first");
        second.setReportOrder(1);

        when(extensionRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc())
                .thenReturn(List.of(first, second));
        when(storageService.downloadBytes(anyString())).thenReturn(stubJar);

        registry.reload();

        assertThat(registry.get(ExtensionRegistry.EventType.REPORT_MANAGER))
                .extracting(ExtensionRegistry.LoadedExtension::getExtensionName)
                .containsExactly("Runs first", "Runs second");
    }

    @Test
    void aBrokenJarIsSkippedRatherThanFailingTheReload() {
        Extension broken = installed(true, true);
        broken.setName("Broken");
        Extension healthy = installed(true, true);
        healthy.setName("Healthy");
        // Distinct hashes, or the healthy JAR would hit the broken one's cached loader.
        broken.setHash("broken-" + UUID.randomUUID());

        when(extensionRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc())
                .thenReturn(List.of(broken, healthy));
        when(storageService.downloadBytes(broken.getJarFileId()))
                .thenThrow(new IllegalStateException("object missing from storage"));
        when(storageService.downloadBytes(healthy.getJarFileId())).thenReturn(stubJar);

        registry.reload();

        // One bad extension must not cost the operator every other integration.
        assertThat(registry.get(ExtensionRegistry.EventType.REPORT_MANAGER))
                .extracting(ExtensionRegistry.LoadedExtension::getExtensionName)
                .containsExactly("Healthy");
    }

    @Test
    void startupNeverPropagatesAnExtensionFailure() {
        when(extensionRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc())
                .thenThrow(new IllegalStateException("database unavailable"));

        // Faction has to boot even when the extension table cannot be read.
        registry.loadOnStartup();

        assertThat(registry.isExtended(ExtensionRegistry.EventType.REPORT_MANAGER)).isFalse();
    }

    @Test
    void aStagedJarExposesItsOwnResourcesThroughTheLoader() throws Exception {
        // The checklist extension renders through a Handlebars template it ships at
        // META-INF/resources/checklist-table.hbs, loaded with
        // getClass().getClassLoader().getResourceAsStream(...). Staging the JAR to a
        // real file is what makes that work: Faction 1 served extension classes from
        // an in-memory byte map behind a custom x-buffer: URL protocol, which resolves
        // class loads but not arbitrary resource paths.
        String template = "<table>{{#each this}}<tr><td>{{question}}</td></tr>{{/each}}</table>";
        byte[] jar = new ExtensionJarFixture()
                .manifest("Checklist Renderer", "Tester", "1.0", null)
                .service("ReportManager", "com.example.Checklist")
                .entry("META-INF/resources/checklist-table.hbs",
                       template.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();

        Extension extension = installed(true, true);
        when(storageService.downloadBytes(extension.getJarFileId())).thenReturn(jar);

        java.net.URLClassLoader loader = classLoaderFactory.loaderFor(extension);
        try (java.io.InputStream in =
                     loader.getResourceAsStream("META-INF/resources/checklist-table.hbs")) {
            assertThat(in).isNotNull();
            assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo(template);
        }
    }

    @Test
    void reloadReplacesThePreviousSetRatherThanAccumulating() {
        Extension extension = installed(true, true);
        stub(extension);

        registry.reload();
        registry.reload();

        assertThat(registry.get(ExtensionRegistry.EventType.REPORT_MANAGER)).hasSize(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Extension installed(boolean enabled, boolean reportHookEnabled) {
        return Extension.builder()
                .id(UUID.randomUUID().toString())
                .name("Stub Report Extension")
                .version("1.0")
                .jarFileId("extensions/" + UUID.randomUUID() + ".jar")
                .hash(UUID.randomUUID().toString())
                .enabled(enabled)
                .displayOrder(0)
                .providesReport(true)
                .reportEnabled(reportHookEnabled)
                .reportOrder(0)
                .build();
    }

    private void stub(Extension extension) {
        when(extensionRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc())
                .thenReturn(List.of(extension));
        when(storageService.downloadBytes(extension.getJarFileId())).thenReturn(stubJar);
    }
}
