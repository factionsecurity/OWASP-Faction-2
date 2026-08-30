package com.faction.clientportal.service.email;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.EmailReplyToken;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.EmailReplyTokenRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Leaving a thread from an email link. The failure mode that matters is telling someone
 * their unsubscribe did not work — that is how a message gets marked as spam.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailUnsubscribeServiceTest {

    private static final String TOKEN = "r7KqNv3xBz2LmQpT8sWyE1cA";

    @Mock private EmailReplyTokenRepository tokenRepository;
    @Mock private VulnerabilityRepository vulnerabilityRepository;
    @Mock private ApplicationRepository applicationRepository;

    @InjectMocks private EmailUnsubscribeService service;

    private Vulnerability vuln;

    @BeforeEach
    void setUp() {
        vuln = Vulnerability.builder()
                .id("vuln-1")
                .name("SQL Injection")
                .subscribers(new ArrayList<>(List.of("alice", "bob")))
                .build();
        when(vulnerabilityRepository.findById("vuln-1")).thenReturn(Optional.of(vuln));
        when(vulnerabilityRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private EmailReplyToken token(String recipient, boolean expired) {
        return EmailReplyToken.builder()
                .id(TOKEN)
                .targetType(MentionTargetType.VULNERABILITY)
                .targetId("vuln-1")
                .assessmentId("asmt-1")
                .recipientUsername(recipient)
                .createdAt(LocalDateTime.now().minusDays(40))
                .expiresAt(LocalDateTime.now().plusDays(expired ? -10 : 10))
                .revoked(false)
                .build();
    }

    @Test
    void removesOnlyTheTokenHolder() {
        when(tokenRepository.findById(TOKEN)).thenReturn(Optional.of(token("alice", false)));

        var result = service.unsubscribe(TOKEN);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("SQL Injection");
        assertThat(vuln.getSubscribers()).containsExactly("bob");
    }

    @Test
    void revokesTheTokenSoItCannotLaterPostAReply() {
        // The same token is the reply credential; leaving a thread must not leave a live
        // way to post into it.
        EmailReplyToken t = token("alice", false);
        when(tokenRepository.findById(TOKEN)).thenReturn(Optional.of(t));

        service.unsubscribe(TOKEN);

        assertThat(t.isRevoked()).isTrue();
    }

    @Test
    void anExpiredTokenStillUnsubscribes() {
        // An old email is still proof of receipt, and a stale link should get someone off
        // a list rather than telling them to go and find the setting themselves.
        when(tokenRepository.findById(TOKEN)).thenReturn(Optional.of(token("alice", true)));

        var result = service.unsubscribe(TOKEN);

        assertThat(result.success()).isTrue();
        assertThat(vuln.getSubscribers()).doesNotContain("alice");
    }

    @Test
    void unsubscribingTwiceStillReportsSuccess() {
        // Idempotent: a "that didn't work" page for someone already unsubscribed is worse
        // than useless.
        when(tokenRepository.findById(TOKEN)).thenReturn(Optional.of(token("carol", false)));

        var result = service.unsubscribe(TOKEN);

        assertThat(result.success()).isTrue();
        assertThat(vuln.getSubscribers()).containsExactly("alice", "bob");
    }

    @Test
    void aDeletedItemStillReportsSuccess() {
        when(tokenRepository.findById(TOKEN)).thenReturn(Optional.of(token("alice", false)));
        when(vulnerabilityRepository.findById("vuln-1")).thenReturn(Optional.empty());

        assertThat(service.unsubscribe(TOKEN).success()).isTrue();
    }

    @Test
    void anUnknownOrMissingTokenIsRejected() {
        when(tokenRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(service.unsubscribe("nope").success()).isFalse();
        assertThat(service.unsubscribe(null).success()).isFalse();
        assertThat(service.unsubscribe("  ").success()).isFalse();
        verify(vulnerabilityRepository, never()).save(any());
    }

    @Test
    void applicationsCanBeLeftTooNowThatTheyKeepASubscriberList() {
        Application application = Application.builder()
                .id("app-1").name("Payments API")
                .subscribers(new ArrayList<>(List.of("alice", "bob")))
                .build();
        when(applicationRepository.findById("app-1")).thenReturn(Optional.of(application));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        EmailReplyToken appToken = token("alice", false);
        appToken.setTargetType(MentionTargetType.APPLICATION);
        appToken.setTargetId("app-1");
        when(tokenRepository.findById(TOKEN)).thenReturn(Optional.of(appToken));

        var result = service.unsubscribe(TOKEN);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Payments API");
        assertThat(application.getSubscribers()).containsExactly("bob");
        // Same token is the reply credential, so leaving must not leave a way back in.
        assertThat(appToken.isRevoked()).isTrue();
    }

    @Test
    void notesStillSaySoRatherThanClaimingSuccess() {
        // A note is a document body, not a thread — there is no membership to leave.
        EmailReplyToken noteToken = token("alice", false);
        noteToken.setTargetType(MentionTargetType.NOTEBOOK);
        when(tokenRepository.findById(TOKEN)).thenReturn(Optional.of(noteToken));

        var result = service.unsubscribe(TOKEN);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("does not have an email subscription");
    }
}
