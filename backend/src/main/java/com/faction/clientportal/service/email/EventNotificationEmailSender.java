package com.faction.clientportal.service.email;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.EmailNotificationConfig.EventSettings;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.service.EmailNotificationConfigService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the one-off event emails — assessment created/changed/completed, retest
 * scheduled/completed, a finding closed in a remediation stage.
 *
 * <p>The SLA reminders deliberately do not come through here: those are digests, assembled
 * per recipient across every application and organisation, and live in
 * {@link com.faction.clientportal.scheduled.VulnerabilityDigestJob}.
 *
 * <p>Every send is fire-and-forget on the mail executor. A stakeholder's mail server being
 * slow must never hold up the request that finalized an assessment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventNotificationEmailSender {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final EmailNotificationConfigService configService;
    private final NotificationRecipientResolver recipientResolver;
    private final EmailService emailService;

    /** One event email, described by the caller. */
    @Value
    @Builder
    public static class Event {

        /** Settings key — {@link EmailNotificationEvent#key(String)}. */
        String key;

        EmailNotificationEvent event;

        Assessment assessment;

        /** Optional: supplies the @mention audience and the finding's own link. */
        Vulnerability vulnerability;

        String subject;

        /** Heading inside the email card. */
        String title;

        /** Body paragraphs, in order. Escaped when rendered. */
        @Builder.Default
        List<String> lines = new ArrayList<>();

        /** Labelled facts rendered as a small table — "Due date", "Severity", and so on. */
        @Builder.Default
        List<String[]> details = new ArrayList<>();

        String ctaLabel;

        /** App-relative link, e.g. {@code /assessments/abc}. */
        String ctaLink;
    }

    /**
     * Resolves the audience and mails them, or does nothing at all when the event is
     * switched off, SMTP is not configured, or nobody resolves to an address.
     */
    @Async("mailExecutor")
    public void send(Event event) {
        try {
            if (event == null || event.getKey() == null) return;
            if (!configService.isEnabled(event.getKey())) return;
            if (!emailService.isConfigured()) return;

            EventSettings settings = configService.settingsFor(event.getKey());
            List<NotificationRecipientResolver.Recipient> recipients = recipientResolver.resolve(
                    event.getEvent(), settings, event.getAssessment(), event.getVulnerability());

            if (recipients.isEmpty()) {
                log.debug("No recipients resolved for {} — not sending", event.getKey());
                return;
            }

            String html = render(event, settings);
            for (NotificationRecipientResolver.Recipient recipient : recipients) {
                emailService.send(EmailMessage.builder()
                        .to(recipient.email())
                        .subject(event.getSubject())
                        .htmlBody(html)
                        // RFC 3834: these are machine-generated, and stakeholder mailboxes
                        // are exactly the sort to have an auto-responder attached.
                        .autoSubmitted(true)
                        .build());
            }

            log.debug("Sent {} to {} recipient(s)", event.getKey(), recipients.size());

        } catch (Exception e) {
            // Notification email is the optional part of whatever just happened; the
            // assessment or retest it describes is already committed.
            log.warn("Could not send notification email for {}: {}",
                    event.getKey(), e.getMessage());
        }
    }

    private String render(Event event, EventSettings settings) {
        StringBuilder body = new StringBuilder();

        for (String line : event.getLines()) {
            if (line != null && !line.isBlank()) body.append(emailService.paragraph(line));
        }

        if (!event.getDetails().isEmpty()) {
            body.append(detailTable(event.getDetails()));
        }

        // The admin's own wording, last: it is the call to action, and it reads as an
        // instruction rather than context.
        if (settings.getCustomMessage() != null && !settings.getCustomMessage().isBlank()) {
            body.append(customMessage(settings.getCustomMessage()));
        }

        return emailService.renderShell(event.getTitle(), body.toString(),
                event.getCtaLabel(), event.getCtaLink(), null);
    }

    /** The admin's custom wording, set off from the narration so it reads as the ask. */
    String customMessage(String message) {
        return "<p style=\"margin:0 0 20px;padding:12px 14px;background:#0f172a;"
                + "border-left:3px solid #7c3aed;border-radius:4px;color:#e2e8f0;"
                + "font-size:14px;line-height:1.5\">"
                + EmailService.escapeHtml(message) + "</p>";
    }

    private String detailTable(List<String[]> details) {
        StringBuilder sb = new StringBuilder(
                "<table style=\"width:100%;border-collapse:collapse;margin:0 0 20px\">");
        for (String[] row : details) {
            if (row == null || row.length < 2 || row[1] == null || row[1].isBlank()) continue;
            sb.append("<tr>")
              .append("<td style=\"padding:4px 12px 4px 0;color:#64748b;font-size:13px;")
              .append("white-space:nowrap;vertical-align:top\">")
              .append(EmailService.escapeHtml(row[0])).append("</td>")
              .append("<td style=\"padding:4px 0;color:#e2e8f0;font-size:13px\">")
              .append(EmailService.escapeHtml(row[1])).append("</td>")
              .append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    // ── Shared formatting ─────────────────────────────────────────────────────

    static String formatDate(LocalDateTime value) {
        return value == null ? null : value.toLocalDate().format(DATE);
    }

    /** "Payments API (Acme)" — the context a recipient needs to place a finding. */
    static String applicationLabel(Application application) {
        return application == null ? null : application.getName();
    }
}
