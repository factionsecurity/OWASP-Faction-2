package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailReplyToken;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.repository.EmailReplyTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Issues the bearer tokens that make an emailed reply routable, and builds the
 * plus-addressed reply address they travel in.
 *
 * <p>Extracted so the mention email and the thread email cannot drift: the token format
 * is the entire contract with {@code InboundMailParser}, and two copies of a
 * security-relevant generator is exactly the sort of duplication that ends with one of
 * them quietly weaker than the other.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyTokenService {

    private static final int TOKEN_BYTES = 24;      // 32 chars base64url
    private static final int TOKEN_TTL_DAYS = 30;

    private final EmailReplyTokenRepository repository;
    private final ReplyMailboxProvider replyMailboxProvider;

    /**
     * The configured reply mailbox, or null when reply-by-email is off. Read per send so
     * an admin toggling it takes effect without a restart.
     */
    public String replyMailbox() {
        return replyMailboxProvider.replyMailbox();
    }

    /** True when a reply has somewhere to land: a mailbox, and a thread rather than a note. */
    public boolean canReply(MentionTargetType type, String targetId, String replyMailbox) {
        return replyMailbox != null && type != null && type.supportsEmailReply() && targetId != null;
    }

    public EmailReplyToken issue(MentionTargetType targetType, String targetId, String assessmentId,
                                 String recipientUsername, String contextLink) {
        byte[] raw = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(raw);
        // base64url without padding: '+' and '/' from standard base64 would corrupt an
        // email local part, and '+' is the sub-address separator itself.
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        LocalDateTime now = LocalDateTime.now();
        return repository.save(EmailReplyToken.builder()
                .id(id)
                .targetType(targetType)
                .targetId(targetId)
                .assessmentId(assessmentId)
                .recipientUsername(recipientUsername)
                .contextLink(contextLink)
                .createdAt(now)
                .expiresAt(now.plusDays(TOKEN_TTL_DAYS))
                .revoked(false)
                .build());
    }

    /** {@code faction@example.com} + token → {@code faction+<token>@example.com}. */
    public String replyToAddress(String replyMailbox, String token) {
        int at = replyMailbox.indexOf('@');
        return replyMailbox.substring(0, at) + "+" + token + replyMailbox.substring(at);
    }

    /**
     * Records the Message-ID the send produced, so an inbound {@code In-Reply-To} can
     * resolve the thread when a provider strips sub-addressing. A failed send revokes
     * instead: nothing can ever arrive quoting a token whose email never left, so leaving
     * it usable would keep a live bearer secret around for no benefit.
     */
    public void settle(EmailReplyToken token, String messageIdOrNull) {
        if (token == null) return;
        if (messageIdOrNull != null) {
            token.setOutboundMessageId(messageIdOrNull);
        } else {
            token.setRevoked(true);
        }
        repository.save(token);
    }
}
