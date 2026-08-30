package com.faction.clientportal.service;

import com.faction.clientportal.dto.AcceptPeerReviewRequest;
import com.faction.clientportal.dto.PeerReviewDto;
import com.faction.clientportal.dto.UpdatePeerReviewRequest;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.PeerReviewRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PeerReviewServiceTest {

    @Mock
    private PeerReviewRepository peerReviewRepository;
    @Mock
    private AssessmentRepository assessmentRepository;
    @Mock
    private VulnerabilityRepository vulnerabilityRepository;
    @Mock
    private UserRepository userRepository;

    @Mock
    private com.faction.clientportal.service.AssessmentWorkflowConfigService workflowConfigService;

    @Mock
    private com.faction.clientportal.service.extension.ExtensionEventService extensionEventService;

    @Mock
    private PeerReviewLockService lockService;

    @InjectMocks
    private PeerReviewService service;

    private Assessment assessment;
    private PeerReview pendingReview;
    private PeerReview completedReview;

    @BeforeEach
    void setUp() {
        assessment = Assessment.builder()
                .id("assess-1")
                .name("Test Assessment")
                .fieldValues(new HashMap<>(Map.of("field-1", "original-value")))
                .peerReviewStatus(AssessmentPeerReviewStatus.IN_PROGRESS)
                .build();

        pendingReview = PeerReview.builder()
                .id("review-1")
                .assessmentId("assess-1")
                .snapshotFieldValues(new HashMap<>(Map.of("field-1", "original-value")))
                .revisedFieldValues(new HashMap<>(Map.of("field-1", "revised-value")))
                .status(PeerReviewStatus.IN_REVIEW)
                .submittedByUserId("user-1")
                .reviewedByUserId("user-2")
                .createdAt(LocalDateTime.now())
                .build();

        completedReview = PeerReview.builder()
                .id("review-2")
                .assessmentId("assess-1")
                .snapshotFieldValues(new HashMap<>(Map.of("field-1", "original-value")))
                .revisedFieldValues(new HashMap<>(Map.of("field-1", "revised-value")))
                .status(PeerReviewStatus.COMPLETED)
                .submittedByUserId("user-1")
                .reviewedByUserId("user-2")
                .createdAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .build();
    }

    // ── updateReview ──────────────────────────────────────────────────────────

    /**
     * Locking a region tells the other reviewers not to type there; it does not show them what is
     * being typed. Without this broadcast a locked editor sits frozen on whatever it held when the
     * lock was taken, which reads as the feature being broken.
     */
    @Test
    void updateReview_pushesTheSavedEditToTheOtherReviewers() {
        when(peerReviewRepository.findById("review-1")).thenReturn(Optional.of(pendingReview));
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assess-1")).thenReturn(Optional.of(assessment));
        when(peerReviewRepository.save(any(PeerReview.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdatePeerReviewRequest request = new UpdatePeerReviewRequest();
        request.setRevisedFieldValues(new HashMap<>(Map.of("field-1", "reviewer's new text")));
        request.setFieldNotes(new HashMap<>(Map.of("field-1", "tighten this")));

        service.updateReview("review-1", request, "amy");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(lockService).broadcastEdits(eq("review-1"), eq("amy"), values.capture(), any(), any());
        assertThat(values.getValue()).containsEntry("field-1", "reviewer's new text");
    }

    /**
     * A review can be worked by several people at once, so it has to record all of them. Before
     * this, only whoever claimed the review was named and every later contributor was invisible.
     */
    @Test
    void updateReview_recordsEveryReviewerWhoSaves() {
        when(peerReviewRepository.findById("review-1")).thenReturn(Optional.of(pendingReview));
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assess-1")).thenReturn(Optional.of(assessment));
        when(peerReviewRepository.save(any(PeerReview.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdatePeerReviewRequest request = new UpdatePeerReviewRequest();
        request.setFieldNotes(new HashMap<>(Map.of("field-1", "a note")));

        // pendingReview was claimed by user-2; a second reviewer now saves against it.
        service.updateReview("review-1", request, "user-9");

        assertThat(pendingReview.getReviewerUserIds())
                .as("the claimer is kept and the new contributor appended, in that order")
                .containsExactly("user-2", "user-9");

        // Saving again must not duplicate them.
        service.updateReview("review-1", request, "user-9");
        assertThat(pendingReview.getReviewerUserIds()).containsExactly("user-2", "user-9");
    }

    // ── submitForPeerReview ───────────────────────────────────────────────────

    @Test
    void submitForPeerReview_SnapshotsFieldValuesAndVulnerabilities() {
        Vulnerability vuln = Vulnerability.builder()
                .id("vuln-1")
                .name("SQL Injection")
                .severity(VulnerabilitySeverity.HIGH)
                .order(0)
                .description("Desc")
                .recommendation("Rec")
                .fieldValues(new HashMap<>())
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull("assess-1"))
                .thenReturn(Optional.of(assessment));
        when(vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull("assess-1"))
                .thenReturn(List.of(vuln));
        when(peerReviewRepository.save(any(PeerReview.class)))
                .thenAnswer(inv -> {
                    PeerReview r = inv.getArgument(0);
                    r.setId("review-1");
                    return r;
                });
        when(assessmentRepository.save(any(Assessment.class))).thenReturn(assessment);

        PeerReviewDto result = service.submitForPeerReview("assess-1", "user-1");

        assertThat(result).isNotNull();
        assertThat(result.getSnapshotFieldValues()).containsEntry("field-1", "original-value");
        assertThat(result.getVulnerabilities()).hasSize(1);
        assertThat(result.getVulnerabilities().get(0).getVulnerabilityId()).isEqualTo("vuln-1");
        assertThat(result.getVulnerabilities().get(0).getName()).isEqualTo("SQL Injection");

        // Assessment should be locked
        ArgumentCaptor<Assessment> assessCaptor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessmentRepository).save(assessCaptor.capture());
        assertThat(assessCaptor.getValue().getPeerReviewStatus())
                .isEqualTo(AssessmentPeerReviewStatus.IN_PEER_REVIEW);
        assertThat(assessCaptor.getValue().getActivePeerReviewId()).isEqualTo("review-1");
    }

    @Test
    void submitForPeerReview_ThrowsWhenAlreadyInPeerReview() {
        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.IN_PEER_REVIEW);
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assess-1"))
                .thenReturn(Optional.of(assessment));

        assertThatThrownBy(() -> service.submitForPeerReview("assess-1", "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked for peer review");
    }

    // ── completeReview ────────────────────────────────────────────────────────

    @Test
    void completeReview_SetsAssessmentToNeedsAcceptance() {
        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.IN_PEER_REVIEW);

        when(peerReviewRepository.findById("review-1"))
                .thenReturn(Optional.of(pendingReview));
        when(peerReviewRepository.save(any(PeerReview.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assess-1"))
                .thenReturn(Optional.of(assessment));
        when(assessmentRepository.save(any(Assessment.class))).thenReturn(assessment);

        PeerReviewDto result = service.completeReview("review-1", "user-2");

        assertThat(result.getStatus()).isEqualTo(PeerReviewStatus.COMPLETED);

        ArgumentCaptor<Assessment> assessCaptor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessmentRepository).save(assessCaptor.capture());
        assertThat(assessCaptor.getValue().getPeerReviewStatus())
                .isEqualTo(AssessmentPeerReviewStatus.NEEDS_ACCEPTANCE);
    }

    // ── acceptChanges ─────────────────────────────────────────────────────────

    @Test
    void acceptChanges_AppliesOnlyAcceptedFieldsToLiveAssessment() {
        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.NEEDS_ACCEPTANCE);
        assessment.setActivePeerReviewId("review-2");

        when(peerReviewRepository.findById("review-2"))
                .thenReturn(Optional.of(completedReview));
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assess-1"))
                .thenReturn(Optional.of(assessment));
        when(assessmentRepository.save(any(Assessment.class))).thenReturn(assessment);
        when(peerReviewRepository.findById("review-2"))
                .thenReturn(Optional.of(completedReview));

        AcceptPeerReviewRequest request = AcceptPeerReviewRequest.builder()
                .acceptedAssessmentFieldIds(List.of("field-1"))
                .acceptedVulnerabilityChanges(new HashMap<>())
                .build();

        service.acceptChanges("review-2", request, "user-1");

        ArgumentCaptor<Assessment> assessCaptor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessmentRepository).save(assessCaptor.capture());
        Assessment saved = assessCaptor.getValue();
        assertThat(saved.getFieldValues().get("field-1")).isEqualTo("revised-value");
        assertThat(saved.getPeerReviewStatus()).isEqualTo(AssessmentPeerReviewStatus.COMPLETE);
        assertThat(saved.getActivePeerReviewId()).isNull();
    }

    /**
     * A blank revised value (empty editor state saved during review) must
     * never wipe live content on accept — this bug erased vulnerability
     * descriptions and assessment summaries in production data.
     */
    @Test
    void acceptChanges_blankRevisedValuesNeverWipeLiveContent() {
        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.NEEDS_ACCEPTANCE);
        assessment.setActivePeerReviewId("review-2");
        completedReview.getRevisedFieldValues().put("field-1", "");

        Vulnerability liveVuln = Vulnerability.builder()
                .id("vuln-1")
                .assessmentId("assess-1")
                .name("Vuln")
                .description("<p>original description</p>")
                .recommendation("<p>original recommendation</p>")
                .fieldValues(new HashMap<>())
                .build();
        completedReview.setVulnerabilities(new ArrayList<>(List.of(
                PeerReviewVulnerability.builder()
                        .vulnerabilityId("vuln-1")
                        .name("Vuln")
                        .description("<p>original description</p>")
                        .revisedDescription("")                      // blank — must be ignored
                        .revisedRecommendation("<p>improved</p>")    // real change — must apply
                        .build())));

        when(peerReviewRepository.findById("review-2"))
                .thenReturn(Optional.of(completedReview));
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assess-1"))
                .thenReturn(Optional.of(assessment));
        when(assessmentRepository.save(any(Assessment.class))).thenReturn(assessment);
        when(vulnerabilityRepository.findById("vuln-1")).thenReturn(Optional.of(liveVuln));
        when(vulnerabilityRepository.save(any(Vulnerability.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AcceptPeerReviewRequest request = AcceptPeerReviewRequest.builder()
                .acceptedAssessmentFieldIds(List.of("field-1"))
                .acceptedVulnerabilityChanges(new HashMap<>(Map.of(
                        "vuln-1", List.of("description", "recommendation"))))
                .build();

        service.acceptChanges("review-2", request, "user-1");

        // Blank values ignored; real revisions applied
        assertThat(assessment.getFieldValues().get("field-1")).isEqualTo("original-value");
        assertThat(liveVuln.getDescription()).isEqualTo("<p>original description</p>");
        assertThat(liveVuln.getRecommendation()).isEqualTo("<p>improved</p>");
    }

    // ── rejectReview ──────────────────────────────────────────────────────────

    @Test
    void rejectReview_UnlocksAssessmentAndClearsActiveReviewId() {
        assessment.setPeerReviewStatus(AssessmentPeerReviewStatus.IN_PEER_REVIEW);
        assessment.setActivePeerReviewId("review-1");

        when(peerReviewRepository.findById("review-1"))
                .thenReturn(Optional.of(pendingReview));
        when(assessmentRepository.findByIdAndDeletedAtIsNull("assess-1"))
                .thenReturn(Optional.of(assessment));
        when(assessmentRepository.save(any(Assessment.class))).thenReturn(assessment);
        when(peerReviewRepository.save(any(PeerReview.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.rejectReview("review-1", "user-1");

        ArgumentCaptor<Assessment> assessCaptor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessmentRepository).save(assessCaptor.capture());
        Assessment saved = assessCaptor.getValue();
        assertThat(saved.getPeerReviewStatus()).isEqualTo(AssessmentPeerReviewStatus.IN_PROGRESS);
        assertThat(saved.getActivePeerReviewId()).isNull();
    }
}
