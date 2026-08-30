package com.faction.clientportal.service;

import com.faction.clientportal.model.MentionQueue;
import com.faction.clientportal.model.MentionTarget;
import com.faction.clientportal.repository.MentionQueueRepository;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.email.MentionEmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class MentionQueueService {

    private static final Pattern DATA_USERNAME_PATTERN =
            Pattern.compile("data-username=\"([^\"]+)\"");

    /**
     * How long a queued mention waits before being sent. Long enough to coalesce the
     * autosaves of a single edit, short enough that the notification still feels like a
     * response to what just happened. With {@link #DRAIN_INTERVAL_MS} this puts delivery
     * at 10-25 seconds; it was previously 30-90.
     */
    private static final int SEND_DELAY_SECONDS = 10;

    private static final long DRAIN_INTERVAL_MS = 15_000;

    /**
     * A repeat mention of the same person on the same item inside this window is
     * suppressed. This — not the send delay — is now what stops notebook autosaves from
     * notifying over and over, so shortening the delay is safe.
     */
    private static final int DEDUP_WINDOW_MINUTES = 10;

    private final MentionQueueRepository mentionQueueRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final MentionEmailSender mentionEmailSender;

    /**
     * Extract all @mention usernames from HTML content by scanning for
     * {@code data-username="..."} attributes. Deduplicates the result.
     */
    public List<String> extractMentions(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }

        List<String> usernames = new ArrayList<>();
        Matcher matcher = DATA_USERNAME_PATTERN.matcher(html);
        while (matcher.find()) {
            String username = matcher.group(1);
            if (!usernames.contains(username)) {
                usernames.add(username);
            }
        }
        return usernames;
    }

    /**
     * Queue notifications for all @mentions found in the HTML content.
     * Skips self-mentions and entries already queued/processed for the
     * same username + contextLink combination.
     */
    public void queueMentions(String html, String contextLink, String mentionedByUsername,
                              MentionTarget target) {
        List<String> mentions = extractMentions(html);

        for (String mentionedUsername : mentions) {
            // Skip self-mention
            if (mentionedUsername.equals(mentionedByUsername)) {
                continue;
            }

            // Skip if this person was already queued for this item recently — whether or
            // not that entry has been sent yet. Covers both a burst of notebook autosaves
            // and a second notification firing moments after the first was delivered.
            if (mentionQueueRepository.existsByMentionedUsernameAndContextLinkAndCreatedAtAfter(
                    mentionedUsername, contextLink,
                    LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES))) {
                continue;
            }

            MentionQueue entry = MentionQueue.builder()
                    .mentionedUsername(mentionedUsername)
                    .mentionedByUsername(mentionedByUsername)
                    .contextLink(contextLink)
                    // Snapshotted so the email can quote what was said. Comments live in
                    // a JSON column on their parent, so there is nothing to look up later.
                    .contentHtml(html)
                    .targetType(target == null ? null : target.type())
                    .targetId(target == null ? null : target.id())
                    .assessmentId(target == null ? null : target.assessmentId())
                    .targetName(target == null ? null : target.name())
                    .scheduledFor(LocalDateTime.now().plusSeconds(SEND_DELAY_SECONDS))
                    .processed(false)
                    .createdAt(LocalDateTime.now())
                    .build();

            mentionQueueRepository.save(entry);
            log.debug("Queued @mention notification for {} by {} at {}",
                    mentionedUsername, mentionedByUsername, entry.getScheduledFor());
        }
    }

    /**
     * Process due mention notifications on every drain tick.
     * Finds all unprocessed entries whose scheduledFor time has passed,
     * sends a notification for each, then marks them as processed.
     */
    @Scheduled(fixedDelay = DRAIN_INTERVAL_MS)
    public void processDue() {
        List<MentionQueue> due = mentionQueueRepository
                .findByProcessedFalseAndScheduledForBefore(LocalDateTime.now());

        if (due.isEmpty()) return;

        log.debug("Processing {} due @mention notification(s)", due.size());

        for (MentionQueue entry : due) {
            try {
                String actorName = displayName(entry.getMentionedByUsername());
                String message = actorName + " mentioned you in a comment";
                // sendEmail=false: MentionEmailSender sends a richer email carrying the
                // comment body and a reply token. Letting the generic one go too would
                // mean two emails for one mention.
                notificationService.send(
                        entry.getMentionedUsername(),
                        "You were mentioned in a comment",
                        message,
                        "MENTION",
                        entry.getContextLink(),
                        false,
                        new NotificationContext(
                                entry.getTargetType(),
                                entry.getTargetId(),
                                entry.getTargetName(),
                                entry.getMentionedByUsername(),
                                actorName,
                                entry.getContentHtml())
                );

                // Marked processed before dispatching the email, so the async send never
                // races this mutation of the same entity. Email is best-effort; the
                // in-app notification above is the durable part.
                entry.setProcessed(true);
                entry.setProcessedAt(LocalDateTime.now());
                mentionQueueRepository.save(entry);

                mentionEmailSender.send(entry);

                log.debug("Sent @mention notification to {}", entry.getMentionedUsername());
            } catch (Exception e) {
                log.warn("Failed to process @mention notification for {}: {}",
                        entry.getMentionedUsername(), e.getMessage());
            }
        }
    }

    /** "First Last" for the mention author, falling back to the username. */
    private String displayName(String username) {
        return userRepository.findByUsername(username)
                .map(MentionQueueService::fullName)
                .orElse(username);
    }

    private static String fullName(User user) {
        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }
}
