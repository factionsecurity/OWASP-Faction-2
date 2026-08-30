package com.faction.clientportal.service;

import com.faction.clientportal.dto.RetestActivitySummaryDto;
import com.faction.clientportal.dto.RetestCompletionLogDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The retest activity log: which retests were verified in a window, by whom, and with what
 * verdict — the record behind "how many retests passed and failed this week".
 *
 * <p>Reads the retests themselves. A completed retest already carries its own completion record
 * ({@code closedDate} and {@code completedBy}, both written only at completion), so a parallel
 * log table would be a second copy of the same fact, free to drift from it.
 */
@Service
@RequiredArgsConstructor
public class RetestActivityLogService {

    /** The terminal statuses that count as a completion. CANCELLED is not a verdict. */
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    private static final Set<String> COMPLETED = Set.of(PASSED, FAILED);

    private final RetestRepository retestRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final AssessmentRepository assessmentRepository;
    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    /**
     * One page of completions in {@code [from, to]}, enriched with the finding, application and
     * organization names and the verifier's display name — batched, no per-row fan-out.
     *
     * @param result optional PASS/FAIL narrowing; null lists both
     */
    public Page<RetestCompletionLogDto> list(LocalDateTime from, LocalDateTime to,
                                             String result, Pageable pageable) {
        Page<Retest> page = retestRepository.findByStatusInAndClosedDateBetweenAndDeletedAtIsNull(
                statusesFor(result), from, to, pageable);
        return new PageImpl<>(enrich(page.getContent()), pageable, page.getTotalElements());
    }

    /** Pass/fail totals for the same window, counted in the database rather than from a page. */
    public RetestActivitySummaryDto summary(LocalDateTime from, LocalDateTime to) {
        long passed = retestRepository.countByStatusAndClosedDateBetweenAndDeletedAtIsNull(PASSED, from, to);
        long failed = retestRepository.countByStatusAndClosedDateBetweenAndDeletedAtIsNull(FAILED, from, to);
        return RetestActivitySummaryDto.builder()
                .passed(passed)
                .failed(failed)
                .total(passed + failed)
                .build();
    }

    /** The statuses a {@code result} filter selects; both when it is absent or unrecognised. */
    private static Set<String> statusesFor(String result) {
        if (result == null || result.isBlank()) return COMPLETED;
        return switch (result.trim().toUpperCase()) {
            case "PASS", PASSED -> Set.of(PASSED);
            case "FAIL", FAILED -> Set.of(FAILED);
            default -> COMPLETED;
        };
    }

    private List<RetestCompletionLogDto> enrich(List<Retest> retests) {
        if (retests.isEmpty()) return List.of();

        Map<String, Vulnerability> vulns = byId(vulnerabilityRepository.findAllById(
                ids(retests.stream().map(Retest::getVulnerabilityId))), Vulnerability::getId);
        Map<String, Assessment> assessments = byId(assessmentRepository.findAllById(
                ids(retests.stream().map(Retest::getAssessmentId))), Assessment::getId);

        // Applications come from the assessment rather than the retest's own applicationId: the
        // assessment is what the finding actually hangs off, and older retests may not carry one.
        Map<String, Application> applications = byId(applicationRepository.findAllById(
                ids(assessments.values().stream().map(Assessment::getApplicationId))), Application::getId);
        Map<String, Organization> organizations = byId(organizationRepository.findAllById(
                ids(assessments.values().stream().map(Assessment::getOrganizationId))), Organization::getId);
        // completedBy is a username (the JWT subject), like createdBy — not a user id.
        List<String> verifiers = ids(retests.stream().map(Retest::getCompletedBy));
        Map<String, User> users = verifiers.isEmpty() ? Map.of()
                : byId(userRepository.findByUsernameIn(verifiers), User::getUsername);

        return retests.stream().map(r -> {
            Vulnerability vuln = vulns.get(r.getVulnerabilityId());
            Assessment assessment = assessments.get(r.getAssessmentId());
            Application application = assessment == null ? null
                    : applications.get(assessment.getApplicationId());
            Organization organization = assessment == null ? null
                    : organizations.get(assessment.getOrganizationId());
            User completedBy = users.get(r.getCompletedBy());

            return RetestCompletionLogDto.builder()
                    .retestId(r.getId())
                    .status(r.getStatus())
                    .result(r.getResult())
                    .completedAt(r.getClosedDate())
                    .completedBy(r.getCompletedBy())
                    .completedByName(completedBy == null ? null : displayName(completedBy))
                    .vulnerabilityId(r.getVulnerabilityId())
                    .vulnerabilityName(vuln == null ? null : vuln.getName())
                    .severity(vuln == null || vuln.getSeverity() == null ? null : vuln.getSeverity().name())
                    .assessmentId(r.getAssessmentId())
                    .assessmentName(assessment == null ? null : assessment.getName())
                    .applicationId(application == null ? null : application.getId())
                    .applicationName(application == null ? null : application.getName())
                    .organizationId(organization == null ? null : organization.getId())
                    .organizationName(organization == null ? null : organization.getName())
                    .comment(r.getComment())
                    .build();
        }).toList();
    }

    private static List<String> ids(java.util.stream.Stream<String> stream) {
        return stream.filter(Objects::nonNull).distinct().toList();
    }

    /** Index entities by the given key, first-wins on a duplicate. */
    private static <T> Map<String, T> byId(Iterable<T> entities, java.util.function.Function<T, String> id) {
        return java.util.stream.StreamSupport.stream(entities.spliterator(), false)
                .collect(Collectors.toMap(id, e -> e, (a, b) -> a));
    }

    private static String displayName(User user) {
        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }
}
