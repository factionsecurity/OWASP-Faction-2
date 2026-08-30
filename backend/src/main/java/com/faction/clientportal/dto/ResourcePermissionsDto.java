package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourcePermissionsDto {
    private String resource;
    private String displayName;
    private String description;
    private List<PermissionDto> permissions;
}
