package com.faction.clientportal.service;

import com.faction.clientportal.dto.EmailConfigDto;
import com.faction.clientportal.dto.TestEmailRequest;
import com.faction.clientportal.dto.TestSsoResponse;
import com.faction.clientportal.dto.UpdateEmailConfigRequest;
import com.faction.clientportal.model.EmailConfig;
import com.faction.clientportal.repository.EmailConfigRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailConfigService {

    private static final String SINGLETON_ID = "singleton";
    private static final String MASKED = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";

    private final EmailConfigRepository emailConfigRepository;
    private final EncryptionService encryptionService;

    public EmailConfig getOrCreate() {
        return emailConfigRepository.findById(SINGLETON_ID).orElseGet(() -> {
            EmailConfig c = EmailConfig.builder().id(SINGLETON_ID).build();
            return emailConfigRepository.save(c);
        });
    }

    public EmailConfigDto getConfig() {
        return EmailConfigDto.fromEntity(getOrCreate());
    }

    public EmailConfigDto updateConfig(UpdateEmailConfigRequest request) {
        EmailConfig config = getOrCreate();

        if (request.getEnabled() != null)     config.setEnabled(request.getEnabled());
        if (request.getProvider() != null)    config.setProvider(request.getProvider());
        if (request.getHost() != null)        config.setHost(request.getHost());
        if (request.getPort() != null)        config.setPort(request.getPort());
        if (request.getUsername() != null)    config.setUsername(request.getUsername());
        if (request.getFromName() != null)    config.setFromName(request.getFromName());
        if (request.getFromEmail() != null)   config.setFromEmail(request.getFromEmail());
        if (request.getSecurity() != null)    config.setSecurity(request.getSecurity());
        if (request.getAuthEnabled() != null) config.setAuthEnabled(request.getAuthEnabled());

        // Update logo: null = no change; empty string = clear; any other value = set
        if (request.getLogoBase64() != null) {
            config.setLogoBase64(request.getLogoBase64().isBlank() ? null : request.getLogoBase64());
            config.setLogoMimeType(request.getLogoBase64().isBlank() ? null : request.getLogoMimeType());
        }

        // Update password only when a real new value is provided
        String pw = request.getPassword();
        if (pw != null && !pw.equals(MASKED) && !pw.isBlank()) {
            config.setEncryptedPassword(
                    encryptionService.isConfigured()
                            ? encryptionService.encrypt(pw)
                            : pw
            );
        }

        emailConfigRepository.save(config);
        return EmailConfigDto.fromEntity(config);
    }

    public TestSsoResponse testConnection(TestEmailRequest request) {
        EmailConfig config = getOrCreate();

        if (config.getHost() == null || config.getHost().isBlank()) {
            return TestSsoResponse.builder()
                    .success(false)
                    .message("SMTP host is not configured.")
                    .build();
        }

        try {
            JavaMailSenderImpl sender = buildSender(config);
            sender.testConnection();

            if (request.getTo() != null && !request.getTo().isBlank()) {
                sendTestEmail(sender, config, request.getTo());
                return TestSsoResponse.builder()
                        .success(true)
                        .message("Connection successful. Test email sent to " + request.getTo() + ".")
                        .build();
            }

            return TestSsoResponse.builder()
                    .success(true)
                    .message("SMTP connection successful.")
                    .build();

        } catch (Exception e) {
            log.warn("Email connection test failed: {}", e.getMessage());
            return TestSsoResponse.builder()
                    .success(false)
                    .message("Connection failed: " + e.getMessage())
                    .details(e.getClass().getSimpleName())
                    .build();
        }
    }

    private void sendTestEmail(JavaMailSenderImpl sender, EmailConfig config, String to) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

        String from = config.getFromEmail() != null && !config.getFromEmail().isBlank()
                ? config.getFromEmail() : config.getUsername();
        String name = config.getFromName() != null && !config.getFromName().isBlank()
                ? config.getFromName() : "Faction";

        helper.setFrom(from, name);
        helper.setTo(to);
        helper.setSubject("Faction — Email Configuration Test");
        helper.setText(
                "<p>This is a test email from <strong>Faction</strong>.</p>" +
                "<p>Your email configuration is working correctly.</p>",
                true
        );
        sender.send(message);
    }

    /** Build a live sender from stored config. Returns null if not enabled or not configured. */
    public JavaMailSenderImpl buildActiveSender() {
        EmailConfig config = getOrCreate();
        if (!config.isEnabled() || config.getHost() == null || config.getHost().isBlank()) {
            return null;
        }
        return buildSender(config);
    }

    JavaMailSenderImpl buildSender(EmailConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getHost());
        sender.setPort(config.getPort());

        if (config.isAuthEnabled() && config.getUsername() != null && !config.getUsername().isBlank()) {
            sender.setUsername(config.getUsername());
            if (config.getEncryptedPassword() != null && !config.getEncryptedPassword().isBlank()) {
                try {
                    sender.setPassword(
                            encryptionService.isConfigured()
                                    ? encryptionService.decrypt(config.getEncryptedPassword())
                                    : config.getEncryptedPassword()
                    );
                } catch (Exception e) {
                    log.warn("Failed to decrypt email password", e);
                }
            }
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(config.isAuthEnabled()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        String security = config.getSecurity() != null ? config.getSecurity() : "STARTTLS";
        switch (security) {
            case "SSL_TLS" -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.trust", config.getHost());
                props.put("mail.smtp.starttls.enable", "false");
            }
            case "NONE" -> {
                props.put("mail.smtp.ssl.enable", "false");
                props.put("mail.smtp.starttls.enable", "false");
            }
            default -> { // STARTTLS
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.enable", "false");
            }
        }

        return sender;
    }
}
