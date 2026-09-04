package com.faction.clientportal.scheduled;

import com.faction.clientportal.model.ContentTemplate;
import com.faction.clientportal.model.DefaultVulnerability;
import com.faction.clientportal.model.NotebookNode;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.ContentTemplateRepository;
import com.faction.clientportal.repository.DefaultVulnerabilityRepository;
import com.faction.clientportal.repository.NotebookNodeRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.service.InlineImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Indexes the inline images of content that already exists, once, at startup.
 *
 * <p>Without this the fix to the image reference index only protects content saved <em>after</em>
 * the upgrade. Everything already in the database still has no references, so the first nightly
 * {@link InlineImageGcJob} run after deploying would delete whatever screenshots had survived up
 * to that point — the upgrade itself would cost a customer a day's evidence, which is a poor
 * reward for installing the fix.
 *
 * <p>Safe to run on every boot: {@code updateRefsForField} reconciles rather than accumulates, so
 * a second pass over the same content is a no-op. It is still cheap, because only rows whose text
 * actually mentions an inline image are considered.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InlineImageBackfillRunner {

    /** The marker every inline image reference contains, used to skip rows with no images. */
    private static final String IMAGE_MARKER = "/api/v1/inline-images/";

    private final VulnerabilityRepository vulnerabilityRepository;
    private final NotebookNodeRepository notebookNodeRepository;
    private final ContentTemplateRepository contentTemplateRepository;
    private final DefaultVulnerabilityRepository defaultVulnerabilityRepository;
    private final InlineImageService inlineImageService;

    /** Escape hatch for an operator who would rather run this out of hours on a very large database. */
    @Value("${faction.inline-images.backfill-on-startup:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        if (!enabled) {
            log.info("Inline image reference backfill disabled by configuration");
            return;
        }
        try {
            int indexed = indexVulnerabilities() + indexNotes()
                    + indexContentTemplates() + indexDefaultVulnerabilities();
            if (indexed > 0) {
                log.info("Inline image reference backfill: indexed {} item(s) carrying images", indexed);
            }
        } catch (Exception e) {
            // Never stop the application from starting over this. The consequence of failing is
            // the pre-existing behaviour, not something worse.
            log.warn("Inline image reference backfill did not complete: {}", e.getMessage(), e);
        }
    }

    private int indexVulnerabilities() {
        int count = 0;
        for (Vulnerability v : vulnerabilityRepository.findByDeletedAtIsNull()) {
            if (!carriesImage(v.getDescription(), v.getRecommendation(), v.getDetails())
                    && !carriesImage(fieldValues(v))) {
                continue;
            }
            index(v.getAssessmentId(), "vulnerability/" + v.getId() + "/description", v.getDescription());
            index(v.getAssessmentId(), "vulnerability/" + v.getId() + "/recommendation", v.getRecommendation());
            index(v.getAssessmentId(), "vulnerability/" + v.getId() + "/details", v.getDetails());
            if (v.getFieldDefinitions() != null && v.getFieldValues() != null) {
                for (UserDefinedField definition : v.getFieldDefinitions()) {
                    if (definition == null || definition.getId() == null) continue;
                    index(v.getAssessmentId(),
                            "vulnerability/" + v.getId() + "/field/" + definition.getId(),
                            v.getFieldValues().get(definition.getId()));
                }
            }
            count++;
        }
        return count;
    }

    private int indexNotes() {
        int count = 0;
        for (NotebookNode node : notebookNodeRepository.findAll()) {
            if (node.getDeletedAt() != null || !carriesImage(node.getContent())) continue;
            inlineImageService.updateRefsForSharedField(
                    "notebook/" + node.getId() + "/content", node.getContent());
            count++;
        }
        return count;
    }

    private int indexContentTemplates() {
        int count = 0;
        for (ContentTemplate template : contentTemplateRepository.findAll()) {
            if (!carriesImage(template.getContent())) continue;
            inlineImageService.updateRefsForSharedField(
                    "content-template/" + template.getId() + "/content", template.getContent());
            count++;
        }
        return count;
    }

    private int indexDefaultVulnerabilities() {
        int count = 0;
        for (DefaultVulnerability vuln : defaultVulnerabilityRepository.findAll()) {
            if (!carriesImage(vuln.getDescription(), vuln.getRecommendation())) continue;
            inlineImageService.updateRefsForSharedField(
                    "default-vulnerability/" + vuln.getId() + "/description", vuln.getDescription());
            inlineImageService.updateRefsForSharedField(
                    "default-vulnerability/" + vuln.getId() + "/recommendation", vuln.getRecommendation());
            count++;
        }
        return count;
    }

    private void index(String assessmentId, String fieldId, String content) {
        if (assessmentId == null) return;
        inlineImageService.updateRefsForField(assessmentId, fieldId, content);
    }

    private static List<String> fieldValues(Vulnerability v) {
        return v.getFieldValues() == null ? List.of() : List.copyOf(v.getFieldValues().values());
    }

    private static boolean carriesImage(List<String> values) {
        return values.stream().anyMatch(value -> value != null && value.contains(IMAGE_MARKER));
    }

    private static boolean carriesImage(String... values) {
        for (String value : values) {
            if (value != null && value.contains(IMAGE_MARKER)) return true;
        }
        return false;
    }

    /** Exposed for the test, which needs to run the pass deterministically rather than on boot. */
    Map<String, Integer> runForTest() {
        return Map.of(
                "vulnerabilities", indexVulnerabilities(),
                "notes", indexNotes(),
                "contentTemplates", indexContentTemplates(),
                "defaultVulnerabilities", indexDefaultVulnerabilities());
    }
}
