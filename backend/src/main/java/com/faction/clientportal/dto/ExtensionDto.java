package com.faction.clientportal.dto;

import com.faction.clientportal.model.Extension;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An installed extension as shown in the App Store.
 *
 * <p>Never carries the JAR bytes or the encrypted config blob. {@link #config}
 * holds the declared configuration with password values masked — see
 * {@code ExtensionConfigCodec.readMasked}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtensionDto {

    private String id;
    private String name;
    private String author;
    private String version;
    private String url;
    private String description;
    private String logoBase64;
    private String logoMimeType;
    private String hash;

    private Boolean enabled;
    private Integer displayOrder;

    /** Which hooks the JAR declares. */
    private Boolean providesAssessment;
    private Boolean providesVulnerability;
    private Boolean providesVerification;
    private Boolean providesInventory;
    private Boolean providesReport;

    /** Which of those hooks are switched on. */
    private Boolean assessmentEnabled;
    private Integer assessmentOrder;
    private Boolean vulnerabilityEnabled;
    private Integer vulnerabilityOrder;
    private Boolean verificationEnabled;
    private Integer verificationOrder;
    private Boolean inventoryEnabled;
    private Integer inventoryOrder;
    private Boolean reportEnabled;
    private Integer reportOrder;

    /** Declared config with password values masked. */
    private Map<String, Map<String, Object>> config;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ExtensionDto from(Extension extension,
                                    LinkedHashMap<String, Map<String, Object>> maskedConfig) {
        return ExtensionDto.builder()
                .id(extension.getId())
                .name(extension.getName())
                .author(extension.getAuthor())
                .version(extension.getVersion())
                .url(extension.getUrl())
                .description(extension.getDescription())
                .logoBase64(extension.getLogoBase64())
                .logoMimeType(extension.getLogoMimeType())
                .hash(extension.getHash())
                .enabled(extension.getEnabled())
                .displayOrder(extension.getDisplayOrder())
                .providesAssessment(extension.getProvidesAssessment())
                .providesVulnerability(extension.getProvidesVulnerability())
                .providesVerification(extension.getProvidesVerification())
                .providesInventory(extension.getProvidesInventory())
                .providesReport(extension.getProvidesReport())
                .assessmentEnabled(extension.getAssessmentEnabled())
                .assessmentOrder(extension.getAssessmentOrder())
                .vulnerabilityEnabled(extension.getVulnerabilityEnabled())
                .vulnerabilityOrder(extension.getVulnerabilityOrder())
                .verificationEnabled(extension.getVerificationEnabled())
                .verificationOrder(extension.getVerificationOrder())
                .inventoryEnabled(extension.getInventoryEnabled())
                .inventoryOrder(extension.getInventoryOrder())
                .reportEnabled(extension.getReportEnabled())
                .reportOrder(extension.getReportOrder())
                .config(maskedConfig)
                .createdAt(extension.getCreatedAt())
                .updatedAt(extension.getUpdatedAt())
                .build();
    }
}
