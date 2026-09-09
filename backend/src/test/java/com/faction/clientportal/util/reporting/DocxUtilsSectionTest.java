package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report sections in the DOCX, as Faction 1 did them: bare tags are the Default section,
 * {@code ${fiBegin Web_App}} … {@code ${fiEnd Web_App}} is a named one, and
 * {@code ${if-section Web_App}} … {@code ${end-section Web_App}} wraps anything that should
 * vanish when that section has nothing to say.
 *
 * <p>The contract these pin down: every finding renders exactly once — under its own section,
 * or under Default when it has none or names a section the assessment no longer has — and
 * the order within a section is the order the findings arrived in.
 */
class DocxUtilsSectionTest {

    private static ReportData.ReportVulnerability finding(String name, String section) {
        return ReportData.ReportVulnerability.builder()
                .id(name).name(name).severity("High").section(section).build();
    }

    private static WordprocessingMLPackage docWith(String... paragraphs) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        for (String text : paragraphs) pkg.getMainDocumentPart().addParagraphOfText(text);
        return pkg;
    }

    private static void run(WordprocessingMLPackage pkg, ReportData data) throws Exception {
        DocxUtils utils = new DocxUtils(pkg, data);
        Method trim = DocxUtils.class.getDeclaredMethod("trimSectionBlocks", String.class, boolean.class);
        Method findings = DocxUtils.class.getDeclaredMethod("setFindings", String.class, String.class);
        Method filter = DocxUtils.class.getDeclaredMethod("getFilteredVulns", String.class);
        trim.setAccessible(true);
        findings.setAccessible(true);
        filter.setAccessible(true);
        List<String> passes = new java.util.ArrayList<>();
        passes.add(DocxUtils.DEFAULT_SECTION);
        if (data.getSections() != null) passes.addAll(data.getSections());
        for (String section : passes) {
            boolean has = !((List<?>) filter.invoke(utils, section)).isEmpty();
            trim.invoke(utils, section, has);
            findings.invoke(utils, section, "");
        }
    }

    /** The document's paragraph texts, in order, blanks dropped. */
    private static List<String> lines(WordprocessingMLPackage pkg) {
        return pkg.getMainDocumentPart().getContent().stream()
                .map(XmlUtils::unwrap)
                .filter(P.class::isInstance)
                .map(P.class::cast)
                .map(DocxUtilsSectionTest::text)
                .filter(t -> !t.isBlank())
                .toList();
    }

    private static String text(P p) {
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

    @Test
    void eachSectionRendersItsOwnFindingsAndDefaultTakesTheRest() throws Exception {
        WordprocessingMLPackage pkg = docWith(
                "General findings",
                "${fiBegin}", "${vulnName}", "${fiEnd}",
                "After the general findings",
                "${if-section Web_App}",
                "Web application findings",
                "${fiBegin Web_App}", "${vulnName}", "${fiEnd Web_App}",
                "${end-section Web_App}");
        ReportData data = ReportData.builder()
                .sections(List.of("Web App"))
                .vulnerabilities(List.of(
                        finding("SQL injection", "Web App"),
                        finding("Weak TLS", null),
                        finding("Old finding", "Retired section"),   // its section no longer exists
                        finding("XSS", "Web App")))
                .build();

        run(pkg, data);

        assertThat(lines(pkg)).containsExactly(
                "General findings",
                "Weak TLS", "Old finding",
                // The paragraph after ${fiEnd} belongs to the template, not to the repeated block.
                "After the general findings",
                "Web application findings",
                "SQL injection", "XSS");
    }

    @Test
    void anEmptySectionDisappearsWithEverythingInsideItsWrapper() throws Exception {
        WordprocessingMLPackage pkg = docWith(
                "Intro",
                "${if-section Mobile}",
                "Mobile findings",
                "${fiBegin Mobile}", "${vulnName}", "${fiEnd Mobile}",
                "${end-section Mobile}",
                "Appendix");
        ReportData data = ReportData.builder()
                .sections(List.of("Mobile"))
                .vulnerabilities(List.of(finding("Weak TLS", null)))
                .build();

        run(pkg, data);

        assertThat(lines(pkg)).containsExactly("Intro", "Appendix");
    }

    @Test
    void theWrapperIsKeptWhenTheSectionHasFindingsAndOnlyTheMarkersGo() throws Exception {
        WordprocessingMLPackage pkg = docWith(
                "${if-section Mobile}", "Mobile heading", "${end-section Mobile}",
                "${if-section Mobile}", "Mobile second region", "${end-section Mobile}");
        ReportData data = ReportData.builder()
                .sections(List.of("Mobile"))
                .vulnerabilities(List.of(finding("Insecure storage", "Mobile")))
                .build();

        run(pkg, data);

        assertThat(lines(pkg)).containsExactly("Mobile heading", "Mobile second region");
    }

    /** Without sections (the open source edition sends none) every finding is Default. */
    @Test
    void withNoSectionsEveryFindingIsDefaultWhateverItSays() throws Exception {
        WordprocessingMLPackage pkg = docWith("${fiBegin}", "${vulnName}", "${fiEnd}");
        ReportData data = ReportData.builder()
                .sections(List.of())
                .vulnerabilities(List.of(finding("SQL injection", "Web App"), finding("Weak TLS", "")))
                .build();

        run(pkg, data);

        assertThat(lines(pkg)).containsExactly("SQL injection", "Weak TLS");
    }

    @Test
    void sectionNamesBecomeUnderscoredTokens() {
        assertThat(DocxUtils.sectionVariable("Web  Application ")).isEqualTo("Web_Application");
        assertThat(DocxUtils.sectionVariable(null)).isEmpty();
    }
}
