package com.faction.clientportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A single log line drained from an extension's {@code getLogs()} after an
 * invocation.
 *
 * <p>Extensions run on a background executor, so a stack trace printed to stdout
 * is effectively invisible to the operator who installed them. Persisting the
 * logs is what makes a misconfigured integration diagnosable from the App Store
 * page instead of the container logs.
 */
@Entity
@Table(name = "extension_log", indexes = {
    @Index(name = "idx_extension_log_extension", columnList = "extension_id, timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtensionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "extension_id")
    private String extensionId;

    /** INFO, WARNING, ERROR or DEBUG — mirrors {@code com.faction.elements.utils.Log.LEVEL}. */
    private String level;

    /** Which hook produced this line, e.g. {@code ASMT_MANAGER}. */
    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(columnDefinition = "TEXT")
    private String stackTrace;

    private LocalDateTime timestamp;
}
