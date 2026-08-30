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
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * An installed App Store extension.
 *
 * <p>An extension is a fat JAR implementing one or more of the
 * {@code com.faction.extender.*} interfaces. The JAR itself lives in object
 * storage (see {@link #jarFileId}); this row holds only the metadata parsed out
 * of it plus the operator's configuration.
 *
 * <p>Each hook the JAR declares gets its own enable flag and ordering column.
 * The flags are seeded at install time from the {@code META-INF/services/}
 * entries found in the JAR, so a JAR that only implements {@code ReportManager}
 * can never be switched on for, say, vulnerability events. {@link #enabled} is
 * the master switch: a hook fires only when both it and its own flag are on.
 */
@Entity
@Table(name = "extension", indexes = {
    @Index(name = "idx_extension_enabled", columnList = "enabled"),
    @Index(name = "idx_extension_deleted", columnList = "deleted_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Extension {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Manifest {@code Title}. */
    private String name;

    /** Manifest {@code Author}. */
    private String author;

    /** Manifest {@code Version}. */
    private String version;

    /** Manifest {@code URL} — the extension's home page. */
    private String url;

    /** Sanitized markdown from {@code META-INF/resources/description.md}. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Base64 of {@code META-INF/resources/logo.png}. */
    @Column(columnDefinition = "TEXT")
    private String logoBase64;

    /** MIME type of {@link #logoBase64}, e.g. {@code image/png}. */
    private String logoMimeType;

    /** Object-storage key for the JAR bytes. */
    private String jarFileId;

    /**
     * SHA-256 of the JAR bytes. Doubles as the classloader cache key, so a
     * re-uploaded JAR with different content always gets a fresh loader.
     */
    private String hash;

    /**
     * AES-GCM encrypted JSON of the extension's config, in the shape declared by
     * its {@code config.json}: {@code {"Jira Host": {"type": "text", "value": "…"}}}.
     * Encrypted because config routinely holds API keys.
     *
     * <p>Kept out of {@code toString()}. The value is ciphertext, so printing it
     * leaks nothing today, but an entity dump is a common way for secrets to reach a
     * log and there is no reason for this field to be in one.
     */
    @ToString.Exclude
    @Column(columnDefinition = "TEXT")
    private String encryptedConfigs;

    /** Master switch. No hook fires while this is false. */
    @Builder.Default
    private Boolean enabled = false;

    /** Ordering across all extensions in the App Store list. */
    @Builder.Default
    @Column(name = "display_order")
    private Integer displayOrder = 0;

    // ── Per-hook flags, seeded from META-INF/services/ at install ─────────────

    @Builder.Default
    private Boolean assessmentEnabled = false;
    @Builder.Default
    private Integer assessmentOrder = 0;

    @Builder.Default
    private Boolean vulnerabilityEnabled = false;
    @Builder.Default
    private Integer vulnerabilityOrder = 0;

    @Builder.Default
    private Boolean verificationEnabled = false;
    @Builder.Default
    private Integer verificationOrder = 0;

    @Builder.Default
    private Boolean inventoryEnabled = false;
    @Builder.Default
    private Integer inventoryOrder = 0;

    @Builder.Default
    private Boolean reportEnabled = false;
    @Builder.Default
    private Integer reportOrder = 0;

    // ── Which hooks the JAR actually declares ────────────────────────────────
    // Kept separate from the enable flags so the UI can show "this JAR provides
    // a Report hook, currently off" rather than conflating absent with disabled.

    @Builder.Default
    private Boolean providesAssessment = false;
    @Builder.Default
    private Boolean providesVulnerability = false;
    @Builder.Default
    private Boolean providesVerification = false;
    @Builder.Default
    private Boolean providesInventory = false;
    @Builder.Default
    private Boolean providesReport = false;

    // ── Audit ────────────────────────────────────────────────────────────────

    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
