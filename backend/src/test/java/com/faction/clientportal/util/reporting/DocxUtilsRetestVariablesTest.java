package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ${retestResult}, ${retestDate}, ${retestedBy} and ${retestEvidence} in a findings block. */
class DocxUtilsRetestVariablesTest {

    private String render(ReportData.ReportVulnerability... vulns) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        for (String p : List.of("${fiBegin}",
                "${vulnName}|${retestResult}|${retestDate}|${retestedBy}",
                "${retestEvidence}",
                "${fiEnd}")) {
            pkg.getMainDocumentPart().addParagraphOfText(p);
        }
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .vulnerabilities(new ArrayList<>(List.of(vulns))).build());
        return XmlUtils.marshaltoString(utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);
    }

    private ReportData.ReportVulnerability vuln(String name) {
        return ReportData.ReportVulnerability.builder().id(name).name(name).severity("High").severityKey("HIGH").build();
    }

    @Test
    void aRetestedFindingRendersItsLatestRetest() throws Exception {
        LocalDateTime retestedAt = LocalDateTime.of(2026, 9, 20, 10, 0);
        ReportData.ReportVulnerability v = vuln("SQLi");
        v.setRetestResult("Passed");
        v.setRetestDate(retestedAt);
        v.setRetestedBy("Jane Tester");
        v.setRetestEvidence("<p>Parameterized query confirmed</p>");

        String xml = render(v);

        // Same pattern and zone conversion DocxUtils uses for every finding date.
        String formatted = new SimpleDateFormat("MM/dd/yyyy")
                .format(Date.from(retestedAt.atZone(ZoneId.systemDefault()).toInstant()));
        assertThat(xml).contains("SQLi|Passed|").contains("|Jane Tester");
        assertThat(xml).contains("SQLi|Passed|" + formatted + "|Jane Tester");
        assertThat(xml).contains("Parameterized query confirmed");
        assertThat(xml).doesNotContain("${retest");
    }

    @Test
    void aFindingNeverRetestedRendersBlanksNotTokens() throws Exception {
        String xml = render(vuln("XSS"));

        assertThat(xml).contains("XSS|||");
        assertThat(xml).doesNotContain("${retest");
    }

    // ── Regex-special characters in the tester's name / result ──────────────

    /** A display name with a group reference and a backslash, which replaceAll would interpret. */
    private static final String AWKWARD_NAME = "Jane $1 Tester \\ QA";

    private ReportData.ReportVulnerability awkwardlyNamedRetest() {
        ReportData.ReportVulnerability v = vuln("SQLi");
        v.setRetestResult("Passed $2");
        v.setRetestDate(LocalDateTime.of(2026, 9, 20, 10, 0));
        v.setRetestedBy(AWKWARD_NAME);
        return v;
    }

    @Test
    void aTesterNameWithDollarAndBackslashRendersVerbatimInAFindingsBlock() throws Exception {
        String xml = render(awkwardlyNamedRetest());

        assertThat(xml).contains("SQLi|Passed $2|").contains("|" + AWKWARD_NAME);
    }

    @Test
    void aTesterNameWithDollarAndBackslashRendersVerbatimInAVulnTable() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        pkg.getMainDocumentPart().addObject(XmlUtils.unmarshalString(
                "<w:tbl xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
              + "<w:tr><w:tc><w:p><w:r><w:t>${vulnTable}</w:t></w:r></w:p></w:tc></w:tr>"
              + "<w:tr><w:tc><w:p><w:r>"
              + "<w:t>${loop}${vulnName}|${retestResult}|${retestedBy}</w:t>"
              + "</w:r></w:p></w:tc></w:tr>"
              + "</w:tbl>"));
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .vulnerabilities(new ArrayList<>(List.of(awkwardlyNamedRetest()))).build());

        String xml = XmlUtils.marshaltoString(utils.generateDocx("", null).getMainDocumentPart().getJaxbElement(), true, false);

        assertThat(xml).contains("SQLi|Passed $2|" + AWKWARD_NAME);
    }
}
