package com.faction.clientportal.util.reporting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A HYPERLINK user-defined field stores one plain string — the value a pentester typed into the
 * assessment form. {@link SmartLink} is the only thing that decides what part of that string is a
 * link and where it points; {@code DocxUtils} just renders whatever comes back.
 *
 * <p>The rule is deliberately conservative: an email address, an explicit {@code http(s)://} URL,
 * and a bare domain become links; everything else stays plain text. A report that quietly turns
 * "N/A" into {@code https://N/A} is worse than one that leaves it alone, because nobody notices
 * until a client clicks it.
 */
class SmartLinkTest {

    private List<SmartLink.Segment> parse(String value) {
        return SmartLink.parse(value);
    }

    /** The text of every segment joined back together — must always equal the input. */
    private String rendered(String value) {
        return parse(value).stream().map(SmartLink.Segment::text).reduce("", String::concat);
    }

    private List<SmartLink.Segment> links(String value) {
        return parse(value).stream().filter(SmartLink.Segment::isLink).toList();
    }

    @Test
    void anEmailAddressBecomesAMailtoLink() {
        assertThat(links("ops@acme.com"))
                .singleElement()
                .returns("ops@acme.com", SmartLink.Segment::text)
                .returns("mailto:ops@acme.com", SmartLink.Segment::href);
    }

    @Test
    void anExplicitUrlIsLinkedAsTyped() {
        assertThat(links("https://acme.com/report?id=7"))
                .singleElement()
                .returns("https://acme.com/report?id=7", SmartLink.Segment::href);
    }

    @Test
    void aPlainHttpUrlKeepsItsScheme() {
        assertThat(links("http://intranet.acme.com"))
                .singleElement()
                .returns("http://intranet.acme.com", SmartLink.Segment::href);
    }

    @Test
    void aBareDomainGetsAnHttpsScheme() {
        assertThat(links("acme.com"))
                .singleElement()
                .returns("acme.com", SmartLink.Segment::text)
                .returns("https://acme.com", SmartLink.Segment::href);
    }

    @Test
    void aWwwHostGetsAnHttpsScheme() {
        assertThat(links("www.acme.com/security"))
                .singleElement()
                .returns("https://www.acme.com/security", SmartLink.Segment::href);
    }

    @Test
    void textThatIsNeitherAnEmailNorAUrlIsNotLinked() {
        assertThat(links("N/A")).isEmpty();
        assertThat(rendered("N/A")).isEqualTo("N/A");
    }

    @Test
    void aPersonsNameIsNotLinked() {
        assertThat(links("John Smith")).isEmpty();
        assertThat(rendered("John Smith")).isEqualTo("John Smith");
    }

    @Test
    void commaSeparatedEmailsEachGetTheirOwnLink() {
        assertThat(links("ops@acme.com, soc@acme.com"))
                .extracting(SmartLink.Segment::href)
                .containsExactly("mailto:ops@acme.com", "mailto:soc@acme.com");
    }

    @Test
    void semicolonSeparatedEmailsEachGetTheirOwnLink() {
        assertThat(links("ops@acme.com;soc@acme.com"))
                .extracting(SmartLink.Segment::href)
                .containsExactly("mailto:ops@acme.com", "mailto:soc@acme.com");
    }

    @Test
    void spaceSeparatedUrlsEachGetTheirOwnLink() {
        assertThat(links("https://acme.com https://acme.io"))
                .extracting(SmartLink.Segment::href)
                .containsExactly("https://acme.com", "https://acme.io");
    }

    @Test
    void delimitersSurviveVerbatimBetweenTheLinks() {
        assertThat(rendered("ops@acme.com,  soc@acme.com; ir@acme.com"))
                .isEqualTo("ops@acme.com,  soc@acme.com; ir@acme.com");
    }

    @Test
    void emailsAndUrlsCanBeMixedInOneValue() {
        assertThat(links("ops@acme.com, https://acme.com, acme.io"))
                .extracting(SmartLink.Segment::href)
                .containsExactly("mailto:ops@acme.com", "https://acme.com", "https://acme.io");
    }

    @Test
    void anUnlinkableSegmentDoesNotStopTheLinkableOnesAroundIt() {
        assertThat(links("ops@acme.com, N/A, acme.com"))
                .extracting(SmartLink.Segment::href)
                .containsExactly("mailto:ops@acme.com", "https://acme.com");
    }

    /**
     * A sentence ends in a full stop and the reader means the domain, not the domain plus the
     * punctuation. The stop stays on screen but outside the href.
     */
    @Test
    void trailingSentencePunctuationStaysOutOfTheHref() {
        List<SmartLink.Segment> segments = parse("See acme.com.");
        assertThat(links("See acme.com."))
                .singleElement()
                .returns("acme.com", SmartLink.Segment::text)
                .returns("https://acme.com", SmartLink.Segment::href);
        assertThat(segments.stream().map(SmartLink.Segment::text).reduce("", String::concat))
                .isEqualTo("See acme.com.");
    }

    @Test
    void aBracketedUrlKeepsTheClosingBracketOutOfTheHref() {
        assertThat(links("(https://acme.com)"))
                .singleElement()
                .returns("https://acme.com", SmartLink.Segment::href);
    }

    @Test
    void anEmptyValueProducesNoSegments() {
        assertThat(parse("")).isEmpty();
        assertThat(parse(null)).isEmpty();
    }

    @Test
    void aValueOfOnlyWhitespaceIsLeftAlone() {
        assertThat(links("   ")).isEmpty();
        assertThat(rendered("   ")).isEqualTo("   ");
    }

    /**
     * Something shaped like a domain but with a nonsense suffix is a typo, not a host. Requiring a
     * two-plus-letter final label keeps "version 1.2" and "10.0.0.1:8080/status" out of the link
     * path.
     */
    @Test
    void aDottedValueThatIsNotAHostnameIsNotLinked() {
        assertThat(links("version 1.2")).isEmpty();
        assertThat(links("release.3")).isEmpty();
    }

    @Test
    void theWholeValueIsOneLineWithNoSegmentDropped() {
        String value = "ops@acme.com; www.acme.com, N/A https://acme.io/x";
        assertThat(rendered(value)).isEqualTo(value);
        assertThat(links(value)).hasSize(3);
    }

    // ── HTML rendering, for a HYPERLINK field referenced from inside a rich-text field ──────

    @Test
    void htmlWrapsAnEmailInAMailtoAnchor() {
        assertThat(SmartLink.toHtml("ops@acme.com"))
                .isEqualTo("<a href=\"mailto:ops@acme.com\">ops@acme.com</a>");
    }

    @Test
    void htmlLeavesUnlinkableTextAsBareText() {
        assertThat(SmartLink.toHtml("N/A")).isEqualTo("N/A");
    }

    @Test
    void htmlKeepsTheDelimitersBetweenSeveralAnchors() {
        assertThat(SmartLink.toHtml("acme.com; acme.io"))
                .isEqualTo("<a href=\"https://acme.com\">acme.com</a>; "
                         + "<a href=\"https://acme.io\">acme.io</a>");
    }

    /**
     * The value is a user-supplied string dropped into a rich-text field that is later sanitized
     * and handed to the XHTML importer. Markup in it has to arrive as text, not as elements.
     */
    @Test
    void htmlEscapesMarkupInTheValue() {
        assertThat(SmartLink.toHtml("<b>not bold</b>"))
                .isEqualTo("&lt;b&gt;not bold&lt;/b&gt;");
    }

    @Test
    void htmlEscapesAnAmpersandInsideAUrl() {
        assertThat(SmartLink.toHtml("https://acme.com/a?x=1&y=2"))
                .isEqualTo("<a href=\"https://acme.com/a?x=1&amp;y=2\">"
                         + "https://acme.com/a?x=1&amp;y=2</a>");
    }

    @Test
    void htmlOfAnEmptyValueIsEmpty() {
        assertThat(SmartLink.toHtml(null)).isEmpty();
        assertThat(SmartLink.toHtml("")).isEmpty();
    }
}
