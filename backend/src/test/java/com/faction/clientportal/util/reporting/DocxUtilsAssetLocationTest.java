package com.faction.clientportal.util.reporting;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ${assetLocation}} in a vulnerability report resolves to the vulnerability's
 * asset location.
 *
 * <p>An asset location is nearly always a URL, which makes two things load-bearing that
 * the plainer variables get away with ignoring: a bare {@code &} in a query string is not
 * valid XML, and {@code $} carries meaning in a regex replacement. Both appear in real
 * URLs, so both are covered here.
 */
class DocxUtilsAssetLocationTest {

    private static String resolve(String assetLocation) throws Exception {
        DocxUtils utils = new DocxUtils(null, ReportData.builder().build());
        Method m = DocxUtils.class.getDeclaredMethod(
                "assetLocation", ReportData.ReportVulnerability.class);
        m.setAccessible(true);
        String replacement = (String) m.invoke(utils,
                ReportData.ReportVulnerability.builder().assetLocation(assetLocation).build());
        // what the substitution actually produces in the document XML
        return "<w:t>${assetLocation}</w:t>".replaceAll("\\$\\{assetLocation\\}", replacement);
    }

    @Test
    void resolvesToTheVulnerabilitysAssetLocation() throws Exception {
        assertThat(resolve("https://app.example.com/login"))
                .isEqualTo("<w:t><![CDATA[https://app.example.com/login]]></w:t>");
    }

    /** A query string's "&" would be invalid XML on its own; CDATA carries it through. */
    @Test
    void carriesQueryStringsThroughUnescaped() throws Exception {
        assertThat(resolve("https://app.example.com/s?q=1&sort=desc&page=2"))
                .contains("q=1&sort=desc&page=2")
                .startsWith("<w:t><![CDATA[");
    }

    /** "$" and "\" are replacement metacharacters — a URL containing them must survive. */
    @Test
    void survivesReplacementMetacharacters() throws Exception {
        assertThat(resolve("https://host/api?cb=$1&path=C:\\shared"))
                .contains("cb=$1&path=C:\\shared");
    }

    @Test
    void aMissingAssetLocationLeavesNothingBehind() throws Exception {
        assertThat(resolve(null)).isEqualTo("<w:t></w:t>");
    }
}
