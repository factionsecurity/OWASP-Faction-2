package com.faction.clientportal.service.extension;

import com.faction.clientportal.model.Extension;
import com.faction.clientportal.service.StorageService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches one {@link URLClassLoader} per installed extension JAR.
 *
 * <p>Faction 1 loaded extension classes through a bespoke {@code x-buffer:} URL
 * protocol backed by an in-memory byte map, which sidestepped the filesystem but
 * broke down for JARs that read their own resources. Here the JAR is staged to a
 * temp file and loaded through the ordinary file URL, which behaves correctly for
 * resource lookups, nested JAR indexes and signed JARs alike.
 *
 * <p>The parent classloader must be Faction's own. Extension JARs are built
 * {@code jar-with-dependencies} and therefore contain their own copy of the
 * {@code com.faction.extender} interfaces; if the loader resolved those locally,
 * the {@code Class} objects would differ from Faction's and {@link java.util.ServiceLoader}
 * would return nothing at all — with no error to explain why. Delegating to the
 * parent first means the interfaces always resolve to Faction's copy.
 *
 * <p>Loaders are cached by JAR hash, so re-reading configuration or toggling a hook
 * does not re-download and re-open the JAR, while a genuinely new upload — which
 * necessarily hashes differently — always gets a fresh loader.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExtensionClassLoaderFactory {

    private final StorageService storageService;

    private final Map<String, URLClassLoader> loadersByHash = new ConcurrentHashMap<>();
    private final Map<String, Path>           stagedByHash  = new ConcurrentHashMap<>();

    private Path stagingDir;

    /**
     * Returns the loader for this extension's JAR, downloading and staging it on
     * first use.
     */
    public URLClassLoader loaderFor(Extension extension) {
        String hash = extension.getHash();
        if (hash == null || extension.getJarFileId() == null) {
            throw new IllegalStateException(
                    "Extension " + extension.getName() + " has no stored JAR");
        }
        return loadersByHash.computeIfAbsent(hash, h -> {
            try {
                Path jar = stage(h, storageService.downloadBytes(extension.getJarFileId()));
                URL[] urls = { jar.toUri().toURL() };
                // Parent = Faction's own classloader. See class javadoc.
                return new URLClassLoader(urls, ExtensionClassLoaderFactory.class.getClassLoader());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Could not stage extension JAR for " + extension.getName(), e);
            }
        });
    }

    /** Drops the cached loader for a hash, closing it so the staged file can be replaced. */
    public void evict(String hash) {
        if (hash == null) return;
        URLClassLoader loader = loadersByHash.remove(hash);
        closeQuietly(loader);
        Path staged = stagedByHash.remove(hash);
        if (staged != null) {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException e) {
                log.debug("Could not delete staged extension JAR {}: {}", staged, e.getMessage());
            }
        }
    }

    /** Drops every cached loader — used when the registry does a full reload. */
    public void evictAll() {
        loadersByHash.keySet().forEach(this::evict);
    }

    private synchronized Path stage(String hash, byte[] jarBytes) throws IOException {
        if (stagingDir == null) {
            stagingDir = Files.createTempDirectory("faction-extensions");
            stagingDir.toFile().deleteOnExit();
        }
        Path jar = stagingDir.resolve(hash + ".jar");
        Files.write(jar, jarBytes);
        jar.toFile().deleteOnExit();
        stagedByHash.put(hash, jar);
        log.debug("Staged extension JAR {} ({} bytes)", jar, jarBytes.length);
        return jar;
    }

    @PreDestroy
    void shutdown() {
        evictAll();
    }

    private void closeQuietly(URLClassLoader loader) {
        if (loader == null) return;
        try {
            loader.close();
        } catch (IOException e) {
            log.debug("Error closing extension classloader: {}", e.getMessage());
        }
    }
}
