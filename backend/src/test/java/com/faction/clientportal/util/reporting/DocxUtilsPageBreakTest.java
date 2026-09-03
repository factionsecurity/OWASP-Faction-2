package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Br;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.STBrType;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ${pageBreak}} in a template becomes a real page break in the DOCX.
 *
 * <p>Ported from Faction 1, where the tag has always been supported. The paragraph holding
 * the tag is replaced rather than edited, so the tag has to be the only thing in it.
 */
class DocxUtilsPageBreakTest {

    private WordprocessingMLPackage docWith(String... paragraphs) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        for (String text : paragraphs) {
            pkg.getMainDocumentPart().addParagraphOfText(text);
        }
        return pkg;
    }

    private void insertPageBreaks(WordprocessingMLPackage pkg) throws Exception {
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());
        Method m = DocxUtils.class.getDeclaredMethod("insertPageBreaks");
        m.setAccessible(true);
        m.invoke(utils);
    }

    /** Every P in document order, with its text and whether it carries a page break. */
    private record Para(String text, boolean isPageBreak) {}

    private List<Para> paragraphs(WordprocessingMLPackage pkg) {
        return pkg.getMainDocumentPart().getContent().stream()
                .map(XmlUtils::unwrap)
                .filter(P.class::isInstance)
                .map(P.class::cast)
                .map(p -> new Para(text(p), hasPageBreak(p)))
                .toList();
    }

    private String text(P p) {
        StringBuilder sb = new StringBuilder();
        for (Object o : p.getContent()) {
            if (XmlUtils.unwrap(o) instanceof R r) {
                for (Object ro : r.getContent()) {
                    if (XmlUtils.unwrap(ro) instanceof Text t) sb.append(t.getValue());
                }
            }
        }
        return sb.toString();
    }

    private boolean hasPageBreak(P p) {
        for (Object o : p.getContent()) {
            if (XmlUtils.unwrap(o) instanceof R r) {
                for (Object ro : r.getContent()) {
                    if (XmlUtils.unwrap(ro) instanceof Br br && br.getType() == STBrType.PAGE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Test
    void theTagBecomesAPageBreakAndTheTagParagraphIsGone() throws Exception {
        WordprocessingMLPackage pkg = docWith("before", "${pageBreak}", "after");

        insertPageBreaks(pkg);

        List<Para> paras = paragraphs(pkg);
        assertThat(paras).hasSize(3);
        assertThat(paras.get(0)).isEqualTo(new Para("before", false));
        assertThat(paras.get(1).isPageBreak()).as("the tag paragraph is now the break").isTrue();
        assertThat(paras.get(1).text()).as("the literal tag must not survive into the report").isEmpty();
        assertThat(paras.get(2)).isEqualTo(new Para("after", false));
    }

    /**
     * A break per finding is the real use: the tag sits inside a repeated findings block,
     * so the document reaches this step already holding one copy per vulnerability.
     */
    @Test
    void everyOccurrenceIsReplaced() throws Exception {
        WordprocessingMLPackage pkg = docWith(
                "finding 1", "${pageBreak}", "finding 2", "${pageBreak}", "finding 3");

        insertPageBreaks(pkg);

        List<Para> paras = paragraphs(pkg);
        assertThat(paras).hasSize(5);
        assertThat(paras.stream().filter(Para::isPageBreak).count()).isEqualTo(2);
        assertThat(paras.stream().map(Para::text).filter(t -> t.contains("pageBreak")))
                .as("no tag left behind — the single pass must not skip an entry")
                .isEmpty();
    }

    @Test
    void aDocumentWithoutTheTagIsUntouched() throws Exception {
        WordprocessingMLPackage pkg = docWith("one", "two");

        insertPageBreaks(pkg);

        assertThat(paragraphs(pkg))
                .containsExactly(new Para("one", false), new Para("two", false));
    }

    /** Consecutive tags are the case a naive index-skipping loop gets wrong. */
    @Test
    void adjacentTagsBothBecomeBreaks() throws Exception {
        WordprocessingMLPackage pkg = docWith("a", "${pageBreak}", "${pageBreak}", "b");

        insertPageBreaks(pkg);

        List<Para> paras = paragraphs(pkg);
        assertThat(paras).hasSize(4);
        assertThat(paras.stream().filter(Para::isPageBreak).count()).isEqualTo(2);
    }
}
