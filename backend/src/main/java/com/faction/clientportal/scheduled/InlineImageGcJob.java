package com.faction.clientportal.scheduled;

import com.faction.clientportal.model.InlineImage;
import com.faction.clientportal.service.InlineImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Nightly job that deletes orphaned inline images — images that are no longer
 * referenced in any assessment field value.
 *
 * A 24-hour grace period prevents deletion of images that were just uploaded
 * but whose parent field has not yet been saved.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InlineImageGcJob {

    private static final int GRACE_PERIOD_HOURS = 24;

    private final InlineImageService inlineImageService;

    @Scheduled(cron = "0 0 2 * * ?") // 2:00 AM every day
    public void run() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(GRACE_PERIOD_HOURS);
        List<InlineImage> candidates = inlineImageService.findCandidatesForGc(threshold);

        if (candidates.isEmpty()) {
            log.info("Inline image GC: no candidates found");
            return;
        }

        log.info("Inline image GC: checking {} candidate(s) uploaded before {}", candidates.size(), threshold);

        int deleted = 0;
        for (InlineImage image : candidates) {
            if (!inlineImageService.hasRefs(image.getId())) {
                inlineImageService.deleteImage(image);
                deleted++;
            }
        }

        log.info("Inline image GC complete: deleted {}/{} orphaned image(s)", deleted, candidates.size());
    }
}
