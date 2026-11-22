package com.faction.clientportal.service;

import com.faction.clientportal.dto.ApplicationDto;
import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.dto.AssessmentTypeDto;
import com.faction.clientportal.dto.GlobalSearchResultDto;
import com.faction.clientportal.dto.GlobalSearchResultDto.Group;
import com.faction.clientportal.dto.GlobalSearchResultDto.Item;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.dto.VulnerabilityListDto;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Global search: one box that finds vulnerabilities, assessments, applications, users and
 * assessment types at once, for staff.
 *
 * <p>It adds no access rules of its own. Each group runs the scoped search behind that record's own
 * list page, gated by the same permissions as that page's endpoint, so search can never show a row
 * the list would not: a team-scoped tester finds their team's findings, an org-scoped manager their
 * organization's, and a kind they cannot list at all is not searched.
 *
 * <p>Client-side (external) accounts are refused outright. Their access is shaped around their own
 * applications, and the users and assessment types groups mean nothing to them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalSearchService {

    /** Shorter text matches too much of everything to be worth the queries. */
    public static final int MIN_QUERY_LENGTH = 2;
    /** Longer text is cut, so a pasted paragraph cannot turn into an expensive scan. */
    public static final int MAX_QUERY_LENGTH = 100;
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 10;

    private static final Set<String> VULNERABILITY_READ = permissions(
            Permission.VULNERABILITIES_READ_ALL, Permission.VULNERABILITIES_READ_TEAM,
            Permission.VULNERABILITIES_READ_ORG, Permission.VULNERABILITIES_READ_OWNED);
    private static final Set<String> ASSESSMENT_READ = permissions(
            Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED,
            Permission.ASSESSMENTS_READ_ORG, Permission.ASSESSMENTS_READ_OWNED);
    private static final Set<String> APPLICATION_READ = permissions(
            Permission.APPLICATIONS_READ_ALL, Permission.APPLICATIONS_READ_OWNED, Permission.APPLICATIONS_READ_ORG);
    private static final Set<String> USER_READ = permissions(
            Permission.USERS_READ_ALL, Permission.USERS_READ_TEAM);

    private final AccessScopeService accessScopeService;
    private final GlobalVulnerabilityService globalVulnerabilityService;
    private final AssessmentService assessmentService;
    private final ApplicationService applicationService;
    private final UserService userService;
    private final AssessmentTypeService assessmentTypeService;

    public GlobalSearchResultDto search(String text, Integer limit, Authentication authentication) {
        User caller = accessScopeService.currentUser(authentication)
                .orElseThrow(() -> new AccessDeniedException("Global search needs a signed-in user"));
        if (!Boolean.TRUE.equals(caller.getIsInternal())) {
            throw new AccessDeniedException("Global search is available to staff accounts only");
        }

        String query = normalize(text);
        if (query.length() < MIN_QUERY_LENGTH) {
            return new GlobalSearchResultDto(query, List.of());
        }
        List<Group> groups = new ArrayList<>();
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        Set<String> authorities = authorities(authentication);

        if (mayRead(authorities, VULNERABILITY_READ)) {
            group(groups, "vulnerabilities",
                    () -> globalVulnerabilityService.quickSearch(query, size, authentication),
                    GlobalSearchService::vulnerabilityItem);
        }
        if (mayRead(authorities, ASSESSMENT_READ)) {
            // The assessments list's own scoped search: name or application name, completed ones
            // included, newest first.
            group(groups, "assessments",
                    () -> assessmentService.searchAssessmentsAdvanced(query, null, null, null, null, null, null,
                            null, null, null, null, null, Boolean.TRUE, null, null,
                            PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "startDate")), authentication),
                    GlobalSearchService::assessmentItem);
        }
        if (mayRead(authorities, APPLICATION_READ)) {
            group(groups, "applications",
                    () -> applicationService.searchApplications(query, PageRequest.of(0, size, Sort.by("name")),
                            authentication),
                    GlobalSearchService::applicationItem);
        }
        if (mayRead(authorities, USER_READ)) {
            group(groups, "users",
                    () -> userService.searchUsersPaginated(query, PageRequest.of(0, size, Sort.by("firstName")),
                            authentication),
                    GlobalSearchService::userItem);
        }
        // Readable by any signed-in user through its own endpoint; staff is all that is checked.
        group(groups, "assessmentTypes",
                () -> assessmentTypeService.searchAssessmentTypes(query, PageRequest.of(0, size, Sort.by("name"))),
                GlobalSearchService::assessmentTypeItem);
        return new GlobalSearchResultDto(query, groups);
    }

    /**
     * Runs one group's search and adds it when it found anything. A group that fails is logged and
     * left out, so one broken source does not take the whole box down with it; a refusal is not a
     * failure and is passed on.
     */
    private <T> void group(List<Group> groups, String type, Supplier<Page<T>> search,
                           Function<T, Item> toItem) {
        Page<T> page;
        try {
            page = search.get();
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Global search could not search {}: {}", type, e.getMessage(), e);
            return;
        }
        if (page == null || page.getContent().isEmpty()) {
            return;
        }
        groups.add(Group.builder()
                .type(type)
                .total(Math.max(page.getTotalElements(), page.getContent().size()))
                .items(page.getContent().stream().map(toItem).toList())
                .build());
    }

    static String normalize(String text) {
        if (text == null) return "";
        String trimmed = text.strip().replaceAll("\\s+", " ");
        return trimmed.length() > MAX_QUERY_LENGTH ? trimmed.substring(0, MAX_QUERY_LENGTH) : trimmed;
    }

    private static Item vulnerabilityItem(VulnerabilityListDto v) {
        return Item.builder()
                .id(v.getId())
                .title(v.getName())
                .subtitle(join(v.getTrackingId(), v.getApplicationName(), v.getAssessmentName()))
                .badge(v.getSeverity() == null ? null : v.getSeverity().name())
                .assessmentId(v.getAssessmentId())
                .build();
    }

    private static Item assessmentItem(AssessmentDto a) {
        return Item.builder()
                .id(a.getId())
                .title(a.getName())
                .subtitle(join(a.getAssessmentTypeName(), a.getApplicationName()))
                .badge(a.getStatus())
                .build();
    }

    private static Item applicationItem(ApplicationDto a) {
        return Item.builder()
                .id(a.getId())
                .title(a.getName())
                .subtitle(a.getOwnerName())
                .badge(a.getAppId())
                .build();
    }

    private static Item userItem(UserDto u) {
        String name = joinWith(" ", u.getFirstName(), u.getLastName());
        return Item.builder()
                .id(u.getId())
                .title(name.isEmpty() ? u.getUsername() : name)
                .subtitle(join(u.getUsername(), u.getEmail()))
                .build();
    }

    private static Item assessmentTypeItem(AssessmentTypeDto t) {
        return Item.builder()
                .id(t.getId())
                .title(t.getName())
                .subtitle(t.getDescription())
                .build();
    }

    private static String join(String... parts) {
        return joinWith(" · ", parts);
    }

    private static String joinWith(String separator, String... parts) {
        return Stream.of(parts).filter(Objects::nonNull).map(String::trim).filter(p -> !p.isEmpty())
                .collect(Collectors.joining(separator));
    }

    private static boolean mayRead(Set<String> authorities, Set<String> any) {
        return authorities.contains(RequiresPermissionAuthorizationManager.SUPER_ADMIN)
                || any.stream().anyMatch(authorities::contains);
    }

    private static Set<String> authorities(Authentication authentication) {
        return authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private static Set<String> permissions(Permission... permissions) {
        return Stream.of(permissions).map(Permission::getPermission).collect(Collectors.toUnmodifiableSet());
    }

    /** For tests: the groups a set of authorities may search, in the order they are returned. */
    static List<String> searchableGroups(Set<String> authorities) {
        return Stream.of(
                        mayRead(authorities, VULNERABILITY_READ) ? "vulnerabilities" : null,
                        mayRead(authorities, ASSESSMENT_READ) ? "assessments" : null,
                        mayRead(authorities, APPLICATION_READ) ? "applications" : null,
                        mayRead(authorities, USER_READ) ? "users" : null,
                        "assessmentTypes")
                .filter(Objects::nonNull).toList();
    }
}
