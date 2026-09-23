package com.faction.clientportal.util.reporting;

import com.faction.clientportal.model.FieldType;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.relationships.Relationship;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.wml.Hdr;
import org.docx4j.wml.HdrFtrRef;
import org.docx4j.wml.HeaderReference;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code HYPERLINK} user-defined field has to become a real Word hyperlink — something a reader
 * can click in the delivered DOCX — from a plain {@code ${varName}} token sitting anywhere in the
 * template.
 *
 * <p>That is the whole reason this type exists. The older {@code ${varName link}} convention only
 * works if whoever built the template had already inserted a Word hyperlink and typed the token
 * into its display text; a token that is just text can never become a link that way, because the
 * plain-text pass is a string replace over marshalled XML and has nowhere to hang a relationship.
 *
 * <p>These tests assert on both halves of a DOCX hyperlink: the {@code w:hyperlink} element in the
 * document body, and the external relationship it points at. Asserting only on the visible text
 * would pass for a field that renders as plain text, which is exactly the bug being fixed.
 */
class DocxUtilsHyperlinkFieldTest {

    /**
     * Generates a one-paragraph document in which {@code contact} is a HYPERLINK field holding
     * {@code value}, and returns the package so both the body XML and the relationships can be
     * inspected.
     */
    private WordprocessingMLPackage generate(String paragraphText, String value) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText(paragraphText);

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .fieldValues(new java.util.HashMap<>(Map.of("contact", value)))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", FieldType.HYPERLINK)))
                .vulnerabilities(new java.util.ArrayList<>())
                .build());
        return utils.generateDocx("", null);
    }

    private String bodyXml(WordprocessingMLPackage pkg) throws Exception {
        return XmlUtils.marshaltoString(pkg.getMainDocumentPart().getJaxbElement(), true, false);
    }

    /** Every external hyperlink target in the document, in relationship order. */
    private List<String> hyperlinkTargets(WordprocessingMLPackage pkg) {
        return pkg.getMainDocumentPart().getRelationshipsPart().getRelationships()
                .getRelationship().stream()
                .filter(r -> r.getType().endsWith("/hyperlink"))
                .map(Relationship::getTarget)
                .toList();
    }

    private int countOf(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    @Test
    void anEmailFieldBecomesAClickableMailtoLink() throws Exception {
        WordprocessingMLPackage pkg = generate("Contact: ${contact}", "ops@acme.com");

        assertThat(bodyXml(pkg)).contains("<w:hyperlink").contains("ops@acme.com");
        assertThat(hyperlinkTargets(pkg)).containsExactly("mailto:ops@acme.com");
    }

    @Test
    void aUrlFieldBecomesAClickableWebLink() throws Exception {
        WordprocessingMLPackage pkg = generate("Portal: ${contact}", "https://acme.com/portal");

        assertThat(bodyXml(pkg)).contains("<w:hyperlink");
        assertThat(hyperlinkTargets(pkg)).containsExactly("https://acme.com/portal");
    }

    @Test
    void aBareHostnameGetsAnHttpsTarget() throws Exception {
        WordprocessingMLPackage pkg = generate("Portal: ${contact}", "www.acme.com");

        assertThat(hyperlinkTargets(pkg)).containsExactly("https://www.acme.com");
    }

    /**
     * The point of the "smart" part: one field, three addresses, three separate links, and the
     * commas the user typed still between them.
     */
    @Test
    void severalEmailsInOneFieldEachBecomeTheirOwnLinkOnTheSameLine() throws Exception {
        WordprocessingMLPackage pkg =
                generate("Contacts: ${contact}", "ops@acme.com, soc@acme.com; ir@acme.com");

        assertThat(hyperlinkTargets(pkg)).containsExactly(
                "mailto:ops@acme.com", "mailto:soc@acme.com", "mailto:ir@acme.com");

        String xml = bodyXml(pkg);
        assertThat(countOf(xml, "<w:hyperlink")).isEqualTo(3);
        // One paragraph, so the delimiters stayed inline rather than becoming line breaks.
        assertThat(countOf(xml, "<w:p>")).isEqualTo(countOf(xml, "</w:p>"));
        assertThat(xml).contains(", ").contains("; ");
    }

    @Test
    void theTextAroundTheTokenSurvives() throws Exception {
        WordprocessingMLPackage pkg =
                generate("Email ${contact} with questions.", "ops@acme.com");

        String xml = bodyXml(pkg);
        assertThat(xml).contains("Email ").contains(" with questions.");
        assertThat(xml).doesNotContain("${contact}");
    }

    /**
     * A value that is not an address must not leave the token behind either — it renders as the
     * plain text the user typed, with no relationship created.
     */
    @Test
    void aNonAddressValueRendersAsPlainTextWithNoLink() throws Exception {
        WordprocessingMLPackage pkg = generate("Contact: ${contact}", "N/A");

        String xml = bodyXml(pkg);
        assertThat(xml).contains("N/A").doesNotContain("${contact}").doesNotContain("<w:hyperlink");
        assertThat(hyperlinkTargets(pkg)).isEmpty();
    }

    @Test
    void anEmptyValueLeavesNoTokenAndNoLink() throws Exception {
        WordprocessingMLPackage pkg = generate("Contact: ${contact}", "");

        String xml = bodyXml(pkg);
        assertThat(xml).doesNotContain("${contact}").doesNotContain("<w:hyperlink");
        assertThat(hyperlinkTargets(pkg)).isEmpty();
    }

    /**
     * A template can reference the same field twice — once in the contacts table, once in the
     * closing paragraph. Both have to resolve, each with its own relationship.
     */
    @Test
    void aFieldUsedTwiceIsLinkedInBothPlaces() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("Primary: ${contact}");
        main.addParagraphOfText("Escalation: ${contact}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .fieldValues(new java.util.HashMap<>(Map.of("contact", "ops@acme.com")))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", FieldType.HYPERLINK)))
                .vulnerabilities(new java.util.ArrayList<>())
                .build());
        WordprocessingMLPackage result = utils.generateDocx("", null);

        assertThat(countOf(bodyXml(result), "<w:hyperlink")).isEqualTo(2);
        assertThat(hyperlinkTargets(result))
                .containsExactly("mailto:ops@acme.com", "mailto:ops@acme.com");
    }

    /**
     * A STRING field next to a HYPERLINK field must still land as plain text — the new pass only
     * claims the tokens whose type asked for it.
     */
    @Test
    void aPlainStringFieldIsUnaffected() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        pkg.getMainDocumentPart().addParagraphOfText("${owner} at ${contact}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .fieldValues(new java.util.HashMap<>(
                        Map.of("owner", "acme.com team", "contact", "ops@acme.com")))
                .fieldTypes(new java.util.HashMap<>(
                        Map.of("owner", FieldType.STRING, "contact", FieldType.HYPERLINK)))
                .vulnerabilities(new java.util.ArrayList<>())
                .build());
        WordprocessingMLPackage result = utils.generateDocx("", null);

        // "acme.com team" is a STRING, so its domain-looking prefix is left alone.
        assertThat(bodyXml(result)).contains("acme.com team");
        assertThat(hyperlinkTargets(result)).containsExactly("mailto:ops@acme.com");
    }

    // ── findings loop ────────────────────────────────────────────────────────

    /**
     * The same field on a finding rather than on the assessment. Findings are rendered by a
     * separate code path: the loop row is marshalled to XML, string-replaced once per finding and
     * unmarshalled back, so the token has to survive that round trip and be linked afterwards.
     */
    private WordprocessingMLPackage generateFindingsTable(String cellText, String fieldValue)
            throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();

        Tbl table = (Tbl) XmlUtils.unmarshalString(
                "<w:tbl xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
              + "<w:tr><w:tc><w:p><w:r><w:t>${vulnTable}</w:t></w:r></w:p></w:tc></w:tr>"
              + "<w:tr><w:tc><w:p><w:r><w:t>${loop}" + cellText + "</w:t></w:r></w:p></w:tc></w:tr>"
              + "</w:tbl>");
        main.addObject(table);

        ReportData.ReportVulnerability vuln = ReportData.ReportVulnerability.builder()
                .name("SQL Injection")
                .severity("Critical")
                .severityKey("CRITICAL")
                .fieldValues(new java.util.HashMap<>(Map.of("contact", fieldValue)))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", FieldType.HYPERLINK)))
                .build();

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .vulnerabilities(new java.util.ArrayList<>(List.of(vuln)))
                .build());
        return utils.generateDocx("", null);
    }

    @Test
    void aFindingsHyperlinkFieldBecomesAClickableLinkInTheLoopRow() throws Exception {
        WordprocessingMLPackage pkg = generateFindingsTable("Owner: ${contact}", "ops@acme.com");

        String xml = bodyXml(pkg);
        assertThat(xml).contains("<w:hyperlink").contains("Owner: ").doesNotContain("${contact}");
        assertThat(hyperlinkTargets(pkg)).containsExactly("mailto:ops@acme.com");
    }

    @Test
    void severalAddressesOnAFindingEachBecomeTheirOwnLink() throws Exception {
        WordprocessingMLPackage pkg =
                generateFindingsTable("${contact}", "ops@acme.com https://acme.com");

        assertThat(hyperlinkTargets(pkg))
                .containsExactly("mailto:ops@acme.com", "https://acme.com");
        assertThat(countOf(bodyXml(pkg), "<w:hyperlink")).isEqualTo(2);
    }

    /** The older convention keeps working — a template already using it must not regress. */
    @Test
    void theOlderLinkSuffixConventionIsUnaffected() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("placeholder");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .fieldValues(new java.util.HashMap<>(Map.of("contact", "ops@acme.com")))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", FieldType.STRING)))
                .vulnerabilities(new java.util.ArrayList<>())
                .build());
        WordprocessingMLPackage result = utils.generateDocx("", null);

        // No pre-existing w:hyperlink in the template, so nothing to rewrite and nothing added.
        assertThat(hyperlinkTargets(result)).isEmpty();
        assertThat(bodyXml(result)).contains("placeholder");
    }

    // ── inside a rich-text field ─────────────────────────────────────────────

    /**
     * A {@code ${varName}} for a HYPERLINK field can also sit inside a rich-text field — a
     * finding's description naming the contact to escalate to. There the value has to arrive as an
     * anchor, because that content goes through the XHTML importer rather than the run-splitting
     * path above. Printing the raw address as text would be the bug: the same field would be a
     * link in one half of the report and not in the other.
     */
    private String replaceUdfsInHtml(String html, FieldType type, String value) throws Exception {
        ReportData.ReportVulnerability vuln = ReportData.ReportVulnerability.builder()
                .fieldValues(new java.util.HashMap<>(Map.of("contact", value)))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", type)))
                .build();
        DocxUtils utils = new DocxUtils(null, ReportData.builder().build());
        Method m = DocxUtils.class.getDeclaredMethod("replaceVulnUdfsInHtml",
                String.class, ReportData.ReportVulnerability.class);
        m.setAccessible(true);
        return (String) m.invoke(utils, html, vuln);
    }

    @Test
    void aHyperlinkFieldInsideRichTextBecomesAnAnchor() throws Exception {
        assertThat(replaceUdfsInHtml(
                "<p>Escalate to ${contact}.</p>", FieldType.HYPERLINK, "ops@acme.com"))
                .isEqualTo("<p>Escalate to <a href=\"mailto:ops@acme.com\">ops@acme.com</a>.</p>");
    }

    @Test
    void aStringFieldInsideRichTextStaysPlainText() throws Exception {
        assertThat(replaceUdfsInHtml(
                "<p>Owner: ${contact}</p>", FieldType.STRING, "ops@acme.com"))
                .isEqualTo("<p>Owner: ops@acme.com</p>");
    }

    @Test
    void severalAddressesInsideRichTextBecomeSeveralAnchors() throws Exception {
        assertThat(replaceUdfsInHtml(
                "<p>${contact}</p>", FieldType.HYPERLINK, "ops@acme.com, acme.com"))
                .isEqualTo("<p><a href=\"mailto:ops@acme.com\">ops@acme.com</a>, "
                         + "<a href=\"https://acme.com\">acme.com</a></p>");
    }

    /**
     * The assessment-level equivalent: an assessment HYPERLINK field referenced from the executive
     * summary or any other rich-text field. Same reasoning as the finding case — the content is
     * HTML on its way to the XHTML importer, so the value has to arrive as an anchor.
     */
    private String replaceAssessmentUdfsInHtml(String html, FieldType type, String value)
            throws Exception {
        DocxUtils utils = new DocxUtils(null, ReportData.builder()
                .fieldValues(new java.util.HashMap<>(Map.of("contact", value)))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", type)))
                .vulnerabilities(new java.util.ArrayList<>())
                .build());
        Method m = DocxUtils.class.getDeclaredMethod("replacement", String.class);
        m.setAccessible(true);
        return (String) m.invoke(utils, html);
    }

    @Test
    void anAssessmentHyperlinkFieldInsideRichTextBecomesAnAnchor() throws Exception {
        assertThat(replaceAssessmentUdfsInHtml(
                "<p>Questions to ${contact}.</p>", FieldType.HYPERLINK, "ops@acme.com"))
                .contains("<a href=\"mailto:ops@acme.com\">ops@acme.com</a>")
                .doesNotContain("${contact}");
    }

    @Test
    void anAssessmentStringFieldInsideRichTextStaysPlainText() throws Exception {
        assertThat(replaceAssessmentUdfsInHtml(
                "<p>Owner: ${contact}</p>", FieldType.STRING, "ops@acme.com"))
                .contains("Owner: ops@acme.com")
                .doesNotContain("<a href");
    }

    /**
     * Word splits a run whenever it feels like it — a spell-check boundary, an editing-session id
     * — so a real template's token is rarely as tidy as the ones above. The run-splitting pass
     * matches within one run and would miss it; {@code VariablePrepare.prepare}, which
     * {@code generateDocx} runs first, stitches those runs back together beforehand.
     *
     * <p>A regression guard rather than a new behaviour: if that preparation step is ever moved
     * or dropped, hyperlink fields go quietly dead in exactly the templates people actually use.
     */
    @Test
    void aTokenWordSplitAcrossTwoRunsIsStillLinked() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addObject(XmlUtils.unmarshalString(
                "<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
              + "<w:r><w:t>Contact: ${con</w:t></w:r>"
              + "<w:r><w:t>tact}</w:t></w:r></w:p>"));

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .fieldValues(new java.util.HashMap<>(Map.of("contact", "ops@acme.com")))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", FieldType.HYPERLINK)))
                .vulnerabilities(new java.util.ArrayList<>())
                .build());
        WordprocessingMLPackage result = utils.generateDocx("", null);

        assertThat(bodyXml(result)).doesNotContain("${contact}").contains("<w:hyperlink");
        assertThat(hyperlinkTargets(result)).containsExactly("mailto:ops@acme.com");
    }

    // ── headers and footers ──────────────────────────────────────────────────

    /**
     * Headers and footers are replaced by string surgery on their marshalled XML — there is no
     * object tree walk there, so a HYPERLINK field cannot become a clickable link in a page
     * header. It must still resolve to the text the user typed.
     *
     * <p>The failure this guards against is the ugly one: the new field type is excluded from the
     * plain-text pass so the body can link it, and a template with {@code ${contact}} in its page
     * header then ships to the client with the literal token printed on every page.
     */
    @Test
    void aHyperlinkFieldInAPageHeaderResolvesToItsText() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("body");

        ObjectFactory factory = new ObjectFactory();
        Hdr hdr = factory.createHdr();
        hdr.getContent().add(main.createParagraphOfText("Questions: ${contact}"));
        HeaderPart headerPart = new HeaderPart();
        headerPart.setPackage(pkg);
        headerPart.setJaxbElement(hdr);
        Relationship headerRel = main.addTargetPart(headerPart);

        SectPr sectPr = main.getJaxbElement().getBody().getSectPr();
        if (sectPr == null) {
            sectPr = factory.createSectPr();
            main.getJaxbElement().getBody().setSectPr(sectPr);
        }
        HeaderReference headerReference = factory.createHeaderReference();
        headerReference.setId(headerRel.getId());
        headerReference.setType(HdrFtrRef.DEFAULT);
        sectPr.getEGHdrFtrReferences().add(headerReference);

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .fieldValues(new java.util.HashMap<>(Map.of("contact", "ops@acme.com")))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", FieldType.HYPERLINK)))
                .vulnerabilities(new java.util.ArrayList<>())
                .build());
        utils.generateDocx("", null);

        // Via the document model's section rather than the package: the package-level
        // getHeaderFooterPolicy() is deprecated.
        HeaderPart defaultHeader = pkg.getDocumentModel().getSections().get(0)
                .getHeaderFooterPolicy().getDefaultHeader();
        String headerXml = XmlUtils.marshaltoString(defaultHeader.getJaxbElement(), true, false);
        assertThat(headerXml).contains("ops@acme.com").doesNotContain("${contact}");
    }

    // ── findings blocks ──────────────────────────────────────────────────────

    /**
     * A finding's HYPERLINK field used inside a {@code ${fiBegin}}…{@code ${fiEnd}} block.
     *
     * <p>A gap in the original implementation, which took three fixes to close — worth recording,
     * since each one alone still left the field as plain text:
     * <ol>
     *   <li>the link pass was wired into the findings <em>table</em> but never the findings
     *       <em>block</em>, a separate code path;</li>
     *   <li>the block hands the pass one bare paragraph at a time, and {@code getParagraphs} visits
     *       a node's children rather than the node itself, so the paragraph was skipped;</li>
     *   <li>the block's plain-text pass, unlike the table's, never excluded HYPERLINK, so the token
     *       was already substituted as text before the link pass could see it. This test is what
     *       found that one.</li>
     * </ol>
     */
    @Test
    void aFindingsHyperlinkFieldIsLinkedInAFindingsBlockToo() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("${fiBegin}");
        main.addParagraphOfText("Owner: ${contact}");
        main.addParagraphOfText("${fiEnd}");

        ReportData.ReportVulnerability vuln = ReportData.ReportVulnerability.builder()
                .name("SQL Injection").severity("Critical").severityKey("CRITICAL")
                .fieldValues(new java.util.HashMap<>(Map.of("contact", "ops@acme.com")))
                .fieldTypes(new java.util.HashMap<>(Map.of("contact", FieldType.HYPERLINK)))
                .build();

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .vulnerabilities(new java.util.ArrayList<>(List.of(vuln)))
                .build());
        WordprocessingMLPackage result = utils.generateDocx("", null);

        assertThat(bodyXml(result)).contains("<w:hyperlink").doesNotContain("${contact}");
        assertThat(hyperlinkTargets(result)).containsExactly("mailto:ops@acme.com");
    }
}
