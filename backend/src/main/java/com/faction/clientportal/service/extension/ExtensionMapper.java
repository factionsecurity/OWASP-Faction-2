package com.faction.clientportal.service.extension;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentChecklist;
import com.faction.clientportal.model.ChecklistResponse;
import com.faction.clientportal.model.ChecklistResult;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.elements.CheckList;
import com.faction.elements.CheckListItem;
import com.faction.elements.CustomField;
import com.faction.elements.CustomType;
import com.faction.elements.Verification;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates between Faction's entities and the {@code com.faction.elements} model
 * that extensions are compiled against.
 *
 * <h2>Identifier bridging</h2>
 * The extender API predates Faction 2 and types every identifier as {@code Long};
 * Faction 2 uses String UUIDs. Rather than fork the API — which would strand every
 * already-compiled extension — each UUID is projected onto a {@code long} by taking
 * the leading 64 bits of its SHA-256.
 *
 * <p>The projection is deterministic, so an extension that files a Jira ticket
 * against a finding today and looks it up again next month sees the same number.
 * Because it is a pure function, write-back does not need a lookup table either:
 * matching an edited element back to its row is just recomputing the projection.
 * The sign bit is cleared so ids read as positive, which is what extension authors
 * expect from a database id.
 *
 * <p>Collisions are theoretically possible and practically not: matching is always
 * scoped to the handful of rows in a single assessment, where a birthday collision
 * across 63 bits does not occur.
 *
 * <h2>Field mapping</h2>
 * Faction 1 copied fields with Spring's {@code BeanUtils.copyProperties}. That is not
 * usable here because the two models diverged: {@code likelyhood}/{@code likelihood},
 * {@code opened}/{@code openedAt}, {@code overall}/{@code severity},
 * {@code tracking}/{@code trackingId}. Everything below is mapped explicitly.
 */
@Component
public class ExtensionMapper {

    // ── Identifier projection ────────────────────────────────────────────────

    /** Projects a Faction 2 UUID onto the {@code Long} id the extender API exposes. */
    public static long surrogateId(String uuid) {
        if (uuid == null) return 0L;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(uuid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 8).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Finds the row an extension-edited element refers to. */
    public static <T> T resolve(List<T> candidates, Long surrogate,
                                java.util.function.Function<T, String> idOf) {
        if (surrogate == null) return null;
        return candidates.stream()
                .filter(c -> surrogateId(idOf.apply(c)) == surrogate)
                .findFirst()
                .orElse(null);
    }

    // ── Severity / rating scales ─────────────────────────────────────────────

    /**
     * Faction 1's numeric severity scale, which extensions hard-code against —
     * the bundled bar-chart extension ships
     * {@code "Critical:5,High:4,Medium:3,Low:2"} as its default mapping and
     * compares {@code vuln.getOverall()} to those values directly.
     */
    public static long severityOrdinal(VulnerabilitySeverity severity) {
        if (severity == null) return 0L;
        return switch (severity) {
            case CRITICAL      -> 5L;
            case HIGH          -> 4L;
            case MEDIUM        -> 3L;
            case LOW           -> 2L;
            case INFORMATIONAL -> 0L;
        };
    }

    /**
     * Faction 2 stores likelihood and impact as free text ("High", "3", a custom
     * label). Extensions expect a number on the same scale as severity, so named
     * levels are translated and anything numeric is taken at face value.
     */
    public static long ratingOrdinal(String rating) {
        if (rating == null || rating.isBlank()) return 0L;
        String normalized = rating.trim().toLowerCase();
        return switch (normalized) {
            case "critical"                     -> 5L;
            case "high"                         -> 4L;
            case "medium", "moderate"           -> 3L;
            case "low"                          -> 2L;
            case "recommended"                  -> 1L;
            case "informational", "info", "none" -> 0L;
            default -> {
                try {
                    yield Long.parseLong(normalized);
                } catch (NumberFormatException ignored) {
                    yield 0L;
                }
            }
        };
    }

    // ── Vulnerability ────────────────────────────────────────────────────────

    /**
     * @param devClosedAt when the fix was verified in the default "development" remediation stage,
     *                    or null. Resolved by the caller from the vulnerability's stage completions
     *                    — the extender API's {@code devClosed} predates configurable stages, and
     *                    the mapper stays a pure function (no repository lookups here).
     */
    public com.faction.elements.Vulnerability toElement(Vulnerability vuln, String categoryName,
                                                        java.time.LocalDateTime devClosedAt) {
        com.faction.elements.Vulnerability element = new com.faction.elements.Vulnerability();
        element.setId(surrogateId(vuln.getId()));
        element.setName(vuln.getName());
        element.setDescription(vuln.getDescription());
        element.setRecommendation(vuln.getRecommendation());
        element.setDetails(vuln.getDetails());
        element.setCategory(categoryName);
        element.setSection(vuln.getSection());
        element.setTracking(vuln.getTrackingId());
        element.setCvssScore(vuln.getCvssScore() == null ? null : String.valueOf(vuln.getCvssScore()));
        element.setCvssString(vuln.getCvssString());
        element.setOverall(severityOrdinal(vuln.getSeverity()));
        element.setLikelyhood(ratingOrdinal(vuln.getLikelihood()));
        element.setImpact(ratingOrdinal(vuln.getImpact()));
        element.setOpened(toDate(vuln.getOpenedAt()));
        element.setClosed(toDate(vuln.getClosedAt()));
        element.setDevClosed(toDate(devClosedAt));
        element.setCustomFields(toCustomFields(vuln.getFieldDefinitions(), vuln.getFieldValues()));
        return element;
    }

    /**
     * Copies an extension's edits back onto the entity.
     *
     * <p>Deliberately broader than Faction 1, which copied only description,
     * recommendation, details and custom fields. That omission meant the reference
     * Jira extension's {@code vuln.setTracking(issueKey)} was silently discarded —
     * the tracking id is the entire point of pushing a finding to an issue tracker,
     * so it is written back here.
     *
     * <p>Still deliberately narrow: name, severity, dates and status are Faction's
     * to own, and an extension that could rewrite them could quietly reclassify a
     * finding.
     */
    public void applyTo(com.faction.elements.Vulnerability element, Vulnerability vuln) {
        if (element == null || vuln == null) return;
        if (element.getDescription() != null)    vuln.setDescription(element.getDescription());
        if (element.getRecommendation() != null) vuln.setRecommendation(element.getRecommendation());
        if (element.getDetails() != null)        vuln.setDetails(element.getDetails());
        if (element.getTracking() != null)       vuln.setTrackingId(element.getTracking());
        applyCustomFields(element.getCustomFields(), vuln.getFieldDefinitions(), vuln.getFieldValues());
    }

    // ── Assessment ───────────────────────────────────────────────────────────

    public com.faction.elements.Assessment toElement(Assessment assessment,
                                                     String assessmentTypeName,
                                                     String campaignName,
                                                     List<User> assessors,
                                                     User engagementContact,
                                                     User remediationContact,
                                                     List<AssessmentChecklist> checklists) {
        com.faction.elements.Assessment element = new com.faction.elements.Assessment();
        element.setName(assessment.getName());
        element.setAppId(assessment.getApplicationId());
        element.setStatus(assessment.getStatus());
        element.setType(assessmentTypeName);
        element.setCampaign(campaignName);
        element.setStart(toDate(assessment.getStartDate()));
        element.setEnd(toDate(assessment.getPlannedEndDate()));
        element.setCompleted(toDate(assessment.getCompletedDate()));
        element.setAccessNotes(assessment.getScope());
        element.setCustomFields(toCustomFields(assessment.getFieldDefinitions(), assessment.getFieldValues()));
        element.setChecklists(toChecklists(checklists));

        List<com.faction.elements.User> mappedAssessors = new ArrayList<>();
        if (assessors != null) {
            assessors.stream().filter(java.util.Objects::nonNull).forEach(u -> mappedAssessors.add(toElement(u)));
        }
        element.setAssessors(mappedAssessors);
        element.setEngagementContact(toElement(engagementContact));
        element.setRemediationContact(toElement(remediationContact));

        // Faction 1 had dedicated summary / riskAnalysis columns. Faction 2 models
        // both as user-defined fields, so surface them by the conventional variable
        // names while still exposing every field through getCustomFields().
        element.setSummary(fieldValueByVariable(assessment, "summary", "executive_summary"));
        element.setRiskAnalysis(fieldValueByVariable(assessment, "riskAnalysis", "risk_analysis"));
        return element;
    }

    /** Mirrors {@link #applyTo(com.faction.elements.Vulnerability, Vulnerability)} for assessments. */
    public void applyTo(com.faction.elements.Assessment element, Assessment assessment) {
        if (element == null || assessment == null) return;
        applyCustomFields(element.getCustomFields(),
                assessment.getFieldDefinitions(), assessment.getFieldValues());
    }

    private String fieldValueByVariable(Assessment assessment, String... variableNames) {
        if (assessment.getFieldDefinitions() == null) return null;
        for (String wanted : variableNames) {
            for (UserDefinedField field : assessment.getFieldDefinitions()) {
                if (wanted.equalsIgnoreCase(field.getVariableName())) {
                    return valueOf(field, assessment.getFieldValues());
                }
            }
        }
        return null;
    }

    // ── User ─────────────────────────────────────────────────────────────────

    public com.faction.elements.User toElement(User user) {
        if (user == null) return null;
        com.faction.elements.User element = new com.faction.elements.User();
        element.setFname(user.getFirstName());
        element.setLname(user.getLastName());
        element.setEmail(user.getEmail());
        element.setUsername(user.getUsername());
        return element;
    }

    // ── Verification (Faction 2 calls these Retests) ─────────────────────────

    public Verification toElement(Retest retest, com.faction.elements.Assessment assessment,
                                  User assessor) {
        Verification element = new Verification();
        element.setId(surrogateId(retest.getId()));
        element.setAssessment(assessment);
        element.setStart(toDate(retest.getScheduledStartDate()));
        element.setEnd(toDate(retest.getScheduledEndDate()));
        element.setCompleted(toDate(retest.getClosedDate()));
        element.setNotes(retest.getComment());
        element.setWorkflowStatus(retest.getStatus());
        element.setAssessor(toElement(assessor));
        return element;
    }

    // ── Checklists ───────────────────────────────────────────────────────────

    public List<CheckList> toChecklists(List<AssessmentChecklist> checklists) {
        List<CheckList> mapped = new ArrayList<>();
        if (checklists == null) return mapped;

        for (AssessmentChecklist checklist : checklists) {
            CheckList element = new CheckList();
            element.setName(checklist.getTemplateName());
            List<CheckListItem> items = new ArrayList<>();
            if (checklist.getResponses() != null) {
                for (ChecklistResponse response : checklist.getResponses()) {
                    CheckListItem item = new CheckListItem();
                    item.setQuestion(response.getQuestionText());
                    item.setNotes(response.getComment());
                    item.setAnswer(toAnswer(response.getResult()));
                    items.add(item);
                }
            }
            element.setCheckListItems(items);
            mapped.add(element);
        }
        return mapped;
    }

    private CheckListItem.Answer toAnswer(ChecklistResult result) {
        if (result == null) return CheckListItem.Answer.Incomplete;
        return switch (result) {
            case PASS -> CheckListItem.Answer.Pass;
            case FAIL -> CheckListItem.Answer.Fail;
            case NA   -> CheckListItem.Answer.NA;
        };
    }

    // ── Custom fields ────────────────────────────────────────────────────────

    /**
     * Projects user-defined fields onto the extender's CustomField model.
     *
     * <p>{@code CustomType.key} carries the field's display name and
     * {@code CustomType.variable} its template variable. That split matters: the
     * reference Jira extension selects its target project with
     * {@code field.getType().getKey().equals("Jira Project")}, so the human-facing
     * label — not the variable name — has to land in {@code key}.
     */
    public List<CustomField> toCustomFields(List<UserDefinedField> definitions,
                                            Map<String, String> values) {
        List<CustomField> fields = new ArrayList<>();
        if (definitions == null) return fields;

        for (UserDefinedField definition : definitions) {
            // CustomField and CustomType expose no id setter, so identity travels
            // in the variable name — see applyCustomFields.
            CustomType type = new CustomType();
            type.setKey(definition.getDisplayName());
            type.setVariable(definition.getVariableName());
            type.setType(definition.getFieldType() == null
                    ? FieldType.STRING.ordinal() : definition.getFieldType().ordinal());

            CustomField field = new CustomField();
            field.setType(type);
            field.setValue(valueOf(definition, values));
            fields.add(field);
        }
        return fields;
    }

    /**
     * Writes edited custom-field values back into the entity's value map.
     *
     * <p>Matched on the field's variable name rather than its id. {@code CustomField}
     * and {@code CustomType} are read-only in the id: neither declares a setter, so
     * a mapped field's {@code getId()} is always null. Faction 1 nonetheless matched
     * on {@code getId()}, which meant no custom-field edit an extension made was
     * ever persisted. The variable name is set, unique within an assessment, and is
     * what extension authors already key on.
     */
    public void applyCustomFields(List<CustomField> edited,
                                  List<UserDefinedField> definitions,
                                  Map<String, String> values) {
        if (edited == null || definitions == null || values == null) return;

        Map<String, UserDefinedField> byVariable = new LinkedHashMap<>();
        definitions.stream()
                .filter(d -> d.getVariableName() != null)
                .forEach(d -> byVariable.put(d.getVariableName(), d));

        for (CustomField field : edited) {
            if (field == null || field.getType() == null) continue;
            UserDefinedField definition = byVariable.get(field.getType().getVariable());
            if (definition == null || field.getValue() == null) continue;
            // Store under whichever key the entity already uses, so this does not
            // create a duplicate entry keyed differently from the editor's.
            String key = values.containsKey(definition.getVariableName())
                    ? definition.getVariableName() : definition.getId();
            values.put(key, field.getValue());
        }
    }

    /** Values are keyed by field id in some editors and by variable name in others. */
    private String valueOf(UserDefinedField definition, Map<String, String> values) {
        if (values == null) return definition.getDefaultValue();
        String value = values.getOrDefault(definition.getId(),
                values.getOrDefault(definition.getVariableName(), null));
        return (value == null || value.isEmpty()) ? definition.getDefaultValue() : value;
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private Date toDate(LocalDateTime dateTime) {
        return dateTime == null ? null
                : Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
