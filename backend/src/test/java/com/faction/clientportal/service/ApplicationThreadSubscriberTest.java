package com.faction.clientportal.service;

import com.faction.clientportal.dto.AddCommentRequest;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.email.ThreadCommentEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thread membership on an application, mirroring the vulnerability side. Everyone on the
 * list is notified and emailed on a new comment, and can reply to that email.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplicationThreadSubscriberTest {

    private static final String MENTION_HTML =
            "<p>ping <span class=\"mention\" data-username=\"alice\">@alice</span></p>";

    @Mock private ApplicationRepository applicationRepository;
    @Mock private UserRepository userRepository;
    @Mock private AccessScopeService accessScopeService;
    @Mock private MentionQueueService mentionQueueService;
    @Mock private NotificationService notificationService;
    @Mock private ThreadCommentEmailSender threadCommentEmailSender;

    @InjectMocks private ApplicationService service;

    private Application application;

    @BeforeEach
    void setUp() {
        application = Application.builder()
                .id("app-1")
                .name("Payments API")
                .comments(new ArrayList<>())
                .subscribers(new ArrayList<>())
                .build();

        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(application));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        // The real extractor, so auto-subscribe is exercised against real editor markup.
        when(mentionQueueService.extractMentions(any())).thenAnswer(i -> {
            String html = i.getArgument(0);
            return html != null && html.contains("data-username=\"alice\"") ? List.of("alice") : List.of();
        });
    }

    @Test
    void assigningAndLeavingAreVisibleInTheList() {
        assertThat(service.addSubscriber("app-1", "alice", null)).containsExactly("alice");
        assertThat(service.addSubscriber("app-1", "bob", null)).containsExactly("alice", "bob");
        assertThat(service.getSubscribers("app-1", null)).containsExactly("alice", "bob");
        assertThat(service.removeSubscriber("app-1", "alice", null)).containsExactly("bob");
    }

    @Test
    void addingSomeoneTwiceDoesNotDuplicateThem() {
        service.addSubscriber("app-1", "alice", null);
        assertThat(service.addSubscriber("app-1", "alice", null)).containsExactly("alice");
    }

    @Test
    void mentioningSomeoneAndCommentingBothSubscribe() {
        // Without subscribing the author too, the thread is one-way: the mentioned person
        // would be on it but whoever mentioned them would not, so they would never see the
        // reply.
        service.addComment("app-1", new AddCommentRequest(MENTION_HTML), "bob");

        assertThat(application.getSubscribers()).containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void aNewCommentNotifiesFollowersButNotTheAuthor() {
        application.getSubscribers().addAll(List.of("carol", "bob"));

        service.addComment("app-1", new AddCommentRequest("<p>status update</p>"), "bob");

        verify(notificationService).send(eq("carol"), any(), any(), eq("COMMENT_ADDED"), any(),
                eq(false), any());
        verify(notificationService, never())
                .send(eq("bob"), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void theNotificationCarriesTheApplicationAndTheCommentItself() {
        // What the mentions dashboard groups and renders by — without it every row reads
        // "someone commented" with no way to tell one thread from another.
        application.getSubscribers().add("carol");

        service.addComment("app-1", new AddCommentRequest("<p>status <b>update</b></p>"), "bob");

        ArgumentCaptor<NotificationContext> context = ArgumentCaptor.forClass(NotificationContext.class);
        verify(notificationService).send(eq("carol"), any(), any(), eq("COMMENT_ADDED"), any(),
                eq(false), context.capture());

        assertThat(context.getValue().targetType()).isEqualTo(MentionTargetType.APPLICATION);
        assertThat(context.getValue().targetId()).isEqualTo("app-1");
        assertThat(context.getValue().targetName()).isEqualTo("Payments API");
        assertThat(context.getValue().actorUsername()).isEqualTo("bob");
        assertThat(context.getValue().contentHtml()).isEqualTo("<p>status <b>update</b></p>");
    }

    @Test
    void followersGetTheThreadEmailAddressedToThisApplication() {
        application.getSubscribers().add("carol");

        service.addComment("app-1", new AddCommentRequest("<p>status update</p>"), "bob");

        verify(threadCommentEmailSender).send(eq("carol"), any(), eq("<p>status update</p>"),
                eq("Payments API"), any(), eq(MentionTargetType.APPLICATION), eq("app-1"),
                eq(null), eq("COMMENT_ADDED"));
    }

    @Test
    void aMentionedFollowerIsNotEmailedTwice() {
        // They are about to receive the richer mention email; two emails for one comment
        // reads as a bug.
        application.getSubscribers().addAll(List.of("alice", "carol"));

        service.addComment("app-1", new AddCommentRequest(MENTION_HTML), "bob");

        verify(threadCommentEmailSender, never()).send(eq("alice"), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(threadCommentEmailSender).send(eq("carol"), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void anEmptyThreadNotifiesNobody() {
        service.addComment("app-1", new AddCommentRequest("<p>talking to myself</p>"), "bob");

        // Only the author, who is never notified of their own comment.
        assertThat(application.getSubscribers()).containsExactly("bob");
        verify(threadCommentEmailSender, never()).send(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void accessIsCheckedBeforeMembershipCanBeRead() {
        service.getSubscribers("app-1", null);
        verify(accessScopeService).checkApplicationAccess(null, application);
    }
}
