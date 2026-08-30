package com.faction.clientportal.service.email;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.EmailNotificationAudience;
import com.faction.clientportal.model.EmailNotificationConfig.EventSettings;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.service.EmailNotificationConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventNotificationEmailSenderTest {

    @Mock private EmailNotificationConfigService configService;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private EmailService emailService;

    @InjectMocks private EventNotificationEmailSender sender;

    private Assessment assessment;

    @BeforeEach
    void setUp() {
        assessment = new Assessment();
        assessment.setId("assess-1");
        assessment.setName("Q3 Pentest");

        when(configService.isEnabled(anyString())).thenReturn(true);
        when(configService.settingsFor(anyString()))
                .thenReturn(EventSettings.builder().notifyStakeholders(true).build());
        when(emailService.isConfigured()).thenReturn(true);
        when(emailService.paragraph(anyString())).thenAnswer(i -> "<p>" + i.getArgument(0) + "</p>");
        when(emailService.renderShell(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(i -> "<html>" + i.getArgument(1) + "</html>");
        when(recipientResolver.resolve(any(), any(), any(), any())).thenReturn(List.of(
                new NotificationRecipientResolver.Recipient(
                        "sam@example.com", "Sam", EmailNotificationAudience.STAKEHOLDERS)));
    }

    private EventNotificationEmailSender.Event.EventBuilder event() {
        return EventNotificationEmailSender.Event.builder()
                .key(EmailNotificationEvent.ASSESSMENT_CREATED.key())
                .event(EmailNotificationEvent.ASSESSMENT_CREATED)
                .assessment(assessment)
                .subject("New assessment: Q3 Pentest")
                .title("Assessment created")
                .lines(List.of("A new assessment has been scheduled."));
    }

    @Test
    void sendsToEveryResolvedRecipient() {
        sender.send(event().build());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).send(captor.capture());
        assertThat(captor.getValue().getTo()).isEqualTo("sam@example.com");
        assertThat(captor.getValue().getSubject()).isEqualTo("New assessment: Q3 Pentest");
        assertThat(captor.getValue().isAutoSubmitted()).isTrue();
    }

    @Test
    void sendsNothingWhenTheEventIsSwitchedOff() {
        when(configService.isEnabled(anyString())).thenReturn(false);

        sender.send(event().build());

        verify(emailService, never()).send(any());
    }

    @Test
    void sendsNothingWhenSmtpIsNotConfigured() {
        when(emailService.isConfigured()).thenReturn(false);

        sender.send(event().build());

        verify(emailService, never()).send(any());
    }

    @Test
    void sendsNothingWhenNobodyResolves() {
        when(recipientResolver.resolve(any(), any(), any(), any())).thenReturn(List.of());

        sender.send(event().build());

        verify(emailService, never()).send(any());
    }

    @Test
    void theAdminsCustomWordingIsRendered() {
        when(configService.settingsFor(anyString())).thenReturn(EventSettings.builder()
                .notifyStakeholders(true)
                .customMessage("Please schedule remediation.")
                .build());

        sender.send(event().build());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).send(captor.capture());
        assertThat(captor.getValue().getHtmlBody()).contains("Please schedule remediation.");
    }

    @Test
    void customWordingIsEscapedRatherThanInjected() {
        String html = sender.customMessage("<script>alert(1)</script>");

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void detailRowsWithNoValueAreOmitted() {
        sender.send(event()
                .details(List.of(
                        new String[]{"Status", "Testing"},
                        new String[]{"Previous status", null},
                        new String[]{"Completed", "  "}))
                .build());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).send(captor.capture());
        assertThat(captor.getValue().getHtmlBody())
                .contains("Status").contains("Testing")
                .doesNotContain("Previous status")
                .doesNotContain("Completed");
    }

    @Test
    void aFailureToSendNeverEscapesToTheCaller() {
        when(emailService.send(any())).thenThrow(new RuntimeException("smtp down"));

        sender.send(event().build()); // must not throw
    }
}
