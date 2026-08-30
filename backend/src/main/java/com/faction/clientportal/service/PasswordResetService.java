package com.faction.clientportal.service;

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

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    private static final int TOKEN_EXPIRY_HOURS = 1;

    /**
     * Request a password reset. Always returns successfully to avoid revealing
     * whether an email address exists in the system.
     */
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

        // Delete any existing tokens for this user
        tokenRepository.deleteByUserId(user.getId());

        // Generate a new token
        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(token)
                .userId(user.getId())
                .expiresAt(LocalDateTime.now().plusHours(TOKEN_EXPIRY_HOURS))
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();
        tokenRepository.save(resetToken);

        // Send the reset email directly to the user's email address
        String resetLink = frontendUrl.replaceAll("/+$", "") + "/reset-password?token=" + token;
        sendResetEmail(user.getEmail(), resetLink);
        log.info("Password reset email sent to user: {}", user.getUsername());
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

        user.setPassword(passwordEncoder.encode(newPassword));
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
