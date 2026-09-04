package com.faction.clientportal.service;

import com.faction.clientportal.dto.SubOrganizationDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.SubOrganization;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.SubOrganizationRepository;
import com.faction.clientportal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Divisions within an organization. A sub-organization is an attribution applied to applications,
 * not an access boundary — the owning organization is unchanged — so there is no scope resolution
 * here beyond confirming the parent organization exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubOrganizationService {

    private final SubOrganizationRepository subOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final AccessScopeService accessScopeService;

    public List<SubOrganizationDto> listForOrganization(String organizationId) {
        return listForOrganization(organizationId, null);
    }

    /**
     * A customer's internal divisions and how many applications sit under each — an org chart and
     * an estate-size disclosure. Confirming the parent organization exists is not a scope check:
     * this endpoint accepts the external {@code :org} and {@code :owned} reads, so without
     * resolving what the caller may actually see, any id returned another customer's structure.
     * The cross-organization variant below has always filtered; this one did not.
     */
    public List<SubOrganizationDto> listForOrganization(String organizationId, Authentication authentication) {
        if (authentication != null && !visibleOrganizationIds(authentication).contains(organizationId)) {
            throw new ResourceNotFoundException("Organization not found with id: " + organizationId);
        }

        requireOrganization(organizationId);
        return subOrganizationRepository.findByOrganizationIdOrderByNameAsc(organizationId).stream()
                .map(sub -> SubOrganizationDto.fromEntity(
                        sub, applicationRepository.countBySubOrganizationId(sub.getId())))
                .toList();
    }

    /**
     * Every division the caller can see, across all organizations, each carrying its parent's name.
     * Importers work from a division name alone ("Payments") and need the organization it hangs off
     * — listing per organization would mean a call per organization to find that out.
     *
     * @param name optional exact (case-insensitive) name filter
     */
    public List<SubOrganizationDto> listAll(String name, Authentication authentication) {
        List<SubOrganization> subs;
        if (name != null && !name.isBlank()) {
            subs = subOrganizationRepository.findByNameIgnoreCase(name.trim());
        } else {
            subs = subOrganizationRepository.findAllByOrderByNameAsc();
        }

        Set<String> visibleOrgIds = visibleOrganizationIds(authentication);
        if (visibleOrgIds != null) {
            subs = subs.stream().filter(s -> visibleOrgIds.contains(s.getOrganizationId())).toList();
        }
        if (subs.isEmpty()) {
            return List.of();
        }

        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : applicationRepository.countGroupedBySubOrganizationId()) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        Map<String, String> orgNames = organizationRepository
                .findAllById(subs.stream().map(SubOrganization::getOrganizationId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Organization::getId, Organization::getName));

        return subs.stream()
                .map(sub -> SubOrganizationDto.fromEntity(
                        sub,
                        counts.getOrDefault(sub.getId(), 0L),
                        orgNames.get(sub.getOrganizationId())))
                .toList();
    }

    /**
     * Organization ids the caller may see, or {@code null} for "everything" (unauthenticated
     * internal calls, super admins, and organizations:read:all). Mirrors the scoping
     * {@code OrganizationService.searchOrganizations} applies to the organizations themselves.
     */
    private Set<String> visibleOrganizationIds(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (authorities.contains("super_admin") || authorities.contains("organizations:read:all")) {
            return null;
        }
        if (authorities.contains("organizations:read:owned")) {
            return accessScopeService.ownedOrganizationIds(resolveUserId(authentication));
        }
        if (authorities.contains("organizations:read:org")) {
            String orgId = accessScopeService.resolveOrgId(authentication);
            return orgId != null ? Set.of(orgId) : Set.of();
        }
        return Set.of();
    }

    private String resolveUserId(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .map(User::getId)
                .orElse(authentication.getName());
    }

    public SubOrganizationDto create(String organizationId, SubOrganizationDto.Request request, String userId) {
        requireOrganization(organizationId);
        String name = request.getName().trim();
        requireNameFree(organizationId, name, null);

        LocalDateTime now = LocalDateTime.now();
        SubOrganization saved = subOrganizationRepository.save(SubOrganization.builder()
                .organizationId(organizationId)
                .name(name)
                .description(request.getDescription())
                .createdBy(userId)
                .lastUpdatedBy(userId)
                .createdAt(now)
                .updatedAt(now)
                .build());

        log.info("Created sub-organization {} under organization {}", saved.getName(), organizationId);
        return SubOrganizationDto.fromEntity(saved, 0);
    }

    public SubOrganizationDto update(String organizationId, String id,
                                     SubOrganizationDto.Request request, String userId) {
        SubOrganization sub = requireSubOrganization(organizationId, id);
        String name = request.getName().trim();
        requireNameFree(organizationId, name, id);

        sub.setName(name);
        sub.setDescription(request.getDescription());
        sub.setLastUpdatedBy(userId);
        sub.setUpdatedAt(LocalDateTime.now());

        SubOrganization saved = subOrganizationRepository.save(sub);
        return SubOrganizationDto.fromEntity(saved, applicationRepository.countBySubOrganizationId(id));
    }

    /**
     * Deleting is blocked while applications still point here. Detaching them silently would
     * quietly drop an attribution someone chose, so the caller reassigns or clears them first —
     * and the message says how many there are.
     */
    public void delete(String organizationId, String id) {
        SubOrganization sub = requireSubOrganization(organizationId, id);
        long applications = applicationRepository.countBySubOrganizationId(id);
        if (applications > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete '" + sub.getName() + "': " + applications + " application"
                            + (applications == 1 ? " is" : "s are") + " still assigned to it. "
                            + "Reassign them first.");
        }
        subOrganizationRepository.delete(sub);
        log.info("Deleted sub-organization {} from organization {}", sub.getName(), organizationId);
    }

    /**
     * Validates that a sub-organization belongs to the given organization before an application is
     * attributed to it — otherwise an application could be tagged with another organization's
     * division. A null id clears the attribution.
     */
    public void validateForOrganization(String subOrganizationId, String organizationId) {
        if (subOrganizationId == null || subOrganizationId.isBlank()) {
            return;
        }
        SubOrganization sub = subOrganizationRepository.findById(subOrganizationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Sub-organization not found: " + subOrganizationId));
        if (!sub.getOrganizationId().equals(organizationId)) {
            throw new IllegalArgumentException(
                    "Sub-organization '" + sub.getName() + "' belongs to a different organization");
        }
    }

    private void requireOrganization(String organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException("Organization not found: " + organizationId);
        }
    }

    private SubOrganization requireSubOrganization(String organizationId, String id) {
        SubOrganization sub = subOrganizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-organization not found: " + id));
        // Reject a mismatched parent rather than acting on it — the id alone must not be enough to
        // reach into another organization's divisions.
        if (!sub.getOrganizationId().equals(organizationId)) {
            throw new ResourceNotFoundException("Sub-organization not found: " + id);
        }
        return sub;
    }

    private void requireNameFree(String organizationId, String name, String excludingId) {
        subOrganizationRepository.findByOrganizationIdAndNameIgnoreCase(organizationId, name)
                .filter(existing -> !existing.getId().equals(excludingId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "A sub-organization named '" + name + "' already exists in this organization");
                });
    }
}
