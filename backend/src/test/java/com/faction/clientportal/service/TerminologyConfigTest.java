package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.TerminologyConfig;
import com.faction.clientportal.repository.TerminologyConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TerminologyConfigTest extends TestContainersConfig {

    @Autowired private TerminologyConfigService service;
    @Autowired private TerminologyConfigRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void anInstallationThatNeverTouchesThisReadsExactlyAsBefore() {
        TerminologyConfig defaults = service.getConfig();

        assertThat(defaults.getOrganizationSingular()).isEqualTo("Organization");
        assertThat(defaults.getOrganizationPlural()).isEqualTo("Organizations");
        assertThat(defaults.getSubOrganizationSingular()).isEqualTo("Sub-organization");
        assertThat(defaults.getSubOrganizationPlural()).isEqualTo("Sub-organizations");
    }

    @Test
    void labelsCanBeRenamed() {
        service.updateConfig(TerminologyConfig.builder()
                .organizationSingular("Value Stream").organizationPlural("Value Streams")
                .subOrganizationSingular("Sub-value Stream").subOrganizationPlural("Sub-value Streams")
                .build());

        TerminologyConfig saved = service.getConfig();
        assertThat(saved.getOrganizationPlural()).isEqualTo("Value Streams");
        assertThat(saved.getSubOrganizationSingular()).isEqualTo("Sub-value Stream");
    }

    @Test
    void pluralIsStoredNotDerived() {
        // "Entity" + "s" is "Entitys". Deriving the plural is right often enough to be tempting
        // and wrong often enough to look careless, so the admin sets both.
        service.updateConfig(TerminologyConfig.builder()
                .organizationSingular("Entity").organizationPlural("Entities").build());

        assertThat(service.getConfig().getOrganizationPlural()).isEqualTo("Entities");
    }

    @Test
    void aBlankLabelLeavesTheExistingOneAlone() {
        service.updateConfig(TerminologyConfig.builder()
                .organizationSingular("Value Stream").organizationPlural("Value Streams").build());

        // A partial update, or a form that submitted an empty box: a screen with a gap where a
        // noun should be is worse than one using the old word.
        service.updateConfig(TerminologyConfig.builder()
                .organizationSingular("  ").organizationPlural(null)
                .subOrganizationSingular("Team").subOrganizationPlural("Teams").build());

        TerminologyConfig after = service.getConfig();
        assertThat(after.getOrganizationSingular()).isEqualTo("Value Stream");
        assertThat(after.getOrganizationPlural()).isEqualTo("Value Streams");
        assertThat(after.getSubOrganizationSingular()).isEqualTo("Team");
    }

    @Test
    void labelsAreTrimmed() {
        // Invisible in the settings field, glaring in a heading built by concatenation.
        service.updateConfig(TerminologyConfig.builder()
                .organizationSingular("  Value Stream  ").build());

        assertThat(service.getConfig().getOrganizationSingular()).isEqualTo("Value Stream");
    }

    @Test
    void anAbsurdlyLongLabelIsRefused() {
        assertThatThrownBy(() -> service.updateConfig(TerminologyConfig.builder()
                .organizationSingular("x".repeat(101)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100 characters");
    }

    @Test
    void thereIsOnlyEverOneRow() {
        service.updateConfig(TerminologyConfig.builder().organizationSingular("A").build());
        service.updateConfig(TerminologyConfig.builder().organizationSingular("B").build());

        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.getConfig().getOrganizationSingular()).isEqualTo("B");
    }
}
