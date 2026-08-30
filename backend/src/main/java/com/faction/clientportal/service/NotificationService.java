package com.faction.clientportal.service;

import com.faction.clientportal.dto.NotificationDto;
import com.faction.clientportal.model.Notification;
import com.faction.clientportal.repository.NotificationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.email.EmailMessage;
import com.faction.clientportal.service.email.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final NotificationPreferenceService preferenceService;
    private final ObjectMapper objectMapper;

    /** Notification types the mentions dashboard is built from. */
    static final List<String> MENTION_FEED_TYPES = List.of("MENTION", "COMMENT_ADDED");

    /** Long enough to tell two comments apart in a table row, short enough not to wrap it. */
    private static final int EXCERPT_MAX_CHARS = 240;

    /**
     * One entry per open browser tab. The section access is captured here because the
     * mentions count pushed down the stream is scoped to what that reader may see, and a
     * push fired from some other user's comment has no request to resolve it from.
     */
    private record Subscription(SseEmitter emitter, MentionSectionAccess access) {}

    // username -> active subscriptions (one per browser tab)
    private final Map<String, List<Subscription>> emitters = new ConcurrentHashMap<>();

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               EmailService emailService,
                               NotificationPreferenceService preferenceService,
                               ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.preferenceService = preferenceService;
        this.objectMapper = objectMapper;
    }

    // ── Subscribe ──────────────────────────────────────────────────────────────

    public SseEmitter subscribe(String username, MentionSectionAccess access) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        Subscription subscription = new Subscription(emitter, access);
        List<Subscription> userEmitters = emitters.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>());
        userEmitters.add(subscription);

        Runnable cleanup = () -> {
            List<Subscription> list = emitters.get(username);
            if (list != null) {
                list.remove(subscription);
                if (list.isEmpty()) emitters.remove(username);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Send both unread counts immediately on connect, so the bell badge and the
        // Mentions sidebar badge are right before anything happens on the stream.
        try {
            long unread = notificationRepository.countByUsernameAndReadFalse(username);
            emitter.send(SseEmitter.event().name("unread_count").data(unread));
            emitter.send(SseEmitter.event().name("mentions_unread_count")
                    .data(getMentionsUnreadCount(username, access)));
        } catch (IOException ignored) {}

        return emitter;
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    public void send(String username, String title, String message, String type, String link) {
        send(username, title, message, type, link, true);
    }

    /**
     * Records the notification and pushes it to any open browser tabs, optionally
     * emailing it too.
     *
     * <p>{@code sendEmail=false} is for callers that send their own, richer email —
     * @mentions carry the comment body and a reply token, which the generic
     * title-and-link email cannot express. Without this flag such a caller would send
     * two emails for one event.
     */
    public void send(String username, String title, String message, String type, String link,
                     boolean sendEmail) {
        send(username, title, message, type, link, sendEmail, null);
    }

    /**
     * As above, plus the item and author the notification is about. Callers that have that
     * context pass it so the mentions dashboard can group the row and quote the comment;
     * {@code context} is null for notifications that are not about one item.
     */
    public void send(String username, String title, String message, String type, String link,
                     boolean sendEmail, NotificationContext context) {
        // Suppressed rather than hidden at read time: someone who switched a category off
        // should not accumulate an unread count for it.
        if (!preferenceService.isInAppEnabled(username, type)) {
            log.debug("{} has muted {} notifications in-app — not recording", username, type);
        } else {
            recordAndPush(username, title, message, type, link, context);
        }

        if (sendEmail && preferenceService.isEmailEnabled(username, type)) {
            emailNotification(username, title, message, link);
        }
    }

    private void recordAndPush(String username, String title, String message, String type,
                               String link, NotificationContext context) {
        Notification notification = Notification.builder()
                .username(username)
                .title(title)
                .message(message)
                .type(type)
                .link(link)
                .targetType(context == null ? null : context.targetType())
                .targetId(context == null ? null : context.targetId())
                .targetName(context == null ? null : context.targetName())
                .actorUsername(context == null ? null : context.actorUsername())
                .actorName(context == null ? null : context.actorName())
                .excerpt(context == null ? null : excerptOf(context.contentHtml()))
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();

        Notification saved = notificationRepository.save(notification);
        NotificationDto dto = NotificationDto.fromEntity(saved);

        // Push live update to connected browser tabs
        pushToEmitters(username, "notification", dto);
        pushUnreadCount(username);

    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<NotificationDto> getForUser(String username) {
        return notificationRepository.findByUsernameOrderByCreatedAtDesc(username)
                .stream()
                .map(NotificationDto::fromEntity)
                .toList();
    }

    public long getUnreadCount(String username) {
        return notificationRepository.countByUsernameAndReadFalse(username);
    }

    /**
     * The mentions feed: every @mention of this user, plus every reply on a thread they
     * follow. Both kinds answer the same question — "what has been said to me" — so the
     * dashboard shows them together, split by the kind of item rather than by which of
     * the two put the row there.
     */
    public List<NotificationDto> getMentions(String username, MentionSectionAccess access) {
        List<Notification> rows = access.isEmpty()
                ? notificationRepository.findByUsernameAndTypeInAndTargetTypeIsNullOrderByCreatedAtDesc(
                        username, MENTION_FEED_TYPES)
                : notificationRepository.findMentionsInSections(
                        username, MENTION_FEED_TYPES, access.visible());
        return rows.stream().map(NotificationDto::fromEntity).toList();
    }

    public long getMentionsUnreadCount(String username, MentionSectionAccess access) {
        return access.isEmpty()
                ? notificationRepository.countByUsernameAndTypeInAndTargetTypeIsNullAndReadFalse(
                        username, MENTION_FEED_TYPES)
                : notificationRepository.countUnreadMentionsInSections(
                        username, MENTION_FEED_TYPES, access.visible());
    }

    public NotificationDto markRead(String id, String username) {
        Notification n = notificationRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + id));
        if (!n.isRead()) {
            n.setRead(true);
            n.setReadAt(LocalDateTime.now());
            notificationRepository.save(n);
        }
        pushUnreadCount(username);
        return NotificationDto.fromEntity(n);
    }

    public void markAllRead(String username) {
        List<Notification> unread = notificationRepository.findByUsernameAndReadFalse(username);
        LocalDateTime now = LocalDateTime.now();
        unread.forEach(n -> { n.setRead(true); n.setReadAt(now); });
        notificationRepository.saveAll(unread);
        pushUnreadCount(username);
    }

    public void delete(String id, String username) {
        notificationRepository.findByIdAndUsername(id, username)
                .ifPresent(n -> {
                    notificationRepository.delete(n);
                    pushUnreadCount(username);
                });
    }

    /** Clears the whole list for one user — the "delete all" action on the dashboard. */
    @Transactional
    public void deleteAll(String username) {
        notificationRepository.deleteByUsername(username);
        pushUnreadCount(username);
    }

    /**
     * Clears the mentions feed without touching the rest of the bell — assignments and
     * retests are not part of this list, so emptying it must not silently drop them.
     *
     * <p>{@code targetType} narrows the clear to one section of the dashboard. Null means
     * the whole feed; {@link MentionTargetFilter#UNTARGETED} means the rows that belong to
     * no section — pre-context rows the dashboard shows under "Other" — which is a
     * different request from "everything", and cannot be expressed by a null enum.
     */
    @Transactional
    public void deleteAllMentions(String username, MentionTargetFilter targetType,
                                  MentionSectionAccess access) {
        if (targetType == null) {
            // Scoped like the list it clears: "delete all" empties what the reader can
            // see, never a section their account has no privileges on.
            if (access.isEmpty()) {
                notificationRepository.deleteByUsernameAndTypeInAndTargetTypeIsNull(
                        username, MENTION_FEED_TYPES);
            } else {
                notificationRepository.deleteMentionsInSections(
                        username, MENTION_FEED_TYPES, access.visible());
            }
        } else if (targetType.isUntargeted()) {
            notificationRepository.deleteByUsernameAndTypeInAndTargetTypeIsNull(username, MENTION_FEED_TYPES);
        } else {
            notificationRepository.deleteByUsernameAndTypeInAndTargetType(
                    username, MENTION_FEED_TYPES, targetType.targetType());
        }
        pushUnreadCount(username);
    }

    /**
     * Comment bodies are editor HTML. The dashboard renders the excerpt as text, so the
     * tags come out here rather than at read time — and never reach the DOM as markup.
     */
    private static String excerptOf(String html) {
        if (html == null || html.isBlank()) return null;
        String plain = html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
        if (plain.isEmpty()) return null;
        return plain.length() > EXCERPT_MAX_CHARS
                ? plain.substring(0, EXCERPT_MAX_CHARS) + "…"
                : plain;
    }

    // ── SSE helpers ───────────────────────────────────────────────────────────

    private void pushToEmitters(String username, String eventName, Object payload) {
        List<Subscription> list = emitters.get(username);
        if (list == null || list.isEmpty()) return;

        List<Subscription> dead = new ArrayList<>();
        for (Subscription subscription : list) {
            try {
                String json = objectMapper.writeValueAsString(payload);
                subscription.emitter().send(SseEmitter.event().name(eventName).data(json));
            } catch (Exception e) {
                dead.add(subscription);
            }
        }
        list.removeAll(dead);
    }

    /**
     * Pushes both counts together. Every mutation moves the bell badge and can move the
     * Mentions badge, and splitting them into separate calls only creates paths where one
     * of the two is left stale.
     *
     * <p>The mentions count is per subscription: two people are never on this stream at
     * once, but one person's tabs all share their access, so the count is resolved once
     * per distinct section set rather than once per tab.
     */
    private void pushUnreadCount(String username) {
        pushToEmitters(username, "unread_count",
                notificationRepository.countByUsernameAndReadFalse(username));

        List<Subscription> list = emitters.get(username);
        if (list == null || list.isEmpty()) return;

        Map<MentionSectionAccess, Long> counted = new HashMap<>();
        List<Subscription> dead = new ArrayList<>();
        for (Subscription subscription : list) {
            long count = counted.computeIfAbsent(subscription.access(),
                    access -> getMentionsUnreadCount(username, access));
            try {
                subscription.emitter().send(SseEmitter.event()
                        .name("mentions_unread_count").data(count));
            } catch (Exception e) {
                dead.add(subscription);
            }
        }
        list.removeAll(dead);
    }

    @Scheduled(fixedDelay = 30_000)
    public void sendHeartbeats() {
        emitters.forEach((username, list) -> {
            List<Subscription> dead = new ArrayList<>();
            for (Subscription subscription : list) {
                try {
                    subscription.emitter().send(SseEmitter.event().comment("heartbeat"));
                } catch (Exception e) {
                    dead.add(subscription);
                }
            }
            list.removeAll(dead);
        });
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    /**
     * The generic notification email: title, message, and a link back into the app.
     * Dispatched onto the mail executor so a slow SMTP server never blocks the request
     * that produced the notification.
     */
    private void emailNotification(String username, String title, String message, String link) {
        if (!emailService.isConfigured()) return;

        String email = userRepository.findByUsername(username)
                .map(u -> u.getEmail())
                .orElse(null);
        if (email == null || email.isBlank()) {
            // Not an error — service accounts legitimately have no address — but it is
            // the difference between "no email sent" and "email failed", so say which.
            log.info("No email address for {}; notification delivered in-app only", username);
            return;
        }

        emailService.sendAsync(EmailMessage.builder()
                .to(email)
                .subject("Faction — " + title)
                .htmlBody(emailService.renderShell(
                        title,
                        emailService.paragraph(message),
                        link == null || link.isBlank() ? null : "View in Faction",
                        link,
                        null))
                .build());
    }
}
