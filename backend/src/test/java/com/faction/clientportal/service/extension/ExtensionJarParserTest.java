package com.faction.clientportal.service.extension;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExtensionJarParser}: reads an uploaded extension JAR's identity, its
 * declared configuration, and — the part Faction actually gates on — which
 * {@code com.faction.extender} hooks it implements.
 */
class ExtensionJarParserTest {

    private final ExtensionJarParser parser = new ExtensionJarParser();

    @Test
    void readsManifestDescriptionAndConfig() throws IOException {
        byte[] jar = new ExtensionJarFixture()
                .manifest("Faction Vulnerability Bar Chart", "Josh Summitt", "1.1",
                          "https://www.factionsecurity.com")
                .description("# Bar Chart\nReplaces `${faction-bar-chart}` with a chart.")
                .configJson("""
                        { "Width": { "type": "text", "value": "600" } }
                        """)
                .logo(new byte[]{1, 2, 3, 4})
                .service("ReportManager", "com.faction.VulnerabilityBarChart")
                .build();

        ExtensionJarParser.ParsedExtension parsed = parser.parse(jar);

        assertThat(parsed.getName()).isEqualTo("Faction Vulnerability Bar Chart");
        assertThat(parsed.getAuthor()).isEqualTo("Josh Summitt");
        assertThat(parsed.getVersion()).isEqualTo("1.1");
        assertThat(parsed.getUrl()).isEqualTo("https://www.factionsecurity.com");
        assertThat(parsed.getDescription()).contains("Bar Chart");
        assertThat(parsed.getConfigJson()).contains("\"Width\"");
        assertThat(parsed.getLogoBase64()).isEqualTo("AQIDBA==");
        assertThat(parsed.getLogoMimeType()).isEqualTo("image/png");
        assertThat(parsed.getHash()).hasSize(64);
    }

    @Test
    void hookFlagsComeFromTheServiceEntries() throws IOException {
        byte[] jar = new ExtensionJarFixture()
                .manifest("Multi Hook", "Tester", "1.0", null)
                .service("AssessmentManager", "com.example.Asmt")
                .service("VerificationManager", "com.example.Ver")
                .build();

        ExtensionJarParser.ParsedExtension parsed = parser.parse(jar);

        assertThat(parsed.isProvidesAssessment()).isTrue();
        assertThat(parsed.isProvidesVerification()).isTrue();
        // Not declared, so Faction must never offer to switch these on.
        assertThat(parsed.isProvidesReport()).isFalse();
        assertThat(parsed.isProvidesVulnerability()).isFalse();
        assertThat(parsed.isProvidesInventory()).isFalse();
    }

    @Test
    void allFiveHooksAreRecognised() throws IOException {
        byte[] jar = new ExtensionJarFixture()
                .manifest("Everything", "Tester", "1.0", null)
                .service("AssessmentManager", "com.example.A")
                .service("VulnerabilityManager", "com.example.B")
                .service("VerificationManager", "com.example.C")
                .service("ApplicationInventory", "com.example.D")
                .service("ReportManager", "com.example.E")
                .build();

        ExtensionJarParser.ParsedExtension parsed = parser.parse(jar);

        assertThat(parsed.isProvidesAssessment()).isTrue();
        assertThat(parsed.isProvidesVulnerability()).isTrue();
        assertThat(parsed.isProvidesVerification()).isTrue();
        assertThat(parsed.isProvidesInventory()).isTrue();
        assertThat(parsed.isProvidesReport()).isTrue();
    }

    @Test
    void hashIsStableForIdenticalBytesAndDiffersOtherwise() throws IOException {
        byte[] first = new ExtensionJarFixture()
                .manifest("Stable", "Tester", "1.0", null)
                .service("ReportManager", "com.example.R")
                .build();
        byte[] second = new ExtensionJarFixture()
                .manifest("Stable", "Tester", "2.0", null)
                .service("ReportManager", "com.example.R")
                .build();

        assertThat(parser.parse(first).getHash()).isEqualTo(parser.parse(first).getHash());
        assertThat(parser.parse(first).getHash()).isNotEqualTo(parser.parse(second).getHash());
    }

    @Test
    void activeMarkupIsStrippedBeforeItIsEverStored() throws IOException {
        byte[] jar = new ExtensionJarFixture()
                .manifest("Hostile", "Tester", "1.0", null)
                .description("Fine text <script>alert(1)</script><img src=x onerror=alert(1)>")
                .service("ReportManager", "com.example.R")
                .build();

        String description = parser.parse(jar).getDescription();

        assertThat(description).contains("Fine text");
        assertThat(description).doesNotContain("script");
        assertThat(description).doesNotContain("onerror");
    }

    @Test
    void markdownSyntaxSurvivesForClientSideRendering() throws IOException {
        // description.md is markdown; the sanitizer must not mangle it on the way in,
        // because the App Store page renders it with marked.
        byte[] jar = new ExtensionJarFixture()
                .manifest("Documented", "Tester", "1.0", null)
                .description("""
                        # Heading
                        Set __${faction-bar-chart}__ in your template.

                        1. First step
                        2. Second step

                        | Option | Default |
                        |--------|---------|
                        | Width  | 600     |
                        """)
                .service("ReportManager", "com.example.R")
                .build();

        String description = parser.parse(jar).getDescription();

        assertThat(description).contains("# Heading");
        assertThat(description).contains("__${faction-bar-chart}__");
        assertThat(description).contains("1. First step");
        assertThat(description).contains("| Option | Default |");
    }

    @Test
    void embeddedPreviewImagesAreKept() throws IOException {
        // Real extension descriptions embed a base64 preview of what they render —
        // the bundled bar-chart extension ships two. Stripping them leaves the
        // operator reading about a chart they cannot see.
        byte[] jar = new ExtensionJarFixture()
                .manifest("Illustrated", "Tester", "1.0", null)
                .description("<center><img style=\"width:40%\" class='chart' "
                             + "src='data:image/png;base64,iVBORw0KGgo='></center>")
                .service("ReportManager", "com.example.R")
                .build();

        String description = parser.parse(jar).getDescription();

        assertThat(description).contains("<img");
        assertThat(description).contains("center");
        // The sanitizer entity-encodes base64 padding ('=' becomes '&#61;'). That is
        // fine: entities in an attribute value are decoded by the HTML parser, so the
        // browser — and DOMPurify, which works on the parsed DOM — sees the real URI.
        assertThat(description).contains("data:image/png;base64,iVBORw0KGgo");
        assertThat(description.replace("&#61;", "=")).contains("data:image/png;base64,iVBORw0KGgo=");
    }

    @Test
    void rejectsAJarThatDeclaresNoHook() throws IOException {
        byte[] jar = new ExtensionJarFixture()
                .manifest("Inert", "Tester", "1.0", null)
                .description("Does nothing at all")
                .build();

        // Such a JAR would install cleanly and then silently never run.
        assertThatThrownBy(() -> parser.parse(jar))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares no com.faction.extender service");
    }

    @Test
    void rejectsAJarWithNoTitle() throws IOException {
        byte[] jar = new ExtensionJarFixture()
                .manifest(null, "Tester", "1.0", null)
                .service("ReportManager", "com.example.R")
                .build();

        assertThatThrownBy(() -> parser.parse(jar))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Title");
    }

    @Test
    void rejectsBytesThatAreNotAJar() {
        assertThatThrownBy(() -> parser.parse("this is not a jar".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> parser.parse(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }
}
