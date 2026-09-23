package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.relationships.Relationship;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An asset location that is a URL becomes a clickable link in the report.
 *
 * <p>It is nearly always a URL — that is what an asset location <em>is</em> — and a reader looking
 * at a finding usually wants to go there. Asking every template author to wrap
 * {@code ${assetLocation}} in a Word hyperlink by hand, for a value that is a link far more often
 * than not, is work the report generator can do for them.
 *
 * <p>The classification is {@link SmartLink}'s, the same one the HYPERLINK field type uses, so the
 * two cannot disagree about what counts as an address. That matters here because an asset location
 * is just as often <em>not</em> a URL: a host and port, a path, a queue name. Those must come
 * through as the plain text they always were.
 */
class DocxUtilsAssetLocationLinkTest {

    private static final String W =
            "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    private WordprocessingMLPackage renderTable(String assetLocation) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();

        main.addObject(XmlUtils.unmarshalString(
                "<w:tbl " + W + ">"
              + "<w:tr><w:tc><w:p><w:r><w:t>${vulnTable}</w:t></w:r></w:p></w:tc></w:tr>"
              + "<w:tr><w:tc><w:p><w:r>"
              + "<w:t>${loop}Affects ${assetLocation} today</w:t>"
              + "</w:r></w:p></w:tc></w:tr>"
              + "</w:tbl>"));

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .vulnerabilities(new java.util.ArrayList<>(List.of(
                        ReportData.ReportVulnerability.builder()
                                .name("SQL Injection").severityKey("CRITICAL")
                                .assetLocation(assetLocation).build())))
                .build());
        return utils.generateDocx("", null);
    }

    private String bodyXml(WordprocessingMLPackage pkg) throws Exception {
        return XmlUtils.marshaltoString(pkg.getMainDocumentPart().getJaxbElement(), true, false);
    }

    private List<String> hyperlinkTargets(WordprocessingMLPackage pkg) {
        return pkg.getMainDocumentPart().getRelationshipsPart().getRelationships()
                .getRelationship().stream()
                .filter(r -> r.getType().endsWith("/hyperlink"))
                .map(Relationship::getTarget)
                .toList();
    }

    // ── locations that are addresses ─────────────────────────────────────────

    @Test
    void aUrlAssetLocationBecomesAClickableLink() throws Exception {
        WordprocessingMLPackage pkg = renderTable("https://app.acme.com/login");

        assertThat(bodyXml(pkg)).contains("<w:hyperlink");
        assertThat(hyperlinkTargets(pkg)).containsExactly("https://app.acme.com/login");
    }

    @Test
    void aBareHostnameAssetLocationGetsAnHttpsTarget() throws Exception {
        assertThat(hyperlinkTargets(renderTable("api.acme.com/v1/users")))
                .containsExactly("https://api.acme.com/v1/users");
    }

    @Test
    void severalLocationsEachBecomeTheirOwnLink() throws Exception {
        WordprocessingMLPackage pkg =
                renderTable("https://app.acme.com, https://admin.acme.com");

        assertThat(hyperlinkTargets(pkg))
                .containsExactly("https://app.acme.com", "https://admin.acme.com");
        assertThat(bodyXml(pkg)).contains(", ");
    }

    @Test
    void theTextAroundTheTokenSurvives() throws Exception {
        String xml = bodyXml(renderTable("https://app.acme.com/login"));

        assertThat(xml).contains("Affects ").contains(" today");
        assertThat(xml).doesNotContain("${assetLocation}");
    }

    /**
     * A query string is the normal shape of an asset location. The character that made the old
     * CDATA handling necessary has to survive the object-model path too — escaped by the
     * marshaller this time rather than wrapped.
     */
    @Test
    void aQueryStringSurvivesAndIsEscaped() throws Exception {
        WordprocessingMLPackage pkg = renderTable("https://app.acme.com/s?q=1&sort=desc");

        assertThat(hyperlinkTargets(pkg)).containsExactly("https://app.acme.com/s?q=1&sort=desc");
        assertThat(bodyXml(pkg)).contains("q=1&amp;sort=desc");
    }

    /** {@code $} is a replacement metacharacter; it must not be interpreted on the way through. */
    @Test
    void aDollarInTheUrlIsNotTreatedAsAReplacementToken() throws Exception {
        WordprocessingMLPackage pkg = renderTable("https://acme.com/a$b/c");

        assertThat(hyperlinkTargets(pkg)).containsExactly("https://acme.com/a$b/c");
        assertThat(bodyXml(pkg)).contains("a$b");
    }

    // ── locations that are not addresses ─────────────────────────────────────

    /**
     * Just as often an asset location is a host and port, a path, or a queue name. Linking those
     * would produce a report full of dead links, so they stay exactly the plain text they were.
     */
    @Test
    void aHostAndPortIsNotLinked() throws Exception {
        WordprocessingMLPackage pkg = renderTable("10.0.0.5:8080");

        assertThat(bodyXml(pkg)).contains("10.0.0.5:8080").doesNotContain("<w:hyperlink");
        assertThat(hyperlinkTargets(pkg)).isEmpty();
    }

    @Test
    void aPathIsNotLinked() throws Exception {
        WordprocessingMLPackage pkg = renderTable("/api/v1/users");

        assertThat(bodyXml(pkg)).contains("/api/v1/users").doesNotContain("<w:hyperlink");
    }

    @Test
    void aPlainNameIsNotLinked() throws Exception {
        WordprocessingMLPackage pkg = renderTable("Payments queue");

        assertThat(bodyXml(pkg)).contains("Payments queue").doesNotContain("<w:hyperlink");
    }

    @Test
    void anEmptyAssetLocationLeavesNeitherTokenNorLink() throws Exception {
        WordprocessingMLPackage pkg = renderTable("");

        String xml = bodyXml(pkg);
        assertThat(xml).doesNotContain("${assetLocation}").doesNotContain("<w:hyperlink");
        assertThat(xml).contains("Affects ").contains(" today");
    }

    // ── the findings-block path ──────────────────────────────────────────────

    /** Findings blocks are a separate code path from the table, and need the same behaviour. */
    @Test
    void aUrlIsLinkedInAFindingsBlockToo() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("${fiBegin}");
        main.addParagraphOfText("Affects ${assetLocation}");
        main.addParagraphOfText("${fiEnd}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .vulnerabilities(new java.util.ArrayList<>(List.of(
                        ReportData.ReportVulnerability.builder()
                                .name("SQLi").severityKey("CRITICAL")
                                .assetLocation("https://app.acme.com/login").build())))
                .build());
        WordprocessingMLPackage result = utils.generateDocx("", null);

        assertThat(bodyXml(result)).contains("<w:hyperlink");
        assertThat(hyperlinkTargets(result)).containsExactly("https://app.acme.com/login");
    }
}
