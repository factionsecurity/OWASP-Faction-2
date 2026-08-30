package com.faction.clientportal.service.email;

import lombok.Builder;
import lombok.Value;

/**
 * One outbound email, already rendered. Deliberately dumb: {@link EmailService} owns
 * transport and the HTML shell, callers own the content.
 */
@Value
@Builder
public class EmailMessage {

    String to;

    String subject;

    /** Full HTML body — build it with {@link EmailService#renderShell}. */
    String htmlBody;

    /**
     * Overrides the configured from-address for replies. Carries the mention reply
     * token ({@code faction+<token>@example.com}); null means replies go to the
     * configured from-address, which nothing reads.
     */
    String replyTo;

    /**
     * Stamps {@code Auto-Submitted: auto-replied} (RFC 3834). Set on machine-generated
     * replies such as rejection notices, so a well-behaved responder on the other end
     * does not answer back and start a loop.
     */
    boolean autoSubmitted;

    /**
     * Absolute URL for {@code List-Unsubscribe}. Clients surface it as a native
     * "unsubscribe" affordance, which is both better UX than hunting for a footer link
     * and what keeps bulk mail out of spam folders.
     */
    String unsubscribeUrl;
}
