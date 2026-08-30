package com.faction.clientportal.service.email;

import org.springframework.stereotype.Service;

/**
 * The open source answer: nowhere to reply.
 *
 * <p>Senders degrade to a plain notification with an unsubscribe link and no reply-to
 * address, which is exactly the behaviour an enterprise install shows when an admin has
 * not configured a mailbox — so this is a well-trodden path, not a special case.
 */
@Service
public class NoReplyMailboxProvider implements ReplyMailboxProvider {

    @Override
    public String replyMailbox() {
        return null;
    }
}
