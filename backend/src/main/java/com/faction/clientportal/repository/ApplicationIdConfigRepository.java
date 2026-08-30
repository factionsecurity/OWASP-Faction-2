package com.faction.clientportal.repository;

import com.faction.clientportal.model.ApplicationIdConfig;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ApplicationIdConfigRepository extends JpaRepository<ApplicationIdConfig, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ApplicationIdConfig c WHERE c.id = :id")
    Optional<ApplicationIdConfig> findByIdForUpdate(@Param("id") String id);
}
