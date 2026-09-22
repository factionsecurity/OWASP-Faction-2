package com.faction.clientportal.util.reporting;

import com.faction.clientportal.model.ReportPalette;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.Tbl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Colour sentinels resolving against the template's palette.
 *
 * <p>An author paints a reserved hex where they want a data-driven colour, and the generator swaps
 * it for whatever that finding's value maps to. Each dimension has a light and a dark sentinel
 * meaning the same thing, so a severity chip can be authored as dark text on a light cell and
 * remain readable in Word — with a single hex the author had to paint amber on amber.
 *
 * <p>The palette used to live in the {@code .docx} as {@code ${color …}} marker paragraphs keyed on
 * the displayed severity label. It now lives on the report template, keyed on the severity enum, so
 * renaming Critical to Sev-1 cannot silently return every finding to black.
 */
class DocxUtilsPaletteTest {

    private static final String W =
            "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    private ReportPalette palette() {
        ReportPalette palette = ReportPalette.defaults();
        palette.getLikelihood().put("High", ReportPalette.ColourPair.of("AA0000", "FFDDDD"));
        palette.getImpact().put("Low", ReportPalette.ColourPair.of("0000AA", "DDDDFF"));
        return palette;
    }

    /**
     * Critical as white-on-red, which is how the two halves of a pair are normally set up and makes
     * which one was used unmistakable in an assertion: {@code FFFFFF} is the on-colour text,
     * {@code C00000} is the colour itself.
     */
    private ReportPalette whiteOnRed() {
        ReportPalette palette = palette();
        palette.getSeverity().put("CRITICAL", ReportPalette.ColourPair.of("FFFFFF", "C00000"));
        return palette;
    }

    private ReportData.ReportVulnerability critical() {
        return ReportData.ReportVulnerability.builder()
                .name("SQL Injection")
                .severity("Sev-1")            // renamed label; nothing may key on it
                .severityKey("CRITICAL")
                .likelihood("High")
                .impact("Low")
                .build();
    }

    /** A findings table whose single loop row is the supplied cell XML. */
    private String renderTable(String cellXml, ReportPalette palette,
                               ReportData.ReportVulnerability vuln) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();

        main.addObject(XmlUtils.unmarshalString(
                "<w:tbl " + W + ">"
              + "<w:tr><w:tc><w:p><w:r><w:t>${vulnTable}</w:t></w:r></w:p></w:tc></w:tr>"
              + "<w:tr>" + cellXml + "</w:tr>"
              + "</w:tbl>"));

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .reportPalette(palette)
                .vulnerabilities(new java.util.ArrayList<>(List.of(vuln)))
                .build());
        WordprocessingMLPackage result = utils.generateDocx("", null);
        return XmlUtils.marshaltoString(result.getMainDocumentPart().getJaxbElement(), true, false);
    }

    /** A cell whose fill is painted with {@code fillHex} and whose text is painted {@code textHex}. */
    private String shadedCell(String fillHex, String textHex, String body) {
        return "<w:tc>"
             + "<w:tcPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + fillHex + "\"/></w:tcPr>"
             + "<w:p><w:r><w:rPr><w:color w:val=\"" + textHex + "\"/></w:rPr>"
             + "<w:t>" + body + "</w:t></w:r></w:p>"
             + "</w:tc>";
    }

    // ── the pair ─────────────────────────────────────────────────────────────

    @Test
    void aLightFillAndDarkTextResolveToTheSameDimension() throws Exception {
        String xml = renderTable(
                shadedCell("FAC701", "1A0701", "${loop}${severity}"), palette(), critical());

        assertThat(xml).contains("w:fill=\"FCE1E1\"");
        assertThat(xml).contains("w:val=\"B91C1C\"");
        assertThat(xml).doesNotContain("FAC701").doesNotContain("1A0701");
    }

    /**
     * Neither member is bound to a role — the attribute already says fill or font. Painting them
     * the other way round has to work, or the pair becomes one more thing to get wrong.
     */
    @Test
    void thePairWorksPaintedTheOtherWayRound() throws Exception {
        String xml = renderTable(
                shadedCell("1A0701", "FAC701", "${loop}${severity}"), palette(), critical());

        assertThat(xml).contains("w:fill=\"FCE1E1\"");
        assertThat(xml).contains("w:val=\"B91C1C\"");
    }

    @Test
    void likelihoodAndImpactHaveTheirOwnSlots() throws Exception {
        String likelihood = renderTable(
                shadedCell("FAC702", "1A0702", "${loop}${likelihood}"), palette(), critical());
        assertThat(likelihood).contains("w:fill=\"FFDDDD\"").contains("w:val=\"AA0000\"");

        String impact = renderTable(
                shadedCell("FAC703", "1A0703", "${loop}${impact}"), palette(), critical());
        assertThat(impact).contains("w:fill=\"DDDDFF\"").contains("w:val=\"0000AA\"");
    }

    @Test
    void aCustomFieldResolvesOnItsAllocatedSlot() throws Exception {
        ReportPalette palette = palette();
        int slot = palette.allocateSlot("risk_rating");
        palette.getCustomFields().get("risk_rating").getValues()
                .put("Elevated", ReportPalette.ColourPair.of("7C2D12", "FFEDD5"));

        ReportData.ReportVulnerability vuln = ReportData.ReportVulnerability.builder()
                .name("SQL Injection").severityKey("CRITICAL")
                .fieldValues(new java.util.HashMap<>(Map.of("risk_rating", "Elevated")))
                .build();

        String xml = renderTable(
                shadedCell(ColourSentinels.light(slot), ColourSentinels.dark(slot), "${loop}x"),
                palette, vuln);

        assertThat(xml).contains("w:fill=\"FFEDD5\"").contains("w:val=\"7C2D12\"");
    }

    // ── the bug the enum keying fixes ────────────────────────────────────────

    /**
     * The finding's displayed severity is "Sev-1". Under the old palette, whose keys were the
     * labels shown in the report, that matched nothing and the cell rendered black on white.
     */
    @Test
    void aRenamedSeverityStillGetsItsColour() throws Exception {
        String xml = renderTable(
                shadedCell("FAC701", "1A0701", "${loop}${severity}"), palette(), critical());

        assertThat(xml).contains("Sev-1");
        assertThat(xml).contains("w:fill=\"FCE1E1\"");
    }

    // ── fallbacks ────────────────────────────────────────────────────────────

    /**
     * The reserved amber must never reach a client. A sentinel for a value nobody configured
     * resolves to an ordinary colour rather than being left alone.
     */
    @Test
    void anUnconfiguredValueLeavesNoSentinelBehind() throws Exception {
        ReportData.ReportVulnerability odd = ReportData.ReportVulnerability.builder()
                .name("Odd").severityKey("CRITICAL").likelihood("Improbable").build();

        String xml = renderTable(
                shadedCell("FAC702", "1A0702", "${loop}${likelihood}"), palette(), odd);

        assertThat(xml).doesNotContain("FAC702").doesNotContain("1A0702");
        assertThat(xml).contains("w:fill=\"FFFFFF\"").contains("w:val=\"000000\"");
    }

    @Test
    void aTemplateWithNoPaletteStillLeavesNoSentinelBehind() throws Exception {
        String xml = renderTable(
                shadedCell("FAC701", "1A0701", "${loop}${severity}"), null, critical());

        assertThat(xml).doesNotContain("FAC701").doesNotContain("1A0701");
    }

    // ── the positions token-driven colouring could not have reached ──────────

    /**
     * A border carries its colour on a {@code w:color} attribute with no text near it, which is
     * one of the two reasons the colour stays painted rather than inferred from the token.
     */
    @Test
    void aSentinelPaintedOnATableBorderResolves() throws Exception {
        String cell =
                "<w:tc><w:tcPr><w:tcBorders>"
              + "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"FAC701\"/>"
              + "</w:tcBorders></w:tcPr>"
              + "<w:p><w:r><w:t>${loop}${severity}</w:t></w:r></w:p></w:tc>";

        String xml = renderTable(cell, whiteOnRed(), critical());

        // A border is the coloured thing, not text sitting on a colour, so it takes the colour.
        assertThat(xml).contains("w:color=\"C00000\"").doesNotContain("FAC701");
    }

    /**
     * The other reason: a shaded run in a findings block has no table cell to belong to.
     */
    @Test
    void aShadedRunOutsideAnyTableResolves() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("${fiBegin}");
        main.addObject(XmlUtils.unmarshalString(
                "<w:p " + W + "><w:r>"
              + "<w:rPr><w:color w:val=\"1A0701\"/>"
              + "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"FAC701\"/></w:rPr>"
              + "<w:t>${severity}</w:t></w:r></w:p>"));
        main.addParagraphOfText("${fiEnd}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .reportPalette(palette())
                .vulnerabilities(new java.util.ArrayList<>(List.of(critical())))
                .build());
        String xml = XmlUtils.marshaltoString(
                utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);

        assertThat(xml).contains("w:fill=\"FCE1E1\"").contains("w:val=\"B91C1C\"");
        assertThat(xml).doesNotContain("FAC701").doesNotContain("1A0701");
    }

    // ── the w:val narrowing ──────────────────────────────────────────────────

    /**
     * {@code w:val} is the attribute for style ids, font sizes and justification as well as run
     * colour. Only the one belonging to a {@code w:color} is a colour; a style named after the
     * sentinel by coincidence must survive untouched.
     */
    @Test
    void aStyleIdThatCollidesWithASentinelIsLeftAlone() throws Exception {
        String cell =
                "<w:tc><w:p><w:pPr><w:pStyle w:val=\"FAC701\"/></w:pPr>"
              + "<w:r><w:rPr><w:color w:val=\"FAC701\"/></w:rPr>"
              + "<w:t>${loop}${severity}</w:t></w:r></w:p></w:tc>";

        String xml = renderTable(cell, whiteOnRed(), critical());

        assertThat(xml).contains("<w:pStyle w:val=\"FAC701\"/>");
        // No fill on this cell, so the text is the coloured thing: red, not the on-red white.
        assertThat(xml).contains("<w:color w:val=\"C00000\"/>");
    }

    // ── the retired marker paragraphs ────────────────────────────────────────

    /**
     * {@code ${color …}} is no longer read. It must still be recognised and removed, or an old
     * template would print its own configuration across a report delivered to a client.
     */
    @Test
    void anOldColourMarkerIsStrippedRatherThanPrinted() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addObject(XmlUtils.unmarshalString(
                "<w:tbl " + W + ">"
              + "<w:tr><w:tc><w:p><w:r><w:t>${vulnTable}</w:t></w:r></w:p></w:tc>"
              + "<w:tc><w:p><w:r><w:t>${color Critical=C00000,High=FFC000}</w:t></w:r></w:p></w:tc></w:tr>"
              + "<w:tr><w:tc><w:p><w:r><w:t>${loop}${vulnName}</w:t></w:r></w:p></w:tc></w:tr>"
              + "</w:tbl>"));

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .reportPalette(palette())
                .vulnerabilities(new java.util.ArrayList<>(List.of(critical())))
                .build());
        String xml = XmlUtils.marshaltoString(
                utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);

        assertThat(xml).doesNotContain("${color").doesNotContain("C00000");
        assertThat(xml).contains("SQL Injection");
    }

    /**
     * In a findings block the marker sits inside the repeated region, so failing to strip it would
     * print the configuration once per finding rather than once.
     */
    @Test
    void anOldMarkerInAFindingsBlockIsNotRepeatedPerFinding() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("${fiBegin}");
        main.addParagraphOfText("${fill Critical=FFEEEE}");
        main.addParagraphOfText("${vulnName}");
        main.addParagraphOfText("${fiEnd}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .reportPalette(palette())
                .vulnerabilities(new java.util.ArrayList<>(List.of(critical(), critical())))
                .build());
        String xml = XmlUtils.marshaltoString(
                utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);

        assertThat(xml).doesNotContain("${fill").doesNotContain("FFEEEE");
        assertThat(xml).contains("SQL Injection");
    }

    // ── the table survives ───────────────────────────────────────────────────

    @Test
    void ordinaryColoursInTheTemplateAreUntouched() throws Exception {
        String xml = renderTable(
                shadedCell("D9E2F3", "202020", "${loop}${severity}"), palette(), critical());

        assertThat(xml).contains("w:fill=\"D9E2F3\"").contains("w:val=\"202020\"");
    }

    // ── which half of the pair applies depends on what the text sits on ──────

    /**
     * Three places a sentinel-coloured run can live, and they do not want the same colour.
     *
     * <p>Setting Critical to white-on-red means white is only ever right <em>on</em> the red. Used
     * anywhere else it is white text on a white page — invisible, and invisible in a way nobody
     * notices until a client opens the report. So the on-colour half applies only when the run
     * actually sits on a sentinel fill; otherwise the run takes the colour itself.
     */
    @Test
    void textOnAFilledCellTakesTheOnColourHalf() throws Exception {
        String xml = renderTable(
                shadedCell("FAC701", "1A0701", "${loop}${severity}"), whiteOnRed(), critical());

        assertThat(xml).contains("w:fill=\"C00000\"");
        assertThat(xml).contains("w:val=\"FFFFFF\"");
    }

    @Test
    void textInACellWithNoFillTakesTheColourItself() throws Exception {
        String cell = "<w:tc><w:p><w:r><w:rPr><w:color w:val=\"1A0701\"/></w:rPr>"
                    + "<w:t>${loop}${severity}</w:t></w:r></w:p></w:tc>";

        String xml = renderTable(cell, whiteOnRed(), critical());

        assertThat(xml).contains("<w:color w:val=\"C00000\"/>").doesNotContain("FFFFFF");
    }

    /**
     * An ordinary coloured paragraph in a findings block — a heading naming the severity, say.
     * There is no cell at all, so nothing is contrasting against anything.
     */
    @Test
    void textOutsideAnyTableTakesTheColourItself() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("${fiBegin}");
        main.addObject(XmlUtils.unmarshalString(
                "<w:p " + W + "><w:r><w:rPr><w:color w:val=\"FAC701\"/></w:rPr>"
              + "<w:t>${severity}</w:t></w:r></w:p>"));
        main.addParagraphOfText("${fiEnd}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .reportPalette(whiteOnRed())
                .vulnerabilities(new java.util.ArrayList<>(List.of(critical())))
                .build());
        String xml = XmlUtils.marshaltoString(
                utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);

        assertThat(xml).contains("<w:color w:val=\"C00000\"/>").doesNotContain("FFFFFF");
    }

    /**
     * A run carrying its own shading is on a colour just as much as one in a filled cell, so it
     * gets the on-colour half even though there is no table involved.
     */
    @Test
    void aShadedRunOutsideATableTakesTheOnColourHalf() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("${fiBegin}");
        main.addObject(XmlUtils.unmarshalString(
                "<w:p " + W + "><w:r><w:rPr><w:color w:val=\"1A0701\"/>"
              + "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"FAC701\"/></w:rPr>"
              + "<w:t>${severity}</w:t></w:r></w:p>"));
        main.addParagraphOfText("${fiEnd}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .reportPalette(whiteOnRed())
                .vulnerabilities(new java.util.ArrayList<>(List.of(critical())))
                .build());
        String xml = XmlUtils.marshaltoString(
                utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);

        assertThat(xml).contains("w:fill=\"C00000\"").contains("<w:color w:val=\"FFFFFF\"/>");
    }

    /** A shaded paragraph is a background too. */
    @Test
    void textOnAShadedParagraphTakesTheOnColourHalf() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("${fiBegin}");
        main.addObject(XmlUtils.unmarshalString(
                "<w:p " + W + "><w:pPr>"
              + "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"FAC701\"/></w:pPr>"
              + "<w:r><w:rPr><w:color w:val=\"1A0701\"/></w:rPr>"
              + "<w:t>${severity}</w:t></w:r></w:p>"));
        main.addParagraphOfText("${fiEnd}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .reportPalette(whiteOnRed())
                .vulnerabilities(new java.util.ArrayList<>(List.of(critical())))
                .build());
        String xml = XmlUtils.marshaltoString(
                utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);

        assertThat(xml).contains("w:fill=\"C00000\"").contains("<w:color w:val=\"FFFFFF\"/>");
    }

    /**
     * One row, two cells, only one of them filled. The rule is per-run, not per-row, so the two
     * must come out differently.
     */
    @Test
    void twoCellsInOneRowResolveIndependently() throws Exception {
        String cells =
                shadedCell("FAC701", "1A0701", "${loop}${severity}")
              + "<w:tc><w:p><w:r><w:rPr><w:color w:val=\"1A0701\"/></w:rPr>"
              + "<w:t>${vulnName}</w:t></w:r></w:p></w:tc>";

        String xml = renderTable(cells, whiteOnRed(), critical());

        assertThat(xml).contains("w:fill=\"C00000\"");      // the filled cell
        assertThat(xml).contains("w:val=\"FFFFFF\"");        // its text, on the red
        assertThat(xml).contains("<w:color w:val=\"C00000\"/>");  // the unfilled cell's text
    }

    /** A cell filled with an ordinary colour is not a sentinel fill, so its text is not "on" one. */
    @Test
    void aCellFilledWithAnOrdinaryColourDoesNotCountAsAColouredBackground() throws Exception {
        String xml = renderTable(
                shadedCell("D9E2F3", "1A0701", "${loop}${severity}"), whiteOnRed(), critical());

        assertThat(xml).contains("w:fill=\"D9E2F3\"");
        assertThat(xml).contains("<w:color w:val=\"C00000\"/>");
    }

    // ── list numbers and bullets ─────────────────────────────────────────────

    /**
     * A numbered or bulleted title inside a cell. The marker — "1." or the bullet glyph — is not in
     * any run: Word colours it from the <em>paragraph mark's</em> run properties, {@code w:pPr/w:rPr}.
     * A walk that only visits {@code w:r} leaves the sentinel sitting on the marker, so the number
     * renders in the reserved amber while the text beside it comes out correctly. That is worse
     * than either failing outright or working, because it looks like a font bug.
     */
    private String numberedCell(String fillHex, String markerHex, String runHex) {
        return "<w:tc>"
             + (fillHex == null ? ""
                 : "<w:tcPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + fillHex + "\"/></w:tcPr>")
             + "<w:p><w:pPr>"
             + "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>"
             + "<w:rPr><w:color w:val=\"" + markerHex + "\"/></w:rPr>"
             + "</w:pPr>"
             + "<w:r><w:rPr><w:color w:val=\"" + runHex + "\"/></w:rPr>"
             + "<w:t>${loop}${severity}</w:t></w:r></w:p></w:tc>";
    }

    @Test
    void aListMarkerInAFilledCellIsColouredLikeItsText() throws Exception {
        String xml = renderTable(
                numberedCell("FAC701", "1A0701", "1A0701"), whiteOnRed(), critical());

        assertThat(xml).doesNotContain("1A0701").doesNotContain("FAC701");
        // Both the paragraph mark and the run: on the red, so both white.
        assertThat(countOf(xml, "<w:color w:val=\"FFFFFF\"/>")).isEqualTo(2);
    }

    @Test
    void aListMarkerInAnUnfilledCellTakesTheColourItself() throws Exception {
        String xml = renderTable(
                numberedCell(null, "1A0701", "1A0701"), whiteOnRed(), critical());

        assertThat(xml).doesNotContain("1A0701");
        assertThat(countOf(xml, "<w:color w:val=\"C00000\"/>")).isEqualTo(2);
    }

    /** The marker can be painted on its own, with the text beside it left alone. */
    @Test
    void aListMarkerResolvesEvenWhenTheTextBesideItIsNotPainted() throws Exception {
        String xml = renderTable(
                numberedCell("FAC701", "1A0701", "202020"), whiteOnRed(), critical());

        assertThat(xml).doesNotContain("1A0701");
        assertThat(xml).contains("<w:color w:val=\"FFFFFF\"/>");
        assertThat(xml).contains("<w:color w:val=\"202020\"/>");
    }

    /** Outside a table, same rule: nothing to contrast against, so the colour itself. */
    @Test
    void aListMarkerOutsideATableTakesTheColourItself() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText("${fiBegin}");
        main.addObject(XmlUtils.unmarshalString(
                "<w:p " + W + "><w:pPr>"
              + "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>"
              + "<w:rPr><w:color w:val=\"FAC701\"/></w:rPr></w:pPr>"
              + "<w:r><w:t>${vulnName}</w:t></w:r></w:p>"));
        main.addParagraphOfText("${fiEnd}");

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .reportPalette(whiteOnRed())
                .vulnerabilities(new java.util.ArrayList<>(List.of(critical())))
                .build());
        String xml = XmlUtils.marshaltoString(
                utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);

        assertThat(xml).doesNotContain("FAC701").contains("<w:color w:val=\"C00000\"/>");
    }

    private int countOf(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    // ── unconfigured values used as text ─────────────────────────────────────

    /**
     * Text outside a filled cell takes the <em>colour</em> half of the pair. When nothing is set for
     * the value, that half falls back to the fill default — white — which is right for a cell and
     * invisible for a heading on a white page. As text it must fall back to black instead.
     */
    @Test
    void unconfiguredTextOutsideAFilledCellFallsBackToBlackNotWhite() throws Exception {
        ReportData.ReportVulnerability odd = ReportData.ReportVulnerability.builder()
                .name("Odd").severityKey("CRITICAL").likelihood("Improbable").build();
        String cell = "<w:tc><w:p><w:r><w:rPr><w:color w:val=\"1A0702\"/></w:rPr>"
                    + "<w:t>${loop}${likelihood}</w:t></w:r></w:p></w:tc>";

        String xml = renderTable(cell, palette(), odd);

        assertThat(xml).contains("<w:color w:val=\"000000\"/>").doesNotContain("FFFFFF");
    }

    /** A border with nothing configured is a line on the page, so black rather than invisible. */
    @Test
    void anUnconfiguredBorderFallsBackToBlack() throws Exception {
        ReportData.ReportVulnerability odd = ReportData.ReportVulnerability.builder()
                .name("Odd").severityKey("CRITICAL").likelihood("Improbable").build();
        String cell = "<w:tc><w:tcPr><w:tcBorders>"
                    + "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"FAC702\"/>"
                    + "</w:tcBorders></w:tcPr>"
                    + "<w:p><w:r><w:t>${loop}${likelihood}</w:t></w:r></w:p></w:tc>";

        String xml = renderTable(cell, palette(), odd);

        assertThat(xml).contains("w:color=\"000000\"").doesNotContain("FAC702");
    }

    /** A configured colour is still used as text exactly as set. */
    @Test
    void aConfiguredColourIsStillUsedAsText() throws Exception {
        String cell = "<w:tc><w:p><w:r><w:rPr><w:color w:val=\"1A0701\"/></w:rPr>"
                    + "<w:t>${loop}${severity}</w:t></w:r></w:p></w:tc>";

        assertThat(renderTable(cell, whiteOnRed(), critical())).contains("<w:color w:val=\"C00000\"/>");
    }
}
