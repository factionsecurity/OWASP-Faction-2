package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

/**
 * Gives a fresh install a report template it can actually generate a report with.
 *
 * <p>Without one, the first thing a new user does — run an assessment and produce the report —
 * fails at the last step, and the fix is to know that a DOCX exists somewhere and go and find
 * it. So on first boot, and only when no template exists at all, fetch the project's default
 * pentest template and install it.
 *
 * <p>Best effort by construction. A download that fails, times out, or returns something that
 * is not a DOCX leaves the install exactly as it was and logs why; it never stops the
 * application starting. An air-gapped deployment can set the URL blank to skip the attempt
 * entirely.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultReportTemplateService {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** DOCX is a zip archive; every one starts "PK\003\004". */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    /** Guards against a redirect to something large; the real template is well under this. */
    private static final int MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024;

    private final ReportTemplateRepository reportTemplateRepository;
    private final AssessmentTypeRepository assessmentTypeRepository;
    private final StorageService storageService;

    @Value("${faction.default-report-template.url:}")
    private String templateUrl;

    @Value("${faction.default-report-template.name:Default Pentest Report}")
    private String templateName;

    /**
     * Install the default template if this install has none.
     *
     * <p>Called from bootstrap after assessment types are seeded — a template must belong to one,
     * so there is nothing to attach to before that.
     */
    public void ensureDefaultTemplate() {
        if (templateUrl == null || templateUrl.isBlank()) {
            log.debug("No default report template URL configured. Skipping.");
            return;
        }

        // Any template at all, not just this one: an install that has built its own templates and
        // deleted ours does not want it reappearing on the next restart.
        if (reportTemplateRepository.countByDeletedAtIsNull() > 0) {
            log.debug("Report templates already exist. Skipping default template bootstrap.");
            return;
        }

        AssessmentType assessmentType = assessmentTypeRepository.findAll().stream()
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .findFirst()
                .orElse(null);
        if (assessmentType == null) {
            log.warn("No assessment type to attach a report template to. Skipping default template.");
            return;
        }

        install(assessmentType, templateName);
    }

    /**
     * The template an assessment of this type gets when none was chosen for it — an assessment
     * created without one, or a scheduled successor whose predecessor's template has since been
     * deleted. In order: the type's template carrying the default name; failing that, the type's
     * only active template (the same one the create form would have auto-selected); failing
     * that, the project default is installed for this type, exactly as on first start.
     *
     * @throws IllegalStateException when nothing is usable and the default cannot be fetched —
     *         an assessment pointing at no template would only fail later, at report generation,
     *         where the cause is no longer visible.
     */
    public ReportTemplate resolveForAssessmentType(String assessmentTypeId) {
        List<ReportTemplate> active = reportTemplateRepository
                .findByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNull(assessmentTypeId, Pageable.unpaged())
                .getContent();
        Optional<ReportTemplate> named = active.stream()
                .filter(t -> templateName.equals(t.getName()))
                .findFirst();
        if (named.isPresent()) {
            return named.get();
        }
        if (active.size() == 1) {
            return active.get(0);
        }

        AssessmentType type = assessmentTypeRepository.findById(assessmentTypeId)
                .orElseThrow(() -> new IllegalStateException("Assessment type not found: " + assessmentTypeId));
        ReportTemplate installed = (templateUrl == null || templateUrl.isBlank())
                ? null : install(type, freeName(type));
        if (installed == null) {
            throw new IllegalStateException("No report template is available for " + type.getName()
                    + " and the default template could not be installed. Upload one under Report Designer.");
        }
        log.info("Installed the default report template for assessment type '{}' because no usable template existed.",
                type.getName());
        return installed;
    }

    /**
     * Template names are unique across the install, deleted ones included, so a second copy of
     * the default (another type, or a replacement for a deleted one) needs a name that is free.
     */
    private String freeName(AssessmentType type) {
        if (!reportTemplateRepository.existsByName(templateName)) return templateName;
        String base = templateName + " - " + type.getName();
        String candidate = base;
        for (int n = 2; reportTemplateRepository.existsByName(candidate); n++) {
            candidate = base + " (" + n + ")";
        }
        return candidate;
    }

    /**
     * Download the default DOCX and install it as an active template of the given type. Returns
     * {@code null} when the download or storage fails, having logged why and left nothing behind.
     */
    private ReportTemplate install(AssessmentType assessmentType, String name) {
        byte[] docx;
        try {
            docx = download(templateUrl);
        } catch (Exception e) {
            // Offline, firewalled, GitHub down, URL moved. None of these are reasons to fail
            // startup — the install simply comes up without a template, as it did before.
            log.warn("Could not download the default report template from {}: {}. "
                    + "The install starts without one; upload a template under Report Designer.",
                    templateUrl, e.getMessage());
            return null;
        }

        ReportTemplate template = ReportTemplate.builder()
                .name(name)
                .description("The project's default pentest report template, installed on first start.")
                .assessmentTypeId(assessmentType.getId())
                // The same stylesheet a hand-created template starts with. Without it the
                // installed template renders with no CSS at all — lists tab over, code has no
                // background — which is exactly the first impression this bootstrap exists to avoid.
                .css(ReportTemplateService.DEFAULT_TEMPLATE_CSS)
                .version(1)
                .sections(new ArrayList<>())
                .userDefinedFields(defaultFields())
                .active(true)
                .createdBy("system")
                .lastUpdatedBy("system")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        template = reportTemplateRepository.save(template);

        String key = "report-templates/" + template.getId() + "/default-report-template.docx";
        try {
            storageService.uploadBytes(key, docx, DOCX_CONTENT_TYPE);
        } catch (Exception e) {
            // Storage was not ready, or is misconfigured. A template row pointing at a file that
            // was never written generates a broken report, which is worse than having no template
            // — so take the row back out.
            reportTemplateRepository.delete(template);
            log.warn("Could not store the default report template: {}. Skipping.", e.getMessage());
            return null;
        }

        template.setTemplateFileId(key);
        template.setTemplateFileName("default-report-template.docx");
        template.setTemplateFileSize((long) docx.length);
        template.setTemplateFileContentType(DOCX_CONTENT_TYPE);
        template.setUpdatedAt(LocalDateTime.now());
        template = reportTemplateRepository.save(template);

        log.info("Installed the default report template '{}' ({} bytes) against assessment type '{}'.",
                name, docx.length, assessmentType.getName());
        return template;
    }

    /**
     * Fetch the template, following the redirect GitHub's /raw/ URLs issue.
     *
     * <p>Timeouts are the point of using HttpClient here rather than {@code URL.openStream()},
     * which has none: this runs during startup, so an endpoint that accepts a connection and
     * then says nothing would otherwise hang the boot indefinitely.
     */
    private byte[] download(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        byte[] body = response.body();
        if (body.length > MAX_DOWNLOAD_BYTES) {
            throw new IOException("file is larger than the " + MAX_DOWNLOAD_BYTES + " byte limit");
        }
        // A moved or deleted file gives a 404 HTML page, which arrives as a perfectly good
        // 200 from a redirect. Storing that as a template fails later, at report generation,
        // where the cause is no longer visible — so check it is a zip before believing it.
        if (!looksLikeDocx(body)) {
            throw new IOException("the response is not a DOCX file");
        }
        return body;
    }

    private boolean looksLikeDocx(byte[] body) {
        if (body.length < ZIP_MAGIC.length) return false;
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (body[i] != ZIP_MAGIC[i]) return false;
        }
        return true;
    }

    /**
     * The same two summary fields a hand-created template starts with; their variable names are
     * what the DOCX generator's built-in ${summary1}/${summary2} placeholders resolve against.
     */
    private List<UserDefinedField> defaultFields() {
        List<UserDefinedField> fields = new ArrayList<>();
        fields.add(UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .variableName("summary1")
                .displayName("Executive Summary")
                .fieldType(FieldType.RICH_TEXT)
                .fieldScope(FieldScope.ASSESSMENT)
                .displayOrder(0)
                .build());
        fields.add(UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .variableName("summary2")
                .displayName("Scope")
                .fieldType(FieldType.RICH_TEXT)
                .fieldScope(FieldScope.ASSESSMENT)
                .displayOrder(1)
                .build());
        return fields;
    }
}
