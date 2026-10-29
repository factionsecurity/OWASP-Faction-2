package com.faction.clientportal.service.email;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.EmailNotificationAudience;
import com.faction.clientportal.model.EmailNotificationConfig.EventSettings;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.EmailNotificationConfigService;
import com.faction.clientportal.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentAudienceNotifierTest {

    @Mock private EmailNotificationConfigService configService;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private ThreadCommentEmailSender threadCommentEmailSender;

    @InjectMocks private CommentAudienceNotifier notifier;

    private final Application application = Application.builder().id("app-1").name("Payments API").build();
    private final CommentAudienceNotifier.Comment comment = new CommentAudienceNotifier.Comment(
            MentionTargetType.APPLICATION, "app-1", null, "Payments API",
            "bob", "Bob Author", "<p>status update</p>", "/applications/app-1/edit?comment=c-1");

    @BeforeEach
    void setUp() {
        EventSettings settings = EventSettings.defaultsFor(EmailNotificationEvent.APPLICATION_COMMENT);
        when(configService.isEnabled(EmailNotificationEvent.APPLICATION_COMMENT)).thenReturn(true);
        when(configService.settingsFor(EmailNotificationEvent.APPLICATION_COMMENT)).thenReturn(settings);
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
    }

    private void audience(NotificationRecipientResolver.Recipient... recipients) {
        when(recipientResolver.resolveCommentAudience(eq(EmailNotificationEvent.APPLICATION_COMMENT), any(),
                eq(application))).thenReturn(List.of(recipients));
    }

    private static NotificationRecipientResolver.Recipient recipient(String email, EmailNotificationAudience audience) {
        return new NotificationRecipientResolver.Recipient(email, email, audience);
    }

    private static User account(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        return user;
    }

    @Test
    void usersWithAnAccountGetTheInAppNotificationAndTheThreadEmail() {
        audience(recipient("carol@example.com", EmailNotificationAudience.ASSIGNED_USERS));
        when(userRepository.findByEmailIgnoreCase("carol@example.com"))
                .thenReturn(Optional.of(account("carol", "carol@example.com")));

        notifier.notify(EmailNotificationEvent.APPLICATION_COMMENT, application, comment, Set.of("bob"));

        verify(notificationService).send(eq("carol"), any(), any(), eq("COMMENT_ADDED"), any(),
                eq(false), any());
        verify(threadCommentEmailSender).send(eq("carol"), eq("Bob Author"), eq("<p>status update</p>"),
                eq("Payments API"), any(), eq(MentionTargetType.APPLICATION), eq("app-1"), any(),
                eq("COMMENT_ADDED"));
        verify(threadCommentEmailSender, never()).sendToAddress(any(), any(), any(), any(), any());
    }

    @Test
    void anAddressWithNoAccountGetsThePlainEmail() {
        audience(recipient("dana@example.com", EmailNotificationAudience.APP_OWNER));

        notifier.notify(EmailNotificationEvent.APPLICATION_COMMENT, application, comment, Set.of("bob"));

        verify(threadCommentEmailSender).sendToAddress(eq("dana@example.com"), eq("Bob Author"),
                eq("<p>status update</p>"), eq("Payments API"), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void someoneTheThreadAlreadyToldIsSkipped() {
        // Carol follows the thread and Bob wrote the comment: both are already handled.
        audience(recipient("carol@example.com", EmailNotificationAudience.ORG_USERS),
                recipient("bob@example.com", EmailNotificationAudience.APP_OWNER));
        when(userRepository.findByEmailIgnoreCase("carol@example.com"))
                .thenReturn(Optional.of(account("carol", "carol@example.com")));
        when(userRepository.findByEmailIgnoreCase("bob@example.com"))
                .thenReturn(Optional.of(account("bob", "bob@example.com")));

        notifier.notify(EmailNotificationEvent.APPLICATION_COMMENT, application, comment, Set.of("bob", "carol"));

        verifyNoInteractions(notificationService);
        verify(threadCommentEmailSender, never()).send(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(threadCommentEmailSender, never()).sendToAddress(any(), any(), any(), any(), any());
    }

    @Test
    void aDeletedAccountsAddressIsTreatedAsAPlainAddress() {
        User deleted = account("old", "old@example.com");
        deleted.setDeletedAt(LocalDateTime.now());
        audience(recipient("old@example.com", EmailNotificationAudience.APP_OWNER));
        when(userRepository.findByEmailIgnoreCase("old@example.com")).thenReturn(Optional.of(deleted));

        notifier.notify(EmailNotificationEvent.APPLICATION_COMMENT, application, comment, Set.of("bob"));

        verify(threadCommentEmailSender).sendToAddress(eq("old@example.com"), any(), any(), any(), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void nothingIsResolvedWhenTheEventIsOff() {
        when(configService.isEnabled(EmailNotificationEvent.APPLICATION_COMMENT)).thenReturn(false);

        notifier.notify(EmailNotificationEvent.APPLICATION_COMMENT, application, comment, Set.of("bob"));

        verifyNoInteractions(recipientResolver, notificationService, threadCommentEmailSender);
    }

    @Test
    void oneFailingRecipientDoesNotStopTheRest() {
        audience(recipient("carol@example.com", EmailNotificationAudience.ASSIGNED_USERS),
                recipient("dana@example.com", EmailNotificationAudience.APP_OWNER));
        when(userRepository.findByEmailIgnoreCase("carol@example.com"))
                .thenReturn(Optional.of(account("carol", "carol@example.com")));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(notificationService)
                .send(eq("carol"), any(), any(), any(), any(), anyBoolean(), any());

        notifier.notify(EmailNotificationEvent.APPLICATION_COMMENT, application, comment, Set.of("bob"));

        verify(threadCommentEmailSender).sendToAddress(eq("dana@example.com"), any(), any(), any(), any());
    }
}
