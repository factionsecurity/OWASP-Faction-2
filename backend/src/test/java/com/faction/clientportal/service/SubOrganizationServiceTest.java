package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.SubOrganizationDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.SubOrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Divisions within an organization: CRUD, the per-organization name rule, and the guard that stops
 * a division being deleted while applications are still attributed to it.
 */
@SpringBootTest
@ActiveProfiles("test")
class SubOrganizationServiceTest extends TestContainersConfig {

    @Autowired private SubOrganizationService service;
    @Autowired private SubOrganizationRepository subOrganizationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ApplicationRepository applicationRepository;

    private String acmeId;
    private String globexId;

    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        subOrganizationRepository.deleteAll();
        organizationRepository.deleteAll();

        acmeId = organizationRepository.save(
                Organization.builder().name("Acme").description("d").build()).getId();
        globexId = organizationRepository.save(
                Organization.builder().name("Globex").description("d").build()).getId();
    }

    private SubOrganizationDto create(String orgId, String name) {
        var request = new SubOrganizationDto.Request();
        request.setName(name);
        return service.create(orgId, request, "user-1");
    }

    @Test
    void anOrganizationCanHaveSeveralSubOrganizations() {
        create(acmeId, "Payments");
        create(acmeId, "Platform");

        assertThat(service.listForOrganization(acmeId))
                .extracting(SubOrganizationDto::getName)
                .containsExactly("Payments", "Platform"); // sorted by name
    }

    @Test
    void subOrganizationsAreScopedToTheirOrganization() {
        create(acmeId, "Payments");
        create(globexId, "Ledger");

        assertThat(service.listForOrganization(acmeId)).extracting(SubOrganizationDto::getName)
                .containsExactly("Payments");
        assertThat(service.listForOrganization(globexId)).extracting(SubOrganizationDto::getName)
                .containsExactly("Ledger");
    }

    @Test
    void namesAreUniquePerOrganizationNotGlobally() {
        create(acmeId, "EMEA");

        // The same division name in another organization is fine…
        assertThat(create(globexId, "EMEA")).isNotNull();
        // …but not twice in the same one, whatever the casing.
        assertThatThrownBy(() -> create(acmeId, "emea"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void renamingKeepsTheUniquenessRuleButAllowsRenamingToItsOwnName() {
        var payments = create(acmeId, "Payments");
        create(acmeId, "Platform");

        var request = new SubOrganizationDto.Request();
        request.setName("Payments");
        request.setDescription("Same name, new description");
        assertThat(service.update(acmeId, payments.getId(), request, "user-1").getDescription())
                .isEqualTo("Same name, new description");

        request.setName("Platform");
        assertThatThrownBy(() -> service.update(acmeId, payments.getId(), request, "user-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingIsBlockedWhileApplicationsAreStillAssigned() {
        var payments = create(acmeId, "Payments");
        applicationRepository.save(Application.builder()
                .name("Checkout").organizationId(acmeId).subOrganizationId(payments.getId()).build());

        assertThatThrownBy(() -> service.delete(acmeId, payments.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("1 application is still assigned");

        assertThat(subOrganizationRepository.findById(payments.getId())).isPresent();
    }

    @Test
    void deletingSucceedsOnceNothingReferencesIt() {
        var payments = create(acmeId, "Payments");
        var app = applicationRepository.save(Application.builder()
                .name("Checkout").organizationId(acmeId).subOrganizationId(payments.getId()).build());

        app.setSubOrganizationId(null);
        applicationRepository.save(app);

        service.delete(acmeId, payments.getId());
        assertThat(subOrganizationRepository.findById(payments.getId())).isEmpty();
    }

    @Test
    void theListReportsHowManyApplicationsAreAttributed() {
        var payments = create(acmeId, "Payments");
        create(acmeId, "Platform");
        applicationRepository.save(Application.builder()
                .name("Checkout").organizationId(acmeId).subOrganizationId(payments.getId()).build());
        applicationRepository.save(Application.builder()
                .name("Refunds").organizationId(acmeId).subOrganizationId(payments.getId()).build());

        assertThat(service.listForOrganization(acmeId))
                .filteredOn(s -> s.getName().equals("Payments"))
                .singleElement()
                .extracting(SubOrganizationDto::getApplicationCount).isEqualTo(2L);
    }

    // ── Cross-organization directory ────────────────────────────────────────────

    @Test
    void theDirectoryListsEveryDivisionWithItsOwningOrganization() {
        create(acmeId, "Payments");
        create(globexId, "Ledger");

        assertThat(service.listAll(null, null))
                .extracting(SubOrganizationDto::getName, SubOrganizationDto::getOrganizationId,
                        SubOrganizationDto::getOrganizationName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Ledger", globexId, "Globex"),
                        org.assertj.core.groups.Tuple.tuple("Payments", acmeId, "Acme"));
    }

    @Test
    void lookingADivisionUpByNameReturnsEveryOrganizationUsingIt() {
        create(acmeId, "EMEA");
        create(globexId, "EMEA");
        create(acmeId, "Payments");

        // Names are unique per organization, so a shared name matches in both — the caller picks.
        assertThat(service.listAll("emea", null))
                .extracting(SubOrganizationDto::getOrganizationId)
                .containsExactlyInAnyOrder(acmeId, globexId);

        assertThat(service.listAll("Payments", null))
                .extracting(SubOrganizationDto::getOrganizationId).containsExactly(acmeId);
        assertThat(service.listAll("nothing here", null)).isEmpty();
    }

    @Test
    void theDirectoryCountsApplicationsPerDivision() {
        var payments = create(acmeId, "Payments");
        create(globexId, "Ledger");
        applicationRepository.save(Application.builder()
                .name("Checkout").organizationId(acmeId).subOrganizationId(payments.getId()).build());
        applicationRepository.save(Application.builder()
                .name("Refunds").organizationId(acmeId).subOrganizationId(payments.getId()).build());

        assertThat(service.listAll(null, null))
                .extracting(SubOrganizationDto::getName, SubOrganizationDto::getApplicationCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Ledger", 0L),
                        org.assertj.core.groups.Tuple.tuple("Payments", 2L));
    }

    // ── Cross-organization protection ───────────────────────────────────────────

    @Test
    void anotherOrganizationsSubOrganizationIsNotFoundThroughThisOne() {
        var ledger = create(globexId, "Ledger");

        // Knowing the id must not be enough to reach into another organization's divisions.
        var request = new SubOrganizationDto.Request();
        request.setName("Renamed");
        assertThatThrownBy(() -> service.update(acmeId, ledger.getId(), request, "user-1"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(acmeId, ledger.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anApplicationCannotBeAttributedToAnotherOrganizationsDivision() {
        var ledger = create(globexId, "Ledger");

        assertThatThrownBy(() -> service.validateForOrganization(ledger.getId(), acmeId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different organization");

        // Its own organization is fine, and a blank id simply clears the attribution.
        service.validateForOrganization(ledger.getId(), globexId);
        service.validateForOrganization(null, acmeId);
        service.validateForOrganization("", acmeId);
    }

    @Test
    void anUnknownOrganizationOrSubOrganizationIsNotFound() {
        assertThatThrownBy(() -> service.listForOrganization("nope"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(acmeId, "nope"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
