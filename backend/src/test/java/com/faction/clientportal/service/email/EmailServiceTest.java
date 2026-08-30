package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailConfig;
import com.faction.clientportal.service.EmailConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailServiceTest {

    @Mock private EmailConfigService emailConfigService;

    @InjectMocks private EmailService emailService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailService, "frontendUrl", "https://faction.example.com/");
        when(emailConfigService.getOrCreate()).thenReturn(EmailConfig.builder()
                .fromEmail("info@example.com").fromName("Faction").build());
    }

    @Test
    void notConfigured_isReportedAndNothingIsSent() {
        when(emailConfigService.buildActiveSender()).thenReturn(null);

        assertThat(emailService.isConfigured()).isFalse();
        assertThat(emailService.send(EmailMessage.builder()
                .to("a@example.com").subject("s").htmlBody("b").build())).isEmpty();
    }

    @Test
    void blankRecipient_isNotSent() {
        when(emailConfigService.buildActiveSender()).thenReturn(new JavaMailSenderImpl());

        assertThat(emailService.send(EmailMessage.builder()
                .to("  ").subject("s").htmlBody("b").build())).isEmpty();
    }

    @Test
    void transportFailure_returnsEmptyRatherThanThrowing() {
        // Email is best-effort at every call site — the notification row or reset token
        // that prompted it is already committed, so a send failure must not unwind it.
        JavaMailSenderImpl broken = mock(JavaMailSenderImpl.class);
        when(broken.createMimeMessage()).thenThrow(new RuntimeException("smtp is down"));
        when(emailConfigService.buildActiveSender()).thenReturn(broken);

        Optional<String> result = emailService.send(EmailMessage.builder()
                .to("a@example.com").subject("s").htmlBody("b").build());

        assertThat(result).isEmpty();
    }

    @Test
    void shellEscapesTheTitleButNotTheBody() {
        String html = emailService.renderShell(
                "<script>alert(1)</script>", "<p>trusted body</p>", null, null, null);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        // The body is inserted verbatim by contract — callers escape their own content.
        assertThat(html).contains("<p>trusted body</p>");
    }

    @Test
    void callToActionResolvesRelativeLinksAgainstTheFrontendUrl() {
        String html = emailService.renderShell("t", "", "View in Faction", "/assessments/1", null);

        assertThat(html).contains("https://faction.example.com/assessments/1");
        assertThat(html).contains("View in Faction");
    }

    @Test
    void callToActionIsOmittedWithoutALink() {
        assertThat(emailService.renderShell("t", "", "View", null, null))
                .doesNotContain("<a href");
    }

    @Test
    void absoluteUrl_leavesAbsoluteLinksAloneAndStripsTrailingSlashes() {
        assertThat(emailService.absoluteUrl("https://elsewhere.test/x"))
                .isEqualTo("https://elsewhere.test/x");
        // The configured URL has a trailing slash; the result must not double up.
        assertThat(emailService.absoluteUrl("/a")).isEqualTo("https://faction.example.com/a");
        assertThat(emailService.absoluteUrl("a")).isEqualTo("https://faction.example.com/a");
        assertThat(emailService.absoluteUrl(null)).isEmpty();
    }

    @Test
    void escapeHtml_coversTheAttributeBreakingCharacters() {
        assertThat(EmailService.escapeHtml("<a href=\"x\">&</a>"))
                .isEqualTo("&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;");
        assertThat(EmailService.escapeHtml(null)).isEmpty();
    }
}
