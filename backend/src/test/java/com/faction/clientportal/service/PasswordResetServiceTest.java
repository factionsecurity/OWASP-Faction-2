package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.PasswordResetTokenRepository;
import com.faction.clientportal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The forgot-password flow had no coverage at all, which is how it shipped broken in the way that
 * matters most: the first request worked and every one after it threw, because clearing the user's
 * previous token is a derived delete and nothing on the path opened a transaction. The second
 * request is the one a real user makes — the first email did not arrive, so they ask again.
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetServiceTest extends TestContainersConfig {

    @Autowired private PasswordResetService passwordResetService;
    @Autowired private PasswordResetTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.save(user("forgetful", "forgetful@test.com", LoginOption.NATIVE));
    }

    @Test
    void askingAgainReplacesTheFirstToken() {
        passwordResetService.requestReset("forgetful@test.com");
        assertThat(tokenRepository.findAll()).hasSize(1);
        String firstToken = tokenRepository.findAll().get(0).getToken();

        assertThatCode(() -> passwordResetService.requestReset("forgetful@test.com"))
                .doesNotThrowAnyException();

        // Exactly one live token: the old one is cleared rather than accumulating, so a stale
        // link cannot be used after a newer one was issued.
        assertThat(tokenRepository.findAll()).hasSize(1);
        assertThat(tokenRepository.findAll().get(0).getToken()).isNotEqualTo(firstToken);
    }

    @Test
    void theNewestLinkIsTheOneThatWorks() {
        passwordResetService.requestReset("forgetful@test.com");
        passwordResetService.requestReset("forgetful@test.com");
        String current = tokenRepository.findAll().get(0).getToken();

        passwordResetService.resetPassword(current, "A-new-password1");

        assertThat(tokenRepository.findByToken(current).orElseThrow().isUsed()).isTrue();
    }

    @Test
    void aTokenCannotBeUsedTwice() {
        passwordResetService.requestReset("forgetful@test.com");
        String token = tokenRepository.findAll().get(0).getToken();
        passwordResetService.resetPassword(token, "First-password1");

        assertThatThrownBy(() -> passwordResetService.resetPassword(token, "Second-password1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void anUnknownTokenIsRejected() {
        assertThatThrownBy(() -> passwordResetService.resetPassword("not-a-token", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid or expired");
    }

    @Test
    void anSsoUserGetsNoResetToken() {
        userRepository.save(user("federated", "federated@test.com", LoginOption.SAML2));

        passwordResetService.requestReset("federated@test.com");

        // Their password lives with the identity provider; issuing a link here would be a way in
        // that the IdP's own controls never see.
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    // ── Administrator-initiated ──────────────────────────────────────────────

    @Test
    void anAdministratorGetsARealAnswerWhereThePublicEndpointStaysSilent() {
        // The public route must answer identically whatever happens, so it cannot be used to
        // discover which addresses have accounts. An administrator is on the other side of this
        // one and needs to know when nothing was sent.
        userRepository.save(user("federated2", "fed2@test.com", LoginOption.SAML2));
        String ssoId = userRepository.findByUsername("federated2").orElseThrow().getId();

        assertThatThrownBy(() -> passwordResetService.sendResetLinkFor(ssoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity provider");
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void anAdministratorSendingToAnUnknownUserIs404() {
        assertThatThrownBy(() -> passwordResetService.sendResetLinkFor("no-such-user"))
                .isInstanceOf(com.faction.clientportal.exception.ResourceNotFoundException.class);
    }

    @Test
    void aUserWithNoEmailAddressIsReportedRatherThanSilentlySkipped() {
        User noEmail = userRepository.save(User.builder()
                .username("no-email").email(null).password("x")
                .loginOption(LoginOption.NATIVE).roleIds(List.of())
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());

        assertThatThrownBy(() -> passwordResetService.sendResetLinkFor(noEmail.getId()))
                .hasMessageContaining("no email address");
    }

    @Test
    void anUnknownEmailIsSilentlyIgnored() {
        // Deliberate: the endpoint always answers the same way, so it cannot be used to find out
        // which addresses have accounts.
        assertThatCode(() -> passwordResetService.requestReset("nobody@test.com"))
                .doesNotThrowAnyException();
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    private User user(String username, String email, LoginOption loginOption) {
        return User.builder()
                .username(username).firstName("F").lastName("U").email(email)
                .password("x").loginOption(loginOption).roleIds(List.of())
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build();
    }
}
