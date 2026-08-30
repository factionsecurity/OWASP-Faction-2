package com.faction.clientportal.dto;

import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.UserDefinedField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for UserDefinedField with validation annotations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDefinedFieldDto {

    private String id;

    // Lenient validation - allow blank/invalid names during editing
    // Validation will be enforced at service level when creating assessments
    private String variableName;

    private String displayName;

    private String helpText;

    // Field type is required but can be changed during editing
    private FieldType fieldType;

    @Builder.Default
    private List<String> dropdownOptions = new ArrayList<>();

    private String defaultValue;

    @Builder.Default
    private Boolean required = false;

    private Integer maxLength;

    private Integer minLength;

    private Integer displayOrder;

    private FieldScope fieldScope;

    /**
     * Convert from entity to DTO
     */
    public static UserDefinedFieldDto fromEntity(UserDefinedField entity) {
        if (entity == null) {
            return null;
        }
        return UserDefinedFieldDto.builder()
            .id(entity.getId())
            .variableName(entity.getVariableName())
            .displayName(entity.getDisplayName())
            .helpText(entity.getHelpText())
            .fieldType(entity.getFieldType())
            .dropdownOptions(entity.getDropdownOptions() != null ? new ArrayList<>(entity.getDropdownOptions()) : new ArrayList<>())
            .defaultValue(entity.getDefaultValue())
            .required(entity.getRequired())
            .maxLength(entity.getMaxLength())
            .minLength(entity.getMinLength())
            .displayOrder(entity.getDisplayOrder())
            .fieldScope(entity.getFieldScope())
            .build();
    }

    /**
     * Convert from DTO to entity
     */
    public UserDefinedField toEntity() {
        return UserDefinedField.builder()
            .id(this.id)
            .variableName(this.variableName)
            .displayName(this.displayName)
            .helpText(this.helpText)
            .fieldType(this.fieldType)
            .dropdownOptions(this.dropdownOptions != null ? new ArrayList<>(this.dropdownOptions) : new ArrayList<>())
            .defaultValue(this.defaultValue)
            .required(this.required != null ? this.required : false)
            .maxLength(this.maxLength)
            .minLength(this.minLength)
            .displayOrder(this.displayOrder)
            .fieldScope(this.fieldScope != null ? this.fieldScope : FieldScope.ASSESSMENT)
            .build();
    }
}
