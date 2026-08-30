package com.faction.clientportal.repository;

import com.faction.clientportal.model.Team;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, String> {
    Optional<Team> findByName(String name);
    boolean existsByName(String name);

    @Query("SELECT t FROM Team t WHERE LOWER(t.name) LIKE LOWER(CONCAT(?1, '%')) OR LOWER(t.description) LIKE LOWER(CONCAT(?1, '%'))")
    Page<Team> searchByNameOrDescription(String searchTerm, Pageable pageable);
}
