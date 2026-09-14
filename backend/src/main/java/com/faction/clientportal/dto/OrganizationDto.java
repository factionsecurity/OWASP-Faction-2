package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationDto {
    private String id;
    private String name;
    private String description;
    private List<UserDefinedFieldDto> fieldDefinitions;
    private Map<String, String> fieldValues;
    private List<AssignedUserDto> assignedUsers;
    /** Internal users responsible for the organization's findings; see Organization#remediationOwnerIds. */
    @Builder.Default private List<String> remediationOwnerIds = new java.util.ArrayList<>();
    /** The same people resolved for display. */
    @Builder.Default private List<RemediationOwner> remediationOwners = new java.util.ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemediationOwner {
        private String userId;
        private String username;
        private String displayName;
        private String email;
    }
}
