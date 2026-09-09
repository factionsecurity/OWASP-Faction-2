package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import jakarta.xml.bind.JAXBElement;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A screenshot pasted inside a numbered step must render centered on its own line beneath
 * that step, without taking a number of its own — so the next step keeps counting.
 *
 * <p>The editor emits the image as a bare child of the {@code <li>}, centered there by an
 * inline style docx4j ignores; left alone, the importer renders it inline after the step
 * text, left-aligned. These pin the wrapping that fixes it, and that it does not disturb
 * images that were already inside a paragraph.
 */
class DocxUtilsListImageTest {

    /** One output paragraph, reduced to the three things that matter here. */
    record Para(boolean numbered, String jc, boolean image, String text) {}

    private String png() throws Exception {
        BufferedImage img = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** Exactly what the editor emits for a screenshot in a step: a bare, inline-styled <img>. */
    private String editorImage() throws Exception {
        return "<img src=\"" + png() + "\" alt=\"shot\" style=\"display: block; margin-left: auto; margin-right: auto;\">";
    }

    @SuppressWarnings("unchecked")
    private List<Para> convert(String html) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());
        Method wrapHTML = DocxUtils.class.getDeclaredMethod("wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Para> paras = new ArrayList<>();
        for (Object o : (List<Object>) wrapHTML.invoke(utils, html, "", "")) {
            Object u = o instanceof JAXBElement<?> el ? el.getValue() : o;
            String xml = XmlUtils.marshaltoString(u, true, false);
            String jc = xml.contains("<w:jc ") ? xml.replaceAll("(?s).*<w:jc w:val=\"([^\"]+)\".*", "$1") : "none";
            String text = xml.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            paras.add(new Para(xml.contains("<w:numPr>"), jc, xml.contains("<wp:extent"), text));
        }
        return paras;
    }

    @Test
    void aScreenshotInAStepIsCenteredOnItsOwnLineAndTakesNoNumber() throws Exception {
        List<Para> p = convert("<ol><li>Log in and take a screenshot:" + editorImage() + "</li><li>Second step</li></ol>");

        assertThat(p).hasSize(3);
        // The step keeps its number and its text, and no longer carries the image inline.
        assertThat(p.get(0).numbered()).isTrue();
        assertThat(p.get(0).image()).isFalse();
        assertThat(p.get(0).text()).startsWith("Log in");
        // The screenshot: its own paragraph, centered, and NOT numbered — a numbered image
        // paragraph would show "2." beside the picture and push every later step along by one.
        assertThat(p.get(1).image()).isTrue();
        assertThat(p.get(1).jc()).isEqualTo("center");
        assertThat(p.get(1).numbered()).isFalse();
        // The next step still counts on from the first.
        assertThat(p.get(2).numbered()).isTrue();
        assertThat(p.get(2).text()).startsWith("Second step");
    }

    @Test
    void aScreenshotInANestedStepIsHandledToo() throws Exception {
        // Depth tracking, not a first-match: the image sits in the inner list's item.
        List<Para> p = convert("<ol><li>Outer<ol><li>Inner:" + editorImage() + "</li></ol></li></ol>");
        Para shot = p.stream().filter(Para::image).findFirst().orElseThrow();
        assertThat(shot.jc()).isEqualTo("center");
        assertThat(shot.numbered()).isFalse();
    }

    @Test
    void anImageAlreadyInAParagraphInsideAStepIsNotWrappedTwice() throws Exception {
        List<Para> p = convert("<ol><li>Step:<p>" + editorImage() + "</p></li><li>Next</li></ol>");
        List<Para> shots = p.stream().filter(Para::image).toList();
        assertThat(shots).hasSize(1);
        assertThat(shots.get(0).jc()).isEqualTo("center");
        assertThat(shots.get(0).numbered()).isFalse();
    }

    @Test
    void anImageOutsideAnyListStillCentersAsBefore() throws Exception {
        // The pre-existing behaviour for a plain pasted image must be untouched.
        List<Para> p = convert("<p>" + editorImage() + "</p><p>After</p>");
        Para shot = p.stream().filter(Para::image).findFirst().orElseThrow();
        assertThat(shot.jc()).isEqualTo("center");
        assertThat(shot.numbered()).isFalse();
    }
}
