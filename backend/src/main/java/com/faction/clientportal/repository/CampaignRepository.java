package com.faction.clientportal.repository;

import com.faction.clientportal.model.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, String> {
    Optional<Campaign> findByName(String name);
    boolean existsByName(String name);
    Optional<Campaign> findByIsDefaultTrue();

    @Query("SELECT c FROM Campaign c WHERE LOWER(c.name) LIKE LOWER(CONCAT(?1, '%'))")
    Page<Campaign> searchByName(String searchTerm, Pageable pageable);
}
