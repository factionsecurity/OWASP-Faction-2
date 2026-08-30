package com.faction.clientportal.dto;

import com.faction.clientportal.model.AssignedUser;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignedUserDto {
    private String userId;
    private String displayName;
    private String email;
    private String accessLevel;

    public static AssignedUserDto fromEntity(AssignedUser entity) {
        return AssignedUserDto.builder()
                .userId(entity.getUserId())
                .displayName(entity.getDisplayName())
                .email(entity.getEmail())
                .accessLevel(entity.getAccessLevel())
                .build();
    }
}
