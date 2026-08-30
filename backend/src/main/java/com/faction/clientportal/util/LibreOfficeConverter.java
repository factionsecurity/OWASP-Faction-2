package com.faction.clientportal.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Converts documents using LibreOffice headless mode.
 *
 * <p>Requires {@code soffice} (LibreOffice) to be installed and accessible on the system PATH,
 * or configure the full path via the {@code libreoffice.path} property.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code libreoffice.path} — path to the soffice executable (default: {@code soffice})</li>
 *   <li>{@code libreoffice.timeout-seconds} — max seconds per conversion (default: {@code 60})</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 *   byte[] pdfBytes = converter.convertToPdf(docxBytes);
 * </pre>
 */
@Component
@Slf4j
public class LibreOfficeConverter {

    @Value("${libreoffice.path:soffice}")
    private String libreofficePath;

    @Value("${libreoffice.timeout-seconds:60}")
    private int timeoutSeconds;

    /**
     * Converts the given DOCX bytes to PDF using LibreOffice headless.
     *
     * @param docxBytes raw bytes of the source DOCX file
     * @return raw bytes of the converted PDF
     * @throws IOException          if the conversion process fails or produces no output
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public byte[] convertToPdf(byte[] docxBytes) throws IOException, InterruptedException {
        return convert(docxBytes, "pdf");
    }

    /**
     * Round-trips DOCX bytes through LibreOffice (docx → docx). Used to
     * normalize docx4j output into a file Microsoft Word will open — raw
     * docx4j output contains constructs Word rejects as corrupt while
     * LibreOffice re-saves them in spec-compliant form.
     */
    public byte[] convertToDocx(byte[] docxBytes) throws IOException, InterruptedException {
        return convert(docxBytes, "docx");
    }

    private byte[] convert(byte[] docxBytes, String targetFormat)
            throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("lo-convert-" + UUID.randomUUID());
        // Output goes to a subdirectory so a docx → docx conversion can't
        // collide with the input file (LibreOffice keeps the base name).
        Path outDir     = Files.createDirectory(tempDir.resolve("out"));
        Path inputFile  = tempDir.resolve("input.docx");
        Path outputFile = outDir.resolve("input." + targetFormat);

        try {
            Files.write(inputFile, docxBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    libreofficePath,
                    "--headless",
                    "--norestore",
                    "--invisible",
                    "--convert-to", targetFormat,
                    "--outdir", outDir.toAbsolutePath().toString(),
                    inputFile.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);

            log.debug("Starting LibreOffice conversion (timeout {}s)", timeoutSeconds);
            Process process = pb.start();
            String processOutput = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(
                        "LibreOffice conversion timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException(
                        "LibreOffice exited with code " + exitCode + ": " + processOutput);
            }

            if (!Files.exists(outputFile)) {
                throw new IOException(
                        "LibreOffice produced no output file. soffice output: " + processOutput);
            }

            byte[] outputBytes = Files.readAllBytes(outputFile);
            log.debug("Converted {} bytes DOCX → {} bytes {}",
                    docxBytes.length, outputBytes.length, targetFormat.toUpperCase());
            return outputBytes;

        } finally {
            deleteQuietly(outputFile);
            deleteQuietly(outDir);
            deleteQuietly(inputFile);
            deleteQuietly(tempDir);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temporary file: {}", path, e);
        }
    }
}
