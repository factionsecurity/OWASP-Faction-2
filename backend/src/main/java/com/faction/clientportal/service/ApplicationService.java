package com.faction.clientportal.service;

import com.faction.clientportal.dto.*;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.EntityFieldConfigRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.repository.VulnerabilityRepositoryCustom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.faction.clientportal.util.InMemorySort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final AccessScopeService accessScopeService;
    private final AssessmentRepository assessmentRepository;
    private final OrganizationRepository organizationRepository;
    private final EntityFieldConfigRepository entityFieldConfigRepository;
    private final UserRepository userRepository;
    private final ApplicationIdConfigService applicationIdConfigService;
    private final MentionQueueService mentionQueueService;
    private final NotificationService notificationService;
    private final com.faction.clientportal.service.email.ThreadCommentEmailSender threadCommentEmailSender;
    private final SubOrganizationService subOrganizationService;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final com.faction.clientportal.service.extension.ExtensionEventService extensionEventService;

    public ApplicationDto createApplication(CreateApplicationRequest request, String userId) {
        return createApplication(request, userId, null);
    }

    public ApplicationDto createApplication(CreateApplicationRequest request, String userId, Authentication authentication) {
        // Verify organization exists if provided
        if (request.getOrganizationId() != null) {
            organizationRepository.findById(request.getOrganizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + request.getOrganizationId()));
        }

        // Auto-generate appId if not provided and generation is enabled
        if (!org.springframework.util.StringUtils.hasText(request.getAppId())) {
            if (applicationIdConfigService.isEnabled()) {
                request.setAppId(applicationIdConfigService.generateNextAppId());
            }
        } else if (applicationRepository.findByAppId(request.getAppId()).isPresent()) {
            throw new IllegalArgumentException("Application with appId '" + request.getAppId() + "' already exists");
        }

        List<AssignedUser> assignedUsers = new ArrayList<>();

        // Auto-assign creator if they have create:owned (but not create:all or super_admin)
        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasCreateAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:create:all"));
            boolean hasCreateOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:create:owned"));

            boolean hasCreateOrg = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:create:org"));

            if (!isSuperAdmin && !hasCreateAll && hasCreateOwned) {
                userRepository.findById(userId).ifPresent(user -> {
                    // App-level-restricted users get assigned to their new app so it
                    // joins their visible set; org-level users own it via the org.
                    if (accessScopeService.isAppLevelRestricted(user.getId())) {
                        assignedUsers.add(AssignedUser.builder()
                                .userId(user.getId())
                                .displayName(buildDisplayName(user))
                                .email(user.getEmail())
                                .accessLevel("WRITE")
                                .build());
                    }
                    // External owners create applications inside their home organization
                    if (user.getOrganizationId() != null) {
                        request.setOrganizationId(user.getOrganizationId());
                    }
                });
            } else if (!isSuperAdmin && !hasCreateAll && !hasCreateOwned && hasCreateOrg) {
                // Org user: force organizationId to their org
                String orgId = resolveOrgId(authentication);
                request.setOrganizationId(orgId);
            }
        }

        Application application = Application.builder()
                .appId(request.getAppId())
                .name(request.getName())
                .description(request.getDescription())
                .urls(toApplicationUrlList(request.getUrls()))
                .stakeHolders(toStakeHolderList(request.getStakeHolders()))
                .technologies(request.getTechnologies() != null ? request.getTechnologies() : new ArrayList<>())
                .appOwner(toAppOwner(request.getAppOwner()))
                .status(request.getStatus())
                .organizationId(request.getOrganizationId())
                .subOrganizationId(normalizeSubOrganization(
                        request.getSubOrganizationId(), request.getOrganizationId()))
                .region(request.getRegion() != null ? request.getRegion() : "Global")
                .applicationType(request.getApplicationType())
                .assessmentFrequency(request.getAssessmentFrequency())
                .customFrequencyMonths(request.getCustomFrequencyMonths())
                .lastAssessmentDate(request.getLastAssessmentDate())
                // Legacy fields for backward compatibility
                .ownerName(request.getOwnerName())
                .ownerEmail(request.getOwnerEmail())
                .fieldValues(request.getFieldValues() != null ? request.getFieldValues() : new HashMap<>())
                .assignedUsers(assignedUsers)
                .createdBy(userId)
                .lastUpdatedBy(userId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Application savedApplication = applicationRepository.save(application);
        return toDto(savedApplication);
    }

    public ApplicationDto updateApplication(String id, UpdateApplicationRequest request, String userId) {
        return updateApplication(id, request, userId, null);
    }

    public ApplicationDto updateApplication(String id, UpdateApplicationRequest request, String userId, Authentication authentication) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));

        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasEditAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:edit:all"));
            boolean hasReadOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:owned"));
            boolean hasEditOrg = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:edit:org"));

            if (!isSuperAdmin && !hasEditAll) {
                if (hasReadOwned) {
                    String currentUserId = resolveUserId(authentication);
                    if (!accessScopeService.canWriteApplication(currentUserId, application)) {
                        throw new AccessDeniedException("Access denied");
                    }
                    // Owned editors cannot move an application to a different organization
                    request.setOrganizationId(application.getOrganizationId());
                } else if (hasEditOrg) {
                    String orgId = resolveOrgId(authentication);
                    if (orgId == null || !orgId.equals(application.getOrganizationId())) {
                        throw new AccessDeniedException("Access denied");
                    }
                    // Org users cannot move an application to a different organization
                    request.setOrganizationId(application.getOrganizationId());
                } else {
                    throw new AccessDeniedException("Access denied");
                }
            }
        }

        // Verify organization exists if provided
        if (request.getOrganizationId() != null) {
            organizationRepository.findById(request.getOrganizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + request.getOrganizationId()));
        }

        // Cascade org change to all assessments
        String previousOrgId = application.getOrganizationId();
        String newOrgId = request.getOrganizationId();
        boolean orgChanged = newOrgId != null && !newOrgId.equals(previousOrgId)
                || (newOrgId == null && previousOrgId != null);
        if (orgChanged) {
            List<com.faction.clientportal.model.Assessment> assessments =
                    assessmentRepository.findByApplicationIdAndDeletedAtIsNull(application.getId());
            assessments.forEach(a -> a.setOrganizationId(newOrgId));
            assessmentRepository.saveAll(assessments);
        }

        application.setName(request.getName());
        if (org.springframework.util.StringUtils.hasText(request.getAppId())
                && !request.getAppId().equals(application.getAppId())) {
            if (applicationRepository.findByAppId(request.getAppId()).isPresent()) {
                throw new IllegalArgumentException("Application with appId '" + request.getAppId() + "' already exists");
            }
            application.setAppId(request.getAppId());
        }
        application.setDescription(request.getDescription());
        application.setUrls(toApplicationUrlList(request.getUrls()));
        application.setStakeHolders(toStakeHolderList(request.getStakeHolders()));
        application.setTechnologies(request.getTechnologies());
        application.setAppOwner(toAppOwner(request.getAppOwner()));
        application.setStatus(request.getStatus());
        application.setOrganizationId(newOrgId);
        // Moving to another organization invalidates the old division, which belonged to the
        // previous one — drop it rather than leave a cross-organization reference behind.
        application.setSubOrganizationId(orgChanged
                ? normalizeSubOrganization(request.getSubOrganizationId(), newOrgId)
                : normalizeSubOrganization(request.getSubOrganizationId(), application.getOrganizationId()));
        application.setApplicationType(request.getApplicationType());
        application.setAssessmentFrequency(request.getAssessmentFrequency());
        application.setCustomFrequencyMonths(request.getCustomFrequencyMonths());
        application.setLastAssessmentDate(request.getLastAssessmentDate());
        if (request.getRegion() != null) {
            application.setRegion(request.getRegion());
        }
        // Legacy fields for backward compatibility
        application.setOwnerName(request.getOwnerName());
        application.setOwnerEmail(request.getOwnerEmail());
        if (request.getFieldValues() != null) {
            application.setFieldValues(request.getFieldValues());
        }
        application.setLastUpdatedBy(userId);
        application.setUpdatedAt(LocalDateTime.now());

        Application updatedApplication = applicationRepository.save(application);
        return toDto(updatedApplication);
    }

    public void deleteApplication(String id) {
        applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));

        applicationRepository.deleteById(id);
    }

    public ApplicationDto findById(String id) {
        return findById(id, null);
    }

    public ApplicationDto findById(String id, Authentication authentication) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));

        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasReadAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:all"));
            boolean hasReadOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:owned"));
            boolean hasReadOrg = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:org"));

            if (!isSuperAdmin && !hasReadAll) {
                if (hasReadOwned) {
                    String currentUserId = resolveUserId(authentication);
                    if (!accessScopeService.ownsApplication(currentUserId, application)) {
                        throw new ResourceNotFoundException("Application not found with id: " + id);
                    }
                } else if (hasReadOrg) {
                    String orgId = resolveOrgId(authentication);
                    if (orgId == null || !orgId.equals(application.getOrganizationId())) {
                        throw new ResourceNotFoundException("Application not found with id: " + id);
                    }
                }
            }
        }

        return toDto(application);
    }

    public Page<ApplicationDto> findAllPaginated(Pageable pageable) {
        return findAllPaginated(pageable, null);
    }

    public Page<ApplicationDto> findAllPaginated(Pageable pageable, Authentication authentication) {
        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasReadAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:all"));
            boolean hasReadOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:owned"));
            boolean hasReadOrg = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:org"));

            if (!isSuperAdmin && !hasReadAll) {
                if (hasReadOwned) {
                    String currentUserId = resolveUserId(authentication);
                    List<Application> owned = applicationRepository.findAllById(
                            accessScopeService.ownedApplicationIds(currentUserId));
                    List<ApplicationDto> dtos = owned.stream().map(this::toDto).collect(Collectors.toList());
                    int start = (int) pageable.getOffset();
                    int end = Math.min(start + pageable.getPageSize(), dtos.size());
                    List<ApplicationDto> page = start > dtos.size() ? new ArrayList<>() : dtos.subList(start, end);
                    return new PageImpl<>(page, pageable, dtos.size());
                } else if (hasReadOrg) {
                    String orgId = resolveOrgId(authentication);
                    List<Application> orgApps = orgId != null
                            ? applicationRepository.findByOrganizationId(orgId)
                            : new ArrayList<>();
                    List<ApplicationDto> dtos = orgApps.stream().map(this::toDto).collect(Collectors.toList());
                    int start = (int) pageable.getOffset();
                    int end = Math.min(start + pageable.getPageSize(), dtos.size());
                    List<ApplicationDto> page = start > dtos.size() ? new ArrayList<>() : dtos.subList(start, end);
                    return new PageImpl<>(page, pageable, dtos.size());
                }
            }
        }
        return applicationRepository.findAll(pageable).map(this::toDto);
    }

    public Page<ApplicationDto> searchApplications(String search, Pageable pageable) {
        return searchApplications(search, pageable, null);
    }

    public Page<ApplicationDto> searchApplications(String search, Pageable pageable, Authentication authentication) {
        return searchApplications(search, null, null, null, pageable, authentication);
    }

    /**
     * The applications list: free-text search plus the optional organization / sub-organization /
     * status filters the Applications page exposes. Filters are ANDed and a null one is ignored.
     */
    public Page<ApplicationDto> searchApplications(String search, String organizationId,
                                                   String subOrganizationId, ApplicationStatus status,
                                                   Pageable pageable, Authentication authentication) {
        Page<ApplicationDto> result = doSearchApplications(
                search, blankToNull(organizationId), blankToNull(subOrganizationId), status,
                pageable, authentication);
        enrichOpenIssueCounts(result.getContent());
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Asks every enabled {@code ApplicationInventory} extension for applications
     * matching an id or name, so an external system of record can back Faction's
     * application picker.
     *
     * <p>Kept out of {@link #searchApplications} on purpose. Those results are
     * external records with no Faction id, and folding them into the paged
     * applications listing would both break the page's total count and hand the UI
     * rows whose every action would fail. Callers that want inventory lookup — the
     * create-application flow — ask for it explicitly.
     *
     * <p>Returns an empty list when no inventory extension is installed.
     */
    public List<ExternalApplicationDto> searchExternalInventory(String applicationId, String name) {
        return extensionEventService.searchInventory(applicationId, name).stream()
                .map(result -> ExternalApplicationDto.builder()
                        .applicationId(result.getApplicationId())
                        .applicationName(result.getApplicationName())
                        .distributionList(result.getDistributionList())
                        .customFields(result.getCustomFields() == null
                                ? Map.of() : new HashMap<>(result.getCustomFields()))
                        .build())
                .collect(Collectors.toList());
    }

    /** Attaches the "Open Issues" count to a page of application DTOs via one batched query. */
    private void enrichOpenIssueCounts(List<ApplicationDto> apps) {
        if (apps == null || apps.isEmpty()) return;
        List<String> ids = apps.stream().map(ApplicationDto::getId).collect(Collectors.toList());
        Map<String, Long> counts = vulnerabilityRepository.countOpenIssuesByApplication(ids).stream()
                .collect(Collectors.toMap(
                        VulnerabilityRepositoryCustom.ApplicationOpenCount::applicationId,
                        VulnerabilityRepositoryCustom.ApplicationOpenCount::openCount));
        for (ApplicationDto app : apps) {
            app.setOpenIssueCount(counts.getOrDefault(app.getId(), 0L));
        }
    }

    /** Sortable application columns, for the scoped branch below that pages in memory. */
    private static final Map<String, Comparator<ApplicationDto>> SORTS = Map.of(
            "appId", InMemorySort.byText(ApplicationDto::getAppId),
            "name", InMemorySort.byText(ApplicationDto::getName),
            "ownerName", InMemorySort.byText(ApplicationDto::getOwnerName),
            "status", InMemorySort.byValue(ApplicationDto::getStatus),
            "lastAssessmentDate", InMemorySort.byValue(ApplicationDto::getLastAssessmentDate));

    private Page<ApplicationDto> doSearchApplications(String search, String organizationId,
                                                     String subOrganizationId, ApplicationStatus status,
                                                     Pageable pageable, Authentication authentication) {
        if (authentication != null) {
            boolean isSuperAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("super_admin"));
            boolean hasReadAll = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:all"));
            boolean hasReadOwned = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:owned"));
            boolean hasReadOrg = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("applications:read:org"));

            if (!isSuperAdmin && !hasReadAll) {
                List<Application> source;
                if (hasReadOwned) {
                    source = applicationRepository.findAllById(
                            accessScopeService.ownedApplicationIds(resolveUserId(authentication)));
                } else if (hasReadOrg) {
                    String orgId = resolveOrgId(authentication);
                    source = orgId != null ? applicationRepository.findByOrganizationId(orgId) : new ArrayList<>();
                } else {
                    source = new ArrayList<>();
                }
                // This branch filters in Java, so the same filters the query applies below have to
                // be applied here too — otherwise a scoped user's filter pills would do nothing.
                Stream<Application> matching = source.stream()
                        .filter(a -> organizationId == null || organizationId.equals(a.getOrganizationId()))
                        .filter(a -> subOrganizationId == null || subOrganizationId.equals(a.getSubOrganizationId()))
                        .filter(a -> status == null || status == a.getStatus());
                if (search != null && !search.trim().isEmpty()) {
                    String lower = search.trim().toLowerCase();
                    matching = matching
                            .filter(a -> (a.getName() != null && a.getName().toLowerCase().contains(lower))
                                    || (a.getDescription() != null && a.getDescription().toLowerCase().contains(lower))
                                    || (a.getAppId() != null && a.getAppId().toLowerCase().contains(lower)));
                }
                List<ApplicationDto> dtos = matching.map(this::toDto).collect(Collectors.toList());
                // This branch pages a list it filtered in Java, so the query never saw the sort —
                // apply it here or a scoped user's column headers would do nothing.
                dtos = InMemorySort.apply(dtos, pageable, SORTS, ApplicationDto::getId);
                int start = (int) pageable.getOffset();
                int end = Math.min(start + pageable.getPageSize(), dtos.size());
                List<ApplicationDto> page = start > dtos.size() ? new ArrayList<>() : dtos.subList(start, end);
                return new PageImpl<>(page, pageable, dtos.size());
            }
        }

        return applicationRepository
                .searchFiltered(search, organizationId, subOrganizationId, status, pageable)
                .map(this::toDto);
    }

    /**
     * Blank/absent clears the attribution; otherwise the division must belong to the application's
     * organization, so an application can't be tagged with another organization's division.
     */
    private String normalizeSubOrganization(String subOrganizationId, String organizationId) {
        if (subOrganizationId == null || subOrganizationId.isBlank()) {
            return null;
        }
        subOrganizationService.validateForOrganization(subOrganizationId, organizationId);
        return subOrganizationId;
    }

    public List<ApplicationDto> findByOrganizationId(String organizationId) {
        return applicationRepository.findByOrganizationId(organizationId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public ApplicationDto moveApplicationToOrganization(String applicationId, String newOrganizationId, String userId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + applicationId));

        // Verify new organization exists
        Organization newOrganization = organizationRepository.findById(newOrganizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + newOrganizationId));

        application.setOrganizationId(newOrganizationId);
        application.setLastUpdatedBy(userId);
        application.setUpdatedAt(LocalDateTime.now());

        Application updatedApplication = applicationRepository.save(application);
        return toDto(updatedApplication);
    }

    // ==================== ASSIGNED USER METHODS ====================

    public List<AssignedUserDto> getAssignedUsers(String appId) {
        Application application = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + appId));
        return application.getAssignedUsers().stream()
                .map(AssignedUserDto::fromEntity)
                .collect(Collectors.toList());
    }

    public AssignedUserDto assignUser(String appId, AssignUserRequest req, String requestingUsername) {
        Application application = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + appId));

        User user = userRepository.findById(req.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + req.getUserId()));

        boolean alreadyAssigned = application.getAssignedUsers().stream()
                .anyMatch(u -> u.getUserId().equals(req.getUserId()));
        if (alreadyAssigned) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already assigned to this application");
        }

        String displayName = buildDisplayName(user);
        AssignedUser assignedUser = AssignedUser.builder()
                .userId(user.getId())
                .displayName(displayName)
                .email(user.getEmail())
                .accessLevel(req.getAccessLevel())
                .build();

        application.getAssignedUsers().add(assignedUser);
        applicationRepository.save(application);
        return AssignedUserDto.fromEntity(assignedUser);
    }

    public AssignedUserDto updateAssignedUser(String appId, String userId, UpdateAssignedUserRequest req, String requestingUsername) {
        Application application = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + appId));

        AssignedUser assignedUser = application.getAssignedUsers().stream()
                .filter(u -> u.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("User not assigned to this application"));

        assignedUser.setAccessLevel(req.getAccessLevel());
        applicationRepository.save(application);
        return AssignedUserDto.fromEntity(assignedUser);
    }

    public void removeAssignedUser(String appId, String userId) {
        Application application = applicationRepository.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + appId));

        boolean removed = application.getAssignedUsers().removeIf(u -> u.getUserId().equals(userId));
        if (!removed) {
            throw new ResourceNotFoundException("User not assigned to this application");
        }
        applicationRepository.save(application);
    }

    // ==================== USER-CENTRIC ASSIGNMENTS ====================

    /** Every application the given user is directly assigned to. */
    public List<UserApplicationAssignmentDto> getUserAssignments(String userId) {
        return applicationRepository.findByAssignedUsersUserId(userId).stream()
                .map(app -> {
                    AssignedUser au = app.getAssignedUsers().stream()
                            .filter(u -> u.getUserId().equals(userId))
                            .findFirst().orElse(null);
                    return UserApplicationAssignmentDto.builder()
                            .applicationId(app.getId())
                            .applicationName(app.getName())
                            .organizationId(app.getOrganizationId())
                            .accessLevel(au != null ? au.getAccessLevel() : "READ")
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Replaces the user's full set of application assignments: adds/updates the
     * requested applications and removes the user from any others. This is what
     * puts an external user into app-level (restricted) mode.
     */
    @Transactional
    public List<UserApplicationAssignmentDto> syncUserAssignments(
            String userId, List<SyncUserApplicationAssignmentsRequest.Assignment> requested) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        Map<String, String> targetLevels = new LinkedHashMap<>();
        for (SyncUserApplicationAssignmentsRequest.Assignment a : requested) {
            String level = "WRITE".equalsIgnoreCase(a.getAccessLevel()) ? "WRITE" : "READ";
            targetLevels.put(a.getApplicationId(), level);
        }

        // Remove the user from applications no longer targeted
        for (Application app : applicationRepository.findByAssignedUsersUserId(userId)) {
            if (!targetLevels.containsKey(app.getId())) {
                app.getAssignedUsers().removeIf(u -> u.getUserId().equals(userId));
                applicationRepository.save(app);
            }
        }

        // Add or update the targeted applications
        String displayName = buildDisplayName(user);
        for (Map.Entry<String, String> entry : targetLevels.entrySet()) {
            Application app = applicationRepository.findById(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Application not found with id: " + entry.getKey()));
            if (app.getAssignedUsers() == null) app.setAssignedUsers(new ArrayList<>());
            AssignedUser existing = app.getAssignedUsers().stream()
                    .filter(u -> u.getUserId().equals(userId))
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.setAccessLevel(entry.getValue());
                existing.setDisplayName(displayName);
                existing.setEmail(user.getEmail());
            } else {
                app.getAssignedUsers().add(AssignedUser.builder()
                        .userId(userId)
                        .displayName(displayName)
                        .email(user.getEmail())
                        .accessLevel(entry.getValue())
                        .build());
            }
            applicationRepository.save(app);
        }

        return getUserAssignments(userId);
    }

    // ==================== PRIVATE HELPERS ====================

    private String resolveUserId(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElse(username);
    }

    private String resolveOrgId(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .map(User::getOrganizationId)
                .orElse(null);
    }

    private String buildDisplayName(User user) {
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String lastName = user.getLastName() != null ? user.getLastName() : "";
        String full = (firstName + " " + lastName).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private ApplicationDto toDto(Application application) {
        List<UserDefinedFieldDto> fieldDefs = entityFieldConfigRepository
                .findByScope(FieldScope.APPLICATION)
                .map(config -> config.getFieldDefinitions().stream()
                        .map(UserDefinedFieldDto::fromEntity)
                        .collect(Collectors.toList()))
                .orElse(new ArrayList<>());

        List<AssignedUserDto> assignedUserDtos = application.getAssignedUsers() != null
                ? application.getAssignedUsers().stream().map(AssignedUserDto::fromEntity).collect(Collectors.toList())
                : new ArrayList<>();

        List<ApplicationCommentDto> commentDtos = application.getComments() != null
                ? application.getComments().stream().map(ApplicationCommentDto::fromEntity).collect(Collectors.toList())
                : new ArrayList<>();

        return ApplicationDto.builder()
                .id(application.getId())
                .appId(application.getAppId())
                .name(application.getName())
                .description(application.getDescription())
                .urls(toApplicationUrlDtoList(application.getUrls()))
                .stakeHolders(toStakeHolderDtoList(application.getStakeHolders()))
                .technologies(application.getTechnologies())
                .appOwner(toAppOwnerDto(application.getAppOwner()))
                .status(application.getStatus())
                .organizationId(application.getOrganizationId())
                .subOrganizationId(application.getSubOrganizationId())
                .region(application.getRegion() != null ? application.getRegion() : "Global")
                .applicationType(application.getApplicationType())
                .assessmentFrequency(application.getAssessmentFrequency())
                .customFrequencyMonths(application.getCustomFrequencyMonths())
                .lastAssessmentDate(application.getLastAssessmentDate())
                // Legacy fields for backward compatibility
                .ownerName(application.getOwnerName())
                .ownerEmail(application.getOwnerEmail())
                .fieldDefinitions(fieldDefs)
                .fieldValues(application.getFieldValues() != null ? application.getFieldValues() : new HashMap<>())
                .assignedUsers(assignedUserDtos)
                .comments(commentDtos)
                .subscribers(application.getSubscribers() != null
                        ? new ArrayList<>(application.getSubscribers())
                        : new ArrayList<>())
                .createdBy(application.getCreatedBy())
                .lastUpdatedBy(application.getLastUpdatedBy())
                .createdAt(application.getCreatedAt())
                .updatedAt(application.getUpdatedAt())
                .build();
    }

    private List<Stakeholder> toStakeHolderList(List<StakeholderDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(StakeholderDto::toEntity)
                .collect(Collectors.toList());
    }

    private List<StakeholderDto> toStakeHolderDtoList(List<Stakeholder> stakeHolders) {
        if (stakeHolders == null) {
            return List.of();
        }
        return stakeHolders.stream()
                .map(StakeholderDto::fromEntity)
                .collect(Collectors.toList());
    }

    private List<ApplicationUrl> toApplicationUrlList(List<ApplicationUrlDto> dtos) {
        if (dtos == null) {
            return new ArrayList<>();
        }
        return dtos.stream()
                .map(dto -> new ApplicationUrl(dto.getUrl(), dto.getTitle()))
                .collect(Collectors.toList());
    }

    private List<ApplicationUrlDto> toApplicationUrlDtoList(List<ApplicationUrl> urls) {
        if (urls == null) {
            return List.of();
        }
        return urls.stream()
                .map(url -> ApplicationUrlDto.builder()
                        .url(url.getUrl())
                        .title(url.getTitle())
                        .build())
                .collect(Collectors.toList());
    }

    private AppOwner toAppOwner(AppOwnerDto dto) {
        if (dto == null) {
            return null;
        }
        return new AppOwner(dto.getFullName(), dto.getEmail());
    }

    private AppOwnerDto toAppOwnerDto(AppOwner owner) {
        if (owner == null) {
            return null;
        }
        return AppOwnerDto.builder()
                .fullName(owner.getFullName())
                .email(owner.getEmail())
                .build();
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    public List<ApplicationCommentDto> addComment(String applicationId, AddCommentRequest request, String username) {
        return addComment(applicationId, request, username, null);
    }

    public List<ApplicationCommentDto> addComment(String applicationId, AddCommentRequest request, String username,
                                                  org.springframework.security.core.Authentication authentication) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + applicationId));
        accessScopeService.checkApplicationAccess(authentication, application);

        String displayName = userRepository.findByUsername(username)
                .map(this::buildDisplayName)
                .orElse(username);

        ApplicationComment comment = ApplicationComment.builder()
                .id(UUID.randomUUID().toString())
                .authorId(username)
                .authorName(displayName)
                .content(request.getContent())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        if (application.getComments() == null) application.setComments(new ArrayList<>());
        application.getComments().add(comment);
        application.setUpdatedAt(LocalDateTime.now());
        applicationRepository.save(application);

        // Queue @mention notifications — applicationId for navigation, commentId for per-comment dedup
        String contextLink = "/applications/" + applicationId + "/edit?comment=" + comment.getId();
        mentionQueueService.queueMentions(request.getContent(), contextLink, username,
                MentionTarget.application(applicationId, application.getName()));

        // Being named on a thread, or speaking on it, puts you on it. Without the second,
        // a thread is one-way: the person mentioned is subscribed but the person who
        // mentioned them is not, so they never see the reply.
        List<String> added = new ArrayList<>(mentionQueueService.extractMentions(request.getContent()));
        added.add(username);
        if (addSubscribers(application, added)) {
            applicationRepository.save(application);
        }

        notifySubscribers(application, comment, username, contextLink);

        return application.getComments().stream()
                .map(ApplicationCommentDto::fromEntity)
                .collect(Collectors.toList());
    }

    // ── Thread subscribers ────────────────────────────────────────────────────

    /** Everyone following this application's discussion. */
    public List<String> getSubscribers(String applicationId,
                                       org.springframework.security.core.Authentication authentication) {
        Application application = getApplicationForThread(applicationId, authentication);
        return application.getSubscribers() == null ? List.of() : List.copyOf(application.getSubscribers());
    }

    public List<String> addSubscriber(String applicationId, String subscriberUsername,
                                      org.springframework.security.core.Authentication authentication) {
        Application application = getApplicationForThread(applicationId, authentication);
        if (addSubscribers(application, List.of(subscriberUsername))) {
            application.setUpdatedAt(LocalDateTime.now());
            applicationRepository.save(application);
        }
        return List.copyOf(application.getSubscribers());
    }

    public List<String> removeSubscriber(String applicationId, String subscriberUsername,
                                         org.springframework.security.core.Authentication authentication) {
        Application application = getApplicationForThread(applicationId, authentication);
        if (application.getSubscribers() != null && application.getSubscribers().remove(subscriberUsername)) {
            application.setUpdatedAt(LocalDateTime.now());
            applicationRepository.save(application);
        }
        return application.getSubscribers() == null ? List.of() : List.copyOf(application.getSubscribers());
    }

    private Application getApplicationForThread(String applicationId,
                                                org.springframework.security.core.Authentication authentication) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + applicationId));
        accessScopeService.checkApplicationAccess(authentication, application);
        return application;
    }

    /** @return true when the list actually changed, so callers can skip a pointless save. */
    private boolean addSubscribers(Application application, List<String> usernames) {
        if (application.getSubscribers() == null) application.setSubscribers(new ArrayList<>());
        boolean changed = false;
        for (String username : usernames) {
            if (username == null || username.isBlank()) continue;
            if (!application.getSubscribers().contains(username)) {
                application.getSubscribers().add(username);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Notifies everyone following the thread, in app and by email, except whoever wrote the
     * comment. Mentioned users are skipped: they are about to get the richer mention
     * notification, and two notifications for one comment reads as a bug.
     */
    private void notifySubscribers(Application application, ApplicationComment comment,
                                   String authorUsername, String contextLink) {
        if (application.getSubscribers() == null || application.getSubscribers().isEmpty()) return;

        Set<String> mentioned = new HashSet<>(mentionQueueService.extractMentions(comment.getContent()));
        String authorName = comment.getAuthorName() != null ? comment.getAuthorName() : authorUsername;

        for (String subscriber : application.getSubscribers()) {
            if (subscriber.equals(authorUsername) || mentioned.contains(subscriber)) continue;
            try {
                // sendEmail=false: the thread email carries the comment body and a reply
                // token, which the generic title-and-link email cannot express.
                notificationService.send(subscriber,
                        "New comment on " + application.getName(),
                        authorName + " commented on " + application.getName(),
                        "COMMENT_ADDED", contextLink, false,
                        new NotificationContext(MentionTargetType.APPLICATION, application.getId(),
                                application.getName(), authorUsername, authorName,
                                comment.getContent()));

                threadCommentEmailSender.send(subscriber, authorName, comment.getContent(),
                        application.getName(), contextLink,
                        MentionTargetType.APPLICATION, application.getId(), null,
                        "COMMENT_ADDED");
            } catch (Exception e) {
                // One bad subscriber must not stop the rest being told.
                log.warn("Could not notify subscriber {} of a comment on application {}: {}",
                        subscriber, application.getId(), e.getMessage());
            }
        }
    }

    /**
     * Append an undeletable, system-generated message to the application's chat.
     * Content is markdown (rendered with marked on the frontend); any "{actor}"
     * token is replaced with the acting user's display name. Quietly no-ops if
     * the application doesn't exist so lifecycle hooks never fail the caller.
     */
    public void addSystemComment(String applicationId, String content, String actorUsername) {
        if (applicationId == null) return;
        applicationRepository.findById(applicationId).ifPresent(application -> {
            String displayName = userRepository.findByUsername(actorUsername)
                    .map(this::buildDisplayName)
                    .orElse(actorUsername);
            String resolvedContent = content.replace("{actor}", displayName);

            ApplicationComment comment = ApplicationComment.builder()
                    .id(UUID.randomUUID().toString())
                    .authorId(actorUsername)
                    .authorName(displayName)
                    .content(resolvedContent)
                    .systemGenerated(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            if (application.getComments() == null) application.setComments(new ArrayList<>());
            application.getComments().add(comment);
            application.setUpdatedAt(LocalDateTime.now());
            applicationRepository.save(application);
        });
    }

    public List<ApplicationCommentDto> deleteComment(String applicationId, String commentId, String username) {
        return deleteComment(applicationId, commentId, username, null);
    }

    public List<ApplicationCommentDto> deleteComment(String applicationId, String commentId, String username,
                                                     org.springframework.security.core.Authentication authentication) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + applicationId));
        accessScopeService.checkApplicationAccess(authentication, application);

        if (application.getComments() == null) return List.of();

        ApplicationComment target = application.getComments().stream()
                .filter(c -> commentId.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found: " + commentId));

        if (target.isSystemGenerated()) {
            throw new IllegalArgumentException("System-generated comments cannot be deleted");
        }

        boolean isAuthor = username.equals(target.getAuthorId());
        if (!isAuthor) {
            throw new IllegalArgumentException("You can only delete your own comments");
        }

        application.getComments().remove(target);
        application.setUpdatedAt(LocalDateTime.now());
        applicationRepository.save(application);

        return application.getComments().stream()
                .map(ApplicationCommentDto::fromEntity)
                .collect(Collectors.toList());
    }
}
