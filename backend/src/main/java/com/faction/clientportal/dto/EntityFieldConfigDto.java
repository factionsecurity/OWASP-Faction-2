package com.faction.clientportal.dto;

import com.faction.clientportal.model.EntityFieldConfig;
import com.faction.clientportal.model.FieldScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityFieldConfigDto {

    private String id;
    private FieldScope scope;
    private List<UserDefinedFieldDto> fieldDefinitions;
    private String lastUpdatedBy;
    private LocalDateTime updatedAt;

    public static EntityFieldConfigDto fromEntity(EntityFieldConfig entity) {
        List<UserDefinedFieldDto> defs = entity.getFieldDefinitions() == null
                ? new ArrayList<>()
                : entity.getFieldDefinitions().stream()
                        .map(UserDefinedFieldDto::fromEntity)
                        .collect(Collectors.toList());
        return EntityFieldConfigDto.builder()
                .id(entity.getId())
                .scope(entity.getScope())
                .fieldDefinitions(defs)
                .lastUpdatedBy(entity.getLastUpdatedBy())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
