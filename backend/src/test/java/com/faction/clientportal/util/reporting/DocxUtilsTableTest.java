package com.faction.clientportal.util.reporting;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Tbl;
import org.junit.jupiter.api.Test;

import jakarta.xml.bind.JAXBElement;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the WYSIWYG → DOCX table pipeline: OWASP sanitize, then
 * docx4j XHTML import. A <table> in rich-text content must come out as a
 * w:tbl in the generated document.
 */
class DocxUtilsTableTest {

    // Representative of what RichTextEditor stores (rte-table class, thead/tbody)
    private static final String TABLE_HTML =
            "<table class=\"rte-table\">"
            + "<thead><tr><th>Header 1</th><th>Header 2</th></tr></thead>"
            + "<tbody><tr><td>cell 1</td><td>cell 2</td></tr>"
            + "<tr><td>cell 3</td><td>cell 4</td></tr></tbody></table>"
            + "<p>after the table</p>";

    @Test
    void sanitizerKeepsTables() throws Exception {
        Method sanitize = DocxUtils.class.getDeclaredMethod("sanitizeForXhtml", String.class);
        sanitize.setAccessible(true);
        String sanitized = (String) sanitize.invoke(null, TABLE_HTML);

        assertThat(sanitized).contains("<table");
        assertThat(sanitized).contains("<td>cell 1</td>");
    }

    @Test
    @SuppressWarnings("unchecked")
    void xhtmlImportProducesTable() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        ReportData data = ReportData.builder().build();
        DocxUtils utils = new DocxUtils(pkg, data);

        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils, TABLE_HTML, "", "");

        boolean hasTable = converted.stream().anyMatch(this::isTable);
        assertThat(hasTable)
                .withFailMessage("Expected a w:tbl in converted output but got: %s",
                        converted.stream().map(o -> unwrap(o).getClass().getSimpleName()).toList())
                .isTrue();
    }

    /**
     * The editor stores tables with newline formatting between structural
     * tags. Those newlines used to become text nodes (via the newline token)
     * inside the table, fragmenting it into one single-cell table per cell.
     */
    @Test
    @SuppressWarnings("unchecked")
    void editorFormattedTableStaysOneTable() throws Exception {
        String editorHtml = "<table class=\"rte-table\">\n<thead>\n<tr>\n"
                + "<th>Header 1</th>\n<th>Header 2</th>\n</tr>\n</thead>\n"
                + "<tbody><tr>\n<td>cell 1</td>\n<td>cell 2</td>\n</tr>\n"
                + "<tr>\n<td>cell 3</td>\n<td>cell 4</td>\n</tr>\n</tbody></table>";

        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());
        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils, editorHtml, "", "");

        List<Tbl> tables = converted.stream()
                .map(this::unwrap)
                .filter(Tbl.class::isInstance)
                .map(Tbl.class::cast)
                .toList();

        assertThat(tables).hasSize(1);
        Tbl table = tables.get(0);
        long rows = table.getContent().stream()
                .map(this::unwrap)
                .filter(org.docx4j.wml.Tr.class::isInstance)
                .count();
        assertThat(rows).isEqualTo(3);
        assertThat(table.getTblGrid().getGridCol()).hasSize(2);

        // Default .rte-table styling must yield visible cell borders
        String xml = org.docx4j.XmlUtils.marshaltoString(table, true, pkg.getMainDocumentPart().getJaxbElement() != null);
        assertThat(xml).contains("Header 1");
        assertThat(xml).doesNotContain("FACTIONNLTOKEN");
        assertThat(xml.contains("single") || xml.contains("solid"))
                .withFailMessage("expected visible cell borders in: %s", xml)
                .isTrue();
    }

    private boolean isTable(Object o) {
        return unwrap(o) instanceof Tbl;
    }

    private Object unwrap(Object o) {
        return o instanceof JAXBElement<?> el ? el.getValue() : o;
    }
}
