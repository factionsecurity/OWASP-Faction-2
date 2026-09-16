package com.faction.clientportal.service.extension;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentChecklist;
import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.ChecklistResponse;
import com.faction.clientportal.model.ChecklistResult;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.elements.CheckList;
import com.faction.elements.CheckListItem;
import com.faction.elements.CustomField;
import com.faction.elements.CustomType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ExtensionMapper}: the bridge between Faction 2's entities and the
 * {@code com.faction.elements} model extensions are compiled against.
 */
class ExtensionMapperTest {

    private final ExtensionMapper mapper = new ExtensionMapper();

    // ── Identifier projection ────────────────────────────────────────────────

    @Test
    void surrogateIdsAreStableDistinctAndPositive() {
        String uuid = UUID.randomUUID().toString();

        // Stable: an extension that stored this id externally must still match later.
        assertThat(ExtensionMapper.surrogateId(uuid)).isEqualTo(ExtensionMapper.surrogateId(uuid));
        assertThat(ExtensionMapper.surrogateId(uuid))
                .isNotEqualTo(ExtensionMapper.surrogateId(UUID.randomUUID().toString()));
        // Extension authors expect a database id to read as positive.
        assertThat(ExtensionMapper.surrogateId(uuid)).isNotNegative();
        assertThat(ExtensionMapper.surrogateId(null)).isZero();
    }

    @Test
    void resolveFindsTheRowAnEditedElementRefersTo() {
        Vulnerability a = vulnerability("First");
        Vulnerability b = vulnerability("Second");

        Vulnerability found = ExtensionMapper.resolve(
                List.of(a, b), ExtensionMapper.surrogateId(b.getId()), Vulnerability::getId);

        assertThat(found).isSameAs(b);
        assertThat(ExtensionMapper.resolve(List.of(a, b), 12345L, Vulnerability::getId)).isNull();
    }

    // ── Severity and rating scales ───────────────────────────────────────────

    @Test
    void severityUsesFactionOnesNumericScale() {
        // The bundled bar-chart extension ships "Critical:5,High:4,Medium:3,Low:2"
        // as its default mapping and compares getOverall() to those values.
        assertThat(ExtensionMapper.severityOrdinal(VulnerabilitySeverity.CRITICAL)).isEqualTo(5L);
        assertThat(ExtensionMapper.severityOrdinal(VulnerabilitySeverity.HIGH)).isEqualTo(4L);
        assertThat(ExtensionMapper.severityOrdinal(VulnerabilitySeverity.MEDIUM)).isEqualTo(3L);
        assertThat(ExtensionMapper.severityOrdinal(VulnerabilitySeverity.LOW)).isEqualTo(2L);
        assertThat(ExtensionMapper.severityOrdinal(VulnerabilitySeverity.INFORMATIONAL)).isZero();
        assertThat(ExtensionMapper.severityOrdinal(null)).isZero();
    }

    @Test
    void freeTextRatingsAreTranslatedOntoTheSameScale() {
        assertThat(ExtensionMapper.ratingOrdinal("High")).isEqualTo(4L);
        assertThat(ExtensionMapper.ratingOrdinal("  medium  ")).isEqualTo(3L);
        assertThat(ExtensionMapper.ratingOrdinal("Moderate")).isEqualTo(3L);
        assertThat(ExtensionMapper.ratingOrdinal("4")).isEqualTo(4L);
        // Unknown custom labels degrade to 0 rather than throwing mid-report.
        assertThat(ExtensionMapper.ratingOrdinal("Spicy")).isZero();
        assertThat(ExtensionMapper.ratingOrdinal(null)).isZero();
    }

    @Test
    void overallIsNeverNull() {
        // The bar-chart extension calls v.getOverall().equals(id) without a null check,
        // so a null here would NPE inside third-party code during report generation.
        Vulnerability vuln = vulnerability("No severity");
        vuln.setSeverity(null);

        assertThat(mapper.toElement(vuln, null, null).getOverall()).isNotNull();
    }

    // ── Vulnerability mapping ────────────────────────────────────────────────

    @Test
    void mapsVulnerabilityFieldsAcrossTheRenamedProperties() {
        Vulnerability vuln = vulnerability("SQL Injection");
        vuln.setSeverity(VulnerabilitySeverity.CRITICAL);
        vuln.setLikelihood("High");
        vuln.setImpact("Medium");
        vuln.setTrackingId("KAN-42");
        vuln.setCvssScore(9.8);
        vuln.setCvssString("CVSS:3.1/AV:N");
        vuln.setSection("Web");
        vuln.setDescription("<p>desc</p>");
        vuln.setRecommendation("<p>fix</p>");
        vuln.setDetails("<p>details</p>");
        vuln.setOpenedAt(LocalDateTime.of(2026, 1, 2, 3, 4));

        com.faction.elements.Vulnerability element = mapper.toElement(vuln, "Injection", LocalDateTime.of(2026, 2, 1, 0, 0));

        assertThat(element.getName()).isEqualTo("SQL Injection");
        assertThat(element.getCategory()).isEqualTo("Injection");
        assertThat(element.getOverall()).isEqualTo(5L);
        assertThat(element.getLikelyhood()).isEqualTo(4L);   // likelihood -> likelyhood
        assertThat(element.getImpact()).isEqualTo(3L);
        assertThat(element.getTracking()).isEqualTo("KAN-42"); // trackingId -> tracking
        assertThat(element.getCvssScore()).isEqualTo("9.8");
        assertThat(element.getSection()).isEqualTo("Web");
        assertThat(element.getOpened()).isNotNull();          // openedAt -> opened
        assertThat(element.getDevClosed()).isNotNull();       // caller-resolved stage completion -> devClosed
        assertThat(element.getId()).isEqualTo(ExtensionMapper.surrogateId(vuln.getId()));
    }

    @Test
    void trackingIdIsWrittenBack() {
        // The reference Jira extension's whole purpose: it calls setTracking(issueKey)
        // and expects the key to land on the finding. Faction 1 dropped it.
        Vulnerability vuln = vulnerability("Finding");
        com.faction.elements.Vulnerability element = mapper.toElement(vuln, null, null);

        element.setTracking("KAN-101");
        element.setDescription("rewritten");
        mapper.applyTo(element, vuln);

        assertThat(vuln.getTrackingId()).isEqualTo("KAN-101");
        assertThat(vuln.getDescription()).isEqualTo("rewritten");
    }

    @Test
    void writeBackLeavesFactionOwnedFieldsAlone() {
        Vulnerability vuln = vulnerability("Original name");
        vuln.setSeverity(VulnerabilitySeverity.LOW);
        com.faction.elements.Vulnerability element = mapper.toElement(vuln, null, null);

        element.setName("Renamed by extension");
        element.setOverall(5L);
        mapper.applyTo(element, vuln);

        // An extension that could rewrite these could quietly reclassify a finding.
        assertThat(vuln.getName()).isEqualTo("Original name");
        assertThat(vuln.getSeverity()).isEqualTo(VulnerabilitySeverity.LOW);
    }

    // ── Custom fields ────────────────────────────────────────────────────────

    @Test
    void customTypeKeyCarriesTheDisplayNameAndVariableTheVariableName() {
        // The reference Jira extension selects its project with
        // field.getType().getKey().equals("Jira Project") — the label, not the variable.
        UserDefinedField definition = UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .displayName("Jira Project")
                .variableName("jira_project")
                .fieldType(FieldType.STRING)
                .build();
        Map<String, String> values = new HashMap<>(Map.of("jira_project", "KAN"));

        List<CustomField> fields = mapper.toCustomFields(List.of(definition), values);

        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).getType().getKey()).isEqualTo("Jira Project");
        assertThat(fields.get(0).getType().getVariable()).isEqualTo("jira_project");
        assertThat(fields.get(0).getValue()).isEqualTo("KAN");
    }

    @Test
    void customFieldValuesAreWrittenBackByVariableName() {
        // CustomField exposes no id setter, so identity has to travel in the variable
        // name — matching on id (as Faction 1 did) never resolves.
        UserDefinedField definition = UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .displayName("Jira Project")
                .variableName("jira_project")
                .fieldType(FieldType.STRING)
                .build();
        Map<String, String> values = new HashMap<>(Map.of("jira_project", "KAN"));

        List<CustomField> fields = mapper.toCustomFields(List.of(definition), values);
        fields.get(0).setValue("OPS");
        mapper.applyCustomFields(fields, List.of(definition), values);

        assertThat(values).containsEntry("jira_project", "OPS");
    }

    @Test
    void fieldValuesKeyedByIdAreReadAndWrittenUnderThatSameKey() {
        // Assessment editors key values by field id; the vulnerability editor keys them
        // by variable name. Writing back under the wrong one would duplicate the entry.
        String fieldId = UUID.randomUUID().toString();
        UserDefinedField definition = UserDefinedField.builder()
                .id(fieldId)
                .displayName("Jira Project")
                .variableName("jira_project")
                .fieldType(FieldType.STRING)
                .build();
        Map<String, String> values = new HashMap<>(Map.of(fieldId, "KAN"));

        List<CustomField> fields = mapper.toCustomFields(List.of(definition), values);
        assertThat(fields.get(0).getValue()).isEqualTo("KAN");

        fields.get(0).setValue("OPS");
        mapper.applyCustomFields(fields, List.of(definition), values);

        assertThat(values).containsEntry(fieldId, "OPS");
        assertThat(values).doesNotContainKey("jira_project");
    }

    @Test
    void emptyValueFallsBackToTheDeclaredDefault() {
        UserDefinedField definition = UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .displayName("Region")
                .variableName("region")
                .fieldType(FieldType.STRING)
                .defaultValue("EMEA")
                .build();

        List<CustomField> fields = mapper.toCustomFields(List.of(definition), new HashMap<>());

        assertThat(fields.get(0).getValue()).isEqualTo("EMEA");
    }

    // ── Checklists ───────────────────────────────────────────────────────────

    @Test
    void checklistAnswersMapAcrossIncludingTheUnansweredCase() {
        AssessmentChecklist checklist = new AssessmentChecklist();
        checklist.setTemplateName("OWASP Top 10");
        checklist.setResponses(List.of(
                response("Injection tested?", ChecklistResult.PASS, "ok"),
                response("XSS tested?", ChecklistResult.FAIL, null),
                response("N/A item", ChecklistResult.NA, null),
                response("Not answered", null, null)));

        List<CheckList> mapped = mapper.toChecklists(List.of(checklist));

        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).getName()).isEqualTo("OWASP Top 10");
        assertThat(mapped.get(0).getCheckListItems())
                .extracting(CheckListItem::getAnswer)
                .containsExactly(CheckListItem.Answer.Pass, CheckListItem.Answer.Fail,
                                 CheckListItem.Answer.NA, CheckListItem.Answer.Incomplete);
        assertThat(mapped.get(0).getCheckListItems().get(0).getNotes()).isEqualTo("ok");
        assertThat(mapper.toChecklists(null)).isEmpty();
    }

    @Test
    void checklistNameIsTheTemplateNameTheTokenIsDerivedFrom() {
        // The checklist extension builds its placeholder by lowercasing the checklist
        // name and replacing spaces with dashes, then matching ${checklist-<that>}.
        // So the name carried across has to be the operator-facing template name —
        // anything else silently produces a token nobody can guess.
        AssessmentChecklist checklist = new AssessmentChecklist();
        checklist.setTemplateName("OWASP Top 10");
        checklist.setResponses(List.of(response("Injection tested?", ChecklistResult.PASS, null)));

        String name = mapper.toChecklists(List.of(checklist)).get(0).getName();

        assertThat(name).isEqualTo("OWASP Top 10");
        assertThat("${checklist-" + name.toLowerCase().replace(" ", "-") + "}")
                .isEqualTo("${checklist-owasp-top-10}");
    }

    // ── Assessment ───────────────────────────────────────────────────────────

    @Test
    void mapsAssessmentIncludingContactsAndCustomFields() {
        UserDefinedField summaryField = UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .displayName("Executive Summary")
                .variableName("executive_summary")
                .fieldType(FieldType.RICH_TEXT)
                .build();

        Assessment assessment = new Assessment();
        assessment.setId(UUID.randomUUID().toString());
        assessment.setName("Q1 Pentest");
        assessment.setApplicationId("app-1");
        assessment.setStatus("Testing");
        assessment.setFieldDefinitions(List.of(summaryField));
        assessment.setFieldValues(new HashMap<>(Map.of("executive_summary", "<p>All good</p>")));

        com.faction.elements.Assessment element = mapper.toElement(
                assessment, "Web App Test", "Spring Campaign",
                List.of(user("Ada", "Lovelace")), user("Eng", "Contact"),
                user("Rem", "Contact"), List.of(), "Default Workflow");

        assertThat(element.getName()).isEqualTo("Q1 Pentest");
        assertThat(element.getType()).isEqualTo("Web App Test");
        assertThat(element.getCampaign()).isEqualTo("Spring Campaign");
        assertThat(element.getAppId()).isEqualTo("app-1");
        assertThat(element.getAssessors()).hasSize(1);
        assertThat(element.getAssessors().get(0).getFname()).isEqualTo("Ada");
        assertThat(element.getEngagementContact().getFname()).isEqualTo("Eng");
        assertThat(element.getRemediationContact().getFname()).isEqualTo("Rem");
        // The assessment's own UDF plus the two synthetic workflow fields appended after it.
        assertThat(element.getCustomFields()).hasSize(3);
        // Faction 1 had a dedicated summary column; here it comes from a named UDF.
        assertThat(element.getSummary()).isEqualTo("<p>All good</p>");
    }

    @Test
    void nullContactsMapToNullRatherThanThrowing() {
        Assessment assessment = new Assessment();
        assessment.setId(UUID.randomUUID().toString());
        assessment.setName("Minimal");

        com.faction.elements.Assessment element =
                mapper.toElement(assessment, null, null, null, null, null, null, null);

        assertThat(element.getEngagementContact()).isNull();
        assertThat(element.getRemediationContact()).isNull();
        assertThat(element.getAssessors()).isEmpty();
        assertThat(element.getChecklists()).isEmpty();
    }

    @Test
    void anAssessmentElementSaysWhichWorkflowItsStatusBelongsTo() {
        Assessment assessment = Assessment.builder()
                .id("a1").name("Q3 Pentest").applicationId("app").assessmentTypeId("t")
                .workflowId("pci").status("Signed Off")
                .build();

        com.faction.elements.Assessment element =
                mapper.toElement(assessment, "Web App", null, List.of(), null, null, List.of(), "PCI");

        assertThat(element.getStatus()).isEqualTo("Signed Off");
        assertThat(customFieldValue(element, "workflowId")).isEqualTo("pci");
        assertThat(customFieldValue(element, "workflowName")).isEqualTo("PCI");
    }

    @Test
    void anAssessmentOnNoNamedWorkflowStillSaysWhichIdItIsOn() {
        Assessment assessment = Assessment.builder()
                .id("a2").name("Legacy").applicationId("app").assessmentTypeId("t")
                .status("Testing")
                .build();

        com.faction.elements.Assessment element =
                mapper.toElement(assessment, "Web App", null, List.of(), null, null, List.of(), null);

        assertThat(customFieldValue(element, "workflowId")).isEqualTo(AssessmentWorkflow.DEFAULT_ID);
        assertThat(customFieldValue(element, "workflowName")).isEmpty();
    }

    @Test
    void aUserDefinedFieldNamedLikeASyntheticFieldDoesNotShadowIt() {
        // Nothing stops an admin from naming a UDF "workflowId". An extension's natural lookup is
        // filter-then-findFirst by variable name, so whichever entry comes first wins.
        UserDefinedField colliding = UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .displayName("Workflow Id (custom)")
                .variableName("workflowId")
                .fieldType(FieldType.STRING)
                .build();

        Assessment assessment = Assessment.builder()
                .id("a3").name("Collision").applicationId("app").assessmentTypeId("t")
                .workflowId("pci").status("Testing")
                .fieldDefinitions(List.of(colliding))
                .fieldValues(new HashMap<>(Map.of("workflowId", "not-the-real-workflow")))
                .build();

        com.faction.elements.Assessment element =
                mapper.toElement(assessment, "Web App", null, List.of(), null, null, List.of(), "PCI");

        // The synthetic field comes first, so the natural lookup gets the real workflow id.
        assertThat(customFieldValue(element, "workflowId")).isEqualTo("pci");
        // Nothing is dropped: the colliding user-defined field is still present in the list.
        assertThat(element.getCustomFields())
                .filteredOn(field -> field.getType() != null && "workflowId".equals(field.getType().getVariable()))
                .extracting(CustomField::getValue)
                .containsExactly("pci", "not-the-real-workflow");
    }

    @Test
    void syntheticWorkflowValuesAreNeverWrittenBackOntoACollidingUserDefinedField() {
        // Same collision as above, but this time the round trip goes all the way through
        // applyTo — an ExtensionManager extension that edits nothing else. The colliding UDF
        // is unset, so if the synthetic "workflowId" field is allowed to reach
        // applyCustomFields, applyCustomFields treats its non-null value ("pci") as an edit
        // and writes it into the UDF's stored value.
        UserDefinedField colliding = UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .displayName("Workflow Id (custom)")
                .variableName("workflowId")
                .fieldType(FieldType.STRING)
                .build();

        Assessment assessment = Assessment.builder()
                .id("a4").name("Round trip").applicationId("app").assessmentTypeId("t")
                .workflowId("pci").status("Testing")
                .fieldDefinitions(List.of(colliding))
                .fieldValues(new HashMap<>())
                .build();

        com.faction.elements.Assessment element =
                mapper.toElement(assessment, "Web App", null, List.of(), null, null, List.of(), "PCI");

        mapper.applyTo(element, assessment);

        // Unset, and stays unset: the synthetic value must not be adopted under any key —
        // neither "workflowId" (the variable-name key) nor the definition's own id (the
        // key applyCustomFields actually falls back to when the value map has no entry yet).
        assertThat(assessment.getFieldValues()).isEmpty();
    }

    @Test
    void aNonCollidingUserDefinedFieldEditIsStillWrittenBackByApplyTo() {
        // The guard above must not be broad enough to swallow ordinary field edits.
        UserDefinedField ticket = UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .displayName("Ticket")
                .variableName("ticket")
                .fieldType(FieldType.STRING)
                .build();

        Assessment assessment = Assessment.builder()
                .id("a5").name("Round trip").applicationId("app").assessmentTypeId("t")
                .workflowId("pci").status("Testing")
                .fieldDefinitions(List.of(ticket))
                .fieldValues(new HashMap<>(Map.of("ticket", "KAN-1")))
                .build();

        com.faction.elements.Assessment element =
                mapper.toElement(assessment, "Web App", null, List.of(), null, null, List.of(), "PCI");
        element.getCustomFields().stream()
                .filter(field -> field.getType() != null && "ticket".equals(field.getType().getVariable()))
                .findFirst().orElseThrow()
                .setValue("KAN-2");

        mapper.applyTo(element, assessment);

        assertThat(assessment.getFieldValues()).containsEntry("ticket", "KAN-2");
    }

    @Test
    void aMalformedFieldWithNoVariableDoesNotDiscardOtherLegitimateEditsInApplyTo() {
        // A buggy/adversarial extension can return a CustomField whose type is non-null but
        // whose variable was never set. SYNTHETIC_ASSESSMENT_VARIABLES.contains(null) throws
        // on an immutable Set, so that one malformed field must not be allowed to blow up the
        // filter and, with it, every other legitimate edit riding alongside it in the same list.
        UserDefinedField ticket = UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .displayName("Ticket")
                .variableName("ticket")
                .fieldType(FieldType.STRING)
                .build();

        Assessment assessment = Assessment.builder()
                .id("a6").name("Round trip").applicationId("app").assessmentTypeId("t")
                .workflowId("pci").status("Testing")
                .fieldDefinitions(List.of(ticket))
                .fieldValues(new HashMap<>(Map.of("ticket", "KAN-1")))
                .build();

        com.faction.elements.Assessment element =
                mapper.toElement(assessment, "Web App", null, List.of(), null, null, List.of(), "PCI");
        element.getCustomFields().stream()
                .filter(field -> field.getType() != null && "ticket".equals(field.getType().getVariable()))
                .findFirst().orElseThrow()
                .setValue("KAN-2");

        CustomField malformed = new CustomField();
        malformed.setType(new CustomType());
        malformed.setValue("noise");
        element.getCustomFields().add(malformed);

        assertThatCode(() -> mapper.applyTo(element, assessment)).doesNotThrowAnyException();
        // The point isn't just that applyTo survives the malformed field — it's that the
        // legitimate edit riding alongside it still lands, instead of being silently discarded.
        assertThat(assessment.getFieldValues()).containsEntry("ticket", "KAN-2");
    }

    /** The value of the element's custom field with this variable name, or null when it has none. */
    private static String customFieldValue(com.faction.elements.Assessment element, String variable) {
        return element.getCustomFields().stream()
                .filter(field -> field.getType() != null && variable.equals(field.getType().getVariable()))
                .map(CustomField::getValue)
                .findFirst().orElse(null);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Vulnerability vulnerability(String name) {
        Vulnerability vuln = new Vulnerability();
        vuln.setId(UUID.randomUUID().toString());
        vuln.setName(name);
        vuln.setFieldDefinitions(List.of());
        vuln.setFieldValues(new HashMap<>());
        return vuln;
    }

    private ChecklistResponse response(String question, ChecklistResult result, String comment) {
        ChecklistResponse response = new ChecklistResponse();
        response.setQuestionText(question);
        response.setResult(result);
        response.setComment(comment);
        return response;
    }

    private User user(String first, String last) {
        User user = new User();
        user.setFirstName(first);
        user.setLastName(last);
        user.setEmail(first.toLowerCase() + "@example.com");
        user.setUsername(first.toLowerCase());
        return user;
    }
}
