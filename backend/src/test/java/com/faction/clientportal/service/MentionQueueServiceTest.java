package com.faction.clientportal.service;

import com.faction.clientportal.model.MentionQueue;
import com.faction.clientportal.model.MentionTarget;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.repository.MentionQueueRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.email.MentionEmailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MentionQueueServiceTest {

    @Mock
    private MentionQueueRepository mentionQueueRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MentionEmailSender mentionEmailSender;

    @InjectMocks
    private MentionQueueService mentionQueueService;

    // ── extractMentions ───────────────────────────────────────────────────────

    @Test
    void extractMentions_nullHtml_returnsEmptyList() {
        List<String> result = mentionQueueService.extractMentions(null);
        assertThat(result).isEmpty();
    }

    @Test
    void extractMentions_blankHtml_returnsEmptyList() {
        List<String> result = mentionQueueService.extractMentions("   ");
        assertThat(result).isEmpty();
    }

    @Test
    void extractMentions_htmlWithMentions_returnsUsernames() {
        String html = "<p>Hello <span data-username=\"alice\">@alice</span> and "
                + "<span data-username=\"bob\">@bob</span></p>";

        List<String> result = mentionQueueService.extractMentions(html);

        assertThat(result).containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void extractMentions_exactEditorMarkup_returnsUsername() {
        // Guards the coupling between the frontend and this parser: RichTextEditor's
        // insertMention() serialises a mention as exactly this span, and the two halves
        // agree on nothing else. If the editor's markup changes, this must change with it.
        String html = "<p>ping <span class=\"mention\" data-username=\"alice\" "
                + "contenteditable=\"false\">@alice</span>&nbsp;</p>";

        assertThat(mentionQueueService.extractMentions(html)).containsExactly("alice");
    }

    @Test
    void extractMentions_duplicateMention_deduplicates() {
        String html = "<p><span data-username=\"alice\">@alice</span> and again "
                + "<span data-username=\"alice\">@alice</span></p>";

        List<String> result = mentionQueueService.extractMentions(html);

        assertThat(result).containsExactly("alice");
        assertThat(result).hasSize(1);
    }

    // ── queueMentions ─────────────────────────────────────────────────────────

    @Test
    void queueMentions_selfMention_isSkipped() {
        String html = "<span data-username=\"authorUser\">@authorUser</span>";

        mentionQueueService.queueMentions(html, "/assessments/1?comment=c1", "authorUser",
                MentionTarget.application("app-1", "Payments API"));

        verify(mentionQueueRepository, never()).save(any());
    }

    @Test
    void queueMentions_recentlyQueuedForTheSameItem_isSkipped() {
        String html = "<span data-username=\"alice\">@alice</span>";
        String contextLink = "/assessments/1?comment=c1";

        when(mentionQueueRepository.existsByMentionedUsernameAndContextLinkAndCreatedAtAfter(
                eq("alice"), eq(contextLink), any()))
                .thenReturn(true);

        mentionQueueService.queueMentions(html, contextLink, "authorUser",
                MentionTarget.application("app-1", "Payments API"));

        verify(mentionQueueRepository, never()).save(any());
    }

    @Test
    void queueMentions_newMention_isSaved() {
        String html = "<span data-username=\"alice\">@alice</span>";
        String contextLink = "/assessments/1?comment=c1";

        when(mentionQueueRepository.existsByMentionedUsernameAndContextLinkAndCreatedAtAfter(
                eq("alice"), eq(contextLink), any()))
                .thenReturn(false);
        when(mentionQueueRepository.save(any(MentionQueue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mentionQueueService.queueMentions(html, contextLink, "authorUser",
                MentionTarget.application("app-1", "Payments API"));

        ArgumentCaptor<MentionQueue> captor = ArgumentCaptor.forClass(MentionQueue.class);
        verify(mentionQueueRepository).save(captor.capture());

        MentionQueue saved = captor.getValue();
        assertThat(saved.getMentionedUsername()).isEqualTo("alice");
        assertThat(saved.getMentionedByUsername()).isEqualTo("authorUser");
        assertThat(saved.getContextLink()).isEqualTo(contextLink);
        assertThat(saved.isProcessed()).isFalse();
        assertThat(saved.getScheduledFor()).isNotNull();
        // Phase 2: the row now carries what the email needs — the content to quote and
        // the target to bind a reply token to.
        assertThat(saved.getContentHtml()).isEqualTo(html);
        assertThat(saved.getTargetType()).isEqualTo(MentionTargetType.APPLICATION);
        assertThat(saved.getTargetId()).isEqualTo("app-1");
        assertThat(saved.getTargetName()).isEqualTo("Payments API");
        // Delivery is 10-25s: a 10s debounce drained on a 15s tick.
        assertThat(saved.getScheduledFor())
                .isBefore(LocalDateTime.now().plusSeconds(11))
                .isAfter(LocalDateTime.now().plusSeconds(5));
    }

    @Test
    void dedupWindowLooksAtSentEntriesToo_notJustUnsentOnes() {
        // The notebook autosaves every 1.5s with a stable contextLink. Checking only
        // unsent rows means the first autosave after delivery queues a second
        // notification — which the old 30s+60s timing hid and the shorter one would not.
        String contextLink = "/assessments/1?section=notebook&node=n1";
        String html = "<span data-username=\"alice\">@alice</span>";

        mentionQueueService.queueMentions(html, contextLink, "authorUser",
                MentionTarget.notebook("n1", "Recon notes"));

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mentionQueueRepository).existsByMentionedUsernameAndContextLinkAndCreatedAtAfter(
                eq("alice"), eq(contextLink), since.capture());

        // A window measured in minutes, so a whole editing session collapses to one.
        assertThat(since.getValue()).isBefore(LocalDateTime.now().minusMinutes(9));
    }
}
