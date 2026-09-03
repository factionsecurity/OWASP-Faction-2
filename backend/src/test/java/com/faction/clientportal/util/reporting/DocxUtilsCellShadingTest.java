package com.faction.clientportal.util.reporting;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cell shading through the WYSIWYG → DOCX pipeline.
 *
 * <p>A fill set from the table editor's context menu goes through the browser's CSSOM,
 * which serialises it as {@code rgb(37, 99, 235)}; one pasted from Word arrives as a hex
 * string. Both have to reach the report. They did not: the sanitizer's style pattern
 * excluded parentheses and has to match the attribute in full, so the {@code rgb()} form
 * took the whole style attribute down with it and the cell rendered in the default colour.
 */
class DocxUtilsCellShadingTest {

    private static String sanitize(String html) throws Exception {
        Method sanitize = DocxUtils.class.getDeclaredMethod("sanitizeForXhtml", String.class);
        sanitize.setAccessible(true);
        return (String) sanitize.invoke(null, html);
    }

    @Test
    void keepsCssomSerialisedCellShading() throws Exception {
        String sanitized = sanitize(
                "<table class=\"rte-table\"><tbody><tr>"
                + "<td style=\"background-color: rgb(37, 99, 235); color: rgb(255, 255, 255);\">Critical</td>"
                + "</tr></tbody></table>");

        assertThat(sanitized).contains("background-color: rgb(37, 99, 235)");
        assertThat(sanitized).contains("color: rgb(255, 255, 255)");
    }

    @Test
    void keepsHexCellShadingFromAPaste() throws Exception {
        String sanitized = sanitize(
                "<table><tbody><tr>"
                + "<td style=\"background-color: #0F4761; color: #ffffff\">Step</td>"
                + "</tr></tbody></table>");

        assertThat(sanitized).contains("background-color: #0F4761");
    }

    @Test
    void keepsColourFunctionsOnInlineSpans() throws Exception {
        String sanitized = sanitize(
                "<p><span style=\"color: rgba(220, 38, 38, 0.8)\">high</span>"
                + "<span style=\"background-color: hsl(48 96% 53%)\">flagged</span></p>");

        assertThat(sanitized).contains("rgba(220, 38, 38, 0.8)");
        assertThat(sanitized).contains("hsl(48 96% 53%)");
    }

    /** The parenthesis exception is for colour functions only — nothing else gets in. */
    @Test
    void stillRejectsNonColourFunctionValues() throws Exception {
        String sanitized = sanitize(
                "<p style=\"background-color: url('javascript:alert(1)')\">x</p>"
                + "<div style=\"width: expression(alert(1))\">y</div>"
                + "<span style=\"color: rgb(expression(alert(1)))\">z</span>");

        assertThat(sanitized).doesNotContain("javascript:");
        assertThat(sanitized).doesNotContain("expression(");
        // The text survives; only the offending style attributes are dropped
        assertThat(sanitized).contains("x").contains("y").contains("z");
    }

    /**
     * The table menu's cell-border colours, which ride in as the `border` shorthand. The
     * cell's own w:tcBorders outrank whatever the template's table style would draw, so
     * an author's choice is what shows.
     */
    @Test
    @SuppressWarnings("unchecked")
    void xhtmlImportKeepsCellBorderColour() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());

        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils,
                "<table class=\"rte-table\"><tbody><tr>"
                + "<td style=\"border: 1px solid rgb(255, 121, 198);\">outlined</td>"
                + "<td style=\"border: none;\">bare</td>"
                + "</tr></tbody></table>", "", "");

        String xml = converted.stream()
                .map(org.docx4j.XmlUtils::marshaltoString)
                .reduce("", String::concat)
                .toLowerCase();

        assertThat(xml).contains("ff79c6");
        // and the cleared cell says "none" rather than leaving the table style to decide
        assertThat(xml).contains("w:val=\"none\"");
    }

    /** Shading has to survive the XHTML import too, not just the sanitizer. */
    @Test
    @SuppressWarnings("unchecked")
    void xhtmlImportKeepsShadedCell() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());

        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils,
                "<table class=\"rte-table\"><tbody><tr>"
                + "<td style=\"background-color: rgb(37, 99, 235); color: rgb(255, 255, 255);\">Critical</td>"
                + "</tr></tbody></table>", "", "");

        // JAXB objects have no meaningful toString, so marshal them to inspect the XML
        String xml = converted.stream()
                .map(org.docx4j.XmlUtils::marshaltoString)
                .reduce("", String::concat);

        // docx4j maps the CSS fill onto w:shd, normalising the colour to hex
        assertThat(xml.toLowerCase()).contains("2563eb");
    }

    /**
     * Classes typed into the table context menu are the hook report CSS styles against,
     * so they have to reach the importer intact and the template's class selectors have
     * to bind to them.
     */
    @Test
    @SuppressWarnings("unchecked")
    void xhtmlImportAppliesTemplateCssByTableClass() throws Exception {
        String html = "<table class=\"rte-table findings-summary\"><tbody><tr>"
                + "<td>Total</td></tr></tbody></table>";

        assertThat(sanitize(html)).contains("findings-summary");

        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());

        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils, html,
                "table.findings-summary td { background-color: #16a34a; }", "");

        String xml = converted.stream()
                .map(org.docx4j.XmlUtils::marshaltoString)
                .reduce("", String::concat);

        assertThat(xml.toLowerCase()).contains("16a34a");
    }

    /**
     * A shaded cell in Word showed a hairline of white along its bottom edge, which went
     * away as soon as the table was dragged to a new size. The cause was the row height
     * the importer measured with its own CSS font metrics and wrote into the file: Word
     * lays the row out from Word's metrics but honours the stored minimum, and paints a
     * gap where the two disagree. Rows have to size to their content instead — including
     * rows of a table nested in a cell.
     */
    @Test
    @SuppressWarnings("unchecked")
    void importedRowsCarryNoMeasuredGeometry() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());

        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils,
                "<table class=\"rte-table\"><tbody><tr>"
                + "<td style=\"background-color: rgb(15, 34, 51); color: rgb(255, 255, 255);\">"
                + "1. Anonymous LDAP exposure"
                + "<table><tbody><tr><td>nested</td></tr></tbody></table></td>"
                + "</tr></tbody></table>", "", "");

        String xml = converted.stream()
                .map(org.docx4j.XmlUtils::marshaltoString)
                .reduce("", String::concat);

        // neither the outer row nor the nested table's row keeps a measured height
        assertThat(xml).doesNotContain("trHeight");
        // the zero/auto spacing goes — carrying it puts Word in the separated-cell model,
        // where the gaps between fills are page background — but the nested table asked
        // for real spacing, so that one stays
        assertThat(xml).doesNotContain("w:tblCellSpacing w:w=\"0\"");
        assertThat(xml).contains("w:tblCellSpacing w:w=\"20\" w:type=\"dxa\"");
        // the fill itself is untouched
        assertThat(xml.toLowerCase()).contains("0f2233");
    }
}
