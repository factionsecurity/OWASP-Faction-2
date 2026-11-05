package com.faction.clientportal.service;

import com.faction.clientportal.dto.EntityFieldConfigDto;
import com.faction.clientportal.dto.UpdateEntityFieldConfigRequest;
import com.faction.clientportal.dto.UserDefinedFieldDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.EntityFieldConfig;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
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
     * Adds any of these fields whose {@code variableName} the scope doesn't define yet, leaving
     * existing ones exactly as configured — an admin may have renamed a field or edited its
     * options since it was first created. Returns the scope's full definition list afterwards.
     */
    public List<UserDefinedField> ensureFields(FieldScope scope, List<UserDefinedField> wanted, String userId) {
        EntityFieldConfig config = entityFieldConfigRepository.findByScope(scope)
                .orElseGet(() -> EntityFieldConfig.builder()
                        .scope(scope)
                        .fieldDefinitions(new ArrayList<>())
                        .build());
        List<UserDefinedField> fields = new ArrayList<>(config.getFieldDefinitions());
        boolean added = false;
        for (UserDefinedField field : wanted) {
            boolean exists = fields.stream()
                    .anyMatch(f -> f.getVariableName() != null
                            && f.getVariableName().equalsIgnoreCase(field.getVariableName()));
            if (!exists) {
                fields.add(field.toBuilder()
                        .id(UUID.randomUUID().toString())
                        .fieldScope(scope)
                        .displayOrder(fields.size())
                        .build());
                added = true;
            }
        }
        if (!added) {
            return fields;
        }
        config.setFieldDefinitions(fields);
        config.setLastUpdatedBy(userId);
        config.setUpdatedAt(LocalDateTime.now());
        return entityFieldConfigRepository.save(config).getFieldDefinitions();
    }

    /**
     * Appends an option to a dropdown field unless it already has one equal ignoring case.
     * Returns the field as stored.
     */
    public UserDefinedField addDropdownOption(FieldScope scope, String fieldId, String option, String userId) {
        EntityFieldConfig config = configWithField(scope, fieldId);
        UserDefinedField field = findField(config, fieldId);
        List<String> options = field.getDropdownOptions() == null
                ? new ArrayList<>() : new ArrayList<>(field.getDropdownOptions());
        if (options.stream().noneMatch(option::equalsIgnoreCase)) {
            options.add(option);
            field.setDropdownOptions(options);
            return findField(save(config, userId), fieldId);
        }
        return field;
    }

    /** Turns a field into a dropdown with exactly these options. Returns the field as stored. */
    public UserDefinedField convertToDropdown(FieldScope scope, String fieldId, List<String> options,
                                              String userId) {
        EntityFieldConfig config = configWithField(scope, fieldId);
        UserDefinedField field = findField(config, fieldId);
        field.setFieldType(FieldType.DROPDOWN);
        field.setDropdownOptions(new ArrayList<>(options));
        return findField(save(config, userId), fieldId);
    }

    private EntityFieldConfig configWithField(FieldScope scope, String fieldId) {
        EntityFieldConfig config = entityFieldConfigRepository.findByScope(scope)
                .orElseThrow(() -> new IllegalArgumentException("No fields are defined for " + scope));
        findField(config, fieldId);
        return config;
    }

    private static UserDefinedField findField(EntityFieldConfig config, String fieldId) {
        return config.getFieldDefinitions().stream()
                .filter(f -> fieldId.equals(f.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown field " + fieldId));
    }

    private EntityFieldConfig save(EntityFieldConfig config, String userId) {
        config.setLastUpdatedBy(userId);
        config.setUpdatedAt(LocalDateTime.now());
        return entityFieldConfigRepository.save(config);
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
