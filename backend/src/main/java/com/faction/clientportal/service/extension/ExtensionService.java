package com.faction.clientportal.service.extension;

import com.faction.clientportal.dto.ExtensionDto;
import com.faction.clientportal.dto.ExtensionLogDto;
import com.faction.clientportal.dto.UpdateExtensionConfigRequest;
import com.faction.clientportal.dto.UpdateExtensionRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Extension;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Quota;
import com.faction.clientportal.edition.QuotaUsageService;
import com.faction.clientportal.repository.ExtensionLogRepository;
import com.faction.clientportal.repository.ExtensionRepository;
import com.faction.clientportal.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Install, configure and remove App Store extensions.
 *
 * <p>Every mutation ends by reloading {@link ExtensionRegistry}, so a change takes
 * effect immediately rather than at the next restart.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExtensionService {

    private static final String JAR_KEY_PREFIX = "extensions/";
    private static final String JAR_CONTENT_TYPE = "application/java-archive";
    private static final int LOG_PAGE_SIZE = 200;

    private final ExtensionRepository extensionRepository;
    private final EditionPolicy editionPolicy;
    private final QuotaUsageService quotaUsageService;
    private final ExtensionLogRepository extensionLogRepository;
    private final ExtensionJarParser jarParser;
    private final ExtensionConfigCodec configCodec;
    private final ExtensionClassLoaderFactory classLoaderFactory;
    private final ExtensionRegistry registry;
    private final StorageService storageService;

    // ── Read ─────────────────────────────────────────────────────────────────

    public List<ExtensionDto> list() {
        return extensionRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc().stream()
                .map(this::toDto)
                .toList();
    }

    public ExtensionDto get(String id) {
        return toDto(getOrThrow(id));
    }

    public List<ExtensionLogDto> logs(String id) {
        getOrThrow(id);
        return extensionLogRepository
                .findByExtensionIdOrderByTimestampDesc(id, PageRequest.of(0, LOG_PAGE_SIZE))
                .stream()
                .map(ExtensionLogDto::from)
                .toList();
    }

    // ── Install ──────────────────────────────────────────────────────────────

    /**
     * Installs a new extension from an uploaded JAR.
     *
     * <p>Installed disabled. Extension code runs inside Faction's own JVM, so
     * enabling is a second, deliberate step rather than a side effect of upload —
     * which also gives the operator a chance to fill in credentials before anything
     * fires.
     */
    @Transactional
    public ExtensionDto install(byte[] jarBytes, String userId) {
        // Only install is capped. Upgrade replaces a JAR in place and so cannot take the
        // count past the limit, and uninstall frees a slot by soft-deleting the row.
        editionPolicy.requireHeadroom(Quota.EXTENSIONS, quotaUsageService.current(Quota.EXTENSIONS));

        ExtensionJarParser.ParsedExtension parsed = jarParser.parse(jarBytes);

        extensionRepository.findByHashAndDeletedAtIsNull(parsed.getHash()).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    "This exact JAR is already installed as '" + existing.getName()
                    + "' v" + existing.getVersion() + ". To replace it, use Upgrade on that extension.");
        });

        String jarKey = JAR_KEY_PREFIX + UUID.randomUUID() + ".jar";
        storageService.uploadBytes(jarKey, jarBytes, JAR_CONTENT_TYPE);

        Extension extension = Extension.builder()
                .name(parsed.getName())
                .author(parsed.getAuthor())
                .version(parsed.getVersion())
                .url(parsed.getUrl())
                .description(parsed.getDescription())
                .logoBase64(parsed.getLogoBase64())
                .logoMimeType(parsed.getLogoMimeType())
                .jarFileId(jarKey)
                .hash(parsed.getHash())
                .enabled(false)
                .displayOrder(nextDisplayOrder())
                .providesAssessment(parsed.isProvidesAssessment())
                .providesVulnerability(parsed.isProvidesVulnerability())
                .providesVerification(parsed.isProvidesVerification())
                .providesInventory(parsed.isProvidesInventory())
                .providesReport(parsed.isProvidesReport())
                // A declared hook defaults to on, so enabling the extension is the
                // only switch an operator has to find for the common case.
                .assessmentEnabled(parsed.isProvidesAssessment())
                .vulnerabilityEnabled(parsed.isProvidesVulnerability())
                .verificationEnabled(parsed.isProvidesVerification())
                .inventoryEnabled(parsed.isProvidesInventory())
                .reportEnabled(parsed.isProvidesReport())
                .createdBy(userId)
                .lastUpdatedBy(userId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        configCodec.write(extension, configCodec.parseDeclared(parsed.getConfigJson()));

        Extension saved = extensionRepository.save(extension);
        log.info("Installed extension '{}' v{} by {}", saved.getName(), saved.getVersion(), saved.getAuthor());
        registry.reload();
        return toDto(saved);
    }

    /**
     * Replaces an installed extension's JAR in place.
     *
     * <p>Keeps the row — and therefore the configured values — so bumping a version
     * does not make an operator re-enter API credentials. Keys the new JAR no longer
     * declares fall away; keys it adds appear with their declared defaults.
     */
    @Transactional
    public ExtensionDto upgrade(String id, byte[] jarBytes, String userId) {
        Extension extension = getOrThrow(id);
        ExtensionJarParser.ParsedExtension parsed = jarParser.parse(jarBytes);

        String previousHash = extension.getHash();
        Map<String, Map<String, Object>> previousConfig = configCodec.read(extension);

        String jarKey = JAR_KEY_PREFIX + UUID.randomUUID() + ".jar";
        storageService.uploadBytes(jarKey, jarBytes, JAR_CONTENT_TYPE);
        String previousJarKey = extension.getJarFileId();

        extension.setName(parsed.getName());
        extension.setAuthor(parsed.getAuthor());
        extension.setVersion(parsed.getVersion());
        extension.setUrl(parsed.getUrl());
        extension.setDescription(parsed.getDescription());
        extension.setLogoBase64(parsed.getLogoBase64());
        extension.setLogoMimeType(parsed.getLogoMimeType());
        extension.setJarFileId(jarKey);
        extension.setHash(parsed.getHash());

        extension.setProvidesAssessment(parsed.isProvidesAssessment());
        extension.setProvidesVulnerability(parsed.isProvidesVulnerability());
        extension.setProvidesVerification(parsed.isProvidesVerification());
        extension.setProvidesInventory(parsed.isProvidesInventory());
        extension.setProvidesReport(parsed.isProvidesReport());

        // A hook the new JAR dropped cannot stay switched on.
        if (!parsed.isProvidesAssessment())    extension.setAssessmentEnabled(false);
        if (!parsed.isProvidesVulnerability()) extension.setVulnerabilityEnabled(false);
        if (!parsed.isProvidesVerification())  extension.setVerificationEnabled(false);
        if (!parsed.isProvidesInventory())     extension.setInventoryEnabled(false);
        if (!parsed.isProvidesReport())        extension.setReportEnabled(false);

        configCodec.write(extension, configCodec.mergePreservingValues(
                configCodec.parseDeclared(parsed.getConfigJson()), previousConfig));

        extension.setLastUpdatedBy(userId);
        extension.setUpdatedAt(LocalDateTime.now());
        Extension saved = extensionRepository.save(extension);

        classLoaderFactory.evict(previousHash);
        deleteQuietly(previousJarKey);

        log.info("Upgraded extension '{}' to v{}", saved.getName(), saved.getVersion());
        registry.reload();
        return toDto(saved);
    }

    // ── Configure ────────────────────────────────────────────────────────────

    @Transactional
    public ExtensionDto update(String id, UpdateExtensionRequest request, String userId) {
        Extension extension = getOrThrow(id);

        apply(request.getEnabled(), extension::setEnabled);
        apply(request.getDisplayOrder(), extension::setDisplayOrder);

        applyHook(request.getAssessmentEnabled(), extension.getProvidesAssessment(),
                "Assessment", extension::setAssessmentEnabled);
        applyHook(request.getVulnerabilityEnabled(), extension.getProvidesVulnerability(),
                "Vulnerability", extension::setVulnerabilityEnabled);
        applyHook(request.getVerificationEnabled(), extension.getProvidesVerification(),
                "Verification", extension::setVerificationEnabled);
        applyHook(request.getInventoryEnabled(), extension.getProvidesInventory(),
                "Inventory", extension::setInventoryEnabled);
        applyHook(request.getReportEnabled(), extension.getProvidesReport(),
                "Report", extension::setReportEnabled);

        apply(request.getAssessmentOrder(), extension::setAssessmentOrder);
        apply(request.getVulnerabilityOrder(), extension::setVulnerabilityOrder);
        apply(request.getVerificationOrder(), extension::setVerificationOrder);
        apply(request.getInventoryOrder(), extension::setInventoryOrder);
        apply(request.getReportOrder(), extension::setReportOrder);

        extension.setLastUpdatedBy(userId);
        extension.setUpdatedAt(LocalDateTime.now());
        Extension saved = extensionRepository.save(extension);

        registry.reload();
        return toDto(saved);
    }

    /**
     * Updates the values of an extension's declared config keys.
     *
     * <p>Keys the extension did not declare are rejected rather than stored: the
     * extension would never read them, so accepting them would let the UI show
     * settings that silently do nothing.
     */
    @Transactional
    public ExtensionDto updateConfig(String id, UpdateExtensionConfigRequest request, String userId) {
        Extension extension = getOrThrow(id);
        LinkedHashMap<String, Map<String, Object>> config = configCodec.read(extension);

        if (request.getValues() != null) {
            request.getValues().forEach((key, value) -> {
                Map<String, Object> spec = config.get(key);
                if (spec == null) {
                    throw new IllegalArgumentException(
                            "'" + key + "' is not a config key declared by this extension");
                }
                // The UI never receives real password values, only the mask. Echoing
                // it back means "leave this one alone".
                boolean isPassword = "password".equalsIgnoreCase(String.valueOf(spec.get("type")));
                if (isPassword && ExtensionConfigCodec.MASK.equals(value)) return;
                spec.put("value", value == null ? "" : value);
            });
        }

        configCodec.write(extension, config);
        extension.setLastUpdatedBy(userId);
        extension.setUpdatedAt(LocalDateTime.now());
        Extension saved = extensionRepository.save(extension);

        // Configs are handed to instances at load time, so they have to be rebuilt
        // for a change to reach a running extension.
        registry.reload();
        return toDto(saved);
    }

    // ── Uninstall ────────────────────────────────────────────────────────────

    @Transactional
    public void uninstall(String id, String userId) {
        Extension extension = getOrThrow(id);

        extension.setDeletedAt(LocalDateTime.now());
        extension.setEnabled(false);
        extension.setLastUpdatedBy(userId);
        extension.setUpdatedAt(LocalDateTime.now());
        extensionRepository.save(extension);

        classLoaderFactory.evict(extension.getHash());
        deleteQuietly(extension.getJarFileId());
        extensionLogRepository.deleteByExtensionId(id);

        log.info("Uninstalled extension '{}'", extension.getName());
        registry.reload();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Extension getOrThrow(String id) {
        return extensionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Extension not found: " + id));
    }

    private ExtensionDto toDto(Extension extension) {
        return ExtensionDto.from(extension, configCodec.readMasked(extension));
    }

    private int nextDisplayOrder() {
        return extensionRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc().stream()
                .map(Extension::getDisplayOrder)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
    }

    private <T> void apply(T value, java.util.function.Consumer<T> setter) {
        if (value != null) setter.accept(value);
    }

    /** Refuses to switch on a hook the JAR does not implement. */
    private void applyHook(Boolean requested, Boolean provided, String hookName,
                           java.util.function.Consumer<Boolean> setter) {
        if (requested == null) return;
        if (requested && !Boolean.TRUE.equals(provided)) {
            throw new IllegalArgumentException(
                    "This extension does not implement the " + hookName + " hook");
        }
        setter.accept(requested);
    }

    private void deleteQuietly(String key) {
        if (key == null) return;
        try {
            storageService.deleteObject(key);
        } catch (Exception e) {
            // The row is already gone; an orphaned object is not worth failing on.
            log.warn("Could not delete extension JAR {}: {}", key, e.getMessage());
        }
    }
}
