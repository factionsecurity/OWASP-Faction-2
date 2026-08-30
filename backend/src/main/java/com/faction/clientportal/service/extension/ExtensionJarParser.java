package com.faction.clientportal.service.extension;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Reads the metadata Faction needs out of an uploaded extension JAR.
 *
 * <p>The layout is the one the published Faction Extender archetype produces, and
 * is unchanged from Faction 1 so existing extension projects build unmodified:
 *
 * <pre>
 *   META-INF/MANIFEST.MF                                  Title, Author, Version, URL
 *   META-INF/resources/description.md                     markdown shown in the App Store
 *   META-INF/resources/config.json                        declared config keys and defaults
 *   META-INF/resources/logo.png                           App Store tile icon
 *   META-INF/services/com.faction.extender.ReportManager  which hooks this JAR provides
 * </pre>
 *
 * <p>The {@code META-INF/services/} entries are the authoritative answer to "what
 * can this extension do". Faction seeds the per-hook flags from them, so an
 * operator can never switch a JAR on for an interface it does not implement.
 */
@Component
@Slf4j
public class ExtensionJarParser {

    private static final String SERVICES_PREFIX = "META-INF/services/com.faction.extender.";

    /**
     * Descriptions are author-supplied markdown that may embed HTML. Strip anything
     * active before it is ever stored — installing an extension should not be able to
     * script the App Store page.
     *
     * <p>The markdown itself passes through untouched (it is plain text as far as an
     * HTML sanitizer is concerned) and is rendered client-side, which is also where
     * the final sanitization happens. This pass is the safety net, not the only one.
     *
     * <p>Images and centering are allowed deliberately: extension descriptions in the
     * wild embed a base64 preview of what the extension produces — the bundled
     * bar-chart extension ships two, showing the chart it will insert. Dropping them
     * leaves an operator reading about a chart they cannot see.
     */
    private static final PolicyFactory DESCRIPTION_POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "div", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
                           "ul", "ol", "li", "pre", "code", "blockquote",
                           "table", "thead", "tbody", "tr", "th", "td",
                           "b", "strong", "i", "em", "u", "s", "span",
                           "center", "figure", "figcaption")
            .allowElements("a")
            .allowAttributes("href", "title").onElements("a")
            .requireRelNofollowOnLinks()
            // Preview images arrive as data: URIs; remote sources stay possible but
            // are the author's choice, not something Faction fetches at install time.
            .allowElements("img")
            .allowUrlProtocols("https", "http", "mailto", "data")
            .allowAttributes("src", "alt", "title", "width", "height", "style", "class")
                .onElements("img")
            .allowAttributes("style", "class").onElements("div", "p", "span", "center")
            .toFactory();

    @Value
    @Builder
    public static class ParsedExtension {
        String name;
        String author;
        String version;
        String url;
        /** Sanitized markdown from description.md. */
        String description;
        String logoBase64;
        String logoMimeType;
        /** Raw config.json text, or null when the JAR declares no config. */
        String configJson;
        /** SHA-256 of the JAR bytes, lowercase hex. */
        String hash;

        boolean providesAssessment;
        boolean providesVulnerability;
        boolean providesVerification;
        boolean providesInventory;
        boolean providesReport;

        public boolean providesAnyHook() {
            return providesAssessment || providesVulnerability || providesVerification
                    || providesInventory || providesReport;
        }
    }

    /**
     * @throws IllegalArgumentException if the bytes are not a readable JAR, or the
     *         JAR declares no {@code com.faction.extender} service at all — such a
     *         file would install cleanly and then silently never run.
     */
    public ParsedExtension parse(byte[] jarBytes) {
        if (jarBytes == null || jarBytes.length == 0) {
            throw new IllegalArgumentException("Extension file is empty");
        }

        ParsedExtension.ParsedExtensionBuilder builder = ParsedExtension.builder()
                .hash(sha256Hex(jarBytes));

        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(jarBytes))) {

            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                throw new IllegalArgumentException(
                        "Extension JAR has no MANIFEST.MF — build it with the maven-assembly-plugin "
                        + "jar-with-dependencies descriptor and the Title/Author/Version/URL manifest entries");
            }
            Attributes attrs = manifest.getMainAttributes();
            builder.name(sanitizePlain(attrs.getValue("Title")))
                   .author(sanitizePlain(attrs.getValue("Author")))
                   .version(sanitizePlain(attrs.getValue("Version")))
                   .url(sanitizePlain(attrs.getValue("URL")));

            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (entry.isDirectory()) continue;
                String entryName = entry.getName();

                if (entryName.endsWith("description.md")) {
                    builder.description(DESCRIPTION_POLICY.sanitize(
                            new String(jar.readAllBytes(), StandardCharsets.UTF_8)));

                } else if (entryName.endsWith("config.json")) {
                    builder.configJson(new String(jar.readAllBytes(), StandardCharsets.UTF_8));

                } else if (entryName.endsWith("logo.png")) {
                    builder.logoBase64(Base64.getEncoder().encodeToString(jar.readAllBytes()))
                           .logoMimeType("image/png");

                } else if (entryName.startsWith(SERVICES_PREFIX)) {
                    switch (entryName.substring(SERVICES_PREFIX.length())) {
                        case "AssessmentManager"    -> builder.providesAssessment(true);
                        case "VulnerabilityManager" -> builder.providesVulnerability(true);
                        case "VerificationManager"  -> builder.providesVerification(true);
                        case "ApplicationInventory" -> builder.providesInventory(true);
                        case "ReportManager"        -> builder.providesReport(true);
                        default -> log.debug("Ignoring unknown extender service entry: {}", entryName);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read extension JAR: " + e.getMessage(), e);
        }

        ParsedExtension parsed = builder.build();

        if (parsed.getName() == null || parsed.getName().isBlank()) {
            throw new IllegalArgumentException(
                    "Extension JAR manifest has no Title entry — Faction uses it as the extension name");
        }
        if (!parsed.providesAnyHook()) {
            throw new IllegalArgumentException(
                    "Extension JAR declares no com.faction.extender service. Add a file under "
                    + "src/main/resources/META-INF/services/ named after the interface you implement, "
                    + "containing your implementing class name.");
        }
        return parsed;
    }

    /** Strips all markup — manifest values land in plain-text UI fields. */
    private String sanitizePlain(String value) {
        if (value == null) return null;
        return value.replaceAll("<[^>]*>", "").trim();
    }

    public String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
