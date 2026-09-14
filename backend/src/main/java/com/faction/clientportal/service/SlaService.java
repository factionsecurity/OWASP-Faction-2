package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflowConfig.VulnerabilitySla;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The only code that sets a finding's stored SLA dates, {@link Vulnerability#getDueAt()} and
 * {@link Vulnerability#getWarningAt()}.
 *
 * <p>Every write that changes an input — opening, closing, reopening, deleting, a severity change,
 * an exception status or expiry change — calls {@link #refresh} immediately before saving, so the
 * stored dates never lag the row they describe. The rules mirror what the readers compute today:
 * due is {@code openedAt + pastDueDays}; the warning window opens {@code warningDays} before that;
 * a finding under exception whose expiry is later than that is due at the expiry instead.
 */
@Service
@RequiredArgsConstructor
public class SlaService {

    /** The vulnerability status under which an exception expiry can extend the due date. */
    static final String EXCEPTION_STATUS = "Exception";

    private final AssessmentWorkflowConfigService workflowConfigService;

    /** The configured SLAs keyed by severity. Build once, then apply to many findings. */
    public record SlaPolicy(Map<VulnerabilitySeverity, VulnerabilitySla> bySeverity) {

        public SlaPolicy {
            bySeverity = Map.copyOf(bySeverity);
        }

        /**
         * Severity names are matched trimmed and upper-cased, as the configuration UI stores them
         * loosely. Null, blank and unknown severities are skipped; for a repeated severity the first
         * entry wins.
         */
        public static SlaPolicy of(List<VulnerabilitySla> slas) {
            Map<VulnerabilitySeverity, VulnerabilitySla> map = new EnumMap<>(VulnerabilitySeverity.class);
            if (slas != null) {
                for (VulnerabilitySla sla : slas) {
                    VulnerabilitySeverity severity = parse(sla);
                    if (severity != null) {
                        map.putIfAbsent(severity, sla);
                    }
                }
            }
            return new SlaPolicy(map);
        }

        private static VulnerabilitySeverity parse(VulnerabilitySla sla) {
            if (sla == null || sla.getSeverity() == null || sla.getSeverity().isBlank()) {
                return null;
            }
            try {
                return VulnerabilitySeverity.valueOf(sla.getSeverity().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The policy from the current workflow configuration. */
    public SlaPolicy policy() {
        return SlaPolicy.of(workflowConfigService.getConfig().getVulnerabilitySlas());
    }

    /** Sets {@code dueAt} and {@code warningAt} on {@code v} from {@code policy}. Does not save. */
    public static void apply(Vulnerability v, SlaPolicy policy) {
        VulnerabilitySla sla = v.getSeverity() == null ? null : policy.bySeverity().get(v.getSeverity());
        if (v.getOpenedAt() == null || v.getClosedAt() != null || v.getDeletedAt() != null || sla == null) {
            v.setDueAt(null);
            v.setWarningAt(null);
            return;
        }
        LocalDateTime base = v.getOpenedAt().plusDays(sla.getPastDueDays());
        v.setWarningAt(base.minusDays(sla.getWarningDays()));
        LocalDateTime expiry = v.getExceptionExpiryDate();
        boolean extendedByException = EXCEPTION_STATUS.equals(v.getStatus())
                && expiry != null && expiry.isAfter(base);
        v.setDueAt(extendedByException ? expiry : base);
    }

    /** Recomputes one finding's dates from the current configuration. Does not save. */
    public void refresh(Vulnerability v) {
        if (v == null) {
            return;
        }
        apply(v, policy());
    }

    /** Recomputes many findings' dates, reading the configuration once. Does not save. */
    public void refreshAll(Collection<Vulnerability> vulnerabilities) {
        if (vulnerabilities == null || vulnerabilities.isEmpty()) {
            return;
        }
        SlaPolicy policy = policy();
        for (Vulnerability v : vulnerabilities) {
            if (v != null) {
                apply(v, policy);
            }
        }
    }
}
