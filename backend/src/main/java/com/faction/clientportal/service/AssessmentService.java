package com.faction.clientportal.service;

import com.faction.clientportal.dto.*;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentChecklistRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentSearchCriteria;
import com.faction.clientportal.repository.AssessmentSurveyRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.CampaignRepository;
import com.faction.clientportal.repository.ChecklistTemplateRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import org.springframework.security.core.Authentication;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentService {

    private final AccessScopeService accessScopeService;
    private final AssessmentRepository assessmentRepository;
    private final ObjectMapper objectMapper;
    private final ReportTemplateRepository reportTemplateRepository;
    private final ApplicationRepository applicationRepository;
    private final AssessmentTypeRepository assessmentTypeRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final InlineImageService inlineImageService;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final AssessmentWorkflowConfigService workflowConfigService;
    private final AssessmentChecklistRepository assessmentChecklistRepository;
    private final AssessmentSurveyRepository assessmentSurveyRepository;
    private final ChecklistTemplateRepository checklistTemplateRepository;
    private final NotebookService notebookService;
    private final NotificationService notificationService;
    private final VulnerabilityEventService vulnerabilityEventService;
    private final ApplicationIdConfigService applicationIdConfigService;
    private final ApplicationService applicationService;
    private final CampaignRepository campaignRepository;
    private final com.faction.clientportal.service.extension.ExtensionEventService extensionEventService;
    private final com.faction.clientportal.service.email.EventNotificationEmailSender eventEmailSender;

    /**
     * Create a new assessment from a report template
     * Snapshots the template's field definitions at creation time
     */
    public AssessmentDto createAssessment(CreateAssessmentRequest request, String userId) {
        Application application;
        if (org.springframework.util.StringUtils.hasText(request.getApplicationId())) {
            application = applicationRepository.findById(request.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + request.getApplicationId()));
        } else if (org.springframework.util.StringUtils.hasText(request.getAppId())) {
            application = applicationRepository.findByAppId(request.getAppId()).orElse(null);
            if (application == null) {
                String appName = org.springframework.util.StringUtils.hasText(request.getApplicationName())
                    ? request.getApplicationName()
                    : request.getName();
                application = createApplicationFromAssessment(request.getAppId(), appName, userId);
            }
        } else {
            if (org.springframework.util.StringUtils.hasText(request.getName())) {
                String appName = org.springframework.util.StringUtils.hasText(request.getApplicationName())
                    ? request.getApplicationName()
                    : request.getName();
                application = createApplicationFromAssessment(null, appName, userId);
            } else {
                throw new IllegalArgumentException("Either applicationId or appId must be provided, or assessment name must be set for auto-creation");
            }
        }

        // Verify assessment type exists
        assessmentTypeRepository.findById(request.getAssessmentTypeId())
            .orElseThrow(() -> new ResourceNotFoundException("Assessment type not found with id: " + request.getAssessmentTypeId()));

        // Get report template and verify it can still be used. Deleted is looked up
        // separately from inactive so the message names the real problem: "not active" on a
        // template that has actually been deleted sends people to a toggle that isn't there.
        ReportTemplate template = reportTemplateRepository.findByIdAndDeletedAtIsNull(request.getReportTemplateId())
            .orElseThrow(() -> reportTemplateRepository.existsById(request.getReportTemplateId())
                ? new IllegalArgumentException(
                    "Report template has been deleted and cannot be used for new assessments")
                : new ResourceNotFoundException(
                    "Report template not found with id: " + request.getReportTemplateId()));

        if (!template.getActive()) {
            throw new IllegalArgumentException("Report template is not active: " + template.getName());
        }

        // Verify template's assessment type matches request
        if (!template.getAssessmentTypeId().equals(request.getAssessmentTypeId())) {
            throw new IllegalArgumentException(
                "Report template assessment type does not match. Expected: " + request.getAssessmentTypeId() +
                ", Template has: " + template.getAssessmentTypeId()
            );
        }

        // Snapshot template data — only ASSESSMENT-scoped fields
        List<UserDefinedField> fieldDefinitionsSnapshot = template.getUserDefinedFields().stream()
            .filter(f -> f.getFieldScope() == null || f.getFieldScope() == FieldScope.ASSESSMENT)
            .collect(Collectors.toList());

        // Validate initial field values if provided
        Map<String, String> fieldValues = new HashMap<>();
        if (request.getInitialFieldValues() != null && !request.getInitialFieldValues().isEmpty()) {
            fieldValues = validateFieldValues(request.getInitialFieldValues(), fieldDefinitionsSnapshot);
        }

        // Auto-populate stakeholders from application if not provided
        List<Stakeholder> stakeholders = new ArrayList<>();
        if (request.getStakeholders() != null && !request.getStakeholders().isEmpty()) {
            stakeholders = request.getStakeholders().stream()
                .map(StakeholderDto::toEntity)
                .collect(Collectors.toList());
        } else if (application.getStakeHolders() != null && !application.getStakeHolders().isEmpty()) {
            // Copy stakeholders from application (new instances so edits on one don't leak to the other)
            stakeholders = application.getStakeHolders().stream()
                .map(sh -> Stakeholder.builder()
                    .name(sh.getName())
                    .email(sh.getEmail())
                    .role(sh.getRole())
                    .build())
                .collect(Collectors.toList());
        }

        // Handle assessorIds - support both legacy assessorId and new assessorIds
        List<String> assessorIds = new ArrayList<>();
        if (request.getAssessorIds() != null && !request.getAssessorIds().isEmpty()) {
            assessorIds = new ArrayList<>(request.getAssessorIds());
        } else if (request.getAssessorId() != null) {
            assessorIds.add(request.getAssessorId());
        }

        // Convert EngagementUrlDto to EngagementUrl
        List<EngagementUrl> engagementUrls = request.getEngagementUrls() != null
            ? request.getEngagementUrls().stream()
                .map(EngagementUrlDto::toEntity)
                .collect(Collectors.toList())
            : new ArrayList<>();

        // Create assessment
        Assessment assessment = Assessment.builder()
            .name(request.getName())
            .applicationId(application.getId())
            .assessmentTypeId(request.getAssessmentTypeId())
            .organizationId(application.getOrganizationId())
            .campaignId(request.getCampaignId())
            .teamId(request.getTeamId())
            .reportTemplateId(template.getId())
            .reportTemplateVersion(template.getVersion())
            .templateName(template.getName())
            .templateCss(template.getCss())
            .templateFont(template.getFont())
            .templateFileId(template.getTemplateFileId())
            .scoringType(template.getScoringType())
            .sections(template.getSections() != null ? new ArrayList<>(template.getSections()) : new ArrayList<>())
            .fieldDefinitions(fieldDefinitionsSnapshot)
            .fieldValues(fieldValues)
            .status(workflowConfigService.getConfig().getNewAssessmentStatus())
            .assessorId(request.getAssessorId()) // Legacy field
            .assessorIds(assessorIds)
            .engagementManagerId(request.getEngagementManagerId())
            .remediationManagerId(request.getRemediationManagerId())
            .assessmentDate(LocalDateTime.now())
            .startDate(request.getStartDate())
            .plannedEndDate(request.getPlannedEndDate())
            .scope(request.getScope())
            .engagementUrls(engagementUrls)
            .stakeholders(stakeholders)
            .createdBy(userId)
            .lastUpdatedBy(userId)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        Assessment savedAssessment = assessmentRepository.save(assessment);
        // Field values supplied at creation were never indexed — only updateAssessment did it —
        // so a screenshot in a field of an assessment nobody edited again was deleted by the GC
        // a day later. The id only exists after the save, which is why this is not up with the
        // validation.
        for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
            inlineImageService.updateRefsForField(
                    savedAssessment.getId(), entry.getKey(), entry.getValue());
        }
        log.info("Created assessment: {} from template: {} (version: {})",
            savedAssessment.getName(), template.getName(), template.getVersion());

        // Notify assigned assessors and managers
        String assessmentLink = "/assessments/" + savedAssessment.getId();
        notifyUsers(savedAssessment.getAssessorIds(), savedAssessment.getName(), assessmentLink, "ASSESSOR_ASSIGNED");
        notifyUserById(savedAssessment.getEngagementManagerId(), savedAssessment.getName(), assessmentLink, "ASSESSMENT_CREATED");
        notifyUserById(savedAssessment.getRemediationManagerId(), savedAssessment.getName(), assessmentLink, "ASSESSMENT_CREATED");

        // Stakeholders and the app owner hear about it through the admin-configured
        // routing table rather than the per-user notification preferences: they are
        // addresses on an application, not accounts with a preference of their own.
        emailAssessmentEvent(com.faction.clientportal.model.EmailNotificationEvent.ASSESSMENT_CREATED,
                savedAssessment, null);

        // Announce the new assessment in the application's chat
        String scheduledMsg = "**Assessment scheduled**: \"" + savedAssessment.getName() + "\" by {actor}"
            + (savedAssessment.getStartDate() != null
                ? ", starting " + savedAssessment.getStartDate().toLocalDate()
                : "")
            + ".";
        applicationService.addSystemComment(savedAssessment.getApplicationId(), scheduledMsg, userId);

        // Auto-create a root notebook node for this assessment
        notebookService.createRootNodeForAssessment(
            savedAssessment.getApplicationId(),
            savedAssessment.getId(),
            savedAssessment.getName(),
            savedAssessment.getStartDate(),
            userId
        );

        extensionEventService.assessmentChanged(
            savedAssessment.getId(), com.faction.extender.AssessmentManager.Operation.Create);

        return migrateAndConvertToDto(savedAssessment);
    }

    /**
     * Update an assessment
     */
    public AssessmentDto updateAssessment(String id, UpdateAssessmentRequest request, String userId) {
        return updateAssessment(id, request, userId, null);
    }

    /**
     * As {@link #updateAssessment(String, UpdateAssessmentRequest, String)}, but enforcing the
     * caller's edit scope: holding an edit permission isn't enough, it has to cover <em>this</em>
     * assessment. Internal callers (peer review, schedulers) use the unauthenticated overload.
     */
    public AssessmentDto updateAssessment(String id, UpdateAssessmentRequest request, String userId,
                                          Authentication authentication) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("Assessment not found with id: " + id));

        // Out-of-scope reads already 404; an out-of-scope write is an explicit denial.
        accessScopeService.checkAssessmentEditAccess(authentication, assessment);

        // Peer-review lock guard — block field edits while a review is in flight.
        // Status-only updates (from the peer review service itself) are permitted.
        if (request.getStatus() == null || request.getName() != null
                || request.getFieldValues() != null || request.getAssessorIds() != null
                || request.getEngagementManagerId() != null || request.getRemediationManagerId() != null
                || request.getStartDate() != null || request.getPlannedEndDate() != null
                || request.getScope() != null || request.getEngagementUrls() != null
                || request.getStakeholders() != null || request.getReportTemplateId() != null
                || request.getTeamId() != null) {
            com.faction.clientportal.model.AssessmentPeerReviewStatus prStatus = assessment.getPeerReviewStatus();
            if (prStatus == com.faction.clientportal.model.AssessmentPeerReviewStatus.IN_PEER_REVIEW
                    || prStatus == com.faction.clientportal.model.AssessmentPeerReviewStatus.NEEDS_ACCEPTANCE) {
                throw new IllegalStateException("Assessment is locked for peer review");
            }
        }

        // Update name
        if (request.getName() != null) {
            assessment.setName(request.getName());
        }

        // Update assessment type. The report template is chosen per type, so a type change has to
        // arrive with a template of the new type (or leave the existing template already matching);
        // otherwise the assessment would keep field definitions that belong to the old type.
        if (request.getAssessmentTypeId() != null
                && !request.getAssessmentTypeId().equals(assessment.getAssessmentTypeId())) {
            assessmentTypeRepository.findById(request.getAssessmentTypeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Assessment type not found with id: " + request.getAssessmentTypeId()));
            String templateId = request.getReportTemplateId() != null
                    ? request.getReportTemplateId() : assessment.getReportTemplateId();
            if (templateId != null) {
                ReportTemplate template = reportTemplateRepository.findById(templateId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                        "Report template not found with id: " + templateId));
                if (!request.getAssessmentTypeId().equals(template.getAssessmentTypeId())) {
                    throw new IllegalArgumentException(
                        "Report template assessment type does not match. Expected: "
                            + request.getAssessmentTypeId() + ", Template has: " + template.getAssessmentTypeId());
                }
            }
            assessment.setAssessmentTypeId(request.getAssessmentTypeId());
        }

        // Update campaign (empty string clears the assignment)
        if (request.getCampaignId() != null) {
            assessment.setCampaignId(request.getCampaignId().isEmpty() ? null : request.getCampaignId());
        }

        // Update application
        if (request.getApplicationId() != null
                && !request.getApplicationId().equals(assessment.getApplicationId())) {
            Application newApp = applicationRepository.findById(request.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + request.getApplicationId()));
            assessment.setApplicationId(request.getApplicationId());
            if (newApp.getOrganizationId() != null) {
                assessment.setOrganizationId(newApp.getOrganizationId());
            }
        }

        // Update team
        if (request.getTeamId() != null) {
            assessment.setTeamId(request.getTeamId().isBlank() ? null : request.getTeamId());
        }

        // Update field values with validation
        if (request.getFieldValues() != null) {
            Map<String, String> validatedValues = validateFieldValues(
                request.getFieldValues(),
                assessment.getFieldDefinitions()
            );
            // Merge with existing values
            assessment.getFieldValues().putAll(validatedValues);
            // Update inline image reference index for each saved field.
            // Assessment fields are not a mention surface — the @ picker is limited to
            // application comments, vulnerability comments and assessment notes.
            for (Map.Entry<String, String> entry : validatedValues.entrySet()) {
                inlineImageService.updateRefsForField(id, entry.getKey(), entry.getValue());
            }
        }

        // Whether this update is the transition into a completed status. Extensions
        // distinguish Finalize from an ordinary Update — it is the point an
        // integration pushes findings out to an issue tracker — so the transition has
        // to be noticed here, while the previous status is still known.
        boolean finalizing = false;

        // Captured before any field is touched: the "assessment changed" email quotes the
        // status it moved away from, and by the time that email is built the entity has
        // already been mutated.
        String previousStatus = assessment.getStatus();

        // Update status
        if (request.getStatus() != null) {
            String oldStatus = previousStatus;

            // Reopening: a completed assessment can be returned to an open status only inside the
            // reopen window. Past that it is a historical record — its findings have been reported
            // and their SLA clocks are running — so correcting it becomes a deliberate act rather
            // than an edit anyone with assessment access can make.
            if (workflowConfigService.isCompletedStatus(oldStatus)
                    && !workflowConfigService.isCompletedStatus(request.getStatus())) {
                if (!withinReopenWindow(assessment)) {
                    throw new IllegalArgumentException(
                            "This assessment was completed more than " + REOPEN_WINDOW_DAYS
                                    + " days ago and can no longer be reopened");
                }
                // No longer completed: clear the stamp so a later completion starts a fresh window.
                assessment.setCompletedDate(null);
            }

            // Block finalization if any preventClosure checklists have unanswered questions
            if (workflowConfigService.isCompletedStatus(request.getStatus())
                    && !workflowConfigService.isCompletedStatus(oldStatus)) {
                List<com.faction.clientportal.model.AssessmentChecklist> checklists =
                        assessmentChecklistRepository.findByAssessmentId(assessment.getId());
                List<String> blocking = checklists.stream()
                        .filter(cl -> {
                            com.faction.clientportal.model.ChecklistTemplate tpl =
                                    checklistTemplateRepository.findById(cl.getTemplateId()).orElse(null);
                            return tpl != null && tpl.isPreventClosure();
                        })
                        .filter(cl -> cl.getResponses() != null &&
                                cl.getResponses().stream().anyMatch(r -> r.getResult() == null))
                        .map(cl -> cl.getTemplateName())
                        .collect(Collectors.toList());
                if (!blocking.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Cannot finalize: the following checklists have unanswered questions: " +
                            String.join(", ", blocking));
                }
            }

            String completedStatus = workflowConfigService.getConfig().getCompletedStatus();
            assessment.setStatus(request.getStatus());

            // Set completion date when status changes to a completed state
            if (workflowConfigService.isCompletedStatus(request.getStatus())
                    && !workflowConfigService.isCompletedStatus(oldStatus)) {
                // An import of historical work supplies the real completion date; everything else
                // is being completed right now.
                assessment.setCompletedDate(request.getCompletedDate() != null
                        ? request.getCompletedDate() : LocalDateTime.now());
                finalizing = true;

                // Mark all unfinalized vulnerabilities as opened on this date
                LocalDateTime now = LocalDateTime.now();
                // The remediation manager picked when the assessment was scheduled becomes each
                // finding's remediation owner. Resolved once here — findings inherit it at the
                // moment they open, and it is reassignable per finding from then on.
                User remediationOwner = assessment.getRemediationManagerId() == null ? null
                        : userRepository.findById(assessment.getRemediationManagerId()).orElse(null);
                List<com.faction.clientportal.model.Vulnerability> vulns =
                        vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(assessment.getId());
                for (com.faction.clientportal.model.Vulnerability v : vulns) {
                    if (v.getOpenedAt() == null) {
                        v.setOpenedAt(now);
                        // Transition from None → Open when first opened
                        if (v.getStatus() == null || "None".equals(v.getStatus())) {
                            v.setStatus("Open");
                        }
                        // Only seed an unowned finding: a reopened assessment must not undo a
                        // reassignment someone made while it was closed.
                        if (remediationOwner != null && v.getRemediationOwnerId() == null) {
                            v.setRemediationOwnerId(remediationOwner.getId());
                            subscribeToThread(v, remediationOwner.getUsername());
                        }
                    }
                }
                vulnerabilityRepository.saveAll(vulns);

                // Announce completion in the application's chat
                applicationService.addSystemComment(assessment.getApplicationId(),
                    "**Assessment completed**: \"" + assessment.getName() + "\" was marked "
                        + request.getStatus() + " by {actor}.",
                    userId);
            }

            if (request.getCompletedDate() != null
                    && workflowConfigService.isCompletedStatus(request.getStatus())) {
                assessment.setCompletedDate(request.getCompletedDate());
            }

            // Set peer review date when status changes to PENDING_REVIEW (legacy)
            if ("PENDING_REVIEW".equals(request.getStatus()) && !"PENDING_REVIEW".equals(oldStatus)) {
                assessment.setPeerReviewedAt(LocalDateTime.now());
            }

            // Set assessment date when status first moves into an active state
            if (assessment.getAssessmentDate() == null && !request.getStatus().equals(oldStatus)) {
                assessment.setAssessmentDate(LocalDateTime.now());
            }
        }

        // Update assessor (legacy)
        if (request.getAssessorId() != null) {
            assessment.setAssessorId(request.getAssessorId());
        }

        // Update assessorIds
        if (request.getAssessorIds() != null) {
            List<String> previous = assessment.getAssessorIds() != null ? assessment.getAssessorIds() : List.of();
            List<String> newlyAdded = request.getAssessorIds().stream()
                    .filter(aid -> !previous.contains(aid))
                    .collect(Collectors.toList());
            assessment.setAssessorIds(request.getAssessorIds());
            notifyUsers(newlyAdded, assessment.getName(), "/assessments/" + assessment.getId(), "ASSESSOR_ASSIGNED");
        }

        // Update engagement manager
        if (request.getEngagementManagerId() != null) {
            assessment.setEngagementManagerId(request.getEngagementManagerId());
        }

        // Update remediation manager
        if (request.getRemediationManagerId() != null) {
            assessment.setRemediationManagerId(request.getRemediationManagerId());
        }

        // Update dates
        if (request.getStartDate() != null) {
            assessment.setStartDate(request.getStartDate());
        }

        if (request.getPlannedEndDate() != null) {
            assessment.setPlannedEndDate(request.getPlannedEndDate());
        }

        // Update scope
        if (request.getScope() != null) {
            assessment.setScope(request.getScope());
        }

        // Update engagement URLs
        if (request.getEngagementUrls() != null) {
            List<EngagementUrl> engagementUrls = request.getEngagementUrls().stream()
                .map(EngagementUrlDto::toEntity)
                .collect(Collectors.toList());
            assessment.setEngagementUrls(engagementUrls);
        }

        // Update stakeholders
        if (request.getStakeholders() != null) {
            List<Stakeholder> stakeholders = request.getStakeholders().stream()
                .map(StakeholderDto::toEntity)
                .collect(Collectors.toList());
            assessment.setStakeholders(stakeholders);
        }

        // Switch report template: re-snapshot metadata and force a field-definition re-sync
        if (request.getReportTemplateId() != null
                && !request.getReportTemplateId().equals(assessment.getReportTemplateId())) {
            ReportTemplate newTemplate = reportTemplateRepository
                    .findById(request.getReportTemplateId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Report template not found: " + request.getReportTemplateId()));
            assessment.setReportTemplateId(newTemplate.getId());
            assessment.setTemplateName(newTemplate.getName());
            assessment.setTemplateCss(newTemplate.getCss());
            assessment.setTemplateFileId(newTemplate.getTemplateFileId());
            if (newTemplate.getScoringType() != null) {
                assessment.setScoringType(newTemplate.getScoringType());
            }
            assessment.setSections(newTemplate.getSections() != null
                    ? new ArrayList<>(newTemplate.getSections()) : new ArrayList<>());
            // Force version mismatch so syncFieldDefinitionsIfNeeded always re-syncs
            assessment.setReportTemplateVersion(-1);
            log.info("Assessment {} template switched to {} ({})",
                    assessment.getId(), newTemplate.getName(), newTemplate.getId());
        }

        assessment.setLastUpdatedBy(userId);
        assessment.setUpdatedAt(LocalDateTime.now());

        // Sync field definitions if template version changed (including the switch above)
        syncFieldDefinitionsIfNeeded(assessment);

        Assessment updatedAssessment = assessmentRepository.save(assessment);
        log.info("Updated assessment: {} (status: {})", updatedAssessment.getName(), updatedAssessment.getStatus());

        extensionEventService.assessmentChanged(updatedAssessment.getId(),
            finalizing ? com.faction.extender.AssessmentManager.Operation.Finalize
                       : com.faction.extender.AssessmentManager.Operation.Update);

        // Completing an assessment is also a change, but only the completion email is
        // sent: two emails describing one save reads as a bug.
        if (finalizing) {
            emailAssessmentEvent(com.faction.clientportal.model.EmailNotificationEvent.ASSESSMENT_COMPLETED,
                    updatedAssessment, null);
        } else {
            emailAssessmentEvent(com.faction.clientportal.model.EmailNotificationEvent.ASSESSMENT_CHANGED,
                    updatedAssessment, previousStatus);
        }

        return migrateAndConvertToDto(updatedAssessment);
    }

    /**
     * Get an assessment by ID
     */
    public AssessmentDto getAssessment(String id) {
        return getAssessment(id, null);
    }

    public AssessmentDto getAssessment(String id, Authentication authentication) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("Assessment not found with id: " + id));

        // Out of scope reads as "not found" rather than 403, so ids in other orgs / other
        // pentesters' assessments can't be probed. Same tiers the list query applies.
        if (!accessScopeService.resolveAssessmentScope(authentication).permits(assessment)) {
            throw new ResourceNotFoundException("Assessment not found with id: " + id);
        }

        syncFieldDefinitionsIfNeeded(assessment);
        applyDateTransitionIfNeeded(assessment);
        return migrateAndConvertToDto(assessment);
    }

    /**
     * Sync the assessment's snapshotted field definitions with the live template.
     * Uses variableName as the stable key so that existing fieldValues (keyed by snapshot ID)
     * remain valid after the sync. Runs only when the template version has advanced.
     */
    private void syncFieldDefinitionsIfNeeded(Assessment assessment) {
        if (assessment.getReportTemplateId() == null) return;
        ReportTemplate template = reportTemplateRepository
            .findById(assessment.getReportTemplateId()).orElse(null);
        if (template == null) return;

        boolean dirty = false;

        // ── Always sync sections (no version gate) ───────────────────────────
        List<String> templateSections = template.getSections() != null
            ? template.getSections() : new ArrayList<>();
        List<String> assessmentSections = assessment.getSections() != null
            ? assessment.getSections() : new ArrayList<>();
        if (!templateSections.equals(assessmentSections)) {
            assessment.setSections(new ArrayList<>(templateSections));
            dirty = true;
            log.info("Synced sections for assessment {} from template", assessment.getId());
        }

        // ── Always sync the template DOCX file + CSS (no version gate) ───────
        // Assessments created before a DOCX was uploaded to the template snapshot
        // a null templateFileId; report generation needs the live template's file.
        if (template.getTemplateFileId() != null
                && !template.getTemplateFileId().equals(assessment.getTemplateFileId())) {
            assessment.setTemplateFileId(template.getTemplateFileId());
            dirty = true;
            log.info("Synced template file for assessment {} from template", assessment.getId());
        }
        if (template.getCss() != null && !template.getCss().equals(assessment.getTemplateCss())) {
            assessment.setTemplateCss(template.getCss());
            dirty = true;
        }
        if (template.getFont() != null && !template.getFont().equals(assessment.getTemplateFont())) {
            assessment.setTemplateFont(template.getFont());
            dirty = true;
        }

        // ── Sync field definitions only when template version advanced ───────
        Integer templateVersion = template.getVersion() != null ? template.getVersion() : 1;
        Integer assessmentVersion = assessment.getReportTemplateVersion() != null
            ? assessment.getReportTemplateVersion() : 0;

        if (!templateVersion.equals(assessmentVersion)) {
            // Build lookup of existing snapshot fields by variableName → snapshot ID
            List<UserDefinedField> snapshot = assessment.getFieldDefinitions() != null
                ? assessment.getFieldDefinitions() : new ArrayList<>();
            Map<String, String> varNameToSnapshotId = new HashMap<>();
            for (UserDefinedField f : snapshot) {
                varNameToSnapshotId.put(f.getVariableName(), f.getId());
            }

            // Only ASSESSMENT-scoped fields belong in an assessment snapshot.
            // null fieldScope is treated as ASSESSMENT for backwards compatibility with
            // records that existed before the fieldScope feature was introduced.
            // Sort by displayOrder so the assessment always reflects the template's field order.
            List<UserDefinedField> assessmentFields = template.getUserDefinedFields().stream()
                .filter(f -> f.getFieldScope() == null || f.getFieldScope() == FieldScope.ASSESSMENT)
                .sorted(Comparator.comparingInt(f -> f.getDisplayOrder() != null ? f.getDisplayOrder() : 0))
                .collect(Collectors.toList());

            // Merge: for each ASSESSMENT-scoped template field, keep snapshot ID if it existed, otherwise use template ID
            List<UserDefinedField> merged = new ArrayList<>();
            for (UserDefinedField tf : assessmentFields) {
                UserDefinedField copy = deepCopyField(tf);
                String existingId = varNameToSnapshotId.get(tf.getVariableName());
                if (existingId != null) {
                    copy.setId(existingId); // preserve so fieldValues keys remain valid
                }
                merged.add(copy);
            }

            // Remove fieldValues whose field IDs are no longer in merged
            Set<String> validIds = merged.stream().map(UserDefinedField::getId).collect(Collectors.toSet());
            if (assessment.getFieldValues() != null) {
                assessment.getFieldValues().entrySet().removeIf(e -> !validIds.contains(e.getKey()));
            }

            assessment.setFieldDefinitions(merged);
            assessment.setReportTemplateVersion(templateVersion);
            dirty = true;
            log.info("Synced field definitions for assessment {} to template version {}",
                assessment.getId(), templateVersion);
        }

        if (dirty) {
            assessmentRepository.save(assessment);
        }
    }

    /**
     * Automatically transitions the assessment status to inProgressStatus when today
     * falls within the [startDate, plannedEndDate] window and the assessment is still
     * in the newAssessmentStatus. This prevents overriding statuses that have been
     * manually advanced past the initial state.
     */
    private void applyDateTransitionIfNeeded(Assessment assessment) {
        if (assessment.getStartDate() == null || assessment.getPlannedEndDate() == null) return;
        if (workflowConfigService.isCompletedStatus(assessment.getStatus())) return;

        var config = workflowConfigService.getConfig();
        String newStatus = config.getNewAssessmentStatus();
        String inProgressStatus = config.getInProgressStatus();

        LocalDateTime now = LocalDateTime.now();
        boolean inWindow = !now.isBefore(assessment.getStartDate()) && !now.isAfter(assessment.getPlannedEndDate());
        if (inWindow && newStatus.equals(assessment.getStatus())) {
            assessment.setStatus(inProgressStatus);
            assessment.setUpdatedAt(LocalDateTime.now());
            assessmentRepository.save(assessment);
            log.info("Auto-transitioned assessment {} to {} (date window active)",
                    assessment.getId(), inProgressStatus);
        }
    }

    private UserDefinedField deepCopyField(UserDefinedField src) {
        UserDefinedField copy = new UserDefinedField();
        copy.setId(src.getId());
        copy.setVariableName(src.getVariableName());
        copy.setDisplayName(src.getDisplayName());
        copy.setFieldType(src.getFieldType());
        copy.setRequired(src.getRequired());
        copy.setHelpText(src.getHelpText());
        copy.setMinLength(src.getMinLength());
        copy.setMaxLength(src.getMaxLength());
        copy.setDropdownOptions(src.getDropdownOptions() != null
            ? new ArrayList<>(src.getDropdownOptions()) : null);
        copy.setDefaultValue(src.getDefaultValue());
        copy.setDisplayOrder(src.getDisplayOrder());
        copy.setFieldScope(src.getFieldScope());
        return copy;
    }

    /**
     * Search assessments with pagination
     */
    public Page<AssessmentDto> searchAssessments(
        String applicationId,
        String organizationId,
        String assessmentTypeId,
        String assessorId,
        String status,
        String name,
        Pageable pageable,
        Authentication authentication
    ) {
        if (isOrgScopedUser(authentication)) {
            organizationId = resolveOrgId(authentication);
        }
        return searchAssessments(applicationId, organizationId, assessmentTypeId, assessorId, status, name, pageable);
    }

    public Page<AssessmentDto> searchAssessments(
        String applicationId,
        String organizationId,
        String assessmentTypeId,
        String assessorId,
        String status,
        String name,
        Pageable pageable
    ) {
        Page<Assessment> assessments;

        if (applicationId != null && assessmentTypeId != null) {
            assessments = assessmentRepository.findByApplicationIdAndAssessmentTypeIdAndDeletedAtIsNull(
                applicationId, assessmentTypeId, pageable
            );
        } else if (applicationId != null) {
            assessments = assessmentRepository.findByApplicationIdAndDeletedAtIsNull(applicationId, pageable);
        } else if (organizationId != null) {
            assessments = assessmentRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId, pageable);
        } else if (assessorId != null) {
            assessments = assessmentRepository.findByAssessorIdAndDeletedAtIsNull(assessorId, pageable);
        } else if (status != null) {
            assessments = assessmentRepository.findByStatusAndDeletedAtIsNull(status, pageable);
        } else if (name != null && !name.isEmpty()) {
            assessments = assessmentRepository.searchByName(name, pageable);
        } else {
            assessments = assessmentRepository.findAll(pageable);
        }

        return assessments.map(this::migrateAndConvertToDto);
    }

    /**
     * Advanced search with multiple filters
     */
    public Page<AssessmentDto> searchAssessmentsAdvanced(
        String search,
        String applicationId,
        Collection<String> applicationIds,
        String organizationId,
        String assessmentTypeId,
        String assessorId,
        String status,
        LocalDateTime startDateFrom,
        LocalDateTime startDateTo,
        LocalDateTime endDateFrom,
        LocalDateTime endDateTo,
        Boolean pastDue,
        Boolean showCompleted,
        Boolean assignedToMe,
        String currentUserId,
        Pageable pageable,
        Authentication authentication
    ) {
        return searchAssessmentsAdvanced(search, applicationId, applicationIds, organizationId, assessmentTypeId, assessorId,
            status, null, null, startDateFrom, startDateTo, endDateFrom, endDateTo, pastDue, showCompleted, assignedToMe,
            currentUserId, null, null, null, pageable, authentication);
    }

    /**
     * Advanced search with multiple filters, including the manager-dashboard-only
     * dimensions: team (any assessor belongs to the team), campaign, and vulnerability
     * severity (assessment has at least one opened vulnerability of a selected severity).
     */
    public Page<AssessmentDto> searchAssessmentsAdvanced(
        String search,
        String applicationId,
        Collection<String> applicationIds,
        String organizationId,
        String assessmentTypeId,
        String assessorId,
        String status,
        Collection<String> statuses,
        Boolean openSurveysOnly,
        LocalDateTime startDateFrom,
        LocalDateTime startDateTo,
        LocalDateTime endDateFrom,
        LocalDateTime endDateTo,
        Boolean pastDue,
        Boolean showCompleted,
        Boolean assignedToMe,
        String currentUserId,
        String teamId,
        String campaignId,
        List<VulnerabilitySeverity> severities,
        Pageable pageable,
        Authentication authentication
    ) {
        // Force-scope the result set to what the caller may read. The tiers are resolved centrally
        // (AccessScopeService#resolveAssessmentScope) and applied here as mandatory query filters —
        // an org-scoped caller can never query another org, and a team- or assigned-scoped pentester
        // only ever sees their team's / their own assessments regardless of the filters they pass.
        var scope = accessScopeService.resolveAssessmentScope(authentication);
        if (scope.denied()) {
            return Page.empty(pageable);
        }
        java.util.Set<String> ownedAppIds = null;
        java.util.Set<String> scopeTeamIds = null;
        String scopeAssessorId = null;
        switch (scope.kind()) {
            case ORG -> organizationId = scope.orgId();
            case OWNED -> ownedAppIds = scope.appIds();
            case TEAM -> scopeTeamIds = scope.teamIds();
            case ASSIGNED -> scopeAssessorId = scope.assessorId();
            default -> { /* unrestricted — the caller's own filters stand */ }
        }

        final java.util.Set<String> effectiveOwnedAppIds = ownedAppIds;
        final java.util.Set<String> effectiveScopeTeamIds = scopeTeamIds;
        final String effectiveScopeAssessorId = scopeAssessorId;
        final String effectiveOrgId = organizationId;
        final String effectiveAppId = applicationId;

        // Resolve team membership once, before the stream (avoids a per-assessment lookup)
        final java.util.Set<String> teamMemberIds = teamId != null
            ? userRepository.findByTeamIdsContaining(teamId).stream()
                .map(User::getId)
                .collect(Collectors.toSet())
            : null;

        log.info("Advanced search - Filters: search={}, appId={}, orgId={}, typeId={}, assessorId={}, status={}, " +
                "pastDue={}, showCompleted={}, assignedToMe={}, currentUserId={}",
                search, effectiveAppId, effectiveOrgId, assessmentTypeId, assessorId, status,
                pastDue, showCompleted, assignedToMe, currentUserId);

        // Resolve the completed-status set once (isCompletedStatus() otherwise hits the
        // uncached singleton config per row) and translate the filters into criteria the
        // repository turns into a single DB query — no findAll() + in-memory scan.
        var completed = completedStatuses();
        var severityOrdinals = (severities == null || severities.isEmpty())
                ? null
                : severities.stream().map(Enum::ordinal).toList();

        // Statuses are user-configured strings, so compare case-insensitively like the single-status
        // filter does; the repository expects them already lower-cased.
        var statusFilter = (statuses == null || statuses.isEmpty()) ? null
                : statuses.stream().filter(st -> st != null && !st.isBlank())
                        .map(st -> st.trim().toLowerCase()).distinct().toList();
        // "Open surveys" lives in another table, so resolve it to assessment ids the query can
        // intersect with. An empty result means nothing matches — not "no filter".
        var openSurveyIds = Boolean.TRUE.equals(openSurveysOnly)
                ? assessmentSurveyRepository.findAssessmentIdsWithStatusNot(SurveyStatus.COMPLETE)
                : null;

        var criteria = AssessmentSearchCriteria.builder()
                .search(search)
                .applicationId(effectiveAppId)
                .applicationIds(applicationIds)
                .organizationId(effectiveOrgId)
                .ownedAppIds(effectiveOwnedAppIds)
                .assessmentTypeId(assessmentTypeId)
                .assessorId(assessorId)
                .status(status)
                .statuses(statusFilter)
                .restrictAssessmentIds(openSurveyIds)
                .startDateFrom(startDateFrom)
                .startDateTo(startDateTo)
                .endDateFrom(endDateFrom)
                .endDateTo(endDateTo)
                .pastDue(Boolean.TRUE.equals(pastDue))
                .excludeCompleted(Boolean.FALSE.equals(showCompleted))
                .reopenableSince(LocalDateTime.now().minusDays(REOPEN_WINDOW_DAYS))
                .assignedToMe(Boolean.TRUE.equals(assignedToMe))
                .currentUserId(currentUserId)
                .teamMemberIds(teamMemberIds)
                .scopeAssessorId(effectiveScopeAssessorId)
                .scopeTeamIds(effectiveScopeTeamIds)
                .campaignId(campaignId)
                .severityOrdinals(severityOrdinals)
                .completedStatuses(completed)
                .now(LocalDateTime.now())
                .build();

        Page<Assessment> page = assessmentRepository.searchAdvanced(criteria, pageable);

        List<AssessmentDto> dtos = page.getContent().stream()
                .map(this::migrateAndConvertToDto)
                .collect(Collectors.toList());

        return new PageImpl<>(dtos, pageable, page.getTotalElements());
    }

    /**
     * How long a completed assessment stays reopenable — and, for the same reason, stays in the
     * assessment queue. Both use this single value so the queue never shows an assessment that can
     * no longer be reopened, nor hides one that still can.
     */
    public static final int REOPEN_WINDOW_DAYS = 30;

    /**
     * Whether a completed assessment is still inside its reopen window. A completed assessment with
     * no {@code completedDate} predates that stamp being recorded; it is treated as outside the
     * window rather than reopenable forever.
     */
    public boolean withinReopenWindow(Assessment assessment) {
        if (!workflowConfigService.isCompletedStatus(assessment.getStatus())) {
            return false;
        }
        LocalDateTime completed = assessment.getCompletedDate();
        return completed != null && completed.isAfter(LocalDateTime.now().minusDays(REOPEN_WINDOW_DAYS));
    }

    /** Row-level scope applied to the assessment summary aggregate. */
    private enum SummaryScope { ORG, OWNED, UNSCOPED }

    /**
     * Aggregate assessment counts for the nav badge / dashboards, scoped like the list endpoint.
     * Uses a single {@code GROUP BY status} query — the badge only needs totals, so it must not
     * route through searchAssessmentsAdvanced (which materializes every row and calls getConfig()
     * per row).
     */
    public AssessmentSummaryDto assessmentSummary(Authentication authentication) {
        // The badge must count exactly the rows the list would show, so it reads the same
        // resolved scope rather than re-deriving one (they used to disagree: team/assigned
        // callers fell through to the global count).
        var scope = accessScopeService.resolveAssessmentScope(authentication);

        List<Object[]> rows = switch (scope.kind()) {
            case DENIED -> List.of();
            // Fail closed everywhere below: a scope that resolves to nothing counts nothing
            // rather than falling back to global totals.
            case ORG -> scope.orgId() == null ? List.of() : assessmentRepository.countByStatusGrouped(scope.orgId());
            case OWNED -> scope.appIds() == null || scope.appIds().isEmpty()
                    ? List.of() : assessmentRepository.countByStatusGroupedOwned(scope.appIds());
            case TEAM -> scope.teamIds() == null || scope.teamIds().isEmpty()
                    ? List.of() : assessmentRepository.countByStatusGroupedTeam(scope.teamIds());
            case ASSIGNED -> scope.assessorId() == null
                    ? List.of() : assessmentRepository.countByStatusGroupedAssigned(scope.assessorId());
            case UNRESTRICTED -> assessmentRepository.countByStatusGroupedAll();
        };

        var completed = completedStatuses();
        long total = rows.stream().mapToLong(row -> ((Number) row[1]).longValue()).sum();
        // A null status is treated as active (matches isCompletedStatus returning false for null).
        // Note this deliberately excludes assessments still inside their reopen window: they remain
        // in the queue so they can be reopened, but they are finished work, and the badge counts
        // what still needs doing. The badge is therefore lower than the unfiltered list length.
        long active = rows.stream()
                .filter(row -> row[0] == null || !completed.contains((String) row[0]))
                .mapToLong(row -> ((Number) row[1]).longValue())
                .sum();
        return AssessmentSummaryDto.builder().active(active).total(total).build();
    }

    /** The "completed" status strings, mirroring AssessmentWorkflowConfigService.isCompletedStatus. */
    private Set<String> completedStatuses() {
        var statuses = new HashSet<>(Set.of("COMPLETED", "APPROVED", "ARCHIVED"));
        var configured = workflowConfigService.getConfig().getCompletedStatus();
        if (configured != null && !configured.isBlank()) {
            statuses.add(configured);
        }
        return statuses;
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }


    /**
     * The users who may be added as assessors on this assessment: the members of the assessment's
     * team, or — when the assessment has no team set — every internal user.
     *
     * <p>Exists so the assessment edit dialog doesn't have to read the whole user directory:
     * {@code /users} requires {@code users:read}, which the Pentester role deliberately lacks, so
     * assessors editing their own assessment had no way to see who they could add. Read access to
     * the assessment is the gate here, which is also what makes the team restriction enforceable
     * server-side rather than by client-side filtering.
     */
    public List<AssignableUserDto> getAssignableAssessors(String assessmentId, Authentication authentication) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Assessment not found with id: " + assessmentId));
        accessScopeService.checkAssessmentAccess(authentication, assessment);

        List<User> candidates = assessment.getTeamId() != null
                ? userRepository.findByTeamIdsContaining(assessment.getTeamId())
                : userRepository.findAll();

        return candidates.stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsInternal()))
                .filter(u -> u.getDeletedAt() == null && u.getDisabledAt() == null)
                .map(AssignableUserDto::fromEntity)
                .sorted(Comparator.comparing(AssignableUserDto::displayName,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /**
     * Get assessments by application
     */
    public Page<AssessmentDto> getAssessmentsByApplication(String applicationId, Pageable pageable) {
        return getAssessmentsByApplication(applicationId, pageable, null);
    }

    public Page<AssessmentDto> getAssessmentsByApplication(String applicationId, Pageable pageable, Authentication authentication) {
        Application app = applicationRepository.findById(applicationId)
            .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + applicationId));

        accessScopeService.checkApplicationAccess(authentication, app);

        // Application access alone isn't enough: within an application a caller still only sees the
        // assessments their read scope allows, so this routes through the scoped search rather than
        // querying the application's assessments directly.
        var scope = accessScopeService.resolveAssessmentScope(authentication);
        if (scope.denied()) {
            return Page.empty(pageable);
        }
        if (scope.unrestricted()) {
            return assessmentRepository.findByApplicationIdAndDeletedAtIsNull(applicationId, pageable)
                    .map(this::migrateAndConvertToDto);
        }
        var criteria = AssessmentSearchCriteria.builder()
                .applicationId(applicationId)
                .organizationId(scope.kind() == AccessScopeService.AssessmentScopeKind.ORG ? scope.orgId() : null)
                .ownedAppIds(scope.kind() == AccessScopeService.AssessmentScopeKind.OWNED ? scope.appIds() : null)
                .scopeTeamIds(scope.kind() == AccessScopeService.AssessmentScopeKind.TEAM ? scope.teamIds() : null)
                .scopeAssessorId(scope.kind() == AccessScopeService.AssessmentScopeKind.ASSIGNED ? scope.assessorId() : null)
                .completedStatuses(completedStatuses())
                .reopenableSince(LocalDateTime.now().minusDays(REOPEN_WINDOW_DAYS))
                .now(LocalDateTime.now())
                .build();
        Page<Assessment> page = assessmentRepository.searchAdvanced(criteria, pageable);
        return new PageImpl<>(page.getContent().stream().map(this::migrateAndConvertToDto).toList(),
                pageable, page.getTotalElements());
    }

    /**
     * Delete an assessment (soft delete)
     */
    public void deleteAssessment(String id, String userId) {
        deleteAssessment(id, userId, null);
    }

    /**
     * Soft-deletes an assessment, enforcing the caller's delete scope: {@code :delete:all} reaches
     * every assessment, {@code :delete:team} only their teams'. The endpoint gate accepts both, so
     * without this check the team tier would delete anything — the gate alone cannot tell them
     * apart. A null authentication is an internal caller and is unscoped.
     */
    public void deleteAssessment(String id, String userId, Authentication authentication) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("Assessment not found with id: " + id));
        accessScopeService.checkAssessmentDeleteAccess(authentication, assessment);

        // Snapshot for extensions before the soft delete hides the row.
        extensionEventService.assessmentDeleting(id);

        assessment.setDeletedAt(LocalDateTime.now());
        assessment.setLastUpdatedBy(userId);
        assessment.setUpdatedAt(LocalDateTime.now());

        assessmentRepository.save(assessment);
        inlineImageService.deleteRefsForAssessment(id);
        log.info("Soft deleted assessment: {}", assessment.getName());
    }

    /**
     * Validate field values against field definitions
     */
    public Map<String, String> validateFieldValues(
        Map<String, String> fieldValues,
        List<UserDefinedField> fieldDefinitions
    ) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            return new HashMap<>();
        }

        // Create a map of field definitions for quick lookup
        Map<String, UserDefinedField> fieldDefMap = new HashMap<>();
        for (UserDefinedField field : fieldDefinitions) {
            fieldDefMap.put(field.getId(), field);
        }

        Map<String, String> validatedValues = new HashMap<>();

        for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
            String fieldId = entry.getKey();
            String value = entry.getValue();

            // Check if field exists
            UserDefinedField fieldDef = fieldDefMap.get(fieldId);
            if (fieldDef == null) {
                throw new IllegalArgumentException("Unknown field ID: " + fieldId);
            }

            // Validate based on field type and constraints
            validateFieldValue(fieldDef, value);

            validatedValues.put(fieldId, value);
        }

        return validatedValues;
    }

    /**
     * Validate a single field value
     */
    private void validateFieldValue(UserDefinedField fieldDef, String value) {
        // Check required
        if (Boolean.TRUE.equals(fieldDef.getRequired()) && (value == null || value.trim().isEmpty())) {
            throw new IllegalArgumentException("Field '" + fieldDef.getDisplayName() + "' is required");
        }

        // Skip further validation if value is null or empty
        if (value == null || value.trim().isEmpty()) {
            return;
        }

        // Validate length for STRING and RICH_TEXT types
        if (fieldDef.getFieldType() == FieldType.STRING || fieldDef.getFieldType() == FieldType.RICH_TEXT) {
            if (fieldDef.getMinLength() != null && value.length() < fieldDef.getMinLength()) {
                throw new IllegalArgumentException(
                    "Field '" + fieldDef.getDisplayName() + "' must be at least " +
                    fieldDef.getMinLength() + " characters long"
                );
            }
            if (fieldDef.getMaxLength() != null && value.length() > fieldDef.getMaxLength()) {
                throw new IllegalArgumentException(
                    "Field '" + fieldDef.getDisplayName() + "' must not exceed " +
                    fieldDef.getMaxLength() + " characters"
                );
            }
        }

        // Validate DROPDOWN values
        if (fieldDef.getFieldType() == FieldType.DROPDOWN) {
            if (fieldDef.getDropdownOptions() != null &&
                !fieldDef.getDropdownOptions().isEmpty() &&
                !fieldDef.getDropdownOptions().contains(value)) {
                throw new IllegalArgumentException(
                    "Field '" + fieldDef.getDisplayName() + "' has invalid value. " +
                    "Must be one of: " + fieldDef.getDropdownOptions()
                );
            }
        }
    }

    /**
     * Get assessment metrics/statistics
     */
    public AssessmentMetricsDto getMetrics(String organizationId, Authentication authentication) {
        if (isOrgScopedUser(authentication)) {
            organizationId = resolveOrgId(authentication);
        }
        return getMetrics(organizationId);
    }

    public AssessmentMetricsDto getMetrics(String organizationId) {
        // Load all non-deleted assessments (filtered by org if specified)
        List<Assessment> all = assessmentRepository.findAll().stream()
            .filter(a -> a.getDeletedAt() == null)
            .filter(a -> organizationId == null || organizationId.equals(a.getOrganizationId()))
            .collect(Collectors.toList());

        long totalCount = all.size();

        // Build per-status count map
        Map<String, Long> statusCounts = all.stream()
            .filter(a -> a.getStatus() != null)
            .collect(Collectors.groupingBy(Assessment::getStatus, Collectors.counting()));

        // Legacy fixed counts (backwards compatibility: count by old enum string values)
        long draftCount = statusCounts.getOrDefault("DRAFT", 0L);
        long inProgressCount = statusCounts.getOrDefault("IN_PROGRESS", 0L);
        long onHoldCount = statusCounts.getOrDefault("ON_HOLD", 0L);
        long pendingReviewCount = statusCounts.getOrDefault("PENDING_REVIEW", 0L);
        long completedCount = statusCounts.getOrDefault("COMPLETED", 0L);
        long approvedCount = statusCounts.getOrDefault("APPROVED", 0L);
        long archivedCount = statusCounts.getOrDefault("ARCHIVED", 0L);

        // Add configured completedStatus to completedCount if it differs from "COMPLETED"
        String configuredCompleted = workflowConfigService.getConfig().getCompletedStatus();
        if (!"COMPLETED".equals(configuredCompleted)) {
            completedCount += statusCounts.getOrDefault(configuredCompleted, 0L);
        }

        // Past due: past planned end date and not in a completed state
        List<Assessment> pastDueAssessments = assessmentRepository.findPastDue(LocalDateTime.now());
        long pastDueCount = pastDueAssessments.stream()
            .filter(a -> a.getDeletedAt() == null)
            .filter(a -> organizationId == null || organizationId.equals(a.getOrganizationId()))
            .filter(a -> !workflowConfigService.isCompletedStatus(a.getStatus()))
            .count();

        return AssessmentMetricsDto.builder()
            .totalCount(totalCount)
            .draftCount(draftCount)
            .inProgressCount(inProgressCount)
            .onHoldCount(onHoldCount)
            .pendingReviewCount(pendingReviewCount)
            .completedCount(completedCount)
            .approvedCount(approvedCount)
            .archivedCount(archivedCount)
            .pastDueCount(pastDueCount)
            .statusCounts(statusCounts)
            .build();
    }

    /**
     * Daily vulnerability trend (counts per severity per day) for a given lifecycle
     * event type, backed by the TimescaleDB continuous aggregate. Org-scoped users are
     * restricted to their own organization.
     */
    public List<VulnerabilityTrendPointDto> getVulnerabilityTrend(
        String organizationId, String eventType, int days, Authentication authentication) {
        if (isOrgScopedUser(authentication)) {
            organizationId = resolveOrgId(authentication);
        }
        String type = (eventType == null || eventType.isBlank())
            ? VulnerabilityEventService.CREATED : eventType;
        return vulnerabilityEventService.dailySeverityTrend(organizationId, type, days);
    }

    /**
     * Get assessments by date range (for calendar view)
     */
    public Page<AssessmentDto> getAssessmentsByDateRange(
        LocalDateTime startDate,
        LocalDateTime endDate,
        Pageable pageable
    ) {
        return getAssessmentsByDateRange(startDate, endDate, pageable, null);
    }

    public Page<AssessmentDto> getAssessmentsByDateRange(
        LocalDateTime startDate,
        LocalDateTime endDate,
        Pageable pageable,
        Authentication authentication
    ) {
        if (isOrgScopedUser(authentication)) {
            final String orgId = resolveOrgId(authentication);
            Page<Assessment> assessments = assessmentRepository.findByDateRange(startDate, endDate, pageable);
            List<Assessment> filtered = assessments.getContent().stream()
                    .filter(a -> orgId != null && orgId.equals(a.getOrganizationId()))
                    .collect(Collectors.toList());
            return new PageImpl<>(
                    filtered.stream().map(this::migrateAndConvertToDto).collect(Collectors.toList()),
                    pageable, filtered.size());
        }
        Page<Assessment> assessments = assessmentRepository.findByDateRange(startDate, endDate, pageable);
        return assessments.map(this::migrateAndConvertToDto);
    }

    /**
     * Detect conflicting assessments
     */
    public List<AssessmentDto> detectConflicts(
        String assessmentId,
        List<String> assessorIds,
        LocalDateTime startDate,
        LocalDateTime endDate
    ) {
        if (assessorIds == null || assessorIds.isEmpty() || startDate == null || endDate == null) {
            return Collections.emptyList();
        }

        try {
            String assessorIdsJson = objectMapper.writeValueAsString(assessorIds);
            List<Assessment> conflicts = assessmentRepository.findConflictingByAssessors(
                assessorIdsJson, startDate, endDate
            );

            // Exclude the current assessment from conflicts
            if (assessmentId != null) {
                conflicts = conflicts.stream()
                    .filter(a -> !a.getId().equals(assessmentId))
                    .collect(Collectors.toList());
            }

            return conflicts.stream()
                .map(this::migrateAndConvertToDto)
                .collect(Collectors.toList());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize assessor IDs", e);
            return Collections.emptyList();
        }
    }

    /**
     * Export assessments to CSV
     */
    public String exportToCsv(List<AssessmentDto> assessments) {
        StringBuilder csv = new StringBuilder();

        // Header
        csv.append("ID,Name,Status,Application ID,Assessment Type ID,Organization ID,")
           .append("Engagement Manager,Remediation Manager,Assessors,")
           .append("Start Date,Planned End Date,Completed Date,")
           .append("Created At,Created By\n");

        // Data rows
        for (AssessmentDto assessment : assessments) {
            csv.append(escapeCSV(assessment.getId())).append(",");
            csv.append(escapeCSV(assessment.getName())).append(",");
            csv.append(escapeCSV(assessment.getStatus() != null ? assessment.getStatus().toString() : "")).append(",");
            csv.append(escapeCSV(assessment.getApplicationId())).append(",");
            csv.append(escapeCSV(assessment.getAssessmentTypeId())).append(",");
            csv.append(escapeCSV(assessment.getOrganizationId())).append(",");
            csv.append(escapeCSV(assessment.getEngagementManagerId())).append(",");
            csv.append(escapeCSV(assessment.getRemediationManagerId())).append(",");
            csv.append(escapeCSV(
                assessment.getAssessorIds() != null ? String.join(";", assessment.getAssessorIds()) : ""
            )).append(",");
            csv.append(escapeCSV(
                assessment.getStartDate() != null ? assessment.getStartDate().toString() : ""
            )).append(",");
            csv.append(escapeCSV(
                assessment.getPlannedEndDate() != null ? assessment.getPlannedEndDate().toString() : ""
            )).append(",");
            csv.append(escapeCSV(
                assessment.getCompletedDate() != null ? assessment.getCompletedDate().toString() : ""
            )).append(",");
            csv.append(escapeCSV(
                assessment.getCreatedAt() != null ? assessment.getCreatedAt().toString() : ""
            )).append(",");
            csv.append(escapeCSV(assessment.getCreatedBy())).append("\n");
        }

        return csv.toString();
    }

    /**
     * Escape CSV values
     */
    private String escapeCSV(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Migrate assessorId to assessorIds if needed and convert to DTO
     */
    private AssessmentDto migrateAndConvertToDto(Assessment assessment) {
        migrateAssessorId(assessment);
        AssessmentDto dto = AssessmentDto.fromEntity(assessment);
        enrichWithDisplayNames(dto);
        dto.setVulnerabilitySummary(computeVulnerabilitySummary(assessment.getId()));
        // Compute isPastDue using workflow config (status-aware)
        dto.setIsPastDue(assessment.getPlannedEndDate() != null
                && LocalDateTime.now().isAfter(assessment.getPlannedEndDate())
                && !workflowConfigService.isCompletedStatus(assessment.getStatus()));
        return dto;
    }

    // All findings count, opened or not — the summary reflects what's been found so far
    private AssessmentDto.VulnerabilitySummary computeVulnerabilitySummary(String assessmentId) {
        return AssessmentDto.VulnerabilitySummary.builder()
            .critical(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.CRITICAL))
            .high(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.HIGH))
            .medium(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.MEDIUM))
            .low(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.LOW))
            .informational(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.INFORMATIONAL))
            .build();
    }

    /**
     * Enrich DTO with display names for related entities
     */
    private void enrichWithDisplayNames(AssessmentDto dto) {
        // Get application name and human-readable appId
        if (dto.getApplicationId() != null) {
            applicationRepository.findById(dto.getApplicationId())
                .ifPresent(app -> {
                    dto.setApplicationName(app.getName());
                    dto.setAppId(app.getAppId());
                });
        }

        // Get assessment type name
        if (dto.getAssessmentTypeId() != null) {
            assessmentTypeRepository.findById(dto.getAssessmentTypeId())
                .ifPresent(type -> dto.setAssessmentTypeName(type.getName()));
        }

        // Get campaign name
        if (dto.getCampaignId() != null) {
            campaignRepository.findById(dto.getCampaignId())
                .ifPresent(campaign -> dto.setCampaignName(campaign.getName()));
        }
        // Get team name
        if (dto.getTeamId() != null) {
            teamRepository.findById(dto.getTeamId())
                .ifPresent(team -> dto.setTeamName(team.getName()));
        }

        // Get assessor names and emails
        List<String> assessorNames = new ArrayList<>();
        List<String> assessorEmails = new ArrayList<>();
        if (dto.getAssessorIds() != null && !dto.getAssessorIds().isEmpty()) {
            for (String assessorId : dto.getAssessorIds()) {
                userRepository.findById(assessorId).ifPresent(user -> {
                    String displayName = user.getFirstName() != null && user.getLastName() != null
                        ? user.getFirstName() + " " + user.getLastName()
                        : user.getUsername();
                    assessorNames.add(displayName);
                    if (user.getEmail() != null) {
                        assessorEmails.add(user.getEmail());
                    }
                });
            }
        }
        dto.setAssessorNames(assessorNames);
        dto.setAssessorEmails(assessorEmails);

        // Get engagement manager name
        if (dto.getEngagementManagerId() != null) {
            userRepository.findById(dto.getEngagementManagerId()).ifPresent(user -> {
                String displayName = user.getFirstName() != null && user.getLastName() != null
                    ? user.getFirstName() + " " + user.getLastName()
                    : user.getUsername();
                dto.setEngagementManagerName(displayName);
                if (user.getEmail() != null) {
                    dto.setEngagementManagerEmail(user.getEmail());
                }
            });
        }

        log.debug("Enriched assessment {}: application={}, type={}, team={}, assessors={}",
            dto.getId(), dto.getApplicationName(), dto.getAssessmentTypeName(),
            dto.getTeamName(), dto.getAssessorNames());
    }

    private Application createApplicationFromAssessment(String appId, String appName, String userId) {
        Application application = Application.builder()
                .appId(appId)
                .name(appName)
                .status(ApplicationStatus.PRODUCTION)
                .region("Global")
                .createdBy(userId)
                .lastUpdatedBy(userId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        if (org.springframework.util.StringUtils.isEmpty(application.getAppId()) && applicationIdConfigService.isEnabled()) {
            application.setAppId(applicationIdConfigService.generateNextAppId());
        }

        return applicationRepository.save(application);
    }

    public String resolveOrgId(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .map(User::getOrganizationId)
                .orElse(null);
    }

    private boolean isOrgScopedUser(Authentication authentication) {
        if (authentication == null) return false;
        boolean isSuperAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(RequiresPermissionAuthorizationManager.SUPER_ADMIN));
        boolean hasReadAll = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(Permission.ASSESSMENTS_READ_ALL.getPermission()));
        boolean hasReadOrg = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().endsWith(":org"));
        return !isSuperAdmin && !hasReadAll && hasReadOrg;
    }

    /**
     * Migrate legacy assessorId to assessorIds array
     */
    private void migrateAssessorId(Assessment assessment) {
        if (assessment.getAssessorId() != null &&
            (assessment.getAssessorIds() == null || assessment.getAssessorIds().isEmpty())) {
            assessment.setAssessorIds(Collections.singletonList(assessment.getAssessorId()));
        }
    }

    // ── File attachment methods ──────────────────────────────────────────────

    /**
     * Allocate a file id and the backend URL the client streams the body to.
     *
     * <p>The two-phase shape (allocate → upload → confirm) is unchanged; what
     * changed is that the upload URL points at this application rather than at
     * object storage, so the bytes arrive over an authenticated request and
     * storage stays unreachable from the browser.
     */
    public UploadTargetResponse prepareUpload(
            String assessmentId, String fileName, String userId) {
        assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + assessmentId));

        String fileId = UUID.randomUUID().toString();
        String key = storageService.buildKey(assessmentId, fileId, fileName);

        log.info("Prepared upload for assessment {} file {} by user {}", assessmentId, fileName, userId);
        return UploadTargetResponse.builder()
                .fileId(fileId)
                .uploadUrl(String.format("/api/v1/assessments/%s/files/%s/content", assessmentId, fileId))
                .storageKey(key)
                .build();
    }

    /** Stream an uploaded body straight into storage under the allocated key. */
    public void storeUpload(String assessmentId, String fileId, String fileName,
                            String contentType, long contentLength, InputStream body) {
        assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + assessmentId));
        String key = storageService.buildKey(assessmentId, fileId, fileName);
        storageService.uploadStream(key, body, contentLength, contentType);
    }

    /**
     * Confirm a completed upload by persisting the file metadata to the assessment.
     */
    public AssessmentFileDto confirmFileUpload(
            String assessmentId, String fileId, String fileName,
            String contentType, Long fileSize, String userId) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + assessmentId));

        String displayName = userRepository.findById(userId).map(u -> {
            String n = ((u.getFirstName() != null ? u.getFirstName() : "") + " " +
                    (u.getLastName() != null ? u.getLastName() : "")).trim();
            return n.isEmpty() ? u.getUsername() : n;
        }).orElse(userId);

        String key = storageService.buildKey(assessmentId, fileId, fileName);
        AssessmentFile file = AssessmentFile.builder()
                .id(fileId)
                .fileName(fileName)
                .contentType(contentType)
                .fileSize(fileSize)
                .storageKey(key)
                .uploadedBy(userId)
                .uploadedByName(displayName)
                .uploadedAt(LocalDateTime.now())
                .build();

        if (assessment.getAttachments() == null) {
            assessment.setAttachments(new ArrayList<>());
        }
        assessment.getAttachments().add(file);
        assessmentRepository.save(assessment);

        log.info("Confirmed upload of file {} ({}) to assessment {}", fileName, fileId, assessmentId);
        return AssessmentFileDto.fromEntity(file);
    }

    /**
     * Open an attachment's bytes for streaming to the client. The caller owns the
     * returned stream and must close it.
     */
    public StorageService.StoredFile openFile(String assessmentId, String fileId) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + assessmentId));

        AssessmentFile file = assessment.getAttachments().stream()
                .filter(f -> fileId.equals(f.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + fileId));

        return new StorageService.StoredFile(
                storageService.openStream(file.getStorageKey()), file.getFileName());
    }

    /**
     * Delete a file from storage and remove its metadata from the assessment.
     */
    public void deleteFile(String assessmentId, String fileId, String userId) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + assessmentId));

        AssessmentFile file = assessment.getAttachments().stream()
                .filter(f -> fileId.equals(f.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + fileId));

        storageService.deleteObject(file.getStorageKey());
        assessment.getAttachments().removeIf(f -> fileId.equals(f.getId()));
        assessmentRepository.save(assessment);

        log.info("Deleted file {} from assessment {} by user {}", fileId, assessmentId, userId);
    }

    // ── Notification helpers ───────────────────────────────────────────────────

    /**
     * Puts a username on a finding's discussion, so the remediation owner hears about comments
     * by default. Mirrors {@code VulnerabilityService.addSubscribers} — the caller saves.
     */
    private void subscribeToThread(com.faction.clientportal.model.Vulnerability vuln, String username) {
        if (username == null || username.isBlank()) return;
        if (vuln.getSubscribers() == null) vuln.setSubscribers(new ArrayList<>());
        if (!vuln.getSubscribers().contains(username)) vuln.getSubscribers().add(username);
    }

    /** Notify a list of users (IDs) that they've been assigned to an assessment. */
    private void notifyUsers(List<String> userIds, String assessmentName, String link, String type) {
        if (userIds == null || userIds.isEmpty()) return;
        for (String userId : userIds) {
            notifyUserById(userId, assessmentName, link, type);
        }
    }

    /**
     * Mails the audiences an admin has switched on for this assessment event.
     *
     * <p>Fire-and-forget: {@code EventNotificationEmailSender.send} is {@code @Async} and
     * swallows its own failures, so nothing here can fail the save that triggered it.
     *
     * @param previousStatus the status the assessment moved away from, quoted in the
     *                       "changed" email; null when it is not a status change.
     */
    private void emailAssessmentEvent(com.faction.clientportal.model.EmailNotificationEvent event,
                                      Assessment assessment, String previousStatus) {
        try {
            String link = "/assessments/" + assessment.getId();
            List<String> lines = new ArrayList<>();
            List<String[]> details = new ArrayList<>();

            switch (event) {
                case ASSESSMENT_CREATED -> lines.add("A new assessment has been scheduled: "
                        + assessment.getName() + ".");
                case ASSESSMENT_COMPLETED -> lines.add("The assessment \"" + assessment.getName()
                        + "\" has been completed.");
                default -> lines.add("The assessment \"" + assessment.getName()
                        + "\" has been updated.");
            }

            details.add(new String[]{"Assessment", assessment.getName()});
            details.add(new String[]{"Status", assessment.getStatus()});
            if (previousStatus != null && !previousStatus.equals(assessment.getStatus())) {
                details.add(new String[]{"Previous status", previousStatus});
            }
            if (assessment.getStartDate() != null) {
                details.add(new String[]{"Start date", assessment.getStartDate().toLocalDate().toString()});
            }
            if (assessment.getPlannedEndDate() != null) {
                details.add(new String[]{"Planned end date",
                        assessment.getPlannedEndDate().toLocalDate().toString()});
            }
            if (assessment.getCompletedDate() != null) {
                details.add(new String[]{"Completed", assessment.getCompletedDate().toLocalDate().toString()});
            }

            eventEmailSender.send(com.faction.clientportal.service.email.EventNotificationEmailSender.Event.builder()
                    .key(event.key())
                    .event(event)
                    .assessment(assessment)
                    .subject(subjectFor(event, assessment))
                    .title(event.label())
                    .lines(lines)
                    .details(details)
                    .ctaLabel("View the assessment")
                    .ctaLink(link)
                    .build());
        } catch (Exception e) {
            log.warn("Could not queue the {} email for assessment {}: {}",
                    event, assessment.getId(), e.getMessage());
        }
    }

    private String subjectFor(com.faction.clientportal.model.EmailNotificationEvent event,
                              Assessment assessment) {
        return switch (event) {
            case ASSESSMENT_CREATED -> "New assessment: " + assessment.getName();
            case ASSESSMENT_COMPLETED -> "Assessment completed: " + assessment.getName();
            default -> "Assessment updated: " + assessment.getName();
        };
    }

    private void notifyUserById(String userId, String assessmentName, String link, String type) {
        if (userId == null || userId.isBlank()) return;
        userRepository.findById(userId).ifPresent(user -> {
            String title;
            String message;
            if ("ASSESSOR_ASSIGNED".equals(type)) {
                title = "You've been assigned to an assessment";
                message = "You have been assigned as an assessor on: " + assessmentName;
            } else {
                title = "New assessment created";
                message = "A new assessment has been created: " + assessmentName;
            }
            try {
                notificationService.send(user.getUsername(), title, message, type, link);
            } catch (Exception e) {
                log.warn("Failed to send notification to {}: {}", user.getUsername(), e.getMessage());
            }
        });
    }
}
