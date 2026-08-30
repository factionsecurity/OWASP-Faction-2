package com.faction.clientportal.dto;

import com.faction.clientportal.model.PeerReview;
import com.faction.clientportal.model.PeerReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeerReviewDto {

    private String id;
    private String assessmentId;
    private String assessmentName;

    @Builder.Default
    private Map<String, String> snapshotFieldValues = new HashMap<>();
    @Builder.Default
    private Map<String, String> revisedFieldValues = new HashMap<>();
    @Builder.Default
    private Map<String, String> fieldNotes = new HashMap<>();

    @Builder.Default
    private List<PeerReviewVulnerabilityDto> vulnerabilities = new ArrayList<>();

    private String submittedByUserId;
    private String submittedByName;
    private String reviewedByUserId;
    private String reviewedByName;

    /** Everyone who has worked this review, and their display names in the same order. */
    @Builder.Default
    private List<String> reviewerUserIds = new ArrayList<>();
    @Builder.Default
    private List<String> reviewerNames = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private PeerReviewStatus status;

    public static PeerReviewDto fromEntity(PeerReview entity) {
        if (entity == null) return null;
        return PeerReviewDto.builder()
                .id(entity.getId())
                .assessmentId(entity.getAssessmentId())
                .snapshotFieldValues(entity.getSnapshotFieldValues() != null
                        ? new HashMap<>(entity.getSnapshotFieldValues()) : new HashMap<>())
                .revisedFieldValues(entity.getRevisedFieldValues() != null
                        ? new HashMap<>(entity.getRevisedFieldValues()) : new HashMap<>())
                .fieldNotes(entity.getFieldNotes() != null
                        ? new HashMap<>(entity.getFieldNotes()) : new HashMap<>())
                .vulnerabilities(entity.getVulnerabilities() != null
                        ? entity.getVulnerabilities().stream()
                            .map(PeerReviewVulnerabilityDto::fromEntity)
                            .collect(Collectors.toList())
                        : new ArrayList<>())
                .submittedByUserId(entity.getSubmittedByUserId())
                .reviewedByUserId(entity.getReviewedByUserId())
                .reviewerUserIds(entity.getReviewerUserIds() != null
                        ? new ArrayList<>(entity.getReviewerUserIds()) : new ArrayList<>())
                .createdAt(entity.getCreatedAt())
                .completedAt(entity.getCompletedAt())
                .status(entity.getStatus())
                .build();
    }
}
