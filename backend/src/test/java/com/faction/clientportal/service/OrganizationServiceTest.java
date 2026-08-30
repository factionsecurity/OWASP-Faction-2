package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateOrganizationRequest;
import com.faction.clientportal.dto.OrganizationDto;
import com.faction.clientportal.dto.UpdateOrganizationRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OrganizationServiceTest extends TestContainersConfig {

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void createOrganization_WithValidData_CreatesSuccessfully() {
        CreateOrganizationRequest request = new CreateOrganizationRequest();
        request.setName("Acme Corporation");
        request.setDescription("A test organization");

        OrganizationDto result = organizationService.createOrganizationDto(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getName()).isEqualTo("Acme Corporation");
        assertThat(result.getDescription()).isEqualTo("A test organization");

        // Verify organization exists in database
        Organization saved = organizationRepository.findByName("Acme Corporation").orElseThrow();
        assertThat(saved.getId()).isEqualTo(result.getId());
    }

    @Test
    void createOrganization_WithDuplicateName_ThrowsException() {
        // Create first organization
        CreateOrganizationRequest request1 = new CreateOrganizationRequest();
        request1.setName("Acme Corporation");
        request1.setDescription("First organization");
        organizationService.createOrganizationDto(request1);

        // Try to create second organization with same name
        CreateOrganizationRequest request2 = new CreateOrganizationRequest();
        request2.setName("Acme Corporation");
        request2.setDescription("Second organization");

        assertThatThrownBy(() -> organizationService.createOrganizationDto(request2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Organization with name 'Acme Corporation' already exists");

        // Verify only one organization exists
        assertThat(organizationRepository.findAll()).hasSize(1);
    }

    @Test
    void createOrganization_WithDifferentNames_BothCreateSuccessfully() {
        CreateOrganizationRequest request1 = new CreateOrganizationRequest();
        request1.setName("Acme Corporation");
        request1.setDescription("First organization");

        CreateOrganizationRequest request2 = new CreateOrganizationRequest();
        request2.setName("Beta Industries");
        request2.setDescription("Second organization");

        OrganizationDto result1 = organizationService.createOrganizationDto(request1);
        OrganizationDto result2 = organizationService.createOrganizationDto(request2);

        assertThat(result1.getName()).isEqualTo("Acme Corporation");
        assertThat(result2.getName()).isEqualTo("Beta Industries");
        assertThat(organizationRepository.findAll()).hasSize(2);
    }

    @Test
    void updateOrganization_WithValidData_UpdatesSuccessfully() {
        // Create organization
        Organization org = Organization.builder()
                .name("Original Name")
                .description("Original description")
                .build();
        org = organizationRepository.save(org);

        // Update organization
        UpdateOrganizationRequest request = new UpdateOrganizationRequest();
        request.setName("Updated Name");
        request.setDescription("Updated description");

        OrganizationDto result = organizationService.updateOrganizationDto(org.getId(), request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getDescription()).isEqualTo("Updated description");

        // Verify in database
        Organization updated = organizationRepository.findById(org.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(updated.getDescription()).isEqualTo("Updated description");
    }

    @Test
    void updateOrganization_WithSameName_UpdatesSuccessfully() {
        // Create organization
        Organization org = Organization.builder()
                .name("Acme Corporation")
                .description("Original description")
                .build();
        org = organizationRepository.save(org);

        // Update organization keeping same name but changing description
        UpdateOrganizationRequest request = new UpdateOrganizationRequest();
        request.setName("Acme Corporation");
        request.setDescription("New description");

        OrganizationDto result = organizationService.updateOrganizationDto(org.getId(), request);

        assertThat(result.getName()).isEqualTo("Acme Corporation");
        assertThat(result.getDescription()).isEqualTo("New description");
    }

    @Test
    void updateOrganization_WithNameOfAnotherOrganization_ThrowsException() {
        // Create two organizations
        Organization org1 = organizationRepository.save(Organization.builder()
                .name("Acme Corporation")
                .description("First organization")
                .build());

        Organization org2 = organizationRepository.save(Organization.builder()
                .name("Beta Industries")
                .description("Second organization")
                .build());

        // Try to update org2 to have the same name as org1
        UpdateOrganizationRequest request = new UpdateOrganizationRequest();
        request.setName("Acme Corporation");
        request.setDescription("Updated description");

        final String org2Id = org2.getId();
        assertThatThrownBy(() -> organizationService.updateOrganizationDto(org2Id, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Organization with name 'Acme Corporation' already exists");

        // Verify org2 name unchanged
        Organization unchanged = organizationRepository.findById(org2Id).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Beta Industries");
    }

    @Test
    void updateOrganization_WithNonExistentId_ThrowsException() {
        UpdateOrganizationRequest request = new UpdateOrganizationRequest();
        request.setName("Some Name");
        request.setDescription("Some description");

        assertThatThrownBy(() -> organizationService.updateOrganizationDto("nonexistent-id", request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organization not found with id: nonexistent-id");
    }

    @Test
    void deleteOrganization_WithNoApplications_DeletesSuccessfully() {
        Organization org = Organization.builder()
                .name("To Delete")
                .description("Will be deleted")
                .build();
        org = organizationRepository.save(org);

        organizationService.deleteOrganizationById(org.getId());

        assertThat(organizationRepository.findById(org.getId())).isEmpty();
    }

    @Test
    void deleteOrganization_WithApplications_ThrowsException() {
        // Create organization
        Organization org = organizationRepository.save(Organization.builder()
                .name("Has Apps")
                .description("Has applications")
                .build());

        // Create application associated with organization
        Application app = Application.builder()
                .name("Test App")
                .organizationId(org.getId())
                .build();
        applicationRepository.save(app);

        // Try to delete organization
        final String orgId = org.getId();
        assertThatThrownBy(() -> organizationService.deleteOrganizationById(orgId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot delete organization with 1 assigned application(s)");

        // Verify organization still exists
        assertThat(organizationRepository.findById(orgId)).isPresent();
    }

    @Test
    void deleteOrganization_WithNonExistentId_ThrowsException() {
        assertThatThrownBy(() -> organizationService.deleteOrganizationById("nonexistent-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organization not found with id: nonexistent-id");
    }

    @Test
    void findOrganizationById_WithValidId_ReturnsOrganization() {
        Organization org = Organization.builder()
                .name("Find Me")
                .description("Test organization")
                .build();
        org = organizationRepository.save(org);

        OrganizationDto result = organizationService.findOrganizationById(org.getId());

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(org.getId());
        assertThat(result.getName()).isEqualTo("Find Me");
        assertThat(result.getDescription()).isEqualTo("Test organization");
    }

    @Test
    void findOrganizationById_WithNonExistentId_ThrowsException() {
        assertThatThrownBy(() -> organizationService.findOrganizationById("nonexistent-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organization not found with id: nonexistent-id");
    }

    @Test
    void findAllPaginated_ReturnsPagedResults() {
        // Create multiple organizations
        for (int i = 1; i <= 15; i++) {
            Organization org = Organization.builder()
                    .name("Organization " + i)
                    .description("Description " + i)
                    .build();
            organizationRepository.save(org);
        }

        // Request first page with size 10
        Page<OrganizationDto> page = organizationService.findAllPaginated(PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(10);
        assertThat(page.getTotalElements()).isEqualTo(15);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.isFirst()).isTrue();
        assertThat(page.isLast()).isFalse();
    }

    @Test
    void searchOrganizations_ByName_ReturnsMatchingResults() {
        // Create organizations
        Organization org1 = Organization.builder()
                .name("Acme Corporation")
                .description("Technology company")
                .build();
        organizationRepository.save(org1);

        Organization org2 = Organization.builder()
                .name("Beta Industries")
                .description("Manufacturing company")
                .build();
        organizationRepository.save(org2);

        Organization org3 = Organization.builder()
                .name("Acme Solutions")
                .description("Consulting company")
                .build();
        organizationRepository.save(org3);

        // Search for "Acme"
        Page<OrganizationDto> results = organizationService.searchOrganizations("Acme", PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(2);
        assertThat(results.getContent())
                .extracting(OrganizationDto::getName)
                .containsExactlyInAnyOrder("Acme Corporation", "Acme Solutions");
    }

    @Test
    void searchOrganizations_ByDescription_ReturnsMatchingResults() {
        // Create organizations
        Organization org1 = Organization.builder()
                .name("Tech Corp")
                .description("Technology solutions")
                .build();
        organizationRepository.save(org1);

        Organization org2 = Organization.builder()
                .name("Finance Corp")
                .description("Banking services")
                .build();
        organizationRepository.save(org2);

        // Search by description
        Page<OrganizationDto> results = organizationService.searchOrganizations("Technology", PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getName()).isEqualTo("Tech Corp");
    }

    @Test
    void searchOrganizations_CaseInsensitive_ReturnsMatchingResults() {
        Organization org = Organization.builder()
                .name("Acme Corporation")
                .description("Test organization")
                .build();
        organizationRepository.save(org);

        // Search with different case
        Page<OrganizationDto> results = organizationService.searchOrganizations("acme", PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getName()).isEqualTo("Acme Corporation");
    }

    @Test
    void searchOrganizations_WithEmptyQuery_ReturnsAllResults() {
        // Create organizations
        for (int i = 1; i <= 3; i++) {
            Organization org = Organization.builder()
                    .name("Organization " + i)
                    .description("Description " + i)
                    .build();
            organizationRepository.save(org);
        }

        // Search with empty string
        Page<OrganizationDto> results = organizationService.searchOrganizations("", PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(3);
    }

    @Test
    void searchOrganizations_WithNoMatches_ReturnsEmptyResults() {
        Organization org = Organization.builder()
                .name("Acme Corporation")
                .description("Test organization")
                .build();
        organizationRepository.save(org);

        // Search for non-existent term
        Page<OrganizationDto> results = organizationService.searchOrganizations("NonExistent", PageRequest.of(0, 10));

        assertThat(results.getContent()).isEmpty();
    }
}
