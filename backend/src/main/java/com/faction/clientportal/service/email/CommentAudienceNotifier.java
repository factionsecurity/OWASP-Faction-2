package com.faction.clientportal.service.email;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.EmailNotificationConfig.EventSettings;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.EmailNotificationConfigService;
import com.faction.clientportal.service.NotificationContext;
import com.faction.clientportal.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Tells an application's people about a new comment — its app owner, the users assigned to it
 * and the users with access through its organization — as routed by the
 * {@link EmailNotificationEvent#APPLICATION_COMMENT} and
 * {@link EmailNotificationEvent#VULNERABILITY_COMMENT} settings.
 *
 * <p>Runs after the thread has notified its own followers and @mentions, and skips them, so
 * nobody hears about one comment twice. Someone with an account gets exactly what a follower
 * gets: the in-app notification and the reply-able thread email. An address with no account
 * behind it (an app owner recorded only by email) gets the email without reply or unsubscribe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentAudienceNotifier {

    private static final String NOTIFICATION_TYPE = "COMMENT_ADDED";

    private final EmailNotificationConfigService configService;
    private final NotificationRecipientResolver recipientResolver;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ThreadCommentEmailSender threadCommentEmailSender;

    /** The comment being announced and where it lives. */
    public record Comment(MentionTargetType targetType, String targetId, String assessmentId,
                          String subject, String authorUsername, String authorName,
                          String content, String contextLink) {}

    /**
     * @param alreadyNotified usernames the thread has already told, or chose not to: its
     *                        followers, the people @mentioned and the author
     */
    public void notify(EmailNotificationEvent event, Application application, Comment comment,
                       Set<String> alreadyNotified) {
        try {
            if (application == null || !configService.isEnabled(event)) return;
            EventSettings settings = configService.settingsFor(event);

            Set<String> told = new HashSet<>(alreadyNotified);
            for (NotificationRecipientResolver.Recipient recipient
                    : recipientResolver.resolveCommentAudience(event, settings, application)) {
                try {
                    deliver(recipient, comment, told);
                } catch (Exception e) {
                    // One bad recipient must not stop the rest being told.
                    log.warn("Could not notify {} of a comment on {}: {}",
                            recipient.email(), comment.targetId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            // The comment is already saved; telling people about it is the optional part.
            log.warn("Could not resolve the audience for a comment on {}: {}",
                    comment.targetId(), e.getMessage());
        }
    }

    private void deliver(NotificationRecipientResolver.Recipient recipient, Comment comment, Set<String> told) {
        Optional<User> account = userRepository.findByEmailIgnoreCase(recipient.email())
                .filter(u -> u.getDeletedAt() == null);

        if (account.isEmpty()) {
            threadCommentEmailSender.sendToAddress(recipient.email(), comment.authorName(),
                    comment.content(), comment.subject(), comment.contextLink(),
                    comment.targetType(), comment.targetId(), comment.assessmentId());
            return;
        }

        String username = account.get().getUsername();
        if (!told.add(username)) return;

        // sendEmail=false: the thread email carries the comment body and a reply token, which
        // the generic title-and-link email cannot express.
        notificationService.send(username,
                "New comment on " + comment.subject(),
                comment.authorName() + " commented on " + comment.subject(),
                NOTIFICATION_TYPE, comment.contextLink(), false,
                new NotificationContext(comment.targetType(), comment.targetId(), comment.subject(),
                        comment.authorUsername(), comment.authorName(), comment.content()));

        threadCommentEmailSender.send(username, comment.authorName(), comment.content(),
                comment.subject(), comment.contextLink(),
                comment.targetType(), comment.targetId(), comment.assessmentId(),
                NOTIFICATION_TYPE);
    }
}
