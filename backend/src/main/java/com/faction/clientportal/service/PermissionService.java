package com.faction.clientportal.service;

import com.faction.clientportal.dto.PermissionDto;
import com.faction.clientportal.dto.ResourcePermissionsDto;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.PermissionResource;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    /**
     * Get all permissions organized by resource
     *
     * @return List of ResourcePermissionsDto containing all permissions grouped by resource
     */
    public List<ResourcePermissionsDto> getAllPermissionsByResource() {
        return Arrays.stream(PermissionResource.values())
                .map(this::mapResourceToDto)
                .collect(Collectors.toList());
    }

    /**
     * Map a PermissionResource to ResourcePermissionsDto with all its permissions
     *
     * @param resource The permission resource to map
     * @return ResourcePermissionsDto containing the resource info and its permissions
     */
    private ResourcePermissionsDto mapResourceToDto(PermissionResource resource) {
        List<PermissionDto> permissions = Permission.getByResource(resource)
                .stream()
                .map(this::mapPermissionToDto)
                .collect(Collectors.toList());

        return ResourcePermissionsDto.builder()
                .resource(resource.name())
                .displayName(resource.getDisplayName())
                .description(resource.getDescription())
                .permissions(permissions)
                .build();
    }

    /**
     * Map a Permission enum to PermissionDto
     *
     * @param permission The permission to map
     * @return PermissionDto containing the permission string and description
     */
    private PermissionDto mapPermissionToDto(Permission permission) {
        return PermissionDto.builder()
                .permission(permission.getPermission())
                .description(permission.getDescription())
                .build();
    }
}
