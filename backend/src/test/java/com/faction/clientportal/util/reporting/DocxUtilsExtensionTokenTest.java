package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholders an App Store extension owns — {@code ${faction-bar-chart}} and the
 * like — put directly in the DOCX template.
 *
 * <p>This is where report variables naturally live in Faction 2: a template author
 * writing {@code ${asmtName}} and {@code ${summary1}} will reach for the same place
 * to put a chart. Without this path an installed, enabled ReportManager extension
 * simply never runs, with nothing in the output to say why.
 */
class DocxUtilsExtensionTokenTest {

    /** Builds a one-paragraph document and runs generateDocx over it. */
    private WordprocessingMLPackage generate(String paragraphText,
                                             DocxUtils.TokenResolver resolver) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        main.addParagraphOfText(paragraphText);

        DocxUtils utils = new DocxUtils(pkg, ReportData.builder()
                .vulnerabilities(new ArrayList<>())
                .build());
        return utils.generateDocx("", resolver);
    }

    private String textOf(WordprocessingMLPackage pkg) {
        return XmlUtils.marshaltoString(pkg.getMainDocumentPart().getJaxbElement(), true, false);
    }

    @Test
    void aLoneTokenParagraphIsReplacedByTheExtensionsHtml() throws Exception {
        List<String> offered = new ArrayList<>();
        WordprocessingMLPackage result = generate("${faction-bar-chart}", token -> {
            offered.add(token);
            return "<p>CHART WENT HERE</p>";
        });

        // The extension is handed the full placeholder, braces included — the shape
        // the bundled bar-chart extension already matches on.
        assertThat(offered).containsExactly("${faction-bar-chart}");
        assertThat(textOf(result)).contains("CHART WENT HERE");
        assertThat(textOf(result)).doesNotContain("faction-bar-chart");
    }

    @Test
    void anImageReturnedByTheExtensionBecomesAnEmbeddedDrawing() throws Exception {
        // 1x1 transparent PNG — the smallest thing the XHTML importer will embed.
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                   + "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
        WordprocessingMLPackage result = generate("${faction-bar-chart}",
                token -> "<img src='data:image/png;base64," + png + "'></img>");

        // A real drawing, not literal markup: this is what the bar chart produces.
        String xml = textOf(result);
        assertThat(xml).contains("drawing");
        assertThat(xml).doesNotContain("&lt;img");
    }

    @Test
    void aTokenNoExtensionClaimsIsLeftAlone() throws Exception {
        WordprocessingMLPackage untouched = generate("${faction-bar-chart}", token -> null);
        assertThat(textOf(untouched)).contains("${faction-bar-chart}");

        // Returning the token unchanged means "not mine" just as much as null does.
        WordprocessingMLPackage echoed = generate("${faction-bar-chart}", token -> token);
        assertThat(textOf(echoed)).contains("${faction-bar-chart}");
    }

    @Test
    void ordinaryProseIsNeverOfferedToExtensions() throws Exception {
        List<String> offered = new ArrayList<>();
        generate("This paragraph mentions no placeholder at all.", token -> {
            offered.add(token);
            return "REPLACED";
        });

        assertThat(offered).isEmpty();
    }

    @Test
    void aPlaceholderMidSentenceIsNotTreatedAsABlockReplacement() throws Exception {
        List<String> offered = new ArrayList<>();
        WordprocessingMLPackage result = generate("See ${faction-bar-chart} below.", token -> {
            offered.add(token);
            return "<p>CHART</p>";
        });

        // Swapping a whole paragraph out for a chart would delete the surrounding
        // sentence, so an inline placeholder is left for the author to fix.
        assertThat(offered).isEmpty();
        assertThat(textOf(result)).contains("See ");
    }

    @Test
    void generationSucceedsWithNoExtensionsInstalled() throws Exception {
        WordprocessingMLPackage result = generate("${faction-bar-chart}", null);
        assertThat(textOf(result)).contains("${faction-bar-chart}");
    }

    @Test
    void aPlaceholderCarryingArgumentsIsOfferedWholeAndUnparsed() throws Exception {
        // The checklist extension is driven by
        // ${checklist-owasp-top-10 columns=[Question,Status,Comment]} and parses the
        // arguments itself, so the placeholder must arrive intact — spaces, brackets
        // and commas included.
        String token = "${checklist-owasp-top-10 columns=[Question,Status,Comment]}";
        List<String> offered = new ArrayList<>();

        WordprocessingMLPackage result = generate(token, t -> {
            offered.add(t);
            return "<table><tr><td>Injection</td><td>PASS</td></tr></table>";
        });

        assertThat(offered).containsExactly(token);
        assertThat(textOf(result)).contains("Injection");
        assertThat(textOf(result)).doesNotContain("checklist-owasp-top-10");
    }

    @Test
    void twoPlaceholdersOnOneLineAreNotSwallowedAsOne() throws Exception {
        // A body that allowed '}' would match across both and hand the extension
        // nonsense; neither is a lone placeholder, so neither is offered.
        List<String> offered = new ArrayList<>();
        generate("${checklist-one} ${checklist-two}", t -> {
            offered.add(t);
            return "REPLACED";
        });

        assertThat(offered).isEmpty();
    }
}
