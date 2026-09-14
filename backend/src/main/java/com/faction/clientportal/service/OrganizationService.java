package com.faction.clientportal.service;

import com.faction.clientportal.dto.*;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.EntityFieldConfigRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.faction.clientportal.util.InMemorySort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final AccessScopeService accessScopeService;
    private final OrganizationRepository organizationRepository;
    private final ApplicationRepository applicationRepository;
    private final EntityFieldConfigRepository entityFieldConfigRepository;
    private final UserRepository userRepository;

    public Organization createOrganization(Organization organization) {
        return organizationRepository.save(organization);
    }

    public Optional<Organization> findByName(String name) {
        return organizationRepository.findByName(name);
    }

    public Optional<Organization> findById(String id) {
        return organizationRepository.findById(id);
    }

    public List<Organization> findAll() {
        return organizationRepository.findAll();
    }

    public Organization updateOrganization(Organization organization) {
        return organizationRepository.save(organization);
    }

    public void deleteOrganization(String id) {
        organizationRepository.deleteById(id);
    }

    // ==================== NEW CRUD METHODS ====================

    public OrganizationDto createOrganizationDto(CreateOrganizationRequest request) {
        // Check if organization with same name already exists
        if (organizationRepository.existsByName(request.getName())) {
            throw new IllegalArgumentException("Organization with name '" + request.getName() + "' already exists");
        }

        Organization organization = Organization.builder()
                .name(request.getName())
                .description(request.getDescription())
                .fieldValues(request.getFieldValues() != null ? request.getFieldValues() : new HashMap<>())
                .build();

        Organization savedOrganization = organizationRepository.save(organization);
        return toDto(savedOrganization);
    }

    public OrganizationDto updateOrganizationDto(String id, UpdateOrganizationRequest request) {
        return updateOrganizationDto(id, request, null);
    }

    public OrganizationDto updateOrganizationDto(String id, UpdateOrganizationRequest request, Authentication authentication) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + id));

        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasEditAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:edit:all"));
            boolean hasEditOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:owned"));

            if (!isSuperAdmin && !hasEditAll) {
                if (hasEditOwned) {
                    String currentUserId = resolveUserId(authentication);
                    boolean canEdit = organization.getAssignedUsers().stream()
                            .anyMatch(u -> u.getUserId().equals(currentUserId) && "WRITE".equals(u.getAccessLevel()));
                    if (!canEdit) {
                        throw new AccessDeniedException("Access denied");
                    }
                } else {
                    throw new AccessDeniedException("Access denied");
                }
            }
        }

        // Check if updating to a name that already exists (and it's not the same organization)
        organizationRepository.findByName(request.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new IllegalArgumentException("Organization with name '" + request.getName() + "' already exists");
            }
        });

        organization.setName(request.getName());
        organization.setDescription(request.getDescription());
        if (request.getFieldValues() != null) {
            organization.setFieldValues(request.getFieldValues());
        }
        if (request.getRemediationOwnerIds() != null) {
            organization.setRemediationOwnerIds(validateRemediationOwners(request.getRemediationOwnerIds()));
        }

        Organization updatedOrganization = organizationRepository.save(organization);
        return toDto(updatedOrganization);
    }

    /**
     * Remediation owners are staff: they fix and track findings, and their notifications link
     * into internal views. An external account here is a mistake worth refusing loudly.
     */
    private List<String> validateRemediationOwners(List<String> ids) {
        List<String> clean = new ArrayList<>();
        List<String> external = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || clean.contains(id)) continue;
            User user = userRepository.findById(id)
                    .filter(u -> u.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
            if (!Boolean.TRUE.equals(user.getIsInternal())) {
                external.add(user.getUsername());
                continue;
            }
            clean.add(id);
        }
        if (!external.isEmpty()) {
            throw new IllegalArgumentException("Remediation owners must be internal users: "
                    + String.join(", ", external));
        }
        return clean;
    }

    public void deleteOrganizationById(String id) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + id));

        // Check if organization has applications
        List<Application> applications = applicationRepository.findByOrganizationId(id);
        if (!applications.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot delete organization with " + applications.size() +
                    " assigned application(s). Please remove or reassign applications first."
            );
        }

        long members = userRepository.countByOrganizationIdsContaining(id);
        if (members > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete organization with " + members
                    + " member user(s). Remove them from the organization first.");
        }

        organizationRepository.deleteById(id);
    }

    public OrganizationDto findOrganizationById(String id) {
        return findOrganizationById(id, null);
    }

    public OrganizationDto findOrganizationById(String id, Authentication authentication) {
        Organization organization = organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + id));

        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasReadAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:all"));
            boolean hasReadOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:owned"));
            boolean hasReadOrg = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:org"));

            if (!isSuperAdmin && !hasReadAll) {
                if (hasReadOwned) {
                    if (!accessScopeService.ownedOrganizationIds(resolveUserId(authentication)).contains(id)) {
                        throw new ResourceNotFoundException("Organization not found with id: " + id);
                    }
                } else if (hasReadOrg) {
                    if (!accessScopeService.visibleOrganizationIds(
                            accessScopeService.resolveOrgAccess(authentication)).contains(id)) {
                        throw new ResourceNotFoundException("Organization not found with id: " + id);
                    }
                }
            }
        }

        return toDto(organization);
    }

    public Page<OrganizationDto> findAllPaginated(Pageable pageable) {
        return findAllPaginated(pageable, null);
    }

    public Page<OrganizationDto> findAllPaginated(Pageable pageable, Authentication authentication) {
        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasReadAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:all"));
            boolean hasReadOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:owned"));
            boolean hasReadOrg = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:org"));

            if (!isSuperAdmin && !hasReadAll) {
                List<Organization> source;
                if (hasReadOwned) {
                    source = accessScopeService.ownedOrganizationIds(resolveUserId(authentication)).stream()
                            .map(organizationRepository::findById)
                            .flatMap(java.util.Optional::stream)
                            .toList();
                } else if (hasReadOrg) {
                    source = accessScopeService.visibleOrganizationIds(
                                    accessScopeService.resolveOrgAccess(authentication)).stream()
                            .map(organizationRepository::findById)
                            .flatMap(java.util.Optional::stream)
                            .toList();
                } else {
                    source = List.of();
                }
                return pageInMemory(source.stream().map(this::toDto).collect(Collectors.toList()), pageable);
            }
        }
        if (needsInMemorySort(pageable)) {
            return pageInMemory(organizationRepository.findAll().stream().map(this::toDto)
                    .collect(Collectors.toList()), pageable);
        }
        return organizationRepository.findAll(pageable).map(this::toDto);
    }

    public List<OrganizationDto> findAllDto() {
        return organizationRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    public Page<OrganizationDto> searchOrganizations(String search, Pageable pageable) {
        return searchOrganizations(search, pageable, null);
    }

    /**
     * Sortable organization columns for the paths that page in memory: the scoped branches, and any
     * sort on a display value the query cannot order by (remediation owner names come from a jsonb
     * list of user ids).
     */
    private static final Map<String, Comparator<OrganizationDto>> SORTS = Map.of(
            "name", InMemorySort.byText(OrganizationDto::getName),
            "description", InMemorySort.byText(OrganizationDto::getDescription),
            "remediationOwners", InMemorySort.byText(dto -> dto.getRemediationOwners() == null
                    || dto.getRemediationOwners().isEmpty() ? null
                    : dto.getRemediationOwners().stream()
                            .map(OrganizationDto.RemediationOwner::getDisplayName)
                            .collect(Collectors.joining(", "))));

    /** Sort keys the query cannot apply; the unrestricted paths fall back to in-memory ordering for them. */
    private static final java.util.Set<String> IN_MEMORY_ONLY_SORTS = java.util.Set.of("remediationOwners");

    private static boolean needsInMemorySort(Pageable pageable) {
        return pageable.getSort().isSorted()
                && IN_MEMORY_ONLY_SORTS.contains(pageable.getSort().iterator().next().getProperty());
    }

    /** Sort, then slice one page out of an already-materialized list. */
    private Page<OrganizationDto> pageInMemory(List<OrganizationDto> dtos, Pageable pageable) {
        dtos = InMemorySort.apply(dtos, pageable, SORTS, OrganizationDto::getId);
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), dtos.size());
        List<OrganizationDto> page = start > dtos.size() ? new ArrayList<>() : dtos.subList(start, end);
        return new PageImpl<>(page, pageable, dtos.size());
    }

    private static boolean matchesSearch(Organization o, String search) {
        if (search == null || search.trim().isEmpty()) return true;
        String lower = search.trim().toLowerCase();
        return (o.getName() != null && o.getName().toLowerCase().contains(lower))
                || (o.getDescription() != null && o.getDescription().toLowerCase().contains(lower));
    }

    public Page<OrganizationDto> searchOrganizations(String search, Pageable pageable, Authentication authentication) {
        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasReadAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:all"));
            boolean hasReadOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:owned"));
            boolean hasReadOrg = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("organizations:read:org"));

            if (!isSuperAdmin && !hasReadAll) {
                List<Organization> source;
                if (hasReadOwned) {
                    source = accessScopeService.ownedOrganizationIds(resolveUserId(authentication)).stream()
                            .map(organizationRepository::findById)
                            .flatMap(java.util.Optional::stream)
                            .toList();
                } else if (hasReadOrg) {
                    source = accessScopeService.visibleOrganizationIds(
                                    accessScopeService.resolveOrgAccess(authentication)).stream()
                            .map(organizationRepository::findById)
                            .flatMap(java.util.Optional::stream)
                            .toList();
                } else {
                    source = List.of();
                }
                // Filtered and paged in Java, so the query never saw the sort — apply it here.
                return pageInMemory(source.stream()
                        .filter(o -> matchesSearch(o, search))
                        .map(this::toDto)
                        .collect(Collectors.toList()), pageable);
            }
        }

        if (needsInMemorySort(pageable)) {
            return pageInMemory(organizationRepository.findAll().stream()
                    .filter(o -> matchesSearch(o, search))
                    .map(this::toDto)
                    .collect(Collectors.toList()), pageable);
        }
        if (search == null || search.trim().isEmpty()) {
            return organizationRepository.findAll(pageable).map(this::toDto);
        }
        return organizationRepository.searchByNameOrDescription(search.trim(), pageable).map(this::toDto);
    }

    // ==================== ASSIGNED USER METHODS ====================

    public List<AssignedUserDto> getAssignedUsers(String orgId) {
        Organization organization = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + orgId));
        return organization.getAssignedUsers().stream()
                .map(AssignedUserDto::fromEntity)
                .collect(Collectors.toList());
    }

    public AssignedUserDto assignUser(String orgId, AssignUserRequest req, String requestingUsername) {
        Organization organization = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + orgId));

        User user = userRepository.findById(req.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + req.getUserId()));

        boolean alreadyAssigned = organization.getAssignedUsers().stream()
                .anyMatch(u -> u.getUserId().equals(req.getUserId()));
        if (alreadyAssigned) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already assigned to this organization");
        }

        String displayName = buildDisplayName(user);
        AssignedUser assignedUser = AssignedUser.builder()
                .userId(user.getId())
                .displayName(displayName)
                .email(user.getEmail())
                .accessLevel(req.getAccessLevel())
                .build();

        organization.getAssignedUsers().add(assignedUser);
        organizationRepository.save(organization);
        return AssignedUserDto.fromEntity(assignedUser);
    }

    public AssignedUserDto updateAssignedUser(String orgId, String userId, UpdateAssignedUserRequest req, String requestingUsername) {
        Organization organization = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + orgId));

        AssignedUser assignedUser = organization.getAssignedUsers().stream()
                .filter(u -> u.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("User not assigned to this organization"));

        assignedUser.setAccessLevel(req.getAccessLevel());
        organizationRepository.save(organization);
        return AssignedUserDto.fromEntity(assignedUser);
    }

    public void removeAssignedUser(String orgId, String userId) {
        Organization organization = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + orgId));

        boolean removed = organization.getAssignedUsers().removeIf(u -> u.getUserId().equals(userId));
        if (!removed) {
            throw new ResourceNotFoundException("User not assigned to this organization");
        }
        organizationRepository.save(organization);
    }

    // ==================== PRIVATE HELPERS ====================

    private String resolveUserId(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElse(username);
    }

    private String buildDisplayName(User user) {
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String lastName = user.getLastName() != null ? user.getLastName() : "";
        String full = (firstName + " " + lastName).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private OrganizationDto toDto(Organization organization) {
        List<UserDefinedFieldDto> fieldDefs = entityFieldConfigRepository
                .findByScope(FieldScope.ORGANIZATION)
                .map(config -> config.getFieldDefinitions().stream()
                        .map(UserDefinedFieldDto::fromEntity)
                        .collect(Collectors.toList()))
                .orElse(new ArrayList<>());

        List<AssignedUserDto> assignedUserDtos = organization.getAssignedUsers() != null
                ? organization.getAssignedUsers().stream().map(AssignedUserDto::fromEntity).collect(Collectors.toList())
                : new ArrayList<>();

        List<String> ownerIds = organization.getRemediationOwnerIds() != null
                ? organization.getRemediationOwnerIds() : List.of();
        List<OrganizationDto.RemediationOwner> owners = ownerIds.stream()
                .map(id -> userRepository.findById(id)
                        .map(u -> OrganizationDto.RemediationOwner.builder()
                                .userId(u.getId()).username(u.getUsername())
                                .displayName(buildDisplayName(u)).email(u.getEmail()).build())
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        return OrganizationDto.builder()
                .id(organization.getId())
                .name(organization.getName())
                .description(organization.getDescription())
                .fieldDefinitions(fieldDefs)
                .fieldValues(organization.getFieldValues() != null ? organization.getFieldValues() : new HashMap<>())
                .assignedUsers(assignedUserDtos)
                .remediationOwnerIds(new ArrayList<>(ownerIds))
                .remediationOwners(owners)
                .build();
    }
}
