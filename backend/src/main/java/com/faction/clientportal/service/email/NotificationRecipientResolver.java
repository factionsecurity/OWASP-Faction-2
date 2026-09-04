package com.faction.clientportal.service.email;

import com.faction.clientportal.model.AppOwner;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.EmailNotificationAudience;
import com.faction.clientportal.model.EmailNotificationConfig.EventSettings;
import com.faction.clientportal.model.EmailNotificationEvent;
import com.faction.clientportal.model.Stakeholder;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityComment;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.AccessScopeService;
import com.faction.clientportal.service.MentionQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an event's audience switches into actual email addresses.
 *
 * <p>Deduplicates by lower-cased address, so someone who is both a stakeholder on the
 * assessment and the app owner gets one email rather than two identical ones. The first
 * name seen wins, which is why the audiences are collected in a fixed order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRecipientResolver {

    /** One resolved recipient: an address, a display name, and why they are on the list. */
    public record Recipient(String email, String name, EmailNotificationAudience audience) {}

    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final MentionQueueService mentionQueueService;
    private final AccessScopeService accessScopeService;

    /**
     * Everyone who should receive this event's email for this assessment.
     *
     * @param vulnerability optional — supplies the remediation owner and the @mention
     *                      audience; null for assessment-level events, which have neither
     *                      an owner nor a comment thread.
     */
    public List<Recipient> resolve(EmailNotificationEvent event,
                                   EventSettings settings,
                                   Assessment assessment,
                                   Vulnerability vulnerability) {

        Map<String, Recipient> byEmail = new LinkedHashMap<>();
        Application application = loadApplication(assessment);

        // First, and outside the audience switches: the remediation owner is accountable for
        // this finding, so they are copied on its alerts whatever the routing table says.
        // Being assigned is the opt-in. First also means their label survives the first-wins
        // dedup when they are on another audience too — "remediation owner" is the reason
        // that matters.
        addAll(byEmail, remediationOwnerRecipients(vulnerability));

        if (enabled(event, settings, EmailNotificationAudience.ASSESSORS)) {
            addAll(byEmail, assessorRecipients(assessment));
        }
        if (enabled(event, settings, EmailNotificationAudience.STAKEHOLDERS)) {
            addAll(byEmail, stakeholderRecipients(assessment, application));
        }
        if (enabled(event, settings, EmailNotificationAudience.APP_OWNER)) {
            addAll(byEmail, appOwnerRecipients(application));
        }
        if (enabled(event, settings, EmailNotificationAudience.MENTIONED_USERS)) {
            addAll(byEmail, mentionedRecipients(vulnerability));
        }
        // Last, so someone who is also a stakeholder or the app owner keeps that label —
        // addAll is first-wins, and the more specific reason is the more useful one.
        if (enabled(event, settings, EmailNotificationAudience.ORG_USERS)) {
            addAll(byEmail, organizationRecipients(application));
        }

        return new ArrayList<>(byEmail.values());
    }

    /** The application an assessment belongs to, or null when it has none or it is gone. */
    public Application loadApplication(Assessment assessment) {
        if (assessment == null || assessment.getApplicationId() == null) return null;
        return applicationRepository.findById(assessment.getApplicationId()).orElse(null);
    }

    // ── Per-audience lookups ──────────────────────────────────────────────────

    /** Assessors plus the engagement and remediation managers — the staff who own the work. */
    private List<Recipient> assessorRecipients(Assessment assessment) {
        if (assessment == null) return List.of();

        List<String> userIds = new ArrayList<>();
        if (assessment.getAssessorIds() != null) userIds.addAll(assessment.getAssessorIds());
        if (assessment.getAssessorId() != null) userIds.add(assessment.getAssessorId());
        if (assessment.getEngagementManagerId() != null) userIds.add(assessment.getEngagementManagerId());
        if (assessment.getRemediationManagerId() != null) userIds.add(assessment.getRemediationManagerId());

        List<Recipient> out = new ArrayList<>();
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) continue;
            userRepository.findById(userId)
                    .map(u -> recipient(u, EmailNotificationAudience.ASSESSORS))
                    .ifPresent(r -> { if (r != null) out.add(r); });
        }
        return out;
    }

    /**
     * Stakeholders from the assessment first, then from the application. Assessment
     * stakeholders are engagement-specific and so are the more precise list; the
     * application's are the standing one.
     */
    private List<Recipient> stakeholderRecipients(Assessment assessment, Application application) {
        List<Recipient> out = new ArrayList<>();
        if (assessment != null) addStakeholders(out, assessment.getStakeholders());
        if (application != null) addStakeholders(out, application.getStakeHolders());
        return out;
    }

    private void addStakeholders(List<Recipient> out, List<Stakeholder> stakeholders) {
        if (stakeholders == null) return;
        for (Stakeholder s : stakeholders) {
            if (s == null || isBlank(s.getEmail())) continue;
            out.add(new Recipient(s.getEmail().trim(),
                    isBlank(s.getName()) ? s.getEmail().trim() : s.getName(),
                    EmailNotificationAudience.STAKEHOLDERS));
        }
    }

    /** The structured owner if there is one, falling back to the legacy ownerEmail field. */
    private List<Recipient> appOwnerRecipients(Application application) {
        if (application == null) return List.of();

        AppOwner owner = application.getAppOwner();
        if (owner != null && !isBlank(owner.getEmail())) {
            return List.of(new Recipient(owner.getEmail().trim(),
                    isBlank(owner.getFullName()) ? owner.getEmail().trim() : owner.getFullName(),
                    EmailNotificationAudience.APP_OWNER));
        }
        if (!isBlank(application.getOwnerEmail())) {
            return List.of(new Recipient(application.getOwnerEmail().trim(),
                    isBlank(application.getOwnerName())
                            ? application.getOwnerEmail().trim() : application.getOwnerName(),
                    EmailNotificationAudience.APP_OWNER));
        }
        return List.of();
    }

    /**
     * The finding's assigned remediation owner, if it has one.
     *
     * <p>Empty for assessment-level events, which carry no vulnerability — ownership is
     * per finding, so there is nobody to resolve.
     */
    private List<Recipient> remediationOwnerRecipients(Vulnerability vulnerability) {
        if (vulnerability == null || isBlank(vulnerability.getRemediationOwnerId())) return List.of();

        Recipient recipient = userRepository.findById(vulnerability.getRemediationOwnerId())
                .filter(u -> u.getDeletedAt() == null && u.getDisabledAt() == null)
                .map(u -> recipient(u, EmailNotificationAudience.REMEDIATION_OWNER))
                .orElse(null);
        return recipient == null ? List.of() : List.of(recipient);
    }

    /**
     * Everyone @mentioned anywhere in the finding's comment thread.
     *
     * <p>The whole thread, not just the latest comment: someone pulled into the
     * conversation three comments ago is still part of it, and an SLA digest has no
     * "latest comment" to read from anyway.
     */
    private List<Recipient> mentionedRecipients(Vulnerability vulnerability) {
        if (vulnerability == null || vulnerability.getComments() == null) return List.of();

        List<Recipient> out = new ArrayList<>();
        for (VulnerabilityComment comment : vulnerability.getComments()) {
            if (comment == null || comment.getContent() == null) continue;
            for (String username : mentionQueueService.extractMentions(comment.getContent())) {
                userRepository.findByUsername(username)
                        // A disabled account still gets the mail — the lockout is temporary and the
                        // thread is waiting for them. A deleted one does not.
                        .filter(u -> u.getDeletedAt() == null)
                        .map(u -> recipient(u, EmailNotificationAudience.MENTIONED_USERS))
                        .ifPresent(r -> { if (r != null) out.add(r); });
            }
        }
        return out;
    }

    /**
     * Everyone whose access to this application comes from its organization.
     *
     * <p>Filtered through {@link AccessScopeService#ownsApplication}, the same rule the API
     * enforces, rather than by home organization alone. A user with application-level
     * assignments is restricted to those applications, so without this filter they would be
     * mailed about findings in their organization that they cannot open — every one of these
     * emails links into the platform, and a link to a 403 is worse than no email.
     *
     * <p>External users only: an organization is only ever assigned to them. Staff reach
     * these events through the assessor audience instead, and an internal account that
     * happens to carry an organization is not a customer contact.
     */
    private List<Recipient> organizationRecipients(Application application) {
        if (application == null || isBlank(application.getOrganizationId())) return List.of();

        List<Recipient> out = new ArrayList<>();
        for (User user : userRepository
                .findByOrganizationIdAndIsInternalFalseAndDeletedAtIsNullAndDisabledAtIsNull(
                        application.getOrganizationId())) {
            if (user == null || user.getId() == null) continue;
            try {
                if (!accessScopeService.ownsApplication(user.getId(), application)) continue;
            } catch (Exception e) {
                // One unresolvable user must not cost everyone else their notification.
                log.warn("Could not resolve organization access for {}: {}",
                        user.getUsername(), e.getMessage());
                continue;
            }
            Recipient recipient = recipient(user, EmailNotificationAudience.ORG_USERS);
            if (recipient != null) out.add(recipient);
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * An audience only counts when the event actually offers it. Without this a stale
     * stored setting — say assessors switched on before an event's audiences were
     * narrowed — would keep mailing a group the UI no longer shows.
     */
    private boolean enabled(EmailNotificationEvent event, EventSettings settings,
                            EmailNotificationAudience audience) {
        return event.supports(audience) && settings.isEnabledFor(audience);
    }

    private Recipient recipient(User user, EmailNotificationAudience audience) {
        if (user == null || isBlank(user.getEmail())) return null;
        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return new Recipient(user.getEmail().trim(),
                name.isEmpty() ? user.getUsername() : name, audience);
    }

    private void addAll(Map<String, Recipient> byEmail, List<Recipient> recipients) {
        for (Recipient r : recipients) {
            if (r == null || isBlank(r.email())) continue;
            byEmail.putIfAbsent(r.email().toLowerCase(Locale.ROOT), r);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Convenience for callers that hold only an application id. */
    public Optional<Application> applicationById(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) return Optional.empty();
        return applicationRepository.findById(applicationId);
    }
}
