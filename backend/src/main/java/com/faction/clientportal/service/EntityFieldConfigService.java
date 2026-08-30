package com.faction.clientportal.service;

import com.faction.clientportal.dto.EntityFieldConfigDto;
import com.faction.clientportal.dto.UpdateEntityFieldConfigRequest;
import com.faction.clientportal.dto.UserDefinedFieldDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.EntityFieldConfig;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.EntityFieldConfigRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntityFieldConfigService {

    private final EntityFieldConfigRepository entityFieldConfigRepository;
    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * Get the field config for a scope, creating an empty one if none exists yet.
     */
    public EntityFieldConfigDto getFieldConfig(FieldScope scope) {
        EntityFieldConfig config = entityFieldConfigRepository.findByScope(scope)
                .orElseGet(() -> EntityFieldConfig.builder()
                        .scope(scope)
                        .fieldDefinitions(new ArrayList<>())
                        .build());
        return EntityFieldConfigDto.fromEntity(config);
    }

    /**
     * Update field definitions for a scope.
     * Generates IDs for new fields and syncs removed field IDs out of all existing entity documents.
     */
    public EntityFieldConfigDto updateFieldConfig(FieldScope scope, UpdateEntityFieldConfigRequest request, String userId) {
        EntityFieldConfig config = entityFieldConfigRepository.findByScope(scope)
                .orElseGet(() -> EntityFieldConfig.builder()
                        .scope(scope)
                        .fieldDefinitions(new ArrayList<>())
                        .build());

        // Track existing field IDs to detect removals
        Set<String> oldFieldIds = config.getFieldDefinitions().stream()
                .map(UserDefinedField::getId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        List<UserDefinedField> fields = prepareFields(request.getFieldDefinitions());

        Set<String> newFieldIds = fields.stream()
                .map(UserDefinedField::getId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        // Removed = in old set but not in new set
        Set<String> removedIds = new HashSet<>(oldFieldIds);
        removedIds.removeAll(newFieldIds);

        config.setFieldDefinitions(fields);
        config.setLastUpdatedBy(userId);
        config.setUpdatedAt(LocalDateTime.now());

        EntityFieldConfig saved = entityFieldConfigRepository.save(config);

        // Sync: strip orphaned fieldValues from all existing entities
        if (!removedIds.isEmpty()) {
            if (scope == FieldScope.APPLICATION) {
                syncApplicationFieldValues(removedIds);
            } else if (scope == FieldScope.ORGANIZATION) {
                syncOrganizationFieldValues(removedIds);
            }
        }

        return EntityFieldConfigDto.fromEntity(saved);
    }

    /**
     * Returns the raw list of field definitions for a scope (used by Application/Organization services).
     */
    public List<UserDefinedField> getFieldDefinitions(FieldScope scope) {
        return entityFieldConfigRepository.findByScope(scope)
                .map(EntityFieldConfig::getFieldDefinitions)
                .orElse(new ArrayList<>());
    }

    /**
     * Returns field definitions as DTOs for a scope.
     */
    public List<UserDefinedFieldDto> getFieldDefinitionDtos(FieldScope scope) {
        return getFieldDefinitions(scope).stream()
                .map(UserDefinedFieldDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Prepare field definitions: generate UUIDs for fields without IDs.
     */
    private List<UserDefinedField> prepareFields(List<UserDefinedFieldDto> fieldDtos) {
        if (fieldDtos == null) {
            return new ArrayList<>();
        }
        List<UserDefinedField> fields = new ArrayList<>();
        for (UserDefinedFieldDto dto : fieldDtos) {
            if (dto.getId() == null || dto.getId().isEmpty()) {
                dto.setId(UUID.randomUUID().toString());
            }
            fields.add(dto.toEntity());
        }
        return fields;
    }

    /**
     * Remove orphaned field IDs from all Application documents' fieldValues.
     */
    private void syncApplicationFieldValues(Set<String> removedIds) {
        List<Application> apps = applicationRepository.findAll();
        for (Application app : apps) {
            if (app.getFieldValues() != null) {
                boolean changed = false;
                for (String removedId : removedIds) {
                    if (app.getFieldValues().remove(removedId) != null) {
                        changed = true;
                    }
                }
                if (changed) {
                    applicationRepository.save(app);
                }
            }
        }
    }

    /**
     * Remove orphaned field IDs from all Organization documents' fieldValues.
     */
    private void syncOrganizationFieldValues(Set<String> removedIds) {
        List<Organization> orgs = organizationRepository.findAll();
        for (Organization org : orgs) {
            if (org.getFieldValues() != null) {
                boolean changed = false;
                for (String removedId : removedIds) {
                    if (org.getFieldValues().remove(removedId) != null) {
                        changed = true;
                    }
                }
                if (changed) {
                    organizationRepository.save(org);
                }
            }
        }
    }
}
