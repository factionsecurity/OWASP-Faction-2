package com.faction.clientportal.repository;

import com.faction.clientportal.model.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, String> {

    Optional<Role> findByName(String name);

    @Query("SELECT r FROM Role r WHERE LOWER(r.name) LIKE LOWER(CONCAT(?1, '%')) OR LOWER(r.description) LIKE LOWER(CONCAT(?1, '%'))")
    Page<Role> searchByNameOrDescription(String searchTerm, Pageable pageable);
}
