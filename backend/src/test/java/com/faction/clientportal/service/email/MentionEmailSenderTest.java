package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailReplyToken;
import com.faction.clientportal.model.MentionQueue;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.EmailReplyTokenRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.NotificationPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The mention email is the point of Phase 2: it has to carry what was actually said and,
 * where the target is a comment thread, a Reply-To the recipient can answer from.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MentionEmailSenderTest {

    private static final String COMMENT_HTML =
            "<p>Can you confirm the <strong>SQL injection</strong> is fixed?</p>";

    @Mock private EmailService emailService;
    @Mock private EmailReplyTokenRepository replyTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReplyMailboxProvider replyMailboxProvider;
    @Mock private NotificationPreferenceService preferenceService;

    @InjectMocks private MentionEmailSender sender;

    @BeforeEach
    void setUp() {
        replyAddressIs("faction@example.com");

        when(emailService.isConfigured()).thenReturn(true);
        when(emailService.unsubscribeUrl(any()))
                .thenAnswer(i -> "https://faction.test/unsubscribe?token=" + i.getArgument(0));
        when(preferenceService.isEmailEnabled(any(), any())).thenReturn(true);
        when(emailService.paragraph(any())).thenAnswer(i -> "<p>" + i.getArgument(0) + "</p>");
        // Compose the shell the way the real one does — title, body, CTA, footer —
        // so assertions can see the "View in Faction" link and its resolved URL.
        when(emailService.renderShell(any(), any(), any(), any(), any())).thenAnswer(i ->
                "<h2>" + i.getArgument(0) + "</h2>" + i.getArgument(1)
                        + (i.getArgument(2) == null || i.getArgument(3) == null ? ""
                           : "<a href=\"https://faction.test" + i.getArgument(3) + "\">"
                             + i.getArgument(2) + "</a>")
                        + (i.getArgument(4) == null ? "" : "<small>" + i.getArgument(4) + "</small>"));
        when(emailService.send(any())).thenReturn(Optional.of("<generated@smtp>"));
        when(replyTokenRepository.save(any(EmailReplyToken.class))).thenAnswer(i -> i.getArgument(0));

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(
                User.builder().username("alice").email("alice@example.com")
                        .firstName("Alice").lastName("Adams").build()));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(
                User.builder().username("bob").email("bob@example.com")
                        .firstName("Bob").lastName("Brown").build()));
    }

    /**
     * Points the sender at a reply mailbox, or at none when null.
     *
     * <p>Null is what the open source edition always answers, so the "notify only" cases
     * below double as coverage of that build — the sender cannot tell the difference
     * between an unlicensed install and an admin who never configured a mailbox.
     */
    private void replyAddressIs(String address) {
        when(replyMailboxProvider.replyMailbox()).thenReturn(address);
    }

    private MentionQueue entry(MentionTargetType type, String targetId, String assessmentId) {
        return MentionQueue.builder()
                .mentionedUsername("alice")
                .mentionedByUsername("bob")
                .contextLink("/vulnerabilities?vuln=v1&comment=c1")
                .contentHtml(COMMENT_HTML)
                .targetType(type)
                .targetId(targetId)
                .assessmentId(assessmentId)
                .build();
    }

    private EmailMessage captureSent() {
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).send(sent.capture());
        return sent.getValue();
    }

    @Test
    void commentMention_quotesTheCommentAndInvitesAReply() {
        sender.send(entry(MentionTargetType.VULNERABILITY, "v1", "asmt-1"));

        EmailMessage msg = captureSent();
        assertThat(msg.getTo()).isEqualTo("alice@example.com");
        assertThat(msg.getSubject()).contains("Bob Brown").contains("vulnerability comment");
        // The whole point: the body carries what was said, not just "you were mentioned".
        assertThat(msg.getHtmlBody()).contains("SQL injection");
        assertThat(msg.getHtmlBody()).contains(MentionEmailSender.REPLY_SENTINEL);
    }

    @Test
    void commentMention_replyToCarriesAPlusAddressedToken() {
        sender.send(entry(MentionTargetType.VULNERABILITY, "v1", "asmt-1"));

        ArgumentCaptor<EmailReplyToken> token = ArgumentCaptor.forClass(EmailReplyToken.class);
        verify(replyTokenRepository, org.mockito.Mockito.atLeastOnce()).save(token.capture());
        EmailReplyToken created = token.getAllValues().get(0);

        assertThat(captureSent().getReplyTo())
                .isEqualTo("faction+" + created.getId() + "@example.com");

        // Bound to one recipient and one thread — that binding is the access control.
        assertThat(created.getRecipientUsername()).isEqualTo("alice");
        assertThat(created.getTargetType()).isEqualTo(MentionTargetType.VULNERABILITY);
        assertThat(created.getTargetId()).isEqualTo("v1");
        assertThat(created.getAssessmentId()).isEqualTo("asmt-1");
        assertThat(created.isRevoked()).isFalse();
    }

    @Test
    void tokenIsUnguessableAndUrlSafe() {
        sender.send(entry(MentionTargetType.APPLICATION, "app-1", null));

        ArgumentCaptor<EmailReplyToken> token = ArgumentCaptor.forClass(EmailReplyToken.class);
        verify(replyTokenRepository, org.mockito.Mockito.atLeastOnce()).save(token.capture());
        String id = token.getAllValues().get(0).getId();

        assertThat(id).hasSize(32);
        // '+' and '/' from standard base64 would corrupt an email local part.
        assertThat(id).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void messageIdIsStored_soInReplyToCanResolveTheThread() {
        sender.send(entry(MentionTargetType.APPLICATION, "app-1", null));

        ArgumentCaptor<EmailReplyToken> token = ArgumentCaptor.forClass(EmailReplyToken.class);
        verify(replyTokenRepository, org.mockito.Mockito.atLeast(2)).save(token.capture());

        EmailReplyToken last = token.getAllValues().get(token.getAllValues().size() - 1);
        assertThat(last.getOutboundMessageId()).isEqualTo("<generated@smtp>");
        assertThat(last.isRevoked()).isFalse();
    }

    @Test
    void failedSend_revokesTheToken() {
        // Nothing can ever arrive quoting a token whose email never left, so leaving it
        // usable would keep a live bearer secret around for no benefit.
        when(emailService.send(any())).thenReturn(Optional.empty());

        sender.send(entry(MentionTargetType.APPLICATION, "app-1", null));

        ArgumentCaptor<EmailReplyToken> token = ArgumentCaptor.forClass(EmailReplyToken.class);
        verify(replyTokenRepository, org.mockito.Mockito.atLeast(2)).save(token.capture());
        assertThat(token.getAllValues().get(token.getAllValues().size() - 1).isRevoked()).isTrue();
    }

    @Test
    void everyMentionEmailLinksToTheThread() {
        // Replying is not always the right move — the reader may want the surrounding
        // context — so the link is present whether or not reply-by-email is configured.
        sender.send(entry(MentionTargetType.VULNERABILITY, "v1", "asmt-1"));

        String body = captureSent().getHtmlBody();
        assertThat(body).contains("View in Faction");
        assertThat(body).contains("/vulnerabilities?vuln=v1&comment=c1");
    }

    @Test
    void notifyOnlyMentionsStillLinkToTheThread() {
        replyAddressIs(null);

        sender.send(entry(MentionTargetType.NOTEBOOK, "node-1", null));

        String body = captureSent().getHtmlBody();
        assertThat(body).contains("View in Faction");
        assertThat(body).contains("/vulnerabilities?vuln=v1&comment=c1");
    }

    @Test
    void theSentinelPrecedesTheQuoteAndTheLink() {
        // A reply quotes this email below what the person typed and the parser cuts at
        // the first sentinel, so anything of ours above it survives into their comment.
        sender.send(entry(MentionTargetType.VULNERABILITY, "v1", "asmt-1"));

        String body = captureSent().getHtmlBody();
        int sentinel = body.indexOf(MentionEmailSender.REPLY_SENTINEL);
        assertThat(sentinel).isGreaterThan(-1);
        assertThat(sentinel).isLessThan(body.indexOf("SQL injection"));
        assertThat(sentinel).isLessThan(body.indexOf("View in Faction"));
    }

    @Test
    void notebookMention_isNotifyOnly() {
        // An assessment note is a document body, not a thread — appending an emailed
        // reply to it would be destructive, so no Reply-To and no reply invitation.
        sender.send(entry(MentionTargetType.NOTEBOOK, "node-1", null));

        EmailMessage msg = captureSent();
        assertThat(msg.getReplyTo()).isNull();
        assertThat(msg.getHtmlBody()).doesNotContain(MentionEmailSender.REPLY_SENTINEL);
        assertThat(msg.getSubject()).contains("assessment note");
        // No unsubscribe either: a note keeps no subscriber list — unlike applications and
        // vulnerabilities — so "remove me" would be a link that errors when clicked.
        assertThat(msg.getUnsubscribeUrl()).isNull();
        assertThat(msg.getHtmlBody()).doesNotContain("Remove me from this conversation");
    }

    @Test
    void noReplyAddressConfigured_fallsBackToNotifyOnly() {
        replyAddressIs(null);

        sender.send(entry(MentionTargetType.VULNERABILITY, "v1", "asmt-1"));

        EmailMessage msg = captureSent();
        assertThat(msg.getReplyTo()).isNull();
        assertThat(msg.getHtmlBody()).doesNotContain(MentionEmailSender.REPLY_SENTINEL);
        // A vulnerability does keep a subscriber list, so unsubscribing works even where
        // replying does not.
        assertThat(msg.getUnsubscribeUrl()).isNotBlank();
        assertThat(msg.getHtmlBody()).contains("Remove me from this conversation");
    }

    @Test
    void commentBodyIsSanitized() {
        MentionQueue entry = entry(MentionTargetType.APPLICATION, "app-1", null);
        entry.setContentHtml("<p>hi<script>alert(1)</script><img src=x onerror=alert(1)></p>");

        sender.send(entry);

        String body = captureSent().getHtmlBody();
        assertThat(body).doesNotContain("script").doesNotContain("onerror");
        assertThat(body).contains("hi");
    }

    @Test
    void recipientWithoutAnEmailAddress_isSkipped() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(
                User.builder().username("alice").email(null).build()));

        sender.send(entry(MentionTargetType.APPLICATION, "app-1", null));

        verify(emailService, never()).send(any());
        verify(replyTokenRepository, never()).save(any());
    }

    @Test
    void emailDisabled_createsNoToken() {
        when(emailService.isConfigured()).thenReturn(false);

        sender.send(entry(MentionTargetType.VULNERABILITY, "v1", "asmt-1"));

        verify(emailService, never()).send(any());
        verify(replyTokenRepository, never()).save(any());
    }

    @Test
    void aMutedMentionSendsNoEmail() {
        // This sender bypasses NotificationService's email path, so without its own check
        // the opt-out would appear in the UI and do nothing.
        when(preferenceService.isEmailEnabled("alice", "MENTION")).thenReturn(false);

        sender.send(entry(MentionTargetType.VULNERABILITY, "v1", "asmt-1"));

        verify(emailService, never()).send(any());
        // And no token, since nothing was sent that could be replied to.
        verify(replyTokenRepository, never()).save(any());
    }

    @Test
    void theEmailDoesNotSayTheSameThingTwice() {
        // The heading already reads "<author> mentioned you", and the sentinel already
        // explains replying. Repeating either is what made this email read as boilerplate.
        sender.send(entry(MentionTargetType.VULNERABILITY, "v1", "asmt-1"));

        String body = captureSent().getHtmlBody();

        // "mentioned you" appears once, as the heading.
        assertThat(body.split("mentioned you", -1).length - 1).isEqualTo(1);
        // The old duplicated reply paragraph is gone; only the sentinel mentions replying.
        assertThat(body).doesNotContain("Reply to this email and your response");
        assertThat(body).contains(MentionEmailSender.REPLY_SENTINEL);
    }

    @Test
    void theSentinelReadsAsAnInstructionRatherThanMarkup() {
        // It sits above the message by necessity, so it has to be worth reading.
        assertThat(MentionEmailSender.REPLY_SENTINEL).contains("Reply above this line");
        assertThat(MentionEmailSender.REPLY_SENTINEL).doesNotContain("##");
    }
}