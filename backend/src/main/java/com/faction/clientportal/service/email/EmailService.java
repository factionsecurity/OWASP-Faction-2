package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailConfig;
import com.faction.clientportal.service.EmailConfigService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The single way this application sends email.
 *
 * <p>Before this existed, {@code NotificationService} and {@code PasswordResetService}
 * each carried their own copy of from-address resolution, {@code MimeMessageHelper}
 * setup, the branded HTML shell and an {@code escapeHtml} helper — two near-identical
 * implementations that had already drifted (one logged failures at {@code debug}, the
 * other at {@code error}). Every new email type would have been a third copy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailConfigService emailConfigService;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    /** True when SMTP is configured and switched on, so a send is worth attempting. */
    public boolean isConfigured() {
        return emailConfigService.buildActiveSender() != null;
    }

    /**
     * Sends on the mail executor so SMTP latency never blocks a request thread. Use this
     * for anything triggered by a user action; the caller learns nothing about the
     * outcome beyond the log, which is correct for a best-effort notification.
     */
    @Async("mailExecutor")
    public void sendAsync(EmailMessage message) {
        send(message);
    }

    /**
     * Blocking send. Returns the {@code Message-ID} JavaMail assigned, which the mention
     * flow persists so an inbound reply's {@code In-Reply-To} can resolve the thread.
     * Empty means the send did not happen — either email is off or it failed.
     *
     * <p>Never throws: email is best-effort everywhere it is used, and the thing that
     * prompted it (a notification row, a reset token) is already committed.
     */
    public Optional<String> send(EmailMessage message) {
        JavaMailSenderImpl sender = emailConfigService.buildActiveSender();
        if (sender == null) {
            log.debug("Email is disabled or unconfigured — not sending '{}' to {}",
                    message.getSubject(), message.getTo());
            return Optional.empty();
        }
        if (message.getTo() == null || message.getTo().isBlank()) {
            log.debug("No recipient for '{}' — not sending", message.getSubject());
            return Optional.empty();
        }

        try {
            EmailConfig config = emailConfigService.getOrCreate();
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(fromAddress(config), fromName(config));
            helper.setTo(message.getTo());
            helper.setSubject(message.getSubject());
            helper.setText(message.getHtmlBody(), true);
            if (message.getReplyTo() != null && !message.getReplyTo().isBlank()) {
                helper.setReplyTo(message.getReplyTo());
            }
            if (message.getUnsubscribeUrl() != null && !message.getUnsubscribeUrl().isBlank()) {
                mime.setHeader("List-Unsubscribe", "<" + message.getUnsubscribeUrl() + ">");
                // Deliberately NOT List-Unsubscribe-Post: that invites one-click POSTs
                // straight from the client, and our endpoint is reached through a
                // confirmation page so link prefetchers cannot unsubscribe people.
            }
            if (message.isAutoSubmitted()) {
                // RFC 3834: tells other automatic responders not to reply to this, which
                // is what stops two systems answering each other indefinitely.
                mime.setHeader("Auto-Submitted", "auto-replied");
            }

            sender.send(mime);

            // Readable only after send(), which triggers saveChanges() and generates it.
            // Reading it back beats trying to impose our own: JavaMail overwrites any
            // Message-ID we set unless MimeMessage.updateMessageID() is subclassed away.
            String messageId = mime.getMessageID();
            log.debug("Sent '{}' to {} (Message-ID {})", message.getSubject(), message.getTo(), messageId);
            return Optional.ofNullable(messageId);

        } catch (Exception e) {
            // warn, not debug: a rejected or bounced send is otherwise indistinguishable
            // from a successful one, which makes "I never got an email" undiagnosable.
            // The recipient is included because a typo'd address is the likeliest cause.
            log.warn("Failed to send '{}' to {}: {}", message.getSubject(), message.getTo(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Wraps content in the branded shell (logo header, dark card, optional call to
     * action). {@code bodyHtml} is inserted verbatim — callers are responsible for
     * escaping anything user-supplied, via {@link #escapeHtml}.
     */
    public String renderShell(String title, String bodyHtml, String ctaLabel, String ctaLink, String footerHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><body style=\"font-family:sans-serif;background:#0f172a;margin:0;padding:32px\">");
        sb.append("<div style=\"max-width:560px;margin:0 auto;background:#1e293b;border-radius:8px;overflow:hidden\">");
        sb.append("<div style=\"background:#7c3aed;padding:20px 28px\">").append(logoTag()).append("</div>");
        sb.append("<div style=\"padding:28px\">");
        sb.append("<h2 style=\"margin:0 0 12px;color:#f1f5f9;font-size:16px\">").append(escapeHtml(title)).append("</h2>");
        sb.append(bodyHtml == null ? "" : bodyHtml);
        if (ctaLabel != null && ctaLink != null && !ctaLink.isBlank()) {
            sb.append("<a href=\"").append(escapeHtml(absoluteUrl(ctaLink)))
              .append("\" style=\"display:inline-block;background:#7c3aed;color:#fff;text-decoration:none;")
              .append("padding:10px 20px;border-radius:6px;font-size:14px\">")
              .append(escapeHtml(ctaLabel)).append("</a>");
        }
        if (footerHtml != null && !footerHtml.isBlank()) {
            sb.append("<p style=\"margin:24px 0 0;color:#64748b;font-size:12px;line-height:1.5\">")
              .append(footerHtml).append("</p>");
        }
        sb.append("</div></div></body></html>");
        return sb.toString();
    }

    /** Footer line offering a way off the thread, for emails someone can be removed from. */
    public String unsubscribeFooter(String unsubscribeUrl, String context) {
        return "You are receiving this because you are following " + escapeHtml(context)
                + ". <a href=\"" + escapeHtml(unsubscribeUrl)
                + "\" style=\"color:#94a3b8\">Remove me from this conversation</a>.";
    }

    /** The page a recipient lands on to confirm leaving a thread. */
    public String unsubscribeUrl(String token) {
        return baseUrl() + "/unsubscribe?token=" + token;
    }

    /** A paragraph in the shell's body style. */
    public String paragraph(String text) {
        return "<p style=\"margin:0 0 20px;color:#94a3b8;font-size:14px;line-height:1.5\">"
                + escapeHtml(text) + "</p>";
    }

    /** Turns an app-relative link like {@code /assessments/1} into an absolute URL. */
    public String absoluteUrl(String link) {
        if (link == null || link.isBlank()) return "";
        if (link.startsWith("http://") || link.startsWith("https://")) return link;
        return baseUrl() + (link.startsWith("/") ? link : "/" + link);
    }

    public String baseUrl() {
        return frontendUrl.replaceAll("/+$", "");
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private String logoTag() {
        EmailConfig config = emailConfigService.getOrCreate();
        if (config.getLogoBase64() != null && !config.getLogoBase64().isBlank()) {
            String mime = config.getLogoMimeType() != null ? config.getLogoMimeType() : "image/png";
            return "<img src=\"data:" + mime + ";base64," + config.getLogoBase64()
                    + "\" alt=\"Logo\" style=\"height:40px;max-width:200px;object-fit:contain;display:block\">";
        }
        return "<img src=\"" + escapeHtml(baseUrl() + "/faction-white-logo.png")
                + "\" alt=\"Faction\" style=\"height:40px;max-width:200px;object-fit:contain;display:block\">";
    }

    private String fromAddress(EmailConfig config) {
        return config.getFromEmail() != null && !config.getFromEmail().isBlank()
                ? config.getFromEmail() : config.getUsername();
    }

    private String fromName(EmailConfig config) {
        return config.getFromName() != null && !config.getFromName().isBlank()
                ? config.getFromName() : "Faction";
    }
}
