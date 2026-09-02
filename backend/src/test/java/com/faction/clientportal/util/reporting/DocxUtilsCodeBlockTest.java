package com.faction.clientportal.util.reporting;

import com.faction.clientportal.service.ReportTemplateService;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Line-numbered code blocks (```start=200 in the editor) reach the DOCX as a two-column
 * table — a gutter of line numbers beside the code — styled to read as a code panel.
 *
 * <p>The look is carried entirely by classes, so a report template can retheme any part
 * of it. The defaults live in DocxUtils' own style block, emitted ahead of the template
 * CSS: same-specificity rules in the template therefore win, which is what makes the
 * panel themeable at all.
 */
class DocxUtilsCodeBlockTest {

    /** The panel's padding: a short shaded row, top and bottom. */
    private static final String PAD_ROW =
            "<tr class=\"code-block-pad\"><td class=\"code-block-gutter\">&nbsp;</td>"
            + "<td class=\"code-block-line\">&nbsp;</td></tr>";

    /** What the editor emits, indentation included (as non-breaking spaces). */
    private static final String CODE_BLOCK =
            "<table class=\"code-block\"><tbody>" + PAD_ROW
            + "<tr><td class=\"code-block-gutter\">200</td>"
            + "<td class=\"code-block-line\">def login(user, pw):</td></tr>"
            + "<tr><td class=\"code-block-gutter\">201</td>"
            + "<td class=\"code-block-line\">\u00a0\u00a0\u00a0\u00a0return auth(user, pw)</td></tr>"
            + PAD_ROW + "</tbody></table>";

    private static String sanitize(String html) throws Exception {
        Method sanitize = DocxUtils.class.getDeclaredMethod("sanitizeForXhtml", String.class);
        sanitize.setAccessible(true);
        return (String) sanitize.invoke(null, html);
    }

    @SuppressWarnings("unchecked")
    private static String convert(String html, String customCss) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());
        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils, html, customCss, "");
        return converted.stream().map(org.docx4j.XmlUtils::marshaltoString).reduce("", String::concat);
    }

    /** Just the table, so the surrounding paragraphs don't muddy the assertions. */
    private static String tableXml(String xml) {
        return xml.substring(xml.indexOf("<w:tbl"), xml.indexOf("</w:tbl>"));
    }

    /** The stylesheet every report actually carries, read from the service that seeds it. */
    private static String defaultTemplateCss() throws Exception {
        Field f = ReportTemplateService.class.getDeclaredField("DEFAULT_TEMPLATE_CSS");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static List<String> fontsIn(String xml) {
        return Pattern.compile("w:ascii=\"([^\"]+)\"").matcher(xml)
                .results().map(r -> r.group(1)).distinct().toList();
    }

    /** The classes are the whole styling contract — they have to survive the sanitizer. */
    @Test
    void sanitizerKeepsTheCodeBlockClasses() throws Exception {
        String sanitized = sanitize(CODE_BLOCK);

        assertThat(sanitized).contains("code-block");
        assertThat(sanitized).contains("code-block-gutter");
        assertThat(sanitized).contains("code-block-line");
        assertThat(sanitized).contains("code-block-pad");
    }

    @Test
    void rendersAsADarkPanelWithNoTemplateCss() throws Exception {
        String xml = convert(CODE_BLOCK, "").toLowerCase();

        assertThat(xml).contains("282a36");   // Dracula panel fill on every cell
        assertThat(xml).contains("f8f8f2");   // code text
        assertThat(xml).contains("6272a4");   // dimmed gutter
        assertThat(xml).contains("200").contains("def login(user, pw):");
    }

    /** Indentation rides in as non-breaking spaces — the importer would eat plain runs. */
    @Test
    void keepsIndentation() throws Exception {
        assertThat(convert(CODE_BLOCK, "")).contains("\u00a0\u00a0\u00a0\u00a0return auth");
    }

    /**
     * The stylesheet every report actually carries. It sets
     * {@code td,th{font-family:Arial}}, and a rule aimed at the cell beats a font merely
     * inherited from the table — so the code printed proportional until the built-in
     * default named the cells too.
     */
    @Test
    void staysMonospacedUnderTheDefaultTemplateCss() throws Exception {
        String table = tableXml(convert(CODE_BLOCK, defaultTemplateCss()));

        assertThat(fontsIn(table)).containsExactly("Consolas");
        assertThat(table).contains("w:before=\"0\"").contains("w:after=\"0\"");
    }

    /**
     * Word draws a hairline of unpainted white where a cell margin meets a fill — the
     * pixels in the reported screenshot sat exactly on that boundary, and real reports
     * came back clean once shaded cells gave their vertical margins up. The panel's own
     * top and bottom margin is a spacer row instead, which has no margin to seam.
     */
    @Test
    void shadedCellsCarryNoVerticalMargin() throws Exception {
        String table = tableXml(convert(CODE_BLOCK, defaultTemplateCss()));

        assertThat(table).doesNotContain("<w:top w:w=\"15\"").doesNotContain("<w:bottom w:w=\"15\"");
        // horizontal margins stay — the seam only ever appears on the horizontal edges
        assertThat(table).contains("<w:left w:w=\"120\" w:type=\"dxa\"/>");
    }

    /**
     * A plain fence — no {@code start=} — is the same panel with the gutter column left
     * out. One shape for both means one set of classes to theme, in the app and in the
     * report, rather than a code block that looks like a panel only when numbered.
     */
    @Test
    void plainCodeBlockIsTheSamePanelWithoutAGutter() throws Exception {
        String plain = "<table class=\"code-block\"><tbody>"
                + "<tr class=\"code-block-pad\"><td class=\"code-block-line\">&nbsp;</td></tr>"
                + "<tr><td class=\"code-block-line\">no line numbers</td></tr>"
                + "<tr class=\"code-block-pad\"><td class=\"code-block-line\">&nbsp;</td></tr>"
                + "</tbody></table>";

        String table = tableXml(convert(plain, defaultTemplateCss()));

        assertThat(table.toLowerCase()).contains("282a36").contains("f8f8f2");
        assertThat(fontsIn(table)).containsExactly("Consolas");
        assertThat(table).doesNotContain("6272a4");            // no gutter to dim
        assertThat(table).doesNotContain("<w:top w:w=\"15\"");  // and still no seam
    }

    /**
     * Shift+Enter in the editor wraps a line without giving it a new number, which is a
     * <br> inside the one cell. It has to reach the report as a break in the same
     * numbered line rather than being flattened away.
     */
    @Test
    void softWrappedLineStaysInOneNumberedRow() throws Exception {
        String wrapped = "<table class=\"code-block\"><tbody>"
                + "<tr><td class=\"code-block-gutter\">203</td>"
                + "<td class=\"code-block-line\">third<br/>line</td></tr>"
                + "</tbody></table>";

        String table = tableXml(convert(wrapped, defaultTemplateCss()));

        assertThat(table).contains("<w:br/>");
        assertThat(table).contains("third").contains("line");
        // one row, one number — the wrap did not become a line of its own
        assertThat(table.split("<w:tr>").length - 1).isEqualTo(1);
    }

    /**
     * A long line used to run off the edge of the paper. The importer measures columns
     * against the CSS engine's viewport, not the page: a full-width table came out 13954
     * twips against a printable 9027, so a third of it was laid out where no printer or
     * reader would ever see it. Over-wide tables are fitted to the page, and the overhang
     * comes off the widest column so a gutter of line numbers is not squeezed until
     * "200" wraps to "20" / "0".
     */
    @Test
    void aTableWiderThanThePageIsFittedToIt() throws Exception {
        String longLine = "curl -s https://target.example.com/api/v1/accounts/1234567890/"
                + "transactions?filter=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9&amp;limit=100"
                + " | jq '.data[] | select(.amount &gt; 1000)'";
        String wide = "<table class=\"code-block\"><tbody>"
                + "<tr><td class=\"code-block-gutter\">200</td>"
                + "<td class=\"code-block-line\">" + longLine + "</td></tr></tbody></table>";

        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());
        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils, wide, defaultTemplateCss(), "");
        String xml = converted.stream().map(org.docx4j.XmlUtils::marshaltoString)
                .reduce("", String::concat);

        List<Integer> cols = Pattern.compile("<w:gridCol w:w=\"(\\d+)\"/>").matcher(xml)
                .results().map(r -> Integer.parseInt(r.group(1))).toList();
        int printable = pkg.getDocumentModel().getSections().get(0)
                .getPageDimensions().getWritableWidthTwips();

        assertThat(cols.stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(printable);
        // the gutter kept its measured width; the code column gave up the overhang
        assertThat(cols.get(0)).isEqualTo(762);
    }

    /**
     * Every part of the panel stays the template's to change — this is the contract the
     * Report Designer's CSS box is sold on, so it is pinned here rather than assumed. The
     * overrides run after the stock template CSS, exactly where a user's edits land.
     */
    @Test
    void templateCssOverridesEveryPartOfThePanel() throws Exception {
        String overrides = """
                .code-block td { background-color: #fdf6e3; color: #073642; font-family: 'Courier New'; }
                .code-block td.code-block-gutter { color: #93a1a1; }
                .code-block td.code-block-line { padding-left: 2px; }
                """;
        String table = tableXml(convert(CODE_BLOCK, defaultTemplateCss() + overrides)).toLowerCase();

        assertThat(table).contains("fdf6e3").doesNotContain("282a36");   // panel fill
        assertThat(table).contains("073642").doesNotContain("f8f8f2");   // code text
        assertThat(table).contains("93a1a1").doesNotContain("6272a4");   // gutter
        assertThat(fontsIn(table)).containsExactly("courier new");       // font
        assertThat(table).contains("<w:left w:w=\"30\" w:type=\"dxa\"/>"); // padding
    }
}
