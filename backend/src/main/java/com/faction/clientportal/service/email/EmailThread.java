package com.faction.clientportal.service.email;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.service.EmailNotificationConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * The subject line and thread id shared by every email about one item — the mention that
 * starts a conversation and each comment after it — so a mail client files them as one
 * conversation and keeps two items apart.
 *
 * <p>The subject names the item and, for a finding, its application: finding names repeat
 * across applications ("Cross-Site Scripting"), and clients group by subject. It carries no
 * author and no verb, which change from message to message and would split the
 * conversation; the email's heading says who wrote what.
 *
 * <p>The thread id goes in {@code References} and {@code In-Reply-To}, which is what most
 * clients actually thread on. The stable subject covers the ones that group by subject.
 *
 * <p>Application and vulnerability names can each be switched off in the email notification
 * settings. A hidden name is replaced by what the item is ("Vulnerability"), and the thread
 * id still keeps two items apart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailThread {

    /** Longer names are cut, so a long finding name cannot push the application off the line. */
    static final int MAX_NAME_CHARS = 60;

    private final AssessmentRepository assessmentRepository;
    private final ApplicationRepository applicationRepository;
    private final EmailNotificationConfigService configService;

    /** The subject for an item, looking up a finding's application from its assessment. */
    public String subject(MentionTargetType type, String targetName, String assessmentId) {
        EmailNaming naming = configService.naming();
        String applicationName = type == MentionTargetType.VULNERABILITY && naming.applicationNames()
                ? applicationName(assessmentId) : null;
        return compose(type, targetName, applicationName, naming.shows(type));
    }

    /**
     * How the body of an email refers to the item: its name, or "a vulnerability" when
     * names of that kind are switched off.
     */
    public String itemLabel(MentionTargetType type, String targetName) {
        if (configService.naming().shows(type)) return targetName;
        return type == MentionTargetType.VULNERABILITY ? "a vulnerability" : "an application";
    }

    static String compose(MentionTargetType type, String targetName, String applicationName) {
        return compose(type, targetName, applicationName, true);
    }

    /**
     * {@code [Faction] Payments API — discussion}, {@code [Faction] SQL Injection — Payments API};
     * with the item's name hidden, {@code [Faction] Vulnerability — Payments API}.
     */
    static String compose(MentionTargetType type, String targetName, String applicationName, boolean nameShown) {
        MentionTargetType kind = type == null ? MentionTargetType.APPLICATION : type;
        String name = nameShown ? shorten(targetName) : genericName(kind);
        if (name == null) name = "Untitled";
        return switch (kind) {
            case APPLICATION -> "[Faction] " + name + " — discussion";
            case VULNERABILITY -> {
                String application = shorten(applicationName);
                yield "[Faction] " + name + (application == null ? "" : " — " + application);
            }
            case NOTEBOOK -> "[Faction] " + name + " — notes";
        };
    }

    /**
     * {@code <vulnerability.ID@thread.faction>}: a Message-ID no email actually has, which
     * every email about the item refers to, so they all hang off the same root. Null when
     * there is no item to name, or the id would not be valid inside a Message-ID.
     */
    public static String threadId(MentionTargetType type, String targetId) {
        if (type == null || targetId == null || !targetId.matches("[A-Za-z0-9._-]+")) return null;
        return "<" + type.name().toLowerCase(Locale.ROOT) + "." + targetId + "@thread.faction>";
    }

    private static String genericName(MentionTargetType type) {
        return type == MentionTargetType.VULNERABILITY ? "Vulnerability" : "Application";
    }

    private String applicationName(String assessmentId) {
        if (assessmentId == null) return null;
        try {
            return assessmentRepository.findById(assessmentId)
                    .map(Assessment::getApplicationId)
                    .flatMap(applicationRepository::findById)
                    .map(Application::getName)
                    .orElse(null);
        } catch (Exception e) {
            // The subject is still usable without it; the email is not worth failing over.
            log.debug("Could not resolve the application for assessment {}: {}", assessmentId, e.getMessage());
            return null;
        }
    }

    private static String shorten(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= MAX_NAME_CHARS ? trimmed : trimmed.substring(0, MAX_NAME_CHARS - 1).trim() + "…";
    }
}
