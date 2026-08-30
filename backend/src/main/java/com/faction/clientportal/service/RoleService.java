package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateRoleRequest;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.dto.RoleDto;
import com.faction.clientportal.dto.UpdateRoleRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final EditionPolicy editionPolicy;

    private static final List<String> DEFAULT_ROLES = Arrays.asList("SuperAdmin", "Pentester");

    public Role createRole(Role role) {
        return roleRepository.save(role);
    }

    public RoleDto createRole(CreateRoleRequest request) {
        // Check if role with same name already exists
        if (roleRepository.findByName(request.getName()).isPresent()) {
            throw new IllegalArgumentException("Role with name '" + request.getName() + "' already exists");
        }

        if (request.isExternalRole()) {
            editionPolicy.require(Feature.EXTERNAL_OWNERS);
        }

        Role role = Role.builder()
                .name(request.getName())
                .description(request.getDescription())
                .permissions(request.getPermissions())
                .externalRole(request.isExternalRole())
                .build();

        Role savedRole = roleRepository.save(role);
        return toDto(savedRole);
    }

    public RoleDto updateRole(String id, UpdateRoleRequest request) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found with id: " + id));

        // Check if updating to a name that already exists (and it's not the same role)
        Optional<Role> existingRole = roleRepository.findByName(request.getName());
        if (existingRole.isPresent() && !existingRole.get().getId().equals(id)) {
            throw new IllegalArgumentException("Role with name '" + request.getName() + "' already exists");
        }

        if (request.isExternalRole() && !role.isExternalRole()) {
            editionPolicy.require(Feature.EXTERNAL_OWNERS);
        }

        role.setName(request.getName());
        role.setDescription(request.getDescription());
        role.setPermissions(request.getPermissions());
        role.setExternalRole(request.isExternalRole());

        Role updatedRole = roleRepository.save(role);
        return toDto(updatedRole);
    }

    public void deleteRole(String id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found with id: " + id));

        // Prevent deletion of default roles
        if (DEFAULT_ROLES.contains(role.getName())) {
            throw new IllegalArgumentException("Cannot delete default role: " + role.getName());
        }

        roleRepository.deleteById(id);

        // Remove this role from all users who had it assigned
        List<User> affectedUsers = userRepository.findByRoleIdsContaining(id);
        affectedUsers.forEach(user -> {
            user.getRoleIds().remove(id);
            userRepository.save(user);
        });
    }

    public Optional<Role> findByName(String name) {
        return roleRepository.findByName(name);
    }

    public Optional<Role> findById(String id) {
        return roleRepository.findById(id);
    }

    public List<Role> findAll() {
        return roleRepository.findAll();
    }

    public Page<Role> findAll(Pageable pageable) {
        return roleRepository.findAll(pageable);
    }

    public Page<RoleDto> findAllPaginated(Pageable pageable) {
        return roleRepository.findAll(pageable).map(this::toDto);
    }

    public Page<RoleDto> searchRoles(String search, Pageable pageable) {
        if (search == null || search.trim().isEmpty()) {
            return findAllPaginated(pageable);
        }
        return roleRepository.searchByNameOrDescription(search.trim(), pageable)
                .map(this::toDto);
    }

    public List<Role> findAllById(List<String> ids) {
        return roleRepository.findAllById(ids);
    }

    public Role updateRole(Role role) {
        return roleRepository.save(role);
    }

    public RoleDto toDto(Role role) {
        return RoleDto.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(role.getPermissions())
                .externalRole(role.isExternalRole())
                .build();
    }

    public List<RoleDto> toDtoList(List<Role> roles) {
        return roles.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }
}
