package com.faction.clientportal.service;

import com.faction.clientportal.dto.ApplicationDto;
import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.dto.AssessmentTypeDto;
import com.faction.clientportal.dto.GlobalSearchResultDto;
import com.faction.clientportal.dto.UserDto;
import com.faction.clientportal.dto.VulnerabilityListDto;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Global search's own rules: staff only, a minimum length, and each group searched only when the
 * caller holds a permission that group's list page requires. The scoping inside a group belongs to
 * the list services and is tested there (see {@link GlobalVulnerabilityListTest}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlobalSearchServiceTest {

    @Mock private AccessScopeService accessScopeService;
    @Mock private GlobalVulnerabilityService globalVulnerabilityService;
    @Mock private AssessmentService assessmentService;
    @Mock private ApplicationService applicationService;
    @Mock private UserService userService;
    @Mock private AssessmentTypeService assessmentTypeService;

    @InjectMocks private GlobalSearchService service;

    private static final String SUPER_ADMIN = RequiresPermissionAuthorizationManager.SUPER_ADMIN;

    @BeforeEach
    void setUp() {
        staff(true);
        when(globalVulnerabilityService.quickSearch(anyString(), anyInt(), any()))
                .thenReturn(page(VulnerabilityListDto.builder().id("v1").name("SQL Injection")
                        .trackingId("VULN-1").severity(VulnerabilitySeverity.HIGH).assessmentId("a1")
                        .applicationName("Payments API").assessmentName("Q3 Pentest").build()));
        when(assessmentService.searchAssessmentsAdvanced(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), eq(Boolean.TRUE), any(), any(), any(Pageable.class), any()))
                .thenReturn(page(AssessmentDto.builder().id("a1").name("Q3 Pentest").status("Testing")
                        .assessmentTypeName("Web Application Pentest").applicationName("Payments API").build()));
        when(applicationService.searchApplications(anyString(), any(Pageable.class), any()))
                .thenReturn(page(ApplicationDto.builder().id("app1").name("Payments API").appId("APP-1").build()));
        when(userService.searchUsersPaginated(anyString(), any(Pageable.class), any()))
                .thenReturn(page(UserDto.builder().id("u1").firstName("Dana").lastName("Smith")
                        .username("dsmith").email("dana@example.com").build()));
        when(assessmentTypeService.searchAssessmentTypes(anyString(), any(Pageable.class)))
                .thenReturn(page(AssessmentTypeDto.builder().id("t1").name("Web Application Pentest").build()));
    }

    @Test
    void anExternalAccountIsRefused() {
        staff(false);

        assertThatThrownBy(() -> service.search("payments", null, auth(SUPER_ADMIN)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(globalVulnerabilityService, applicationService, userService, assessmentTypeService);
    }

    @Test
    void textShorterThanTheMinimumSearchesNothing() {
        GlobalSearchResultDto result = service.search(" a ", null, auth(SUPER_ADMIN));

        assertThat(result.getGroups()).isEmpty();
        verifyNoInteractions(globalVulnerabilityService, applicationService, userService, assessmentTypeService);
    }

    @Test
    void onlyTheGroupsTheCallerCanListAreSearched() {
        GlobalSearchResultDto result = service.search("pay", null,
                auth(Permission.VULNERABILITIES_READ_TEAM.getPermission()));

        assertThat(result.getGroups()).extracting(GlobalSearchResultDto.Group::getType)
                .containsExactly("vulnerabilities", "assessmentTypes");
        verify(applicationService, never()).searchApplications(anyString(), any(Pageable.class), any());
        verify(userService, never()).searchUsersPaginated(anyString(), any(Pageable.class), any());
    }

    @Test
    void eachGroupFollowsTheListPagesPermissions() {
        assertThat(GlobalSearchService.searchableGroups(Set.of(SUPER_ADMIN))).containsExactly(
                "vulnerabilities", "assessments", "applications", "users", "assessmentTypes");
        assertThat(GlobalSearchService.searchableGroups(Set.of())).containsExactly("assessmentTypes");
        // The global vulnerabilities list does not admit the per-assessment permission.
        assertThat(GlobalSearchService.searchableGroups(
                Set.of(Permission.VULNERABILITIES_READ_ASSESSMENT.getPermission(),
                        Permission.ASSESSMENTS_READ_ASSIGNED.getPermission())))
                .containsExactly("assessments", "assessmentTypes");
        assertThat(GlobalSearchService.searchableGroups(Set.of(Permission.USERS_READ_TEAM.getPermission())))
                .containsExactly("users", "assessmentTypes");
        assertThat(GlobalSearchService.searchableGroups(Set.of(Permission.APPLICATIONS_READ_OWNED.getPermission())))
                .containsExactly("applications", "assessmentTypes");
    }

    @Test
    void itemsCarryWhatThePanelShows() {
        GlobalSearchResultDto result = service.search("pay", null, auth(SUPER_ADMIN));

        var vuln = group(result, "vulnerabilities").getItems().get(0);
        assertThat(vuln.getTitle()).isEqualTo("SQL Injection");
        assertThat(vuln.getSubtitle()).isEqualTo("VULN-1 · Payments API · Q3 Pentest");
        assertThat(vuln.getBadge()).isEqualTo("HIGH");
        assertThat(vuln.getAssessmentId()).isEqualTo("a1");

        var assessment = group(result, "assessments").getItems().get(0);
        assertThat(assessment.getSubtitle()).isEqualTo("Web Application Pentest · Payments API");
        assertThat(assessment.getBadge()).isEqualTo("Testing");

        var user = group(result, "users").getItems().get(0);
        assertThat(user.getTitle()).isEqualTo("Dana Smith");
        assertThat(user.getSubtitle()).isEqualTo("dsmith · dana@example.com");
    }

    @Test
    void theLimitIsCappedAndTheTextTrimmed() {
        service.search("  sql   injection  ", 500, auth(SUPER_ADMIN));

        verify(globalVulnerabilityService).quickSearch(eq("sql injection"), eq(GlobalSearchService.MAX_LIMIT), any());
    }

    @Test
    void aGroupThatFailsIsLeftOutRatherThanFailingTheSearch() {
        when(applicationService.searchApplications(anyString(), any(Pageable.class), any()))
                .thenThrow(new IllegalStateException("boom"));

        GlobalSearchResultDto result = service.search("pay", null, auth(SUPER_ADMIN));

        assertThat(result.getGroups()).extracting(GlobalSearchResultDto.Group::getType)
                .doesNotContain("applications").contains("vulnerabilities", "users");
    }

    @Test
    void aGroupWithNoMatchesIsLeftOut() {
        when(userService.searchUsersPaginated(anyString(), any(Pageable.class), any())).thenReturn(Page.empty());

        GlobalSearchResultDto result = service.search("pay", null, auth(SUPER_ADMIN));

        assertThat(result.getGroups()).extracting(GlobalSearchResultDto.Group::getType).doesNotContain("users");
    }

    @Test
    void veryLongTextIsCut() {
        assertThat(GlobalSearchService.normalize("x".repeat(500))).hasSize(GlobalSearchService.MAX_QUERY_LENGTH);
        assertThat(GlobalSearchService.normalize(null)).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void staff(boolean internal) {
        User user = User.builder().id("me").username("me").isInternal(internal).build();
        when(accessScopeService.currentUser(any())).thenReturn(Optional.of(user));
    }

    @SafeVarargs
    private static <T> Page<T> page(T... items) {
        return new PageImpl<>(List.of(items), PageRequest.of(0, 5), items.length);
    }

    private static GlobalSearchResultDto.Group group(GlobalSearchResultDto result, String type) {
        return result.getGroups().stream().filter(g -> g.getType().equals(type)).findFirst().orElseThrow();
    }

    private static Authentication auth(String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new UsernamePasswordAuthenticationToken("me", null, granted);
    }
}
