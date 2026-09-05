package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.TerminologyConfigRequest;
import com.faction.clientportal.model.TerminologyConfig;
import com.faction.clientportal.model.VulnerabilitySeverity;
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
        assertThat(defaults.getSeverityCritical()).isEqualTo("Critical");
        assertThat(defaults.getSeverityHigh()).isEqualTo("High");
        assertThat(defaults.getSeverityMedium()).isEqualTo("Medium");
        assertThat(defaults.getSeverityLow()).isEqualTo("Low");
        assertThat(defaults.getSeverityInformational()).isEqualTo("Informational");
    }

    @Test
    void severitiesCanBeRenamedToWhateverTheTeamAlreadySays() {
        service.updateConfig(TerminologyConfigRequest.builder()
                .severityCritical("Sev-1").severityHigh("Sev-2").severityMedium("Sev-3")
                .severityLow("Sev-4").severityInformational("Sev-5")
                .build());

        assertThat(service.severityLabel(VulnerabilitySeverity.CRITICAL)).isEqualTo("Sev-1");
        assertThat(service.severityLabel(VulnerabilitySeverity.HIGH)).isEqualTo("Sev-2");
        assertThat(service.severityLabel(VulnerabilitySeverity.MEDIUM)).isEqualTo("Sev-3");
        assertThat(service.severityLabel(VulnerabilitySeverity.LOW)).isEqualTo("Sev-4");
        assertThat(service.severityLabel(VulnerabilitySeverity.INFORMATIONAL)).isEqualTo("Sev-5");
    }

    @Test
    void renamingOneSeverityLeavesEveryOtherLabelAlone() {
        // The organization labels share this row. A caller sending only a severity — an API client
        // scripting a rename — must not silently undo an organization rename made earlier.
        service.updateConfig(TerminologyConfigRequest.builder()
                .organizationSingular("Value Stream").organizationPlural("Value Streams")
                .build());
        service.updateConfig(TerminologyConfigRequest.builder().severityCritical("P1").build());

        TerminologyConfig saved = service.getConfig();
        assertThat(saved.getSeverityCritical()).isEqualTo("P1");
        assertThat(saved.getSeverityHigh()).isEqualTo("High");
        assertThat(saved.getOrganizationPlural()).isEqualTo("Value Streams");
    }

    @Test
    void aRatingHeldAsFreeTextFollowsTheSameRename() {
        // Likelihood and impact are strings, not the enum, and the finding screens and the
        // default-vulnerability form have historically written them in different cases.
        service.updateConfig(TerminologyConfigRequest.builder().severityCritical("Sev-1").build());

        assertThat(service.severityLabelForName("CRITICAL")).isEqualTo("Sev-1");
        assertThat(service.severityLabelForName("Critical")).isEqualTo("Sev-1");
        assertThat(service.severityLabelForName("critical")).isEqualTo("Sev-1");
    }

    @Test
    void aRatingThatIsNotASeverityIsLeftExactlyAsItIs() {
        // Imports and older installations put arbitrary text in these fields. A rename must not
        // reinterpret "3" or blank out something it does not recognise.
        service.updateConfig(TerminologyConfigRequest.builder().severityCritical("Sev-1").build());

        assertThat(service.severityLabelForName("3")).isEqualTo("3");
        assertThat(service.severityLabelForName("Very High")).isEqualTo("Very High");
        assertThat(service.severityLabelForName("")).isEmpty();
        assertThat(service.severityLabelForName(null)).isNull();
    }

    @Test
    void anUnsetSeverityHasNoLabelRatherThanTheWordNull() {
        assertThat(service.severityLabel(null)).isEmpty();
    }

    @Test
    void renamingDoesNotDisturbTheEnumTheRestOfTheSystemRunsOn() {
        // The whole design rests on this: labels are display only. Ranking, and therefore report
        // ordering, the SLA config, and both export formats, key off the constants.
        service.updateConfig(TerminologyConfigRequest.builder()
                .severityCritical("Sev-1").severityInformational("FYI").build());

        assertThat(VulnerabilitySeverity.valueOf("CRITICAL").reportRank()).isEqualTo(0);
        assertThat(VulnerabilitySeverity.INFORMATIONAL.reportRank()).isEqualTo(4);
        assertThat(VulnerabilitySeverity.values()).hasSize(5);
    }

    @Test
    void labelsCanBeRenamed() {
        service.updateConfig(TerminologyConfigRequest.builder()
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
        service.updateConfig(TerminologyConfigRequest.builder()
                .organizationSingular("Entity").organizationPlural("Entities").build());

        assertThat(service.getConfig().getOrganizationPlural()).isEqualTo("Entities");
    }

    @Test
    void aBlankLabelLeavesTheExistingOneAlone() {
        service.updateConfig(TerminologyConfigRequest.builder()
                .organizationSingular("Value Stream").organizationPlural("Value Streams").build());

        // A partial update, or a form that submitted an empty box: a screen with a gap where a
        // noun should be is worse than one using the old word.
        service.updateConfig(TerminologyConfigRequest.builder()
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
        service.updateConfig(TerminologyConfigRequest.builder()
                .organizationSingular("  Value Stream  ").build());

        assertThat(service.getConfig().getOrganizationSingular()).isEqualTo("Value Stream");
    }

    @Test
    void anAbsurdlyLongLabelIsRefused() {
        assertThatThrownBy(() -> service.updateConfig(TerminologyConfigRequest.builder()
                .organizationSingular("x".repeat(101)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100 characters");
    }

    @Test
    void thereIsOnlyEverOneRow() {
        service.updateConfig(TerminologyConfigRequest.builder().organizationSingular("A").build());
        service.updateConfig(TerminologyConfigRequest.builder().organizationSingular("B").build());

        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.getConfig().getOrganizationSingular()).isEqualTo("B");
    }
}
