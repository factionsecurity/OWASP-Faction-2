package com.faction.clientportal.util;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trip tests for {@link LibreOfficeConverter}. These exercise the real
 * soffice binary and are skipped when LibreOffice is not installed (CI without
 * a LibreOffice layer). The docx → docx round-trip is what normalizes raw
 * docx4j output into a file Microsoft Word will open.
 */
class LibreOfficeConverterTest {

    private final LibreOfficeConverter converter = new LibreOfficeConverter();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(converter, "libreofficePath", "soffice");
        ReflectionTestUtils.setField(converter, "timeoutSeconds", 120);
        assumeTrue(sofficeAvailable(), "soffice not installed — skipping LibreOffice tests");
    }

    private boolean sofficeAvailable() {
        try {
            Process p = new ProcessBuilder("soffice", "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] sampleDocx() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        pkg.getMainDocumentPart().addParagraphOfText("Hello from the converter test");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pkg.save(out);
        return out.toByteArray();
    }

    @Test
    void convertToDocx_roundTripsToLoadableDocx() throws Exception {
        byte[] result = converter.convertToDocx(sampleDocx());

        assertThat(result).isNotEmpty();
        // Valid zip container
        assertThat(new String(result, 0, 2)).isEqualTo("PK");
        // Still a loadable WordprocessingML package after the round-trip
        WordprocessingMLPackage reloaded =
                WordprocessingMLPackage.load(new ByteArrayInputStream(result));
        assertThat(reloaded.getMainDocumentPart()).isNotNull();
    }

    @Test
    void convertToPdf_producesPdfBytes() throws Exception {
        byte[] result = converter.convertToPdf(sampleDocx());

        assertThat(result).isNotEmpty();
        assertThat(new String(result, 0, 4)).isEqualTo("%PDF");
    }
}
