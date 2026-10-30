package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailReplyToken;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Emails a new comment to the people following a thread.
 *
 * <p>The counterpart to {@link MentionEmailSender}: that one fires when someone is named,
 * this one fires for everyone on the subscriber list, so a conversation continues without
 * needing an @mention in every message. Each recipient gets their own reply token, so any
 * of them can answer from their inbox.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreadCommentEmailSender {

    private static final int MAX_BODY_CHARS = 4000;

    /** Same narrow policy as the mention email: comment HTML is never sanitised on write. */
    private static final PolicyFactory EMAIL_BODY_POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "div", "ul", "ol", "li", "blockquote", "pre",
                           "b", "strong", "i", "em", "u", "s", "code", "span")
            .allowElements("a")
            .allowUrlProtocols("https", "http", "mailto")
            .allowAttributes("href").onElements("a")
            .requireRelNofollowOnLinks()
            .toFactory();

    private final EmailService emailService;
    private final ReplyTokenService replyTokens;
    private final UserRepository userRepository;
    private final NotificationPreferenceService preferenceService;
    private final EmailThread emailThread;

    /**
     * @param subject       the item being discussed, e.g. the vulnerability name; the
     *                      email's subject line is built from it by {@link EmailThread}
     * @param notificationType drives the per-category opt-out check
     */
    @Async("mailExecutor")
    public void send(String recipientUsername, String authorName, String commentHtml,
                     String subject, String contextLink,
                     MentionTargetType targetType, String targetId, String assessmentId,
                     String notificationType) {

        Optional<User> recipient = userRepository.findByUsername(recipientUsername);
        if (recipient.isEmpty()) return;

        String to = recipient.get().getEmail();
        if (to == null || to.isBlank()) {
            log.info("No email address for {} — thread comment delivered in-app only", recipientUsername);
            return;
        }
        if (!emailService.isConfigured()) return;
        if (!preferenceService.isEmailEnabled(recipientUsername, notificationType)) {
            log.debug("{} has muted {} email — thread comment delivered in-app only",
                    recipientUsername, notificationType);
            return;
        }

        String replyMailbox = replyTokens.replyMailbox();
        boolean replyable = replyTokens.canReply(targetType, targetId, replyMailbox);

        // A token is issued even when replies cannot be received: it is also what the
        // unsubscribe link is built from, and every email about a thread has to offer a
        // way off it, whether or not reply-by-email happens to be configured.
        EmailReplyToken token = replyTokens.issue(
                targetType, targetId, assessmentId, recipientUsername, contextLink);
        String unsubscribeUrl = emailService.unsubscribeUrl(token.getId());

        String footer = emailService.unsubscribeFooter(unsubscribeUrl, subject);

        EmailMessage message = EmailMessage.builder()
                .to(to)
                .subject(emailThread.subject(targetType, subject, assessmentId))
                .htmlBody(emailService.renderShell(
                        "New comment on " + subject,
                        body(authorName, commentHtml, replyable),
                        "View in Faction",
                        contextLink,
                        footer))
                .replyTo(replyable ? replyTokens.replyToAddress(replyMailbox, token.getId()) : null)
                .unsubscribeUrl(unsubscribeUrl)
                .threadId(EmailThread.threadId(targetType, targetId))
                .build();

        replyTokens.settle(token, emailService.send(message).orElse(null));
    }

    /**
     * The same comment email for an address with no Faction account — an app owner recorded
     * only as a name and email. No reply token, so no reply-by-email and no unsubscribe link:
     * both are keyed to an account. Marked auto-submitted, like the other event email that
     * reaches outside addresses.
     */
    @Async("mailExecutor")
    public void sendToAddress(String to, String authorName, String commentHtml,
                              String subject, String contextLink,
                              MentionTargetType targetType, String targetId, String assessmentId) {
        if (to == null || to.isBlank() || !emailService.isConfigured()) return;

        emailService.send(EmailMessage.builder()
                .to(to)
                .subject(emailThread.subject(targetType, subject, assessmentId))
                .htmlBody(emailService.renderShell(
                        "New comment on " + subject,
                        body(authorName, commentHtml, false),
                        "View in Faction",
                        contextLink,
                        null))
                .threadId(EmailThread.threadId(targetType, targetId))
                .autoSubmitted(true)
                .build());
    }

    private String body(String authorName, String commentHtml, boolean replyable) {
        StringBuilder sb = new StringBuilder();

        if (replyable) {
            // Top, not bottom: a reply quotes this whole email below what the person
            // typed and the parser cuts at the first sentinel, so anything of ours above
            // it survives into their comment.
            sb.append("<div style=\"margin:0 0 16px;color:#64748b;font-size:12px\">")
              .append(EmailService.escapeHtml(MentionEmailSender.REPLY_SENTINEL))
              .append("</div>");
        }

        sb.append(emailService.paragraph(authorName + " commented:"));

        String quoted = sanitize(commentHtml);
        if (!quoted.isBlank()) {
            sb.append("<div style=\"margin:0 0 20px;padding:12px 16px;background:#0f172a;")
              .append("border-left:3px solid #7c3aed;border-radius:4px;color:#cbd5e1;")
              .append("font-size:14px;line-height:1.6\">")
              .append(quoted)
              .append("</div>");
        }
        return sb.toString();
    }

    private String sanitize(String html) {
        if (html == null || html.isBlank()) return "";
        String safe = EMAIL_BODY_POLICY.sanitize(html);
        if (safe.length() > MAX_BODY_CHARS) {
            safe = EMAIL_BODY_POLICY.sanitize(safe.substring(0, MAX_BODY_CHARS)) + "…";
        }
        return safe;
    }
}
