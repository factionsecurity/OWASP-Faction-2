package com.faction.clientportal.dto;

import com.faction.clientportal.model.Stakeholder;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Stakeholder embedded document
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StakeholderDto {

    @NotBlank(message = "Stakeholder name is required")
    private String name;

    @NotBlank(message = "Stakeholder email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Stakeholder role is required")
    private String role;

    /**
     * Convert from entity to DTO
     */
    public static StakeholderDto fromEntity(Stakeholder entity) {
        if (entity == null) {
            return null;
        }
        return StakeholderDto.builder()
            .name(entity.getName())
            .email(entity.getEmail())
            .role(entity.getRole())
            .build();
    }

    /**
     * Convert from DTO to entity
     */
    public Stakeholder toEntity() {
        return Stakeholder.builder()
            .name(this.name)
            .email(this.email)
            .role(this.role)
            .build();
    }
}
