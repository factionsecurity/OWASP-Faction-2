package com.faction.clientportal.util.reporting;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import jakarta.xml.bind.JAXBElement;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report images render at natural size unless wider than 600px, in which case
 * they are scaled down to fit (set via XHTMLImporterImpl.setMaxWidth — the
 * importer's renderer ignores CSS max-width on images).
 */
class DocxUtilsImageTest {

    private static final long EMU_PER_PIXEL_96DPI = 9525;
    private static final long CAP_EMU = 600 * EMU_PER_PIXEL_96DPI;

    private String pngDataUri(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private long convertedImageWidthEmu(int pxWidth) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxUtils utils = new DocxUtils(pkg, ReportData.builder().build());
        Method wrapHTML = DocxUtils.class.getDeclaredMethod(
                "wrapHTML", String.class, String.class, String.class);
        wrapHTML.setAccessible(true);
        List<Object> converted = (List<Object>) wrapHTML.invoke(utils,
                "<p><img src=\"" + pngDataUri(pxWidth, 50) + "\" alt=\"shot\"></p>", "", "");

        for (Object o : converted) {
            Object u = o instanceof JAXBElement<?> el ? el.getValue() : o;
            String xml = XmlUtils.marshaltoString(u, true, false);
            Matcher m = Pattern.compile("<wp:extent cx=\"(\\d+)\"").matcher(xml);
            if (m.find()) return Long.parseLong(m.group(1));
        }
        throw new AssertionError("no image extent found in converted output");
    }

    @Test
    void smallImageKeepsNaturalSizeAt96Dpi() throws Exception {
        // Without explicit dimensions docx4j sizes DPI-less images at 72dpi
        // (4/3 too large); the injected pixel attributes must pin it to the
        // browser-visible size: 200px = 200 × 9525 EMU.
        long cx = convertedImageWidthEmu(200);
        assertThat(cx).isCloseTo(200 * EMU_PER_PIXEL_96DPI, org.assertj.core.data.Percentage.withPercentage(2));
    }

    @Test
    void mediumImageKeepsNaturalSizeAt96Dpi() throws Exception {
        // Regression for the reported case: a 331px screenshot rendered ~4.6"
        // wide (72dpi interpretation) instead of ~3.45".
        long cx = convertedImageWidthEmu(331);
        assertThat(cx).isCloseTo(331 * EMU_PER_PIXEL_96DPI, org.assertj.core.data.Percentage.withPercentage(2));
    }

    @Test
    void wideImageIsCappedAt600px() throws Exception {
        long cx = convertedImageWidthEmu(1200);
        assertThat(cx).isCloseTo(CAP_EMU, org.assertj.core.data.Percentage.withPercentage(2));
    }
}
