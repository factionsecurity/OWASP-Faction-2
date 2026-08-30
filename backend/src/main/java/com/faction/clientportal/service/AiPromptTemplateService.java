package com.faction.clientportal.service;

import com.faction.clientportal.dto.AiPromptSummaryDto;
import com.faction.clientportal.dto.AiPromptTemplateDto;
import com.faction.clientportal.dto.SaveAiPromptTemplateRequest;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Quota;
import com.faction.clientportal.edition.QuotaUsageService;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.AiPromptScope;
import com.faction.clientportal.model.AiPromptTemplate;
import com.faction.clientportal.repository.AiPromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiPromptTemplateService {

    private final AiPromptTemplateRepository aiPromptTemplateRepository;
    private final EditionPolicy editionPolicy;
    private final QuotaUsageService quotaUsageService;

    public List<AiPromptTemplateDto> getPrompts() {
        return aiPromptTemplateRepository.findAll().stream()
                .sorted(Comparator.comparing(AiPromptTemplate::getName, String.CASE_INSENSITIVE_ORDER))
                .map(AiPromptTemplateDto::fromEntity)
                .toList();
    }

    /** Enabled prompts for a scope — what non-admin users see in the editor AI menu. */
    public List<AiPromptSummaryDto> getEnabledPromptSummaries(AiPromptScope scope) {
        return aiPromptTemplateRepository.findByScopeAndEnabledTrueOrderByNameAsc(scope).stream()
                .map(AiPromptSummaryDto::fromEntity)
                .toList();
    }

    public AiPromptTemplateDto createPrompt(SaveAiPromptTemplateRequest request) {
        // Nothing seeds prompts, so all four open source slots belong to the operator.
        editionPolicy.requireHeadroom(Quota.AI_PROMPTS, quotaUsageService.current(Quota.AI_PROMPTS));

        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Prompt name is required");
        }
        if (request.getScope() == null) {
            throw new IllegalArgumentException("Prompt scope is required");
        }
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new IllegalArgumentException("Prompt text is required");
        }
        AiPromptTemplate template = AiPromptTemplate.builder()
                .createdAt(LocalDateTime.now())
                .build();
        applyRequest(template, request);
        return AiPromptTemplateDto.fromEntity(aiPromptTemplateRepository.save(template));
    }

    public AiPromptTemplateDto updatePrompt(String id, SaveAiPromptTemplateRequest request) {
        AiPromptTemplate template = getEntity(id);
        applyRequest(template, request);
        return AiPromptTemplateDto.fromEntity(aiPromptTemplateRepository.save(template));
    }

    public void deletePrompt(String id) {
        aiPromptTemplateRepository.delete(getEntity(id));
    }

    private AiPromptTemplate getEntity(String id) {
        return aiPromptTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AI prompt not found: " + id));
    }

    /** Null request fields mean "no change"; empty strings clear optional fields. */
    private void applyRequest(AiPromptTemplate template, SaveAiPromptTemplateRequest request) {
        if (request.getName() != null && !request.getName().isBlank()) template.setName(request.getName().trim());
        if (request.getDescription() != null) {
            template.setDescription(request.getDescription().isBlank() ? null : request.getDescription().trim());
        }
        if (request.getScope() != null) template.setScope(request.getScope());
        if (request.getPrompt() != null && !request.getPrompt().isBlank()) {
            template.setPrompt(request.getPrompt());
        }
        if (request.getProviderId() != null) {
            template.setProviderId(request.getProviderId().isBlank() ? null : request.getProviderId());
        }
        if (request.getModel() != null) {
            template.setModel(request.getModel().isBlank() ? null : request.getModel().trim());
        }
        if (request.getAllowWebAccess() != null) template.setAllowWebAccess(request.getAllowWebAccess());
        if (request.getEnabled() != null) template.setEnabled(request.getEnabled());
        template.setUpdatedAt(LocalDateTime.now());
    }
}
