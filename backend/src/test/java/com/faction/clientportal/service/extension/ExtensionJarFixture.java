package com.faction.clientportal.service.extension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Builds extension JARs in memory for tests.
 *
 * <p>Produces the same layout the published Faction Extender archetype does — the
 * manifest attributes, {@code META-INF/resources/*} and the
 * {@code META-INF/services/} entries — so parser and registry tests exercise the
 * real thing rather than a simplified stand-in.
 */
final class ExtensionJarFixture {

    private final Map<String, byte[]> entries = new LinkedHashMap<>();
    private final Manifest manifest = new Manifest();

    ExtensionJarFixture() {
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    }

    ExtensionJarFixture manifest(String title, String author, String version, String url) {
        Attributes attrs = manifest.getMainAttributes();
        if (title != null)   attrs.putValue("Title", title);
        if (author != null)  attrs.putValue("Author", author);
        if (version != null) attrs.putValue("Version", version);
        if (url != null)     attrs.putValue("URL", url);
        return this;
    }

    ExtensionJarFixture description(String markdown) {
        return entry("META-INF/resources/description.md", markdown.getBytes(StandardCharsets.UTF_8));
    }

    ExtensionJarFixture configJson(String json) {
        return entry("META-INF/resources/config.json", json.getBytes(StandardCharsets.UTF_8));
    }

    ExtensionJarFixture logo(byte[] pngBytes) {
        return entry("META-INF/resources/logo.png", pngBytes);
    }

    /**
     * Declares a hook, e.g. {@code service("ReportManager", "com.example.MyChart")}.
     */
    ExtensionJarFixture service(String simpleInterfaceName, String implementationClass) {
        return entry("META-INF/services/com.faction.extender." + simpleInterfaceName,
                implementationClass.getBytes(StandardCharsets.UTF_8));
    }

    /** Adds a compiled class so a registry test can actually instantiate the hook. */
    ExtensionJarFixture classFile(Class<?> type) throws IOException {
        String path = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Could not read bytecode for " + type.getName());
            return entry(path, in.readAllBytes());
        }
    }

    ExtensionJarFixture entry(String name, byte[] content) {
        entries.put(name, content);
        return this;
    }

    byte[] build() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // The manifest must be written first: JarInputStream.getManifest() only sees
        // it when it is the first entry, which is what the parser relies on.
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
