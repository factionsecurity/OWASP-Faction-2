package com.faction.clientportal.service;

import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.PasswordResetToken;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.PasswordResetTokenRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.email.EmailMessage;
import com.faction.clientportal.service.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    private static final int TOKEN_EXPIRY_HOURS = 1;

    /**
     * Request a password reset. Always returns successfully to avoid revealing
     * whether an email address exists in the system.
     */
    /**
     * {@code @Transactional} because clearing the user's previous tokens is a derived
     * {@code deleteBy…}, which removes rows one at a time and needs one. Without it a first
     * request succeeded (nothing to delete) and every request after it threw — so someone who did
     * not receive the first email and asked again got an error, or worse, the 200 this endpoint
     * always returns with no mail behind it.
     */
    /**
     * Sends a reset link on an administrator's behalf.
     *
     * <p>Separate from {@link #requestReset} because the two have opposite obligations. The public
     * one must answer identically whatever happens, or it becomes a way to find out which email
     * addresses have accounts. This one has a named administrator on the other end who needs to
     * know what actually happened — that the address is missing, or that the account signs in
     * through an identity provider and no link will help.
     *
     * @return the address the link was sent to, for the confirmation message
     */
    @Transactional
    public String sendResetLinkFor(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (user.getDeletedAt() != null) {
            throw new IllegalArgumentException("That user has been deleted");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalArgumentException(
                    "That user has no email address, so a reset link cannot be sent");
        }
        if (user.getLoginOption() != null
                && !com.faction.clientportal.model.LoginOption.NATIVE.equals(user.getLoginOption())) {
            throw new IllegalArgumentException(
                    "That user signs in through your identity provider; their password is managed there");
        }
        if (!emailService.isConfigured()) {
            throw new IllegalArgumentException(
                    "Email is not configured, so the link cannot be delivered. "
                    + "Set it up under Administration > Email.");
        }

        issueTokenAndSend(user);
        log.info("Administrator-initiated password reset sent to {}", user.getUsername());
        return user.getEmail();
    }

    @Transactional
    public void requestReset(String email) {
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            log.debug("Password reset requested for unknown email: {}", email);
            return;
        }

        User user = userOpt.get();

        // Only native-login users can reset their password via email
        if (user.getLoginOption() != null &&
                !com.faction.clientportal.model.LoginOption.NATIVE.equals(user.getLoginOption())) {
            log.debug("Password reset skipped for SSO user: {}", user.getUsername());
            return;
        }

        issueTokenAndSend(user);
        log.info("Password reset email sent to user: {}", user.getUsername());
    }

    /**
     * Replaces any outstanding token for this user with a fresh one and mails the link.
     *
     * <p>Replacing rather than adding matters: two live links would mean an older one still works
     * after a newer has been asked for, which is the opposite of what asking again implies.
     */
    private void issueTokenAndSend(User user) {
        tokenRepository.deleteByUserId(user.getId());

        String token = UUID.randomUUID().toString();
        tokenRepository.save(PasswordResetToken.builder()
                .token(token)
                .userId(user.getId())
                .expiresAt(LocalDateTime.now().plusHours(TOKEN_EXPIRY_HOURS))
                .used(false)
                .createdAt(LocalDateTime.now())
                .build());

        sendResetEmail(user.getEmail(),
                frontendUrl.replaceAll("/+$", "") + "/reset-password?token=" + token);
    }

    /**
     * Validate the token and set the new password.
     */
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset link."));

        if (resetToken.isUsed()) {
            throw new IllegalArgumentException("This reset link has already been used.");
        }

        if (LocalDateTime.now().isAfter(resetToken.getExpiresAt())) {
            tokenRepository.delete(resetToken);
            throw new IllegalArgumentException("This reset link has expired. Please request a new one.");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        // The reset link is the most common way a password is set, so it is the one route the
        // policy must never miss.
        passwordPolicyService.validate(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        // Whoever proved control of the mailbox gets in: a reset clears a lockout that is still
        // running, or they would be turned away with a correct password.
        passwordPolicyService.clearFailedAttempts(user);
        userRepository.save(user);

        // Mark token as used
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        log.info("Password reset successfully for user: {}", user.getUsername());
    }

    private void sendResetEmail(String toEmail, String resetLink) {
        if (!emailService.isConfigured()) {
            log.warn("Email not configured — cannot send password reset email to {}", toEmail);
            return;
        }

        String body = emailService.paragraph(
                "You requested a password reset. Click the button below to set a new password. "
                        + "This link will expire in " + TOKEN_EXPIRY_HOURS + " hour.");

        // Blocking, not sendAsync: requestReset() logs "reset email sent" immediately
        // after, and that line must not be able to precede a failure.
        emailService.send(EmailMessage.builder()
                .to(toEmail)
                .subject("Faction — Password Reset")
                .htmlBody(emailService.renderShell(
                        "Reset Your Password",
                        body,
                        "Reset Password",
                        resetLink,
                        "If you did not request a password reset, you can safely ignore this email."))
                .build());
    }
}
