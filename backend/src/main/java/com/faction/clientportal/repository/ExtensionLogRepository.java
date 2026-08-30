package com.faction.clientportal.repository;

import com.faction.clientportal.model.ExtensionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for extension log lines.
 */
@Repository
public interface ExtensionLogRepository extends JpaRepository<ExtensionLog, String> {

    List<ExtensionLog> findByExtensionIdOrderByTimestampDesc(String extensionId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ExtensionLog l WHERE l.extensionId = ?1")
    void deleteByExtensionId(String extensionId);

    /** Trims the log table so a chatty extension cannot grow it without bound. */
    @Modifying
    @Query("DELETE FROM ExtensionLog l WHERE l.timestamp < ?1")
    int deleteByTimestampBefore(LocalDateTime cutoff);
}
