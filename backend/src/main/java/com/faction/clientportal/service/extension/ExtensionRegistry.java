package com.faction.clientportal.service.extension;

import com.faction.clientportal.model.Extension;
import com.faction.clientportal.repository.ExtensionRepository;
import com.faction.extender.ApplicationInventory;
import com.faction.extender.AssessmentManager;
import com.faction.extender.BaseInterface;
import com.faction.extender.ReportManager;
import com.faction.extender.VerificationManager;
import com.faction.extender.VulnerabilityManager;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Holds the live extension instances, grouped by hook type.
 *
 * <p>Faction 1 constructed a fresh {@code Extensions} object on every single event,
 * which meant base64-decoding and re-parsing every installed JAR each time a
 * vulnerability was saved. Here the instances are loaded once and kept, and are
 * rebuilt only when the installed set actually changes — install, upgrade, enable,
 * disable, reconfigure or uninstall.
 *
 * <p>Instances are stateful (they hold their configs) and are shared across
 * threads, which matches how Faction 1 used them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExtensionRegistry {

    public enum EventType { INVENTORY, VER_MANAGER, ASMT_MANAGER, VULN_MANAGER, REPORT_MANAGER }

    /** An extension instance paired with the row it came from, so logs can be attributed. */
    @Value
    public static class LoadedExtension<T extends BaseInterface> {
        String extensionId;
        String extensionName;
        T instance;
    }

    private final ExtensionRepository extensionRepository;
    private final ExtensionClassLoaderFactory classLoaderFactory;
    private final ExtensionConfigCodec configCodec;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<EventType, List<LoadedExtension<?>>> byType = emptyMap();

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        try {
            reload();
        } catch (Exception e) {
            // A broken extension must never stop Faction from booting.
            log.error("Failed to load extensions on startup", e);
        }
    }

    /** Rebuilds every hook list from the database. Safe to call repeatedly. */
    public void reload() {
        List<Extension> installed = extensionRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc();
        Map<EventType, List<LoadedExtension<?>>> rebuilt = emptyMap();

        for (Extension extension : installed) {
            if (!Boolean.TRUE.equals(extension.getEnabled())) continue;

            URLClassLoader loader;
            try {
                loader = classLoaderFactory.loaderFor(extension);
            } catch (Exception e) {
                log.error("Could not open JAR for extension '{}' — skipping", extension.getName(), e);
                continue;
            }

            Map<String, String> configs = configCodec.toMap(extension);

            discover(rebuilt, EventType.ASMT_MANAGER,   AssessmentManager.class,
                     extension, loader, configs, Extension::getAssessmentEnabled);
            discover(rebuilt, EventType.VULN_MANAGER,   VulnerabilityManager.class,
                     extension, loader, configs, Extension::getVulnerabilityEnabled);
            discover(rebuilt, EventType.VER_MANAGER,    VerificationManager.class,
                     extension, loader, configs, Extension::getVerificationEnabled);
            discover(rebuilt, EventType.INVENTORY,      ApplicationInventory.class,
                     extension, loader, configs, Extension::getInventoryEnabled);
            discover(rebuilt, EventType.REPORT_MANAGER, ReportManager.class,
                     extension, loader, configs, Extension::getReportEnabled);
        }

        // Order within a hook follows that hook's own order column, so an operator
        // can decide which of two report extensions gets to rewrite the text first.
        rebuilt.forEach((type, list) -> list.sort(Comparator.comparingInt(
                le -> orderFor(installed, le.getExtensionId(), type))));

        lock.writeLock().lock();
        try {
            byType = rebuilt;
        } finally {
            lock.writeLock().unlock();
        }

        log.info("Extension registry loaded: {}",
                rebuilt.entrySet().stream()
                       .filter(e -> !e.getValue().isEmpty())
                       .map(e -> e.getKey() + "=" + e.getValue().size())
                       .toList());
    }

    private <T extends BaseInterface> void discover(
            Map<EventType, List<LoadedExtension<?>>> target,
            EventType type,
            Class<T> hookInterface,
            Extension extension,
            URLClassLoader loader,
            Map<String, String> configs,
            Function<Extension, Boolean> hookFlag) {

        if (!Boolean.TRUE.equals(hookFlag.apply(extension))) return;

        // ServiceLoader consults the thread context classloader for some providers,
        // so set it for the duration of discovery exactly as Faction 1 did, and
        // always put it back.
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            for (T instance : ServiceLoader.load(hookInterface, loader)) {
                if (instance == null) continue;
                instance.setConfigs(new java.util.HashMap<>(configs));
                target.get(type).add(new LoadedExtension<>(
                        extension.getId(), extension.getName(), instance));
                log.info("Loaded {} hook '{}' from extension '{}' v{}",
                        hookInterface.getSimpleName(), instance.getClass().getName(),
                        extension.getName(), extension.getVersion());
            }
        } catch (Throwable t) {
            // Extension code runs during discovery (static initialisers, constructors).
            // Catch Throwable, not Exception: a NoClassDefFoundError from a badly
            // shaded JAR is the common failure and must not take the registry down.
            log.error("Failed to load {} from extension '{}'",
                      hookInterface.getSimpleName(), extension.getName(), t);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private int orderFor(List<Extension> installed, String extensionId, EventType type) {
        return installed.stream()
                .filter(e -> e.getId().equals(extensionId))
                .findFirst()
                .map(e -> switch (type) {
                    case ASMT_MANAGER   -> nullSafe(e.getAssessmentOrder());
                    case VULN_MANAGER   -> nullSafe(e.getVulnerabilityOrder());
                    case VER_MANAGER    -> nullSafe(e.getVerificationOrder());
                    case INVENTORY      -> nullSafe(e.getInventoryOrder());
                    case REPORT_MANAGER -> nullSafe(e.getReportOrder());
                })
                .orElse(0);
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    // ── Query ────────────────────────────────────────────────────────────────

    /**
     * True when at least one enabled extension implements this hook. Callers use
     * this to skip the (expensive) work of cloning an assessment into the
     * extender element model when nothing is listening.
     */
    public boolean isExtended(EventType type) {
        lock.readLock().lock();
        try {
            return !byType.get(type).isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends BaseInterface> List<LoadedExtension<T>> get(EventType type) {
        lock.readLock().lock();
        try {
            return (List<LoadedExtension<T>>) (List<?>) List.copyOf(byType.get(type));
        } finally {
            lock.readLock().unlock();
        }
    }

    private static Map<EventType, List<LoadedExtension<?>>> emptyMap() {
        Map<EventType, List<LoadedExtension<?>>> map = new EnumMap<>(EventType.class);
        for (EventType type : EventType.values()) {
            map.put(type, new ArrayList<>());
        }
        return map;
    }
}
