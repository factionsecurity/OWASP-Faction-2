package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailReplyToken;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.repository.EmailReplyTokenRepository;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Removes someone from a thread using the token from an email they received.
 *
 * <p>Reuses the reply token rather than minting a second kind: it is already unguessable
 * and already bound to exactly one recipient and one thread, which is precisely the
 * authority needed. Possession of the email is the proof — no login, because someone who
 * wants to stop receiving mail should not have to sign in to say so.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailUnsubscribeService {

    public record Result(boolean success, String message) {}

    private final EmailReplyTokenRepository tokenRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final ApplicationRepository applicationRepository;

    /**
     * Idempotent by design: unsubscribing twice, or with a token for a thread you already
     * left, reports success. A "that didn't work" page for someone who is already
     * unsubscribed is worse than useless.
     */
    @Transactional
    public Result unsubscribe(String token) {
        if (token == null || token.isBlank()) {
            return new Result(false, "This unsubscribe link is not valid.");
        }

        Optional<EmailReplyToken> found = tokenRepository.findById(token);
        if (found.isEmpty()) {
            return new Result(false, "This unsubscribe link is not valid or has already expired.");
        }
        EmailReplyToken replyToken = found.get();

        // Deliberately not checking expiry: an old email is still proof of receipt, and a
        // stale link should still get someone off a list they no longer want to be on.
        return switch (replyToken.getTargetType()) {
            case VULNERABILITY -> unsubscribeFromVulnerability(replyToken);
            case APPLICATION -> unsubscribeFromApplication(replyToken);
            // A note is a document body, not a thread, so there is no membership to leave.
            case NOTEBOOK -> new Result(false,
                    "This conversation does not have an email subscription to remove.");
        };
    }

    private Result unsubscribeFromApplication(EmailReplyToken token) {
        Application application = applicationRepository.findById(token.getTargetId()).orElse(null);
        if (application == null) {
            return new Result(true, "You will not receive any more emails about this item.");
        }

        String username = token.getRecipientUsername();
        if (application.getSubscribers() != null && application.getSubscribers().remove(username)) {
            applicationRepository.save(application);
            log.info("{} unsubscribed from application {} by email link", username, application.getId());
        }

        // Revoke so the same link cannot later post a reply into a thread just left.
        token.setRevoked(true);
        tokenRepository.save(token);

        return new Result(true, "You have been removed from the conversation on \""
                + application.getName() + "\" and will not receive any more emails about it.");
    }

    private Result unsubscribeFromVulnerability(EmailReplyToken token) {
        Vulnerability vuln = vulnerabilityRepository.findById(token.getTargetId()).orElse(null);
        if (vuln == null) {
            return new Result(true, "You will not receive any more emails about this item.");
        }

        String username = token.getRecipientUsername();
        if (vuln.getSubscribers() != null && vuln.getSubscribers().remove(username)) {
            vulnerabilityRepository.save(vuln);
            log.info("{} unsubscribed from vulnerability {} by email link", username, vuln.getId());
        }

        // Revoke so the same link cannot later be used to post a reply into a thread the
        // person has just left.
        token.setRevoked(true);
        tokenRepository.save(token);

        return new Result(true, "You have been removed from the conversation on \""
                + vuln.getName() + "\" and will not receive any more emails about it.");
    }
}
