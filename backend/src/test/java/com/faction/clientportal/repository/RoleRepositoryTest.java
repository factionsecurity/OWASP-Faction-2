package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")

class RoleRepositoryTest extends TestContainersConfig {

    @Autowired
    private RoleRepository roleRepository;

    @BeforeEach
    void setUp() {
        roleRepository.deleteAll();
    }

    @Test
    void saveAndFindByName_Success() {
        Role role = Role.builder()
                .name("TestRole")
                .description("Test role description")
                .permissions(List.of("read", "write", "delete"))
                .build();

        Role savedRole = roleRepository.save(role);
        assertThat(savedRole.getId()).isNotNull();

        Optional<Role> foundRole = roleRepository.findByName("TestRole");
        assertThat(foundRole).isPresent();
        assertThat(foundRole.get().getName()).isEqualTo("TestRole");
        assertThat(foundRole.get().getDescription()).isEqualTo("Test role description");
        assertThat(foundRole.get().getPermissions()).containsExactly("read", "write", "delete");
    }

    @Test
    void findByName_ReturnsEmpty_WhenRoleDoesNotExist() {
        Optional<Role> foundRole = roleRepository.findByName("NonExistentRole");
        assertThat(foundRole).isEmpty();
    }

    @Test
    void saveRole_WithEmptyPermissions() {
        Role role = Role.builder()
                .name("EmptyPermissionsRole")
                .description("Role with no permissions")
                .permissions(List.of())
                .build();

        Role savedRole = roleRepository.save(role);
        assertThat(savedRole.getId()).isNotNull();
        assertThat(savedRole.getPermissions()).isEmpty();
    }

    @Test
    void findAllById_ReturnsMultipleRoles() {
        Role role1 = Role.builder()
                .name("Role1")
                .description("First role")
                .permissions(List.of("perm1"))
                .build();
        Role role2 = Role.builder()
                .name("Role2")
                .description("Second role")
                .permissions(List.of("perm2"))
                .build();

        Role savedRole1 = roleRepository.save(role1);
        Role savedRole2 = roleRepository.save(role2);

        List<Role> roles = roleRepository.findAllById(List.of(savedRole1.getId(), savedRole2.getId()));
        assertThat(roles).hasSize(2);
        assertThat(roles).extracting(Role::getName).containsExactlyInAnyOrder("Role1", "Role2");
    }
}
