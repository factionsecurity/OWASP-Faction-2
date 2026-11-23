package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailReplyToken;
import com.faction.clientportal.repository.EmailReplyTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplyTokenServiceTest {

    @Mock private EmailReplyTokenRepository repository;
    @Mock private ReplyMailboxProvider replyMailboxProvider;

    @InjectMocks private ReplyTokenService service;

    @Test
    void newTokensSurviveBeingLowerCased() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String id = ReplyTokenService.newTokenId();
            assertThat(id).hasSize(32).matches("[a-z2-7]+");
            assertThat(id.toLowerCase()).isEqualTo(id);
            seen.add(id);
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void anExactMatchNeedsNoCaseInsensitiveSearch() {
        EmailReplyToken token = EmailReplyToken.builder().id("abc").build();
        when(repository.findById("abc")).thenReturn(Optional.of(token));

        assertThat(service.find("abc")).contains(token);
        verify(repository, never()).findAllByIdIgnoreCase("abc");
    }

    @Test
    void anOlderMixedCaseTokenIsFoundAfterItWasLowerCasedInTransit() {
        // What jellyfish saw: Gmail's Delivered-To carried the address lower-cased.
        EmailReplyToken token = EmailReplyToken.builder().id("fEmkKJmjdfZ7O3Ci81DOLnI6-hldyyKs").build();
        when(repository.findById("femkkjmjdfz7o3ci81dolni6-hldyyks")).thenReturn(Optional.empty());
        when(repository.findAllByIdIgnoreCase("femkkjmjdfz7o3ci81dolni6-hldyyks")).thenReturn(List.of(token));

        assertThat(service.find("femkkjmjdfz7o3ci81dolni6-hldyyks")).contains(token);
    }

    @Test
    void twoTokensDifferingOnlyByCaseMatchNeither() {
        when(repository.findById("ab")).thenReturn(Optional.empty());
        when(repository.findAllByIdIgnoreCase("ab")).thenReturn(List.of(
                EmailReplyToken.builder().id("Ab").build(), EmailReplyToken.builder().id("aB").build()));

        assertThat(service.find("ab")).isEmpty();
    }

    @Test
    void nothingIsLookedUpForABlankToken() {
        assertThat(service.find(" ")).isEmpty();
        assertThat(service.find(null)).isEmpty();
        verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
    }
}
