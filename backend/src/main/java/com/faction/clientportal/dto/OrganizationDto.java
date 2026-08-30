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
}
