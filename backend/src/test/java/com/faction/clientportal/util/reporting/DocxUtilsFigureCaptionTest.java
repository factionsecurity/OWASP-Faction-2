package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import jakarta.xml.bind.JAXBElement;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A captioned image must render as the picture with its caption beneath it — not beside it.
 *
 * <p>The default template CSS shipped {@code figure{display:inline-block}}, and an inline-block
 * figure makes the importer flow the image and its caption into the surrounding line: caption
 * next to the picture, and a numbered step containing one loses its number. Older templates
 * still carry that rule, so the generator forces every figure to a block itself; these run with
 * that rule in force to prove the fix holds against it.
 */
class DocxUtilsFigureCaptionTest {

    /** The figure and img rules exactly as older templates saved them. */
    private static final String INLINE_BLOCK_TEMPLATE_CSS =
        "figure{ text-align: center; padding: 0px; margin: 10px 0px; display: inline-block; border: none; }"
      + "img{ max-width: 600px; height: auto !important; display: block; margin: auto !important }";

    record Para(boolean numbered, String jc, boolean image, String text) {}

    private String png() throws Exception {
        BufferedImage img = new BufferedImage(320, 120, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics(); g.setColor(java.awt.Color.DARK_GRAY); g.fillRect(0, 0, 320, 120); g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** What the editor emits for an image: centered by an inline style. */
    private String editorImage() throws Exception {
        return "<img src=\"" + png() + "\" alt=\"image.png\" style=\"max-width: 100%; display: block; margin-left: auto; margin-right: auto;\">";
    }

    @SuppressWarnings("unchecked")
    private List<Object> wrap(WordprocessingMLPackage pkg, String html, String css) throws Exception {
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());
        Method m = DocxUtils.class.getDeclaredMethod("wrapHTML", String.class, String.class, String.class);
        m.setAccessible(true);
        return (List<Object>) m.invoke(utils, html, css, "");
    }

    private List<Para> convert(String html) throws Exception {
        List<Para> paras = new ArrayList<>();
        for (Object o : wrap(WordprocessingMLPackage.createPackage(), html, INLINE_BLOCK_TEMPLATE_CSS)) {
            Object u = o instanceof JAXBElement<?> el ? el.getValue() : o;
            String xml = XmlUtils.marshaltoString(u, true, false);
            String jc = xml.contains("<w:jc ") ? xml.replaceAll("(?s).*<w:jc w:val=\"([^\"]+)\".*", "$1") : "none";
            String text = xml.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            paras.add(new Para(xml.contains("<w:numPr>"), jc, xml.contains("<wp:extent"), text));
        }
        return paras;
    }

    private String figure(String caption) throws Exception {
        return "<figure>" + editorImage() + "<figcaption>" + caption + "</figcaption></figure>";
    }

    /** The paragraph right after the image, skipping any blank one an inline wrapper leaves. */
    private static Para after(List<Para> p, Para shot) {
        return p.subList(p.indexOf(shot) + 1, p.size()).stream()
                .filter(x -> !x.text().isEmpty() || x.image()).findFirst().orElseThrow();
    }

    @Test
    void aCaptionRendersBeneathItsImageEvenWithTheInlineBlockTemplateRule() throws Exception {
        List<Para> p = convert("<p>Text before.</p>" + figure("Figure 1: The login page") + "<p>Text after.</p>");

        Para shot = p.stream().filter(Para::image).findFirst().orElseThrow();
        // The picture is a paragraph of its own — nothing but the image in it. The caption is
        // the paragraph beneath it, centered. Keeping them in one paragraph is what put the
        // caption beside the picture once a template stylesheet was in play.
        assertThat(shot.text()).isEmpty();
        assertThat(shot.jc()).isEqualTo("center");
        Para caption = after(p, shot);
        assertThat(caption.image()).isFalse();
        assertThat(caption.jc()).isEqualTo("center");
        // The author's caption, verbatim — no auto-numbered "Figure 1 " prefixed onto it.
        assertThat(caption.text()).isEqualTo("Figure 1: The login page");
        // And the figure never flowed into its neighbours.
        assertThat(p.get(p.indexOf(shot) - 1).text()).startsWith("Text before");
        assertThat(after(p, caption).text()).startsWith("Text after");
    }

    @Test
    void aCaptionedFigureInsideAStepKeepsTheNumberAndSitsBeneathIt() throws Exception {
        List<Para> p = convert("<ol><li>Log in and take a screenshot:" + figure("Figure 2: Result")
                + "</li><li>Second step</li></ol>");

        // With the inline-block rule the image and caption flowed into the step's own line and
        // the step lost its number. Fixed: the step is text only and numbered.
        assertThat(p.get(0).numbered()).isTrue();
        assertThat(p.get(0).image()).isFalse();
        assertThat(p.get(0).text()).startsWith("Log in");
        // Picture, then caption beneath it — neither numbered, both centered.
        Para shot = p.stream().filter(Para::image).findFirst().orElseThrow();
        assertThat(shot.text()).isEmpty();
        assertThat(shot.numbered()).isFalse();
        assertThat(shot.jc()).isEqualTo("center");
        Para caption = after(p, shot);
        assertThat(caption.text()).isEqualTo("Figure 2: Result");
        assertThat(caption.numbered()).isFalse();
        assertThat(caption.jc()).isEqualTo("center");
        // The next step still counts on.
        Para last = p.get(p.size() - 1);
        assertThat(last.numbered()).isTrue();
        assertThat(last.text()).startsWith("Second step");
    }

    @Test
    void theBoldWrappedShapeTheEditorProducesIsHandledToo() throws Exception {
        // Adding a caption while bold is active wraps image and caption in a <strong>.
        List<Para> p = convert("<p>Before.</p><figure><strong>" + editorImage() + "<figcaption>Hi there</figcaption></strong></figure><p>After.</p>");
        Para shot = p.stream().filter(Para::image).findFirst().orElseThrow();
        assertThat(shot.text()).isEmpty();
        assertThat(shot.jc()).isEqualTo("center");
        Para caption = after(p, shot);
        assertThat(caption.text()).isEqualTo("Hi there");
        assertThat(caption.jc()).isEqualTo("center");
        assertThat(after(p, caption).text()).startsWith("After");
    }

    /** Writes .docx fixtures for an eyes-on LibreOffice check: -Dfigure.fixtures.dir=/some/dir */
    @Test
    @EnabledIfSystemProperty(named = "figure.fixtures.dir", matches = ".+")
    void writeFixturesForLibreOffice() throws Exception {
        String dir = System.getProperty("figure.fixtures.dir");
        String[][] cases = {
            {"fixed_clean", "<p>Text before.</p>" + figure("Figure 1: The login page") + "<p>Text after.</p>"},
            {"fixed_list",  "<ol><li>Log in and take a screenshot:" + figure("Figure 2: Result") + "</li><li>Second step</li></ol>"},
        };
        for (String[] c : cases) {
            WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
            pkg.getMainDocumentPart().getContent().addAll(wrap(pkg, c[1], INLINE_BLOCK_TEMPLATE_CSS));
            pkg.save(new File(dir, c[0] + ".docx"));
        }
    }
}
