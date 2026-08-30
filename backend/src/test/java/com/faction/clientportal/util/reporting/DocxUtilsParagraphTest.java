package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.junit.jupiter.api.Test;

import jakarta.xml.bind.JAXBElement;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rich-text paragraphs must come out of the XHTML import as real DOCX
 * paragraphs: visible spacing between <p> blocks, and <br> line breaks
 * preserved within a block.
 */
class DocxUtilsParagraphTest {

    @SuppressWarnings("unchecked")
    private List<Object> convert(String html) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());
        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        return (List<Object>) wrapHTML.invoke(utils, html, "", "");
    }

    private Object unwrap(Object o) {
        return o instanceof JAXBElement<?> el ? el.getValue() : o;
    }

    @Test
    void paragraphsGetSpacingBetweenThem() throws Exception {
        List<Object> converted = convert("<p>first paragraph</p><p>second paragraph</p>");

        List<P> paragraphs = converted.stream()
                .map(this::unwrap)
                .filter(P.class::isInstance)
                .map(P.class::cast)
                .toList();
        assertThat(paragraphs).hasSize(2);

        String xml = XmlUtils.marshaltoString(paragraphs.get(0), true, false);
        // 10px bottom margin ≈ w:spacing w:after > 0
        assertThat(xml).matches("(?s).*w:after=\"[1-9][0-9]*\".*");
    }

    /**
     * The editor stores lists with newline formatting between <li> tags. Those
     * newlines used to become text nodes (via the newline token) directly
     * inside the <ul>, which the XHTML importer rendered as an EMPTY bulleted
     * paragraph before every real item — "doubled bullets" in the report.
     */
    @Test
    void editorFormattedListYieldsOneBulletPerItem() throws Exception {
        String editorHtml = "<ul>\n"
                + "<li><strong>User Input:</strong> applications take input</li>\n"
                + "<li>Lack of validation</li>\n"
                + "<li>Injection points</li>\n"
                + "</ul>";

        List<Object> converted = convert(editorHtml);

        List<P> listParagraphs = converted.stream()
                .map(this::unwrap)
                .filter(P.class::isInstance)
                .map(P.class::cast)
                .filter(p -> XmlUtils.marshaltoString(p, true, false).contains("<w:numPr>"))
                .toList();

        assertThat(listParagraphs).hasSize(3);
        for (P p : listParagraphs) {
            String xml = XmlUtils.marshaltoString(p, true, false);
            assertThat(xml)
                    .withFailMessage("empty bulleted paragraph generated: %s", xml)
                    .contains("<w:t");
        }
    }

    @Test
    void lineBreaksWithinParagraphSurvive() throws Exception {
        List<Object> converted = convert("<p>line one<br>line two</p>");

        String xml = XmlUtils.marshaltoString(unwrap(converted.get(0)), true, false);
        assertThat(xml).contains("line one");
        assertThat(xml).contains("line two");
        assertThat(xml).contains("<w:br/>");
    }
}
