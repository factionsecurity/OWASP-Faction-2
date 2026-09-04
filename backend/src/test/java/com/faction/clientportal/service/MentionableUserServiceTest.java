package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.MentionableUserDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.ApplicationComment;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssignedUser;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who an external (portal) user may @mention: their own organization, the remediation contact,
 * and whoever is already on the thread — and never another organization's users.
 */
@SpringBootTest
@ActiveProfiles("test")
class MentionableUserServiceTest extends TestContainersConfig {

    @Autowired private MentionableUserService service;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;

    private String orgA;
    private String orgB;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        orgA = organizationRepository.save(Organization.builder().name("Acme").description("d").build()).getId();
        orgB = organizationRepository.save(Organization.builder().name("Globex").description("d").build()).getId();
    }

    // ── The organization ────────────────────────────────────────────────────────

    @Test
    void externalUser_seesTheirOwnOrganisation() {
        external("me", orgA);
        external("colleague", orgA);

        assertThat(usernames(find(null, null, null, externalAuth("me")))).containsExactly("colleague");
    }

    @Test
    void externalUser_neverSeesAnotherOrganisation() {
        external("me", orgA);
        external("colleague", orgA);
        external("rival", orgB);

        assertThat(usernames(find(null, null, null, externalAuth("me")))).containsExactly("colleague");
    }

    @Test
    void externalUser_doesNotSeeThemselves() {
        external("me", orgA);

        assertThat(find(null, null, null, externalAuth("me"))).isEmpty();
    }

    // ── Disabled vs deleted ─────────────────────────────────────────────────────
    // Two different states. Disabling is temporary — a password lockout, or an imported account
    // nobody has activated — and the person is still a colleague, so the thread can name them and
    // the notification lands when an admin lets them back in. Deleting is for someone who has
    // left, and nothing should offer them up again.

    @Test
    void aDisabledColleagueIsStillMentionable() {
        external("me", orgA);
        disable(external("locked-out", orgA));

        assertThat(usernames(find(null, null, null, externalAuth("me")))).containsExactly("locked-out");
    }

    @Test
    void aDeletedColleagueIsNot() {
        external("me", orgA);
        softDelete(external("departed", orgA));

        assertThat(find(null, null, null, externalAuth("me"))).isEmpty();
    }

    @Test
    void aDeletedUserIsNotOfferedEvenFromTheThreadTheyAreOn() {
        // The directory is not the only route in — a subscriber or remediation owner is added by
        // username, so the rule has to hold on the way out, not just on the way in.
        User me = external("me", orgA);
        User owner = softDelete(internal("departed-owner"));
        softDelete(internal("departed-subscriber"));
        internal("current-subscriber");
        String vulnId = vulnerabilityOn(ownedApp(orgA, me), owner.getId(),
                List.of("departed-subscriber", "current-subscriber"));

        assertThat(usernames(find(null, vulnId, null, externalAuth("me"))))
                .containsExactly("current-subscriber");
    }

    @Test
    void staffDoNotSeeDeletedAccountsInTheDirectoryEither() {
        internal("staff");
        internal("still-here");
        softDelete(internal("departed"));
        disable(internal("locked-out"));

        var result = usernames(find(null, null, null,
                auth("staff", Permission.USERS_READ_ALL.getPermission())));

        assertThat(result).contains("still-here", "locked-out");
        assertThat(result).doesNotContain("departed");
    }

    // ── The conversation ────────────────────────────────────────────────────────

    @Test
    void externalUser_seesTheRemediationContactAndSubscribers() {
        User me = external("me", orgA);
        User owner = internal("remediation-owner");
        internal("subscriber");
        internal("unrelated-staff");
        String vulnId = vulnerabilityOn(ownedApp(orgA, me), owner.getId(), List.of("subscriber"));

        var result = usernames(find(null, vulnId, null, externalAuth("me")));

        assertThat(result).containsExactlyInAnyOrder("remediation-owner", "subscriber");
        assertThat(result).doesNotContain("unrelated-staff");
    }

    @Test
    void externalUser_seesTheThreadAheadOfTheDirectory() {
        User me = external("me", orgA);
        User owner = internal("remediation-owner");
        external("colleague", orgA);
        String vulnId = vulnerabilityOn(ownedApp(orgA, me), owner.getId(), List.of());

        assertThat(usernames(find(null, vulnId, null, externalAuth("me"))))
                .containsExactly("remediation-owner", "colleague");
    }

    @Test
    void externalUser_getsNothingFromAThreadTheyCannotRead() {
        external("me", orgA);
        User other = external("other", orgB);
        User owner = internal("remediation-owner");
        // The vulnerability belongs to another organization's application entirely.
        String vulnId = vulnerabilityOn(ownedApp(orgB, other), owner.getId(), List.of("remediation-owner"));

        assertThat(find(null, vulnId, null, externalAuth("me"))).isEmpty();
    }

    @Test
    void externalUser_seesWhoHasCommentedOnTheirApplication() {
        User me = external("me", orgA);
        internal("commenter");
        internal("never-commented");
        Application app = ownedApp(orgA, me);
        app.setComments(new ArrayList<>(List.of(
                ApplicationComment.builder().id("c1").authorId("commenter").authorName("Commenter")
                        .content("hi").createdAt(LocalDateTime.now()).build())));
        applicationRepository.save(app);

        var result = usernames(find(null, null, app.getId(), externalAuth("me")));

        assertThat(result).containsExactly("commenter");
        assertThat(result).doesNotContain("never-commented");
    }

    // ── Search + internal callers ───────────────────────────────────────────────

    @Test
    void externalUser_searchNarrowsTheList() {
        external("me", orgA);
        external("alice", orgA);
        external("bob", orgA);

        assertThat(usernames(find("ali", null, null, externalAuth("me")))).containsExactly("alice");
    }

    @Test
    void internalUser_withUsersReadAll_keepsTheDirectory() {
        internal("staff");
        external("client-a", orgA);
        external("client-b", orgB);

        var result = usernames(find(null, null, null,
                auth("staff", Permission.USERS_READ_ALL.getPermission())));

        // The directory is not organization-scoped for staff — that is the point of :all.
        assertThat(result).contains("client-a", "client-b");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private List<MentionableUserDto> find(String search, String vulnId, String appId, Authentication auth) {
        return service.find(search, vulnId, appId, auth);
    }

    private List<String> usernames(List<MentionableUserDto> users) {
        return users.stream().map(MentionableUserDto::username).toList();
    }

    private User external(String username, String orgId) {
        return userRepository.save(baseUser(username).organizationId(orgId).isInternal(false).build());
    }

    private User internal(String username) {
        return userRepository.save(baseUser(username).isInternal(true).build());
    }

    /** Temporarily off — a lockout or an unactivated import. Still a colleague. */
    private User disable(User user) {
        user.setDisabledAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    /** Gone from the company. */
    private User softDelete(User user) {
        user.setDeletedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private User.UserBuilder baseUser(String username) {
        return User.builder()
                .username(username).firstName(username).lastName("User").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE)
                .failedLoginAttempts(0).createdAt(LocalDateTime.now());
    }

    private Application ownedApp(String orgId, User owner) {
        return applicationRepository.save(Application.builder()
                .name("App-" + orgId + "-" + owner.getUsername()).organizationId(orgId)
                .assignedUsers(List.of(AssignedUser.builder().userId(owner.getId()).accessLevel("WRITE").build()))
                .build());
    }

    private String vulnerabilityOn(Application app, String remediationOwnerId, List<String> subscribers) {
        String assessmentId = assessmentRepository.save(Assessment.builder()
                .name("A").applicationId(app.getId()).organizationId(app.getOrganizationId())
                .assessmentTypeId("t").status("IN_PROGRESS").createdAt(LocalDateTime.now())
                .build()).getId();
        return vulnerabilityRepository.save(Vulnerability.builder()
                .name("v").assessmentId(assessmentId).severity(VulnerabilitySeverity.HIGH).order(0)
                .status("Open").openedAt(LocalDateTime.now())
                .remediationOwnerId(remediationOwnerId)
                .subscribers(new ArrayList<>(subscribers))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build()).getId();
    }

    /** A portal user's authorities: the App Owner role's owned-scope grants. */
    private Authentication externalAuth(String username) {
        return auth(username,
                Permission.ASSESSMENTS_READ_OWNED.getPermission(),
                Permission.VULNERABILITIES_READ_OWNED.getPermission(),
                Permission.APPLICATIONS_READ_OWNED.getPermission(),
                Permission.ORGANIZATIONS_READ_OWNED.getPermission());
    }

    private Authentication auth(String username, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new UsernamePasswordAuthenticationToken(username, null, granted);
    }
}
