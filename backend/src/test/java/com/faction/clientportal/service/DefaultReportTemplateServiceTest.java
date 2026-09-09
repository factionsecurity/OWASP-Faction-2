package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default report template is fetched over the network during startup, which is the whole
 * risk: an install that is offline, firewalled, or pointed at a moved file must still boot.
 * So these cover the failure paths as closely as the happy one, against a real HTTP server
 * rather than a mocked client — the timeouts and redirect handling are the parts worth
 * exercising, and a mock would skip exactly those.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefaultReportTemplateServiceTest extends TestContainersConfig {

    @Autowired private DefaultReportTemplateService service;
    @Autowired private ReportTemplateRepository reportTemplateRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private StorageService storageService;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        reportTemplateRepository.deleteAll();
        assessmentTypeRepository.deleteAll();

        assessmentTypeRepository.save(AssessmentType.builder()
                .name("Web Application Pentest")
                .description("For the template to belong to")
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        ReflectionTestUtils.setField(service, "templateName", "Default Pentest Report");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Minimal but genuine DOCX: a zip, so it passes the same magic-byte check the real one does. */
    private static byte[] docxBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private void serve(String path, int status, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            }
        });
    }

    private void pointAt(String path) {
        ReflectionTestUtils.setField(service, "templateUrl", baseUrl + path);
    }

    @Test
    void installsTheTemplateAndItsFileWhenTheInstallHasNone() throws Exception {
        byte[] docx = docxBytes();
        serve("/template.docx", 200, docx);
        pointAt("/template.docx");

        service.ensureDefaultTemplate();

        List<ReportTemplate> templates = reportTemplateRepository.findAll();
        assertThat(templates).hasSize(1);
        ReportTemplate t = templates.get(0);
        assertThat(t.getName()).isEqualTo("Default Pentest Report");
        assertThat(t.getAssessmentTypeId()).isNotNull();
        assertThat(t.getTemplateFileSize()).isEqualTo((long) docx.length);
        assertThat(t.getUserDefinedFields()).extracting("variableName")
                .containsExactly("summary1", "summary2");

        // Installed with the default stylesheet, not none — see listResetRulesAreInTheDefaultCss
        // for what that stylesheet must carry.
        assertThat(t.getCss()).isEqualTo(ReportTemplateService.DEFAULT_TEMPLATE_CSS);

        // The row is only worth anything if the bytes really landed in storage.
        assertThat(storageService.downloadBytes(t.getTemplateFileId())).isEqualTo(docx);
    }

    @Test
    void listResetRulesAreInTheDefaultCss() {
        // Word indents every list item by a full tab stop unless the template says otherwise,
        // which pushes numbered steps and their code blocks off to the right in the generated
        // report. These zero that out for both list kinds and their items. They are the
        // stylesheet every new template starts with, so a regression here reaches every install.
        String css = ReportTemplateService.DEFAULT_TEMPLATE_CSS;
        for (String selector : new String[]{"ol", "ul", "li"}) {
            assertThat(css).as(selector + " block").matches("(?s).*\\b" + selector + "\\s*\\{[^}]*margin-left:\\s*0px\\s*!important[^}]*\\}.*");
            assertThat(css).as(selector + " block").matches("(?s).*\\b" + selector + "\\s*\\{[^}]*padding-left:\\s*0px\\s*!important[^}]*\\}.*");
        }
        assertThat(css).matches("(?s).*\\bli\\s*\\{[^}]*margin-bottom:\\s*10px\\s*!important[^}]*\\}.*");
    }

    @Test
    void followsARedirect() throws Exception {
        // The configured GitHub URL is a /raw/ link, which 302s to raw.githubusercontent.com.
        // Without redirect handling the real URL fetches nothing.
        byte[] docx = docxBytes();
        serve("/actual.docx", 200, docx);
        server.createContext("/redirect.docx", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/actual.docx");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        pointAt("/redirect.docx");

        service.ensureDefaultTemplate();

        assertThat(reportTemplateRepository.findAll()).hasSize(1);
    }

    @Test
    void aMissingFileLeavesTheInstallAloneAndDoesNotThrow() {
        serve("/gone.docx", 404, "Not Found".getBytes(StandardCharsets.UTF_8));
        pointAt("/gone.docx");

        service.ensureDefaultTemplate();

        assertThat(reportTemplateRepository.findAll()).isEmpty();
    }

    @Test
    void anHtmlErrorPageIsNotAcceptedAsATemplate() {
        // A moved file on a CDN often answers 200 with an HTML page. Storing that as a DOCX
        // fails later, at report generation, where the cause is no longer visible.
        serve("/moved.docx", 200, "<!DOCTYPE html><title>404</title>".getBytes(StandardCharsets.UTF_8));
        pointAt("/moved.docx");

        service.ensureDefaultTemplate();

        assertThat(reportTemplateRepository.findAll()).isEmpty();
    }

    @Test
    void anUnreachableHostIsSurvivable() {
        // Port 1 on loopback: connection refused immediately, standing in for an air-gapped
        // or firewalled install. Startup must not care.
        ReflectionTestUtils.setField(service, "templateUrl", "http://127.0.0.1:1/template.docx");

        service.ensureDefaultTemplate();

        assertThat(reportTemplateRepository.findAll()).isEmpty();
    }

    @Test
    void aBlankUrlMakesNoRequestAtAll() {
        ReflectionTestUtils.setField(service, "templateUrl", "");

        service.ensureDefaultTemplate();

        assertThat(reportTemplateRepository.findAll()).isEmpty();
    }

    @Test
    void doesNotReinstallOverAnInstallThatAlreadyHasATemplate() throws Exception {
        reportTemplateRepository.save(ReportTemplate.builder()
                .name("Something the customer built")
                .assessmentTypeId(assessmentTypeRepository.findAll().get(0).getId())
                .version(1)
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        serve("/template.docx", 200, docxBytes());
        pointAt("/template.docx");

        service.ensureDefaultTemplate();

        assertThat(reportTemplateRepository.findAll()).hasSize(1);
        assertThat(reportTemplateRepository.findAll().get(0).getName())
                .isEqualTo("Something the customer built");
    }

    @Test
    void aSoftDeletedTemplateDoesNotCountAsHavingOne() throws Exception {
        // Deliberately the other way round from the case above: an install that deleted every
        // template has none, so a restart is entitled to put the default back.
        reportTemplateRepository.save(ReportTemplate.builder()
                .name("Deleted one")
                .assessmentTypeId(assessmentTypeRepository.findAll().get(0).getId())
                .version(1)
                .active(true)
                .deletedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        serve("/template.docx", 200, docxBytes());
        pointAt("/template.docx");

        service.ensureDefaultTemplate();

        assertThat(reportTemplateRepository.findAll()).hasSize(2);
    }

    @Test
    void withoutAnAssessmentTypeItSkipsRatherThanCreatingAnOrphan() throws Exception {
        assessmentTypeRepository.deleteAll();
        serve("/template.docx", 200, docxBytes());
        pointAt("/template.docx");

        service.ensureDefaultTemplate();

        assertThat(reportTemplateRepository.findAll()).isEmpty();
    }
}
