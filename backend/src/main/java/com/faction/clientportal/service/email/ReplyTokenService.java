package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailReplyToken;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.repository.EmailReplyTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    private static final int TOKEN_BYTES = 20;      // 160 bits, 32 chars of base32
    private static final int TOKEN_TTL_DAYS = 30;

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

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
        String id = newTokenId();

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

    /**
     * A new token id: 160 random bits as lowercase base32.
     *
     * <p>Lowercase because the id travels in an email address, and mail systems treat the
     * local part as case-insensitive — Gmail's {@code Delivered-To} arrives lower-cased.
     * The earlier base64url ids lost half their letters' meaning on that trip and stopped
     * matching; an id with no capitals has nothing to lose.
     */
    public static String newTokenId() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder(TOKEN_BYTES * 8 / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : raw) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        return sb.toString();
    }

    /**
     * The token an inbound reply names. Exact first; failing that, ignoring case, which is
     * what rescues a base64url token issued before ids went lowercase and lower-cased in
     * transit. Two old ids differing only by case would make that ambiguous, so neither is
     * returned rather than guessing.
     */
    public Optional<EmailReplyToken> find(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) return Optional.empty();
        Optional<EmailReplyToken> exact = repository.findById(tokenId);
        if (exact.isPresent()) return exact;
        List<EmailReplyToken> matches = repository.findAllByIdIgnoreCase(tokenId);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
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
