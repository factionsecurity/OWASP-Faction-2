package com.faction.clientportal.repository;

import com.faction.clientportal.model.MentionQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MentionQueueRepository extends JpaRepository<MentionQueue, String> {

    List<MentionQueue> findByProcessedFalseAndScheduledForBefore(LocalDateTime now);

    boolean existsByMentionedUsernameAndContextLinkAndProcessedFalse(String mentionedUsername, String contextLink);

    /**
     * Dedup across a time window rather than only against unqueued rows.
     *
     * <p>The notebook autosaves every 1.5 seconds of idle typing and re-queues on each
     * save with a stable contextLink. Checking only {@code processed = false} means that
     * once an entry has been sent, the very next autosave queues another one — so the
     * same mention notifies repeatedly while someone edits a note. That was masked by the
     * old 30s + 60s timing, which was long enough to swallow a typing session; it is not
     * masked at the shorter interval.
     */
    boolean existsByMentionedUsernameAndContextLinkAndCreatedAtAfter(
            String mentionedUsername, String contextLink, LocalDateTime since);
}
