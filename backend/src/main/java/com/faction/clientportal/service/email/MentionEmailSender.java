package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailReplyToken;
import com.faction.clientportal.model.MentionQueue;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.EmailReplyTokenRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Sends the @mention email: what was said, who said it, and — where the target is a
 * comment thread — a {@code Reply-To} that lets the recipient answer from their inbox.
 *
 * <p>Runs on the mail executor rather than the caller's thread. The caller is
 * {@code MentionQueueService.processDue()}, which sits on the single shared
 * {@code @Scheduled} pool alongside the 2-second lock sweep and the SSE heartbeat, so a
 * blocking SMTP send there would stall both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentionEmailSender {

    /**
     * Marker the inbound parser cuts at. Quote-stripping heuristics ("On <date>, X
     * wrote:", leading ">", trailing blockquote) vary by client and are guesswork; an
     * explicit sentinel makes the common case deterministic.
     *
     * <p>Worded as the instruction it replaces, rather than as machine noise. It has to sit
     * near the top — a reply quotes this whole email below what the person typed, and the
     * parser cuts at the first sentinel it sees, so anything above it survives into their
     * comment. Since something visible has to be up there anyway, it may as well be the
     * sentence telling them what to do, instead of that plus a separate row of hashes.
     *
     * <p>Kept distinctive with the leading and trailing dashes so a recipient typing a
     * similar sentence cannot accidentally truncate their own reply.
     */
    public static final String REPLY_SENTINEL =
            "-- Reply above this line and your response is added to the conversation --";

    /**
     * The original sentinel. Still matched when stripping, so replies to emails sent before
     * the wording changed are not mangled.
     */
    public static final String LEGACY_REPLY_SENTINEL =
            "##- Please type your reply above this line -##";

    private static final int TOKEN_BYTES = 24;          // 32 chars base64url
    private static final int TOKEN_TTL_DAYS = 30;
    private static final int MAX_BODY_CHARS = 4000;

    /**
     * Comment bodies are author-supplied HTML that is never sanitised on write, so it is
     * sanitised here before being embedded in an email. Deliberately narrower than the
     * report policy: basic formatting only, no tables, no images, no ids or classes.
     */
    private static final PolicyFactory EMAIL_BODY_POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "div", "ul", "ol", "li", "blockquote", "pre",
                           "b", "strong", "i", "em", "u", "s", "code", "span")
            .allowElements("a")
            .allowUrlProtocols("https", "http", "mailto")
            .allowAttributes("href").onElements("a")
            .requireRelNofollowOnLinks()
            .toFactory();

    private final EmailService emailService;
    private final EmailReplyTokenRepository replyTokenRepository;
    private final UserRepository userRepository;
    private final ReplyMailboxProvider replyMailboxProvider;
    private final NotificationPreferenceService preferenceService;

    @Async("mailExecutor")
    public void send(MentionQueue entry) {
        Optional<User> recipient = userRepository.findByUsername(entry.getMentionedUsername());
        if (recipient.isEmpty()) {
            log.warn("Mentioned user {} no longer exists — skipping email", entry.getMentionedUsername());
            return;
        }
        String to = recipient.get().getEmail();
        if (to == null || to.isBlank()) {
            log.info("No email address for {} — mention delivered in-app only", entry.getMentionedUsername());
            return;
        }
        if (!emailService.isConfigured()) {
            log.debug("Email disabled — mention for {} delivered in-app only", entry.getMentionedUsername());
            return;
        }
        // This email is sent outside NotificationService (it carries the comment body and
        // a reply token), so it does not inherit that path's preference check and has to
        // make its own — otherwise muting mention email would have no effect at all.
        if (!preferenceService.isEmailEnabled(entry.getMentionedUsername(), "MENTION")) {
            log.debug("{} has muted mention email — delivered in-app only", entry.getMentionedUsername());
            return;
        }

        String authorName = displayName(entry.getMentionedByUsername());
        // Read once per send: an admin can switch inbound email on or off at runtime, so
        // caching it in a field would leave sends deciding on stale config.
        String replyAddress = configuredReplyAddress();
        boolean replyable = canReplyByEmail(entry, replyAddress);

        // A token is issued when it has a job to do: carrying a reply, or carrying an
        // unsubscribe link. Only vulnerabilities keep a subscriber list, so offering
        // "remove me" elsewhere produced a link that errored when clicked.
        boolean unsubscribable = entry.getTargetType() != null
                && entry.getTargetType().supportsSubscribers();
        EmailReplyToken token = (replyable || unsubscribable) ? createToken(entry) : null;
        String unsubscribeUrl = (token != null && unsubscribable)
                ? emailService.unsubscribeUrl(token.getId()) : null;

        EmailMessage message = EmailMessage.builder()
                .to(to)
                .subject(subjectFor(entry, authorName))
                // Through the shared shell, like every other email: logo header, branded
                // card, and a "View in Faction" button onto the exact comment. Replying
                // is not always the right move — the thread may have moved on, or the
                // reader may want the surrounding context — so the link is always there,
                // whether or not reply-by-email is configured.
                .htmlBody(emailService.renderShell(
                        authorName + " mentioned you",
                        body(entry, replyable),
                        "View in Faction",
                        entry.getContextLink(),
                        footer(unsubscribeUrl)))
                .replyTo(replyable ? replyToAddress(replyAddress, token.getId()) : null)
                .unsubscribeUrl(unsubscribeUrl)
                .build();

        Optional<String> messageId = emailService.send(message);

        // Persist the Message-ID so an inbound In-Reply-To can resolve this thread even
        // if the provider strips sub-addressing. A failed send revokes instead: nothing
        // can arrive quoting a token whose email never left.
        if (token != null) {
            if (messageId.isPresent()) {
                token.setOutboundMessageId(messageId.get());
            } else {
                token.setRevoked(true);
            }
            replyTokenRepository.save(token);
        }
    }

    /**
     * Small print. Deliberately does not repeat the reply instruction — the sentinel above
     * the message already says it, and saying it twice is what made this email read as
     * boilerplate.
     */
    private String footer(String unsubscribeUrl) {
        if (unsubscribeUrl == null) {
            return "You are receiving this because you were mentioned in Faction.";
        }
        return "You were mentioned, so you now follow this item. "
                + "<a href=\"" + EmailService.escapeHtml(unsubscribeUrl)
                + "\" style=\"color:#94a3b8\">Remove me from this conversation</a>.";
    }

    // ── Reply addressing ──────────────────────────────────────────────────────

    /**
     * The reply mailbox, or null when reply-by-email is off. Read per send so an admin
     * toggling it takes effect without a restart, and so an open source build — where
     * nothing receives replies — simply never gets one.
     */
    private String configuredReplyAddress() {
        return replyMailboxProvider.replyMailbox();
    }

    /** Replies need somewhere to land: a configured mailbox and a thread, not a note. */
    private boolean canReplyByEmail(MentionQueue entry, String replyAddress) {
        MentionTargetType type = entry.getTargetType();
        return replyAddress != null
                && type != null && type.supportsEmailReply()
                && entry.getTargetId() != null;
    }

    private String replyToAddress(String replyAddress, String token) {
        int at = replyAddress.indexOf('@');
        return replyAddress.substring(0, at) + "+" + token + replyAddress.substring(at);
    }

    private EmailReplyToken createToken(MentionQueue entry) {
        byte[] raw = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(raw);
        // base64url without padding: safe in an email local part, unlike '+' or '/'.
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        LocalDateTime now = LocalDateTime.now();
        return replyTokenRepository.save(EmailReplyToken.builder()
                .id(id)
                .targetType(entry.getTargetType())
                .targetId(entry.getTargetId())
                .assessmentId(entry.getAssessmentId())
                .recipientUsername(entry.getMentionedUsername())
                .contextLink(entry.getContextLink())
                .createdAt(now)
                .expiresAt(now.plusDays(TOKEN_TTL_DAYS))
                .revoked(false)
                .build());
    }

    // ── Content ───────────────────────────────────────────────────────────────

    private String subjectFor(MentionQueue entry, String authorName) {
        String where = switch (entry.getTargetType() == null ? MentionTargetType.APPLICATION : entry.getTargetType()) {
            case APPLICATION -> "an application comment";
            case VULNERABILITY -> "a vulnerability comment";
            case NOTEBOOK -> "an assessment note";
        };
        return "Faction — " + authorName + " mentioned you in " + where;
    }

    /**
     * The shell already renders "{author} mentioned you" as the heading, so this does not
     * repeat it — the message itself should be the first thing read.
     */
    private String body(MentionQueue entry, boolean replyable) {
        StringBuilder sb = new StringBuilder();

        if (replyable) {
            // Near the top by necessity: a reply quotes this whole email underneath what
            // the person typed, and the parser cuts at the first sentinel, so everything of
            // ours ABOVE it survives into their comment. Only the heading is above this.
            sb.append("<div style=\"margin:0 0 16px;color:#64748b;font-size:12px\">")
              .append(EmailService.escapeHtml(REPLY_SENTINEL))
              .append("</div>");
        }

        String quoted = sanitizedBody(entry.getContentHtml());
        if (!quoted.isBlank()) {
            // The point of the whole feature: the email carries what was actually said, so
            // it can be read and answered without opening the app.
            sb.append("<div style=\"margin:0 0 20px;padding:12px 16px;background:#0f172a;")
              .append("border-left:3px solid #7c3aed;border-radius:4px;color:#cbd5e1;")
              .append("font-size:14px;line-height:1.6\">")
              .append(quoted)
              .append("</div>");
        }
        return sb.toString();
    }

    /**
     * Sanitises and length-caps the comment body. Truncation is by character count on the
     * sanitised output, so it can land mid-tag — hence the sanitiser runs first and the
     * result is closed by the surrounding div rather than trusted to be balanced.
     */
    private String sanitizedBody(String html) {
        if (html == null || html.isBlank()) return "";
        String safe = EMAIL_BODY_POLICY.sanitize(html);
        if (safe.length() > MAX_BODY_CHARS) {
            safe = EMAIL_BODY_POLICY.sanitize(safe.substring(0, MAX_BODY_CHARS)) + "…";
        }
        return safe;
    }

    private String displayName(String username) {
        return userRepository.findByUsername(username)
                .map(u -> {
                    String name = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                            + (u.getLastName() != null ? u.getLastName() : "")).trim();
                    return name.isEmpty() ? username : name;
                })
                .orElse(username);
    }
}
