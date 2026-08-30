package com.faction.clientportal.service.email;

/**
 * Where an emailed reply would land, if replies are received at all.
 *
 * <p>Outbound email is open source; receiving the replies is not. This is the single
 * seam between them, so the senders can ask "is there a mailbox?" without knowing that
 * inbound email exists as a feature.
 *
 * <p>Note what does <em>not</em> sit behind this seam: reply tokens themselves stay in
 * the core. They are also what every unsubscribe link is built from, and an email about
 * a thread must always offer a way off it, whether or not anyone is listening for
 * replies.
 */
public interface ReplyMailboxProvider {

    /** The configured reply mailbox, or {@code null} when replies cannot be received. */
    String replyMailbox();
}
