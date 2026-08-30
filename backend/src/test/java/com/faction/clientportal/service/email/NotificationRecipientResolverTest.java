package com.faction.clientportal.service.email;

import com.faction.clientportal.model.AppOwner;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.EmailNotificationAudience;
import com.faction.clientportal.model.EmailNotificationConfig.EventSettings;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.model.Stakeholder;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityComment;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.AccessScopeService;
import com.faction.clientportal.service.MentionQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationRecipientResolverTest {

    @Mock private UserRepository userRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private MentionQueueService mentionQueueService;
    @Mock private AccessScopeService accessScopeService;

    @InjectMocks private NotificationRecipientResolver resolver;

    private Assessment assessment;
    private Application application;

    @BeforeEach
    void setUp() {
        application = new Application();
        application.setId("app-1");
        application.setName("Payments API");
        application.setAppOwner(new AppOwner("Dana Owner", "dana@example.com"));
        application.setStakeHolders(new ArrayList<>(List.of(
                Stakeholder.builder().name("App Stakeholder").email("appstake@example.com").build())));

        assessment = new Assessment();
        assessment.setId("assess-1");
        assessment.setName("Q3 Pentest");
        assessment.setApplicationId("app-1");
        assessment.setAssessorIds(new ArrayList<>(List.of("user-1")));
        assessment.setStakeholders(new ArrayList<>(List.of(
                Stakeholder.builder().name("Sam Stakeholder").email("sam@example.com").build())));

        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(application));

        User assessor = new User();
        assessor.setId("user-1");
        assessor.setUsername("apearson");
        assessor.setFirstName("Alex");
        assessor.setLastName("Pearson");
        assessor.setEmail("alex@example.com");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(assessor));
    }

    private EventSettings all() {
        return EventSettings.builder()
                .notifyAssessors(true).notifyStakeholders(true)
                .notifyAppOwner(true).includeMentionedUsers(true)
                .build();
    }

    @Test
    void resolvesEachSwitchedOnAudience() {
        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.ASSESSMENT_CREATED,
                EventSettings.builder().notifyStakeholders(true).notifyAppOwner(true).build(),
                assessment, null);

        assertThat(recipients).extracting(NotificationRecipientResolver.Recipient::email)
                .containsExactlyInAnyOrder("sam@example.com", "appstake@example.com", "dana@example.com");
    }

    @Test
    void switchedOffAudiencesAreNotResolved() {
        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.ASSESSMENT_CREATED,
                EventSettings.builder().notifyAppOwner(true).build(),
                assessment, null);

        assertThat(recipients).extracting(NotificationRecipientResolver.Recipient::email)
                .containsExactly("dana@example.com");
    }

    @Test
    void anAudienceTheEventDoesNotOfferIsIgnoredEvenWhenStoredAsOn() {
        // RETEST_SCHEDULED has no assessor audience; a stale stored switch must not mail them.
        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.RETEST_SCHEDULED,
                EventSettings.builder().notifyAssessors(true).build(),
                assessment, null);

        assertThat(recipients).isEmpty();
    }

    @Test
    void someoneWhoIsBothStakeholderAndAppOwnerGetsOneEmail() {
        application.setAppOwner(new AppOwner("Sam Stakeholder", "SAM@example.com"));

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.ASSESSMENT_CREATED, all(), assessment, null);

        assertThat(recipients).extracting(NotificationRecipientResolver.Recipient::email)
                .filteredOn(e -> e.equalsIgnoreCase("sam@example.com"))
                .hasSize(1);
    }

    @Test
    void mentionedUsersAreCollectedAcrossTheWholeCommentThread() {
        Vulnerability vuln = Vulnerability.builder()
                .id("vuln-1")
                .comments(new ArrayList<>(List.of(
                        VulnerabilityComment.builder().content("first @jdoe").build(),
                        VulnerabilityComment.builder().content("later @kroy").build())))
                .build();

        when(mentionQueueService.extractMentions("first @jdoe")).thenReturn(List.of("jdoe"));
        when(mentionQueueService.extractMentions("later @kroy")).thenReturn(List.of("kroy"));

        User jdoe = new User();
        jdoe.setUsername("jdoe");
        jdoe.setEmail("jdoe@example.com");
        User kroy = new User();
        kroy.setUsername("kroy");
        kroy.setEmail("kroy@example.com");
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(jdoe));
        when(userRepository.findByUsername("kroy")).thenReturn(Optional.of(kroy));

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().includeMentionedUsers(true).build(),
                assessment, vuln);

        assertThat(recipients).extracting(NotificationRecipientResolver.Recipient::email)
                .containsExactlyInAnyOrder("jdoe@example.com", "kroy@example.com");
    }

    // ── Remediation owner ─────────────────────────────────────────────────────

    private Vulnerability ownedVuln() {
        User owner = new User();
        owner.setId("user-owner");
        owner.setUsername("rowner");
        owner.setFirstName("Robin");
        owner.setLastName("Owner");
        owner.setEmail("robin@example.com");
        when(userRepository.findById("user-owner")).thenReturn(Optional.of(owner));

        return Vulnerability.builder().id("vuln-1").remediationOwnerId("user-owner").build();
    }

    @Test
    void theRemediationOwnerIsCopiedEvenWithEveryAudienceSwitchedOff() {
        // Being assigned is the opt-in — an SLA breach on a finding whose owner was never
        // told is the failure the feature exists to prevent.
        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().build(),
                assessment, ownedVuln());

        assertThat(recipients).singleElement().satisfies(r -> {
            assertThat(r.email()).isEqualTo("robin@example.com");
            assertThat(r.audience()).isEqualTo(EmailNotificationAudience.REMEDIATION_OWNER);
        });
    }

    @Test
    void theRemediationOwnerKeepsThatLabelWhenTheyAreAlsoAnotherAudience() {
        Vulnerability vuln = ownedVuln();
        assessment.setStakeholders(new ArrayList<>(List.of(
                Stakeholder.builder().name("Robin Owner").email("ROBIN@example.com").build())));

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_WARNING,
                EventSettings.builder().notifyStakeholders(true).build(),
                assessment, vuln);

        assertThat(recipients)
                .filteredOn(r -> r.email().equalsIgnoreCase("robin@example.com"))
                .singleElement()
                .satisfies(r -> assertThat(r.audience())
                        .isEqualTo(EmailNotificationAudience.REMEDIATION_OWNER));
    }

    @Test
    void aDisabledRemediationOwnerIsNotMailed() {
        User owner = new User();
        owner.setId("user-gone");
        owner.setEmail("gone@example.com");
        owner.setDisabledAt(java.time.LocalDateTime.now());
        when(userRepository.findById("user-gone")).thenReturn(Optional.of(owner));

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().build(),
                assessment,
                Vulnerability.builder().id("vuln-1").remediationOwnerId("user-gone").build());

        assertThat(recipients).isEmpty();
    }

    @Test
    void assessmentLevelEventsResolveNoRemediationOwner() {
        // No vulnerability, no owner — ownership is per finding.
        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.ASSESSMENT_CREATED,
                EventSettings.builder().build(),
                assessment, null);

        assertThat(recipients).isEmpty();
    }

    @Test
    void recipientsWithoutAnAddressAreDropped() {
        assessment.setStakeholders(new ArrayList<>(List.of(
                Stakeholder.builder().name("No Address").email("  ").build())));
        application.setStakeHolders(new ArrayList<>());
        application.setAppOwner(null);

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.ASSESSMENT_CREATED,
                EventSettings.builder().notifyStakeholders(true).notifyAppOwner(true).build(),
                assessment, null);

        assertThat(recipients).isEmpty();
    }

    @Test
    void theLegacyOwnerEmailFieldIsUsedWhenThereIsNoStructuredOwner() {
        application.setAppOwner(null);
        application.setOwnerName("Legacy Owner");
        application.setOwnerEmail("legacy@example.com");

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.ASSESSMENT_CREATED,
                EventSettings.builder().notifyAppOwner(true).build(),
                assessment, null);

        assertThat(recipients).singleElement().satisfies(r -> {
            assertThat(r.email()).isEqualTo("legacy@example.com");
            assertThat(r.name()).isEqualTo("Legacy Owner");
            assertThat(r.audience()).isEqualTo(EmailNotificationAudience.APP_OWNER);
        });
    }

    // ── Organization access ───────────────────────────────────────────────────

    private User orgUser(String id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setOrganizationId("org-1");
        user.setIsInternal(false);
        return user;
    }

    private void givenOrgUsers(User... users) {
        application.setOrganizationId("org-1");
        when(userRepository
                .findByOrganizationIdAndIsInternalFalseAndDeletedAtIsNullAndDisabledAtIsNull("org-1"))
                .thenReturn(List.of(users));
    }

    @Test
    void usersWithOrganizationAccessAreResolved() {
        User customer = orgUser("u-10", "customer", "customer@example.com");
        givenOrgUsers(customer);
        when(accessScopeService.ownsApplication("u-10", application)).thenReturn(true);

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().notifyOrgUsers(true).build(),
                assessment, null);

        assertThat(recipients).singleElement().satisfies(r -> {
            assertThat(r.email()).isEqualTo("customer@example.com");
            assertThat(r.audience()).isEqualTo(EmailNotificationAudience.ORG_USERS);
        });
    }

    @Test
    void aUserRestrictedToOtherApplicationsIsNotMailed() {
        // Same organization, but their assignments do not include this application — they
        // would get a 403 on the link, so they must not be told about it.
        User restricted = orgUser("u-11", "restricted", "restricted@example.com");
        givenOrgUsers(restricted);
        when(accessScopeService.ownsApplication("u-11", application)).thenReturn(false);

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().notifyOrgUsers(true).build(),
                assessment, null);

        assertThat(recipients).isEmpty();
    }

    @Test
    void organizationAccessIsOffUnlessSwitchedOn() {
        User customer = orgUser("u-10", "customer", "customer@example.com");
        givenOrgUsers(customer);
        when(accessScopeService.ownsApplication("u-10", application)).thenReturn(true);

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().notifyAppOwner(true).build(),
                assessment, null);

        assertThat(recipients).extracting(NotificationRecipientResolver.Recipient::email)
                .containsExactly("dana@example.com");
    }

    @Test
    void everyEventOffersOrganizationAccess() {
        // The request was that it applies to each section, so no event may omit it.
        for (EmailNotificationEvent event : EmailNotificationEvent.values()) {
            assertThat(event.supports(EmailNotificationAudience.ORG_USERS))
                    .as("%s offers organization access", event)
                    .isTrue();
        }
    }

    @Test
    void someoneWithBothOrgAccessAndAStakeholderEntryGetsOneEmail() {
        User sam = orgUser("u-12", "sam", "sam@example.com"); // same address as the stakeholder
        givenOrgUsers(sam);
        when(accessScopeService.ownsApplication("u-12", application)).thenReturn(true);

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().notifyStakeholders(true).notifyOrgUsers(true).build(),
                assessment, null);

        assertThat(recipients).filteredOn(r -> r.email().equalsIgnoreCase("sam@example.com"))
                .singleElement()
                // Stakeholder is resolved first and wins the label: the more specific reason
                // is the more useful one to report.
                .satisfies(r -> assertThat(r.audience())
                        .isEqualTo(EmailNotificationAudience.STAKEHOLDERS));
    }

    @Test
    void anAccessLookupFailureSkipsThatUserRatherThanEveryone() {
        User broken = orgUser("u-13", "broken", "broken@example.com");
        User fine = orgUser("u-14", "fine", "fine@example.com");
        givenOrgUsers(broken, fine);
        when(accessScopeService.ownsApplication("u-13", application))
                .thenThrow(new RuntimeException("lookup exploded"));
        when(accessScopeService.ownsApplication("u-14", application)).thenReturn(true);

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().notifyOrgUsers(true).build(),
                assessment, null);

        assertThat(recipients).extracting(NotificationRecipientResolver.Recipient::email)
                .containsExactly("fine@example.com");
    }

    @Test
    void anExternalUserWithNoApplicationAssignmentsHearsAboutTheWholeOrganization() {
        // The org-level case: no app assignments, so ownsApplication is true for every
        // application in their organization and they get all of its alerts.
        User customer = orgUser("u-20", "customer", "customer@example.com");
        givenOrgUsers(customer);
        when(accessScopeService.ownsApplication(eq("u-20"), any())).thenReturn(true);

        for (EmailNotificationEvent event : EmailNotificationEvent.values()) {
            assertThat(resolver.resolve(event,
                    EventSettings.builder().notifyOrgUsers(true).build(), assessment, null))
                    .as("%s reaches the organization audience", event)
                    .extracting(NotificationRecipientResolver.Recipient::email)
                    .containsExactly("customer@example.com");
        }
    }

    @Test
    void anApplicationWithNoOrganizationResolvesNobody() {
        application.setOrganizationId(null);

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.VULNERABILITY_PAST_DUE,
                EventSettings.builder().notifyOrgUsers(true).build(),
                assessment, null);

        assertThat(recipients).isEmpty();
    }

    @Test
    void assessorsResolveToTheirUserAccountEmail() {
        assessment.setEngagementManagerId("user-1"); // same person twice, one email

        List<NotificationRecipientResolver.Recipient> recipients = resolver.resolve(
                EmailNotificationEvent.ASSESSMENT_CREATED,
                EventSettings.builder().notifyAssessors(true).build(),
                assessment, null);

        assertThat(recipients).singleElement().satisfies(r -> {
            assertThat(r.email()).isEqualTo("alex@example.com");
            assertThat(r.name()).isEqualTo("Alex Pearson");
        });
    }
}
