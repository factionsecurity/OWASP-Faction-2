package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An installation can rename its severities — "Critical" becomes "Sev-1", "P1", whatever the
 * team already says. The label is the only thing that moves.
 *
 * <p>These tests exist because the obvious implementation breaks every DOCX template silently.
 * The {@code ${riskCountN}} tallies and the {@code ${[asmtCRITICAL]}} finding loops used to match
 * on the display string, so renaming Critical made {@code severityToInt} return -1 and
 * {@code equalsIgnoreCase} miss: reports kept generating, kept looking right, and reported zero
 * Criticals. Both now match {@link ReportData.ReportVulnerability#getSeverityKey()}, the enum
 * name, which no rename touches.
 */
class DocxUtilsSeverityLabelTest {

    /** A finding whose displayed severity has been renamed but whose enum key has not. */
    private ReportData.ReportVulnerability renamed(String name, String label, String key) {
        return ReportData.ReportVulnerability.builder()
                .name(name).severity(label).severityKey(key).build();
    }

    private String generate(String paragraphText, List<ReportData.ReportVulnerability> vulns)
            throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText(paragraphText);

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .vulnerabilities(new java.util.ArrayList<>(vulns))
                .build());
        WordprocessingMLPackage result = utils.generateDocx("", null);
        return XmlUtils.marshaltoString(result.getMainDocumentPart().getJaxbElement(), true, false);
    }

    @Test
    void riskCountsStillTallyAfterASeverityIsRenamed() throws Exception {
        String xml = generate("Criticals: ${riskCount9} of ${riskTotal}", List.of(
                renamed("SQL Injection", "Sev-1", "CRITICAL"),
                renamed("Weak TLS", "Sev-1", "CRITICAL"),
                renamed("Verbose errors", "Sev-4", "LOW")));

        // Two criticals out of three findings — the count follows the enum, not the label.
        assertThat(xml).contains("Criticals: 2 of 3");
    }

    @Test
    void countsAreUnaffectedWhenTheLabelIsLeftAlone() throws Exception {
        String xml = generate("Criticals: ${riskCount9} of ${riskTotal}", List.of(
                renamed("SQL Injection", "Critical", "CRITICAL"),
                renamed("Verbose errors", "Low", "LOW")));

        assertThat(xml).contains("Criticals: 1 of 2");
    }

    /**
     * Runs the severity loop replacement over a fragment.
     *
     * <p>Called directly rather than through {@code generateDocx} because the
     * {@code ${[asmtCRITICAL]}} loops resolve inside the report's HTML fields — the executive
     * summary and the rich-text sections — not in plain template paragraphs. Same reflection
     * approach as {@code DocxUtilsAssetLocationTest}.
     */
    private String loopReplace(List<ReportData.ReportVulnerability> vulns, String content)
            throws Exception {
        DocxUtils utils = new DocxUtils(null, ReportData.builder()
                .vulnerabilities(new java.util.ArrayList<>(vulns))
                .build());
        Method m = DocxUtils.class.getDeclaredMethod("loopReplace", String.class);
        m.setAccessible(true);
        return (String) m.invoke(utils, content);
    }

    @Test
    void theSeverityLoopTokenStillResolvesAfterARename() throws Exception {
        // ${[asmtCRITICAL]} is written into templates by their authors and is keyed on the enum,
        // so it must keep working for a customer who has never heard the word "Critical".
        String html = loopReplace(List.of(
                renamed("SQL Injection", "Sev-1", "CRITICAL"),
                renamed("Verbose errors", "Sev-4", "LOW")), "${[asmtCRITICAL]}");

        assertThat(html).contains("SQL Injection");
        assertThat(html).doesNotContain("Verbose errors");
        assertThat(html).doesNotContain("No vulnerabilities found at this severity");
    }

    @Test
    void aSeverityWithNoFindingsStillSaysSoRatherThanLeavingTheTokenBehind() throws Exception {
        String html = loopReplace(
                List.of(renamed("Verbose errors", "Sev-4", "LOW")), "${[asmtCRITICAL]}");

        assertThat(html).contains("No vulnerabilities found at this severity");
        assertThat(html).doesNotContain("asmtCRITICAL");
    }

    @Test
    void everySeverityHasALoopTokenAndNoneIsKeyedOnTheLabel() throws Exception {
        // All five, because SEVERITIES is a hand-maintained list — one stale entry and that
        // severity's loop token stops resolving in every template that uses it.
        for (String key : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFORMATIONAL")) {
            String html = loopReplace(
                    List.of(renamed("A finding", "renamed-" + key, key)), "${[asmt" + key + "]}");
            assertThat(html).as("loop token for %s", key).contains("A finding");
        }
    }

    @Test
    void anUnclassifiedFindingIsCountedByNeitherTallyNorLoop() throws Exception {
        String xml = generate("Criticals: ${riskCount9} of ${riskTotal}", List.of(
                renamed("SQL Injection", "Sev-1", "CRITICAL"),
                renamed("Unrated observation", "", "")));

        assertThat(xml).contains("Criticals: 1 of 1");
    }
}
