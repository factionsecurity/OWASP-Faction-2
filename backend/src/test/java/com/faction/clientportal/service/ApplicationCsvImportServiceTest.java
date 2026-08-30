package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.ApplicationStatus;
import com.faction.clientportal.model.ApplicationUrl;
import com.faction.clientportal.model.Stakeholder;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.SubOrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The CSV application sync: upsert matching, on-demand organizations and divisions, and the
 * per-row error reporting that keeps one bad line from discarding the rest of the file.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationCsvImportServiceTest extends TestContainersConfig {

    @Autowired private ApplicationCsvImportService service;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SubOrganizationRepository subOrganizationRepository;

    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        subOrganizationRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private com.faction.clientportal.dto.ApplicationImportResultDto upload(String csv) throws IOException {
        return service.importCsv(new MockMultipartFile(
                "file", "apps.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)), "admin");
    }

    @Test
    void insertsNewApplicationsWithEveryColumn() throws IOException {
        var result = upload("""
                appId,name,description,organization,subOrganization,status,region,applicationType,assessmentFrequency,ownerName,ownerEmail
                APP-1,Checkout,Customer checkout,Acme,Payments,PRODUCTION,EMEA,Web Application,Yearly,Jane Doe,jane@example.com
                """);

        assertThat(result.getProcessed()).isEqualTo(1);
        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getUpdated()).isZero();
        assertThat(result.getErrors()).isEmpty();

        Application app = applicationRepository.findByAppId("APP-1").orElseThrow();
        assertThat(app.getName()).isEqualTo("Checkout");
        assertThat(app.getDescription()).isEqualTo("Customer checkout");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PRODUCTION);
        assertThat(app.getRegion()).isEqualTo("EMEA");
        assertThat(app.getApplicationType()).isEqualTo("Web Application");
        assertThat(app.getAssessmentFrequency()).isEqualTo("Yearly");
        assertThat(app.getOwnerName()).isEqualTo("Jane Doe");
        assertThat(app.getOwnerEmail()).isEqualTo("jane@example.com");

        Organization org = organizationRepository.findByNameIgnoreCase("Acme").orElseThrow();
        assertThat(app.getOrganizationId()).isEqualTo(org.getId());
        var division = subOrganizationRepository
                .findByOrganizationIdAndNameIgnoreCase(org.getId(), "Payments").orElseThrow();
        assertThat(app.getSubOrganizationId()).isEqualTo(division.getId());
        assertThat(result.getCreatedOrganizations()).containsExactly("Acme");
        assertThat(result.getCreatedSubOrganizations()).containsExactly("Acme / Payments");
    }

    @Test
    void updatesByAppIdEvenWhenTheNameChanged() throws IOException {
        applicationRepository.save(Application.builder()
                .appId("APP-1").name("Old Name").status(ApplicationStatus.DEVELOPMENT).build());

        var result = upload("""
                appId,name,status
                app-1,New Name,PRODUCTION
                """);

        assertThat(result.getCreated()).isZero();
        assertThat(result.getUpdated()).isEqualTo(1);
        assertThat(applicationRepository.findAll()).singleElement()
                .satisfies(app -> {
                    assertThat(app.getName()).isEqualTo("New Name");        // matched case-insensitively
                    assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PRODUCTION);
                });
    }

    @Test
    void updatesByNameWhenThereIsNoAppId() throws IOException {
        applicationRepository.save(Application.builder().name("Checkout").build());

        var result = upload("""
                name,region
                checkout,APAC
                """);

        assertThat(result.getUpdated()).isEqualTo(1);
        assertThat(applicationRepository.findAll()).singleElement()
                .satisfies(app -> assertThat(app.getRegion()).isEqualTo("APAC"));
    }

    @Test
    void columnsMissingFromTheFileAreLeftAlone() throws IOException {
        applicationRepository.save(Application.builder()
                .name("Checkout").description("Keep me").region("EMEA")
                .status(ApplicationStatus.PRODUCTION).build());

        upload("""
                name,region
                Checkout,APAC
                """);

        Application app = applicationRepository.findByName("Checkout").orElseThrow();
        assertThat(app.getRegion()).isEqualTo("APAC");
        // A three-column sync file must not blank out everything it doesn't mention.
        assertThat(app.getDescription()).isEqualTo("Keep me");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PRODUCTION);
    }

    @Test
    void reusesOrganizationsAndDivisionsAcrossRowsAndRuns() throws IOException {
        var first = upload("""
                name,organization,subOrganization
                Checkout,Acme,Payments
                Refunds,Acme,Payments
                Ledger,acme,payments
                """);

        assertThat(first.getCreated()).isEqualTo(3);
        assertThat(first.getCreatedOrganizations()).containsExactly("Acme");
        assertThat(first.getCreatedSubOrganizations()).containsExactly("Acme / Payments");
        assertThat(organizationRepository.findAll()).hasSize(1);
        assertThat(subOrganizationRepository.findAll()).hasSize(1);

        // A second run of the same file changes nothing structural.
        var second = upload("""
                name,organization,subOrganization
                Checkout,Acme,Payments
                """);
        assertThat(second.getUpdated()).isEqualTo(1);
        assertThat(second.getCreatedOrganizations()).isEmpty();
        assertThat(organizationRepository.findAll()).hasSize(1);
    }

    @Test
    void movingAnApplicationToAnotherOrganizationDropsTheOldDivision() throws IOException {
        upload("""
                name,organization,subOrganization
                Checkout,Acme,Payments
                """);

        upload("""
                name,organization
                Checkout,Globex
                """);

        Application app = applicationRepository.findByName("Checkout").orElseThrow();
        Organization globex = organizationRepository.findByNameIgnoreCase("Globex").orElseThrow();
        assertThat(app.getOrganizationId()).isEqualTo(globex.getId());
        // The old division belonged to Acme, so keeping it would tag Globex with Acme's structure.
        assertThat(app.getSubOrganizationId()).isNull();
    }

    @Test
    void badRowsAreReportedByLineWhileTheRestStillApply() throws IOException {
        var result = upload("""
                appId,name,status
                APP-1,Checkout,PRODUCTION
                APP-2,Broken,RETIRED
                ,,
                APP-3,Ledger,TESTING
                """);

        assertThat(result.getCreated()).isEqualTo(2);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getLine()).isEqualTo(3);          // header is line 1
            assertThat(error.getIdentifier()).isEqualTo("Broken");
            assertThat(error.getMessage()).contains("Unknown status 'RETIRED'");
        });
        assertThat(applicationRepository.findAll()).extracting(Application::getName)
                .containsExactlyInAnyOrder("Checkout", "Ledger");
    }

    @Test
    void aDivisionWithoutAnOrganizationIsRejected() throws IOException {
        var result = upload("""
                name,subOrganization
                Checkout,Payments
                """);

        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0).getMessage()).contains("needs an organization");
        assertThat(applicationRepository.findAll()).isEmpty();
    }

    @Test
    void appIdWinsOverNameWhenBothWouldMatchDifferentApplications() throws IOException {
        applicationRepository.save(Application.builder().appId("APP-1").name("Checkout").build());
        applicationRepository.save(Application.builder().name("Refunds").build());

        var result = upload("""
                name,appId,region
                Refunds,APP-1,EMEA
                """);

        // appId is the stable identifier, so the row updates (and renames) APP-1 rather than the
        // application that merely shares the row's name.
        assertThat(result.getUpdated()).isEqualTo(1);
        assertThat(result.getCreated()).isZero();
        assertThat(applicationRepository.findByAppId("APP-1").orElseThrow())
                .satisfies(app -> {
                    assertThat(app.getName()).isEqualTo("Refunds");
                    assertThat(app.getRegion()).isEqualTo("EMEA");
                });
        assertThat(applicationRepository.findAll()).hasSize(2);
    }

    @Test
    void importsTechnologiesUrlsAndStakeholders() throws IOException {
        upload("""
                name,technologies,urls,stakeholders
                Checkout,"Java; React ;PostgreSQL","https://checkout.example.com|Production Site;https://staging.example.com|Staging","Jane Doe|jane@example.com|Product Owner;Sam Lee|sam@example.com|Security Champion"
                """);

        Application app = applicationRepository.findByName("Checkout").orElseThrow();
        assertThat(app.getTechnologies()).containsExactly("Java", "React", "PostgreSQL");
        assertThat(app.getUrls()).extracting(ApplicationUrl::getUrl, ApplicationUrl::getTitle)
                .containsExactly(
                        tuple("https://checkout.example.com", "Production Site"),
                        tuple("https://staging.example.com", "Staging"));
        assertThat(app.getStakeHolders())
                .extracting(Stakeholder::getName, Stakeholder::getEmail, Stakeholder::getRole)
                .containsExactly(
                        tuple("Jane Doe", "jane@example.com", "Product Owner"),
                        tuple("Sam Lee", "sam@example.com", "Security Champion"));
    }

    @Test
    void aUrlWithoutATitleFallsBackToTheAddressAndPartialStakeholdersAreAllowed() throws IOException {
        upload("""
                name,urls,stakeholders
                Checkout,https://checkout.example.com,Jane Doe
                """);

        Application app = applicationRepository.findByName("Checkout").orElseThrow();
        assertThat(app.getUrls()).singleElement()
                .satisfies(url -> assertThat(url.getTitle()).isEqualTo("https://checkout.example.com"));
        assertThat(app.getStakeHolders()).singleElement().satisfies(person -> {
            assertThat(person.getName()).isEqualTo("Jane Doe");
            assertThat(person.getEmail()).isNull();
            assertThat(person.getRole()).isNull();
        });
    }

    @Test
    void listCellsReplaceTheWholeListAndAreLeftAloneWhenAbsent() throws IOException {
        upload("""
                name,technologies
                Checkout,Java;React
                """);

        // Present and filled: the cell is the list, so React is gone.
        upload("""
                name,technologies
                Checkout,Go
                """);
        assertThat(applicationRepository.findByName("Checkout").orElseThrow().getTechnologies())
                .containsExactly("Go");

        // Column absent entirely: untouched.
        upload("""
                name,region
                Checkout,EMEA
                """);
        assertThat(applicationRepository.findByName("Checkout").orElseThrow().getTechnologies())
                .containsExactly("Go");
    }

    @Test
    void anEntryMissingItsRequiredPartIsRejected() throws IOException {
        var result = upload("""
                name,urls
                Checkout,|Production Site
                """);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0).getMessage()).contains("has no address");

        result = upload("""
                name,stakeholders
                Checkout,|jane@example.com|Product Owner
                """);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0).getMessage()).contains("has no name");
    }

    @Test
    void quotedFieldsKeepTheirCommasAndQuotes() throws IOException {
        upload("""
                name,description
                "Checkout, EU","He said ""hello"", loudly"
                """);

        assertThat(applicationRepository.findAll()).singleElement().satisfies(app -> {
            assertThat(app.getName()).isEqualTo("Checkout, EU");
            assertThat(app.getDescription()).isEqualTo("He said \"hello\", loudly");
        });
    }

    @Test
    void headersAreCaseInsensitiveAndUnknownColumnsAreRejected() throws IOException {
        upload("""
                Name,ORGANIZATION
                Checkout,Acme
                """);
        assertThat(applicationRepository.findByName("Checkout")).isPresent();

        assertThatThrownBy(() -> upload("""
                name,widgets
                Checkout,3
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown column(s): widgets");
    }

    @Test
    void aFileWithNothingToMatchOnIsRejected() throws IOException {
        assertThatThrownBy(() -> upload("""
                description,region
                Something,EMEA
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a 'name' or 'appId' column");

        assertThatThrownBy(() -> upload(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No CSV file was uploaded");
    }

    @Test
    void theTemplateRoundTripsThroughTheImporter() throws IOException {
        var result = upload(service.template());

        assertThat(result.getProcessed()).isEqualTo(1);
        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getErrors()).isEmpty();
        assertThat(applicationRepository.findByAppId("APP-001")).isPresent();
    }
}
