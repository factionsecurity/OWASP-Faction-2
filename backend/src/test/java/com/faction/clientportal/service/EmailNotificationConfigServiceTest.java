package com.faction.clientportal.service;

import com.faction.clientportal.dto.EmailNotificationConfigDto;
import com.faction.clientportal.dto.UpdateEmailNotificationConfigRequest;
import com.faction.clientportal.model.AssessmentWorkflowConfig.RemediationStage;
import com.faction.clientportal.model.EmailNotificationAudience;
import com.faction.clientportal.model.EmailNotificationConfig;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.repository.EmailNotificationConfigRepository;
import com.faction.clientportal.service.email.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailNotificationConfigServiceTest {

    @Mock private EmailNotificationConfigRepository repository;
    @Mock private AssessmentWorkflowConfigService workflowConfigService;
    @Mock private EmailService emailService;

    @InjectMocks private EmailNotificationConfigService service;

    private EmailNotificationConfig stored;

    @BeforeEach
    void setUp() {
        stored = EmailNotificationConfig.builder().id(EmailNotificationConfig.SINGLETON_ID).build();
        when(repository.findById(EmailNotificationConfig.SINGLETON_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(EmailNotificationConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(workflowConfigService.remediationStages()).thenReturn(List.of(
                new RemediationStage("development", "Development"),
                new RemediationStage("production", "Production")));
        when(emailService.isConfigured()).thenReturn(true);
    }

    private UpdateEmailNotificationConfigRequest.EventUpdate update(String key) {
        UpdateEmailNotificationConfigRequest.EventUpdate u =
                new UpdateEmailNotificationConfigRequest.EventUpdate();
        u.setKey(key);
        return u;
    }

    @Test
    void everySwitchDefaultsOffSoAnUpgradeNeverStartsMailingStakeholders() {
        EmailNotificationConfigDto dto = service.getConfig();

        assertThat(dto.isEnabled()).isFalse();
        assertThat(dto.getEvents()).isNotEmpty();
        assertThat(dto.getEvents()).allSatisfy(e -> {
            assertThat(e.isNotifyAssessors()).isFalse();
            assertThat(e.isNotifyStakeholders()).isFalse();
            assertThat(e.isNotifyAppOwner()).isFalse();
            assertThat(e.isIncludeMentionedUsers()).isFalse();
            assertThat(e.isNotifyOrgUsers()).isFalse();
        });
    }

    @Test
    void closedEventIsExpandedOncePerRemediationStage() {
        List<EmailNotificationConfigDto.EventDto> closed = service.getConfig().getEvents().stream()
                .filter(e -> EmailNotificationEvent.VULNERABILITY_CLOSED.name().equals(e.getEvent()))
                .toList();

        assertThat(closed).hasSize(2);
        assertThat(closed).extracting(EmailNotificationConfigDto.EventDto::getKey)
                .containsExactly("VULNERABILITY_CLOSED:development", "VULNERABILITY_CLOSED:production");
        assertThat(closed).extracting(EmailNotificationConfigDto.EventDto::getLabel)
                .containsExactly("Vulnerability closed in Development",
                                 "Vulnerability closed in Production");
        assertThat(closed).allSatisfy(e -> assertThat(e.isPerStage()).isTrue());
    }

    @Test
    void eachEventOffersOnlyTheAudiencesThatMeanSomethingForIt() {
        List<EmailNotificationConfigDto.EventDto> events = service.getConfig().getEvents();

        EmailNotificationConfigDto.EventDto created = events.stream()
                .filter(e -> "ASSESSMENT_CREATED".equals(e.getKey())).findFirst().orElseThrow();
        assertThat(created.getAudiences()).containsExactly(
                EmailNotificationAudience.ASSESSORS,
                EmailNotificationAudience.STAKEHOLDERS,
                EmailNotificationAudience.APP_OWNER,
                EmailNotificationAudience.ORG_USERS);

        EmailNotificationConfigDto.EventDto pastDue = events.stream()
                .filter(e -> "VULNERABILITY_PAST_DUE".equals(e.getKey())).findFirst().orElseThrow();
        assertThat(pastDue.getAudiences()).containsExactly(
                EmailNotificationAudience.STAKEHOLDERS,
                EmailNotificationAudience.APP_OWNER,
                EmailNotificationAudience.MENTIONED_USERS,
                EmailNotificationAudience.ORG_USERS);
    }

    @Test
    void aPartialUpdateLeavesEverythingItDidNotMention() {
        UpdateEmailNotificationConfigRequest first = new UpdateEmailNotificationConfigRequest();
        first.setEnabled(true);
        UpdateEmailNotificationConfigRequest.EventUpdate on = update("VULNERABILITY_PAST_DUE");
        on.setNotifyStakeholders(true);
        on.setCustomMessage("Please schedule remediation.");
        first.setEvents(List.of(on));
        service.updateConfig(first);

        // A second save touching only the app-owner switch must not clear the wording.
        UpdateEmailNotificationConfigRequest second = new UpdateEmailNotificationConfigRequest();
        UpdateEmailNotificationConfigRequest.EventUpdate owner = update("VULNERABILITY_PAST_DUE");
        owner.setNotifyAppOwner(true);
        second.setEvents(List.of(owner));

        EmailNotificationConfigDto dto = service.updateConfig(second);
        EmailNotificationConfigDto.EventDto pastDue = dto.getEvents().stream()
                .filter(e -> "VULNERABILITY_PAST_DUE".equals(e.getKey())).findFirst().orElseThrow();

        assertThat(dto.isEnabled()).isTrue();
        assertThat(pastDue.isNotifyStakeholders()).isTrue();
        assertThat(pastDue.isNotifyAppOwner()).isTrue();
        assertThat(pastDue.getCustomMessage()).isEqualTo("Please schedule remediation.");
    }

    @Test
    void anEmptyCustomMessageClearsItAndNullLeavesItAlone() {
        UpdateEmailNotificationConfigRequest set = new UpdateEmailNotificationConfigRequest();
        UpdateEmailNotificationConfigRequest.EventUpdate withMessage = update("VULNERABILITY_WARNING");
        withMessage.setCustomMessage("To avoid escalation, schedule a retest.");
        set.setEvents(List.of(withMessage));
        service.updateConfig(set);

        UpdateEmailNotificationConfigRequest untouched = new UpdateEmailNotificationConfigRequest();
        untouched.setEvents(List.of(update("VULNERABILITY_WARNING")));
        assertThat(customMessage(service.updateConfig(untouched)))
                .isEqualTo("To avoid escalation, schedule a retest.");

        UpdateEmailNotificationConfigRequest cleared = new UpdateEmailNotificationConfigRequest();
        UpdateEmailNotificationConfigRequest.EventUpdate blank = update("VULNERABILITY_WARNING");
        blank.setCustomMessage("   ");
        cleared.setEvents(List.of(blank));
        assertThat(customMessage(service.updateConfig(cleared))).isNull();
    }

    private String customMessage(EmailNotificationConfigDto dto) {
        return dto.getEvents().stream()
                .filter(e -> "VULNERABILITY_WARNING".equals(e.getKey()))
                .findFirst().orElseThrow().getCustomMessage();
    }

    @Test
    void theOrganizationAccessSwitchSavesAndEnablesTheEventOnItsOwn() {
        UpdateEmailNotificationConfigRequest request = new UpdateEmailNotificationConfigRequest();
        request.setEnabled(true);
        UpdateEmailNotificationConfigRequest.EventUpdate on = update("ASSESSMENT_COMPLETED");
        on.setNotifyOrgUsers(true);
        request.setEvents(List.of(on));

        EmailNotificationConfigDto dto = service.updateConfig(request);

        assertThat(dto.getEvents().stream()
                .filter(e -> "ASSESSMENT_COMPLETED".equals(e.getKey()))
                .findFirst().orElseThrow().isNotifyOrgUsers()).isTrue();
        // Org access alone is a real audience, so the event is live with nothing else set.
        assertThat(service.isEnabled(EmailNotificationEvent.ASSESSMENT_COMPLETED)).isTrue();
    }

    @Test
    void unknownKeysAreIgnoredRatherThanStored() {
        UpdateEmailNotificationConfigRequest request = new UpdateEmailNotificationConfigRequest();
        UpdateEmailNotificationConfigRequest.EventUpdate bogus = update("NOT_AN_EVENT");
        bogus.setNotifyStakeholders(true);
        request.setEvents(List.of(bogus));

        service.updateConfig(request);

        assertThat(stored.getEvents()).doesNotContainKey("NOT_AN_EVENT");
    }

    @Test
    void repeatIntervalIsClampedSoADigestCannotResendOnEveryRun() {
        UpdateEmailNotificationConfigRequest request = new UpdateEmailNotificationConfigRequest();
        request.setPastDueRepeatCount(-3);
        request.setPastDueRepeatIntervalDays(0);

        EmailNotificationConfigDto dto = service.updateConfig(request);

        assertThat(dto.getPastDueRepeatCount()).isZero();
        assertThat(dto.getPastDueRepeatIntervalDays()).isEqualTo(1);
    }

    @Test
    void isEnabledRequiresBothTheMasterSwitchAndAnAudience() {
        UpdateEmailNotificationConfigRequest request = new UpdateEmailNotificationConfigRequest();
        UpdateEmailNotificationConfigRequest.EventUpdate on = update("ASSESSMENT_CREATED");
        on.setNotifyStakeholders(true);
        request.setEvents(List.of(on));
        service.updateConfig(request);

        // Audience on, master switch still off.
        assertThat(service.isEnabled(EmailNotificationEvent.ASSESSMENT_CREATED)).isFalse();

        UpdateEmailNotificationConfigRequest enable = new UpdateEmailNotificationConfigRequest();
        enable.setEnabled(true);
        service.updateConfig(enable);

        assertThat(service.isEnabled(EmailNotificationEvent.ASSESSMENT_CREATED)).isTrue();
        // Master switch on, but this event has nobody selected.
        assertThat(service.isEnabled(EmailNotificationEvent.ASSESSMENT_CHANGED)).isFalse();
    }
}
