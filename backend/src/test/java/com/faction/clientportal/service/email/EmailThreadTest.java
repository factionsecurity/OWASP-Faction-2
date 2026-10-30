package com.faction.clientportal.service.email;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailThreadTest {

    @Mock private AssessmentRepository assessmentRepository;
    @Mock private ApplicationRepository applicationRepository;

    @InjectMocks private EmailThread emailThread;

    @Test
    void aFindingIsNamedWithItsApplication() {
        Assessment assessment = new Assessment();
        assessment.setApplicationId("app-1");
        when(assessmentRepository.findById("asmt-1")).thenReturn(Optional.of(assessment));
        when(applicationRepository.findById("app-1"))
                .thenReturn(Optional.of(Application.builder().id("app-1").name("Payments API").build()));

        assertThat(emailThread.subject(MentionTargetType.VULNERABILITY, "Cross-Site Scripting", "asmt-1"))
                .isEqualTo("[Faction] Cross-Site Scripting — Payments API");
    }

    @Test
    void theSameFindingNameOnTwoApplicationsGetsTwoSubjects() {
        assertThat(EmailThread.compose(MentionTargetType.VULNERABILITY, "Cross-Site Scripting", "Payments API"))
                .isNotEqualTo(EmailThread.compose(MentionTargetType.VULNERABILITY, "Cross-Site Scripting", "Billing"));
    }

    @Test
    void aFindingWhoseApplicationCannotBeFoundStillGetsASubject() {
        when(assessmentRepository.findById("gone")).thenReturn(Optional.empty());

        assertThat(emailThread.subject(MentionTargetType.VULNERABILITY, "SQL Injection", "gone"))
                .isEqualTo("[Faction] SQL Injection");
    }

    @Test
    void anApplicationNeedsNoLookup() {
        assertThat(emailThread.subject(MentionTargetType.APPLICATION, "Payments API", null))
                .isEqualTo("[Faction] Payments API — discussion");
        verifyNoInteractions(assessmentRepository, applicationRepository);
    }

    @Test
    void longNamesAreCutSoTheApplicationStaysVisible() {
        String subject = EmailThread.compose(MentionTargetType.VULNERABILITY, "x".repeat(200), "Payments API");

        assertThat(subject).endsWith(" — Payments API");
        assertThat(subject).contains("x".repeat(EmailThread.MAX_NAME_CHARS - 1) + "…");
    }

    @Test
    void theThreadIdIsStablePerItemAndDistinctAcrossItems() {
        assertThat(EmailThread.threadId(MentionTargetType.VULNERABILITY, "v-1"))
                .isEqualTo("<vulnerability.v-1@thread.faction>")
                .isEqualTo(EmailThread.threadId(MentionTargetType.VULNERABILITY, "v-1"))
                .isNotEqualTo(EmailThread.threadId(MentionTargetType.VULNERABILITY, "v-2"))
                .isNotEqualTo(EmailThread.threadId(MentionTargetType.APPLICATION, "v-1"));
    }

    @Test
    void anIdThatCannotSitInAMessageIdGetsNoThread() {
        assertThat(EmailThread.threadId(MentionTargetType.APPLICATION, "a b>")).isNull();
        assertThat(EmailThread.threadId(MentionTargetType.APPLICATION, null)).isNull();
    }
}
