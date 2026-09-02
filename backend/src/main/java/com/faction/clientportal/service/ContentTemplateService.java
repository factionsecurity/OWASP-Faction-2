package com.faction.clientportal.service;

import com.faction.clientportal.dto.ContentTemplateDto;
import com.faction.clientportal.dto.SaveContentTemplateRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.ContentTemplate;
import com.faction.clientportal.model.ContentTemplateScope;
import com.faction.clientportal.repository.ContentTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentTemplateService {

    private final ContentTemplateRepository contentTemplateRepository;

    /** Every template, enabled or not — the admin list. */
    public List<ContentTemplateDto> getTemplates() {
        return contentTemplateRepository.findAll().stream()
                .sorted(Comparator.comparing(ContentTemplate::getName, String.CASE_INSENSITIVE_ORDER))
                .map(ContentTemplateDto::fromEntity)
                .toList();
    }

    /** Enabled templates for a scope — what the editor's template picker offers. */
    public List<ContentTemplateDto> getEnabledTemplates(ContentTemplateScope scope) {
        return contentTemplateRepository.findByScopeAndEnabledTrueOrderByNameAsc(scope).stream()
                .map(ContentTemplateDto::fromEntity)
                .toList();
    }

    public ContentTemplateDto createTemplate(SaveContentTemplateRequest request, String username) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Template name is required");
        }
        if (request.getScope() == null) {
            throw new IllegalArgumentException("Template scope is required");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new IllegalArgumentException("Template content is required");
        }
        ContentTemplate template = ContentTemplate.builder()
                .createdBy(username)
                .createdAt(LocalDateTime.now())
                .build();
        applyRequest(template, request, username);
        return ContentTemplateDto.fromEntity(contentTemplateRepository.save(template));
    }

    public ContentTemplateDto updateTemplate(String id, SaveContentTemplateRequest request, String username) {
        ContentTemplate template = getEntity(id);
        applyRequest(template, request, username);
        return ContentTemplateDto.fromEntity(contentTemplateRepository.save(template));
    }

    public void deleteTemplate(String id) {
        contentTemplateRepository.delete(getEntity(id));
    }

    private ContentTemplate getEntity(String id) {
        return contentTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Content template not found: " + id));
    }

    /** Null request fields mean "no change"; an empty description clears it. */
    private void applyRequest(ContentTemplate template, SaveContentTemplateRequest request, String username) {
        if (request.getName() != null && !request.getName().isBlank()) template.setName(request.getName().trim());
        if (request.getDescription() != null) {
            template.setDescription(request.getDescription().isBlank() ? null : request.getDescription().trim());
        }
        if (request.getScope() != null) template.setScope(request.getScope());
        if (request.getContent() != null && !request.getContent().isBlank()) {
            template.setContent(request.getContent());
        }
        if (request.getEnabled() != null) template.setEnabled(request.getEnabled());
        template.setLastUpdatedBy(username);
        template.setUpdatedAt(LocalDateTime.now());
    }
}
