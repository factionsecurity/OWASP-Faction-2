package com.faction.clientportal.service;

import com.faction.clientportal.dto.AssessmentImportPreviewDto;
import com.faction.clientportal.dto.AssessmentImportResultDto;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.exception.AssessmentImportInvalidException;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import com.faction.clientportal.util.CsvReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;

/**
 * Bulk assessment scheduling from a CSV — the Faction 2 successor to 1.x's assessment upload.
 *
 * <p>Columns are matched by header name, ignoring case, and every lookup ignores case too. An
 * application or campaign the file names but the platform lacks is created with the batch (a
 * campaign only when the caller may create campaigns); an assessment type, user, team or report
 * template has to exist already, because a blank one of those would be half-configured.
 *
 * <p>Unlike the application sync this is all-or-nothing: {@link #preview} is a dry run, and
 * {@code importCsv} refuses while any row has an error. Creating an assessment mails people and
 * posts to chat, so a half-applied file is far harder to clean up than to fix and re-upload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentCsvImportService {

    private final ApplicationRepository applicationRepository;
    private final AssessmentTypeRepository assessmentTypeRepository;
    private final CampaignRepository campaignRepository;
    private final TeamRepository teamRepository;
    private final ReportTemplateRepository reportTemplateRepository;
    private final UserRepository userRepository;
    private final DefaultReportTemplateService defaultReportTemplateService;
    private final AssessmentService assessmentService;
    private final AssessmentImportCommitter committer;

    /** The built-in columns, in template order. Any other header must be a custom-field variable. */
    static final List<String> COLUMNS = List.of(
            "name", "appId", "applicationName", "assessmentType", "startDate", "endDate",
            "durationDays", "assessors", "campaign", "team", "engagementManager",
            "remediationManager", "reportTemplate", "scope");

    private static final Set<String> COLUMN_KEYS = COLUMNS.stream()
            .map(c -> c.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static final String LIST_SEPARATOR = ";";

    /**
     * The most rows one file may hold. Measured: every row on one application takes ~35 s at
     * 2,000 rows, ~64 s at 3,000 and ~580 s at 10,000, because each assessment's notebook root
     * loads every existing root of its application. Keep it where a worst-case file commits
     * well inside a minute.
     */
    static final int MAX_ROWS = 2_000;

    /** Refused before parsing; a full-width file at the row cap is a small fraction of this. */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** Ten years: longer is a typo, and a far-off end date fails at commit rather than in the preview. */
    private static final int MAX_DURATION_DAYS = 3650;

    private static final String CAMPAIGNS_CREATE_ALL = "campaigns:create:all";
    private static final String SUPER_ADMIN = "super_admin";

    private static final String TEMPLATE_EXAMPLE =
            "Q4 External Pentest,APP-001,Example Checkout,Penetration Test,2026-10-05,2026-10-16,,"
            + "\"jane.doe;sam.lee@example.com\",2026 Q4,Red Team,jane.doe,sam.lee,,"
            + "External network and web application";

    /**
     * The CSV users download to fill in: every built-in column, then one column per custom-field
     * variable any live report template defines, and one example row showing each column's shape.
     */
    public String template() {
        List<String> header = new ArrayList<>(COLUMNS);
        Collection<String> customFields = customFieldVariables().values();
        customFields.forEach(variable -> header.add(csvCell(variable)));
        return String.join(",", header) + "\n"
                + TEMPLATE_EXAMPLE + ",".repeat(customFields.size()) + "\n";
    }

    /**
     * A header cell as CSV: quoted when it holds a comma, quote or line break, and — so a
     * spreadsheet doesn't run it as a formula — quoted and prefixed with {@code '} when it starts
     * with {@code =}, {@code +}, {@code -} or {@code @}. {@link #header} strips that prefix again.
     */
    static String csvCell(String value) {
        boolean formula = !value.isEmpty() && FORMULA_LEADS.indexOf(value.charAt(0)) >= 0;
        if (!formula && value.chars().noneMatch(c -> c == ',' || c == '"' || c == '\r' || c == '\n')) {
            return value;
        }
        return "\"" + (formula ? "'" : "") + value.replace("\"", "\"\"") + "\"";
    }

    private static final String FORMULA_LEADS = "=+-@";

    /** @param authorities the caller's granted authorities; they decide whether new campaigns may be created */
    public AssessmentImportPreviewDto preview(MultipartFile file, Set<String> authorities) throws IOException {
        return plan(file, authorities).preview();
    }

    /**
     * Re-plans the file (anything may have changed since the preview) and, only if every row is
     * valid, creates the whole batch in one transaction. Notifications and extension events go
     * out after the commit, so nobody is mailed about a batch that rolled back.
     */
    public AssessmentImportResultDto importCsv(MultipartFile file, boolean notifyStakeholders,
                                               String userId, Set<String> authorities) throws IOException {
        AssessmentImportPlan plan = plan(file, authorities);
        if (!plan.preview().isValid()) {
            throw new AssessmentImportInvalidException(plan.preview());
        }

        AssessmentImportCommitter.Outcome outcome = committer.commit(plan, userId);

        for (Assessment assessment : outcome.assessments()) {
            try {
                assessmentService.announceNewAssessment(assessment, notifyStakeholders);
            } catch (RuntimeException e) {
                // The batch is committed; one failed announcement must not hide that.
                log.warn("Could not announce imported assessment {}: {}", assessment.getId(), e.getMessage());
            }
        }

        log.info("CSV assessment import by {}: {} created, {} new applications, {} new campaigns, notify={}",
                userId, outcome.assessments().size(), outcome.createdApplications().size(),
                outcome.createdCampaigns().size(), notifyStakeholders);
        return AssessmentImportResultDto.builder()
                .created(outcome.assessments().size())
                .createdApplications(outcome.createdApplications())
                .createdCampaigns(outcome.createdCampaigns())
                .assessmentIds(outcome.assessments().stream().map(Assessment::getId).toList())
                .build();
    }

    // ── Planning ─────────────────────────────────────────────────────────────

    AssessmentImportPlan plan(MultipartFile file, Set<String> authorities) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No CSV file was uploaded");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("The file is too large (the limit is 10 MB)");
        }
        List<List<String>> rows;
        try (InputStream in = file.getInputStream()) {
            rows = CsvReader.read(in);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }

        Header header = header(rows.get(0), customFieldVariables());
        List<List<String>> dataRows = rows.subList(1, rows.size());
        if (dataRows.isEmpty()) {
            throw new IllegalArgumentException("The file has a header row but no assessments");
        }
        if (dataRows.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Too many rows: " + dataRows.size() + " (the limit is "
                            + String.format(Locale.ROOT, "%,d", MAX_ROWS)
                            + "). Split the file and import each part");
        }

        boolean canCreateCampaigns = authorities != null
                && (authorities.contains(CAMPAIGNS_CREATE_ALL) || authorities.contains(SUPER_ADMIN));
        Planner planner = new Planner(header, canCreateCampaigns);
        for (int i = 0; i < dataRows.size(); i++) {
            planner.add(i + 2, dataRows.get(i)); // 1-based, and the header is line 1
        }
        return planner.finish();
    }

    /** Lower-cased variable → variable as first written, across every live template's ASSESSMENT fields. */
    private Map<String, String> customFieldVariables() {
        Map<String, String> variables = new TreeMap<>();
        for (ReportTemplate template : reportTemplateRepository.findByDeletedAtIsNull(Pageable.unpaged())) {
            for (UserDefinedField field : assessmentFields(template)) {
                String variable = field.getVariableName();
                if (variable == null || variable.isBlank()) {
                    continue;
                }
                String key = variable.toLowerCase(Locale.ROOT);
                // A variable named like a built-in column is only reachable as that column.
                if (!COLUMN_KEYS.contains(key)) {
                    variables.putIfAbsent(key, variable);
                }
            }
        }
        return variables;
    }

    private static List<UserDefinedField> assessmentFields(ReportTemplate template) {
        if (template.getUserDefinedFields() == null) {
            return List.of();
        }
        return template.getUserDefinedFields().stream()
                .filter(f -> f.getFieldScope() == null || f.getFieldScope() == FieldScope.ASSESSMENT)
                .toList();
    }

    /** Built-in columns by lower-cased name, and custom-field columns by variable name. */
    private record Header(Map<String, Integer> builtIn, Map<String, Integer> custom) {
    }

    private Header header(List<String> cells, Map<String, String> customFields) {
        Map<String, Integer> builtIn = new HashMap<>();
        Map<String, Integer> custom = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            String name = cells.get(i) == null ? "" : cells.get(i).trim();
            if (name.length() > 1 && name.charAt(0) == '\'' && FORMULA_LEADS.indexOf(name.charAt(1)) >= 0) {
                name = name.substring(1); // the template's guard against formula injection
            }
            if (name.isEmpty()) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (COLUMN_KEYS.contains(key)) {
                if (builtIn.putIfAbsent(key, i) != null) {
                    throw new IllegalArgumentException("Column '" + name + "' appears more than once");
                }
            } else if (customFields.containsKey(key)) {
                if (custom.putIfAbsent(customFields.get(key), i) != null) {
                    throw new IllegalArgumentException("Column '" + name + "' appears more than once");
                }
            } else {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown column(s): " + String.join(", ", unknown)
                    + ". Expected any of: " + String.join(", ", COLUMNS)
                    + ", or the variable name of an assessment custom field");
        }
        for (String required : List.of("name", "assessmentType", "startDate")) {
            if (!builtIn.containsKey(required.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The file needs a '" + required + "' column");
            }
        }
        if (!builtIn.containsKey("appid") && !builtIn.containsKey("applicationname")) {
            throw new IllegalArgumentException("The file needs an 'appId' or 'applicationName' column");
        }
        if (!builtIn.containsKey("enddate") && !builtIn.containsKey("durationdays")) {
            throw new IllegalArgumentException("The file needs an 'endDate' or 'durationDays' column");
        }
        return new Header(builtIn, custom);
    }

    /**
     * Resolves rows one at a time, sharing lookups (a 500-row file names the same few types and
     * people over and over) and the pending applications and campaigns across the whole file.
     */
    private final class Planner {
        private final Header header;
        private final List<AssessmentImportPlan.PlannedRow> planned = new ArrayList<>();
        private final List<AssessmentImportPreviewDto.Row> previewRows = new ArrayList<>();
        private final Map<String, AssessmentImportPlan.PendingApplication> pendingApplications = new LinkedHashMap<>();
        private final Map<String, String> pendingCampaigns = new LinkedHashMap<>();
        private final Map<String, List<AssessmentType>> types = new HashMap<>();
        private final Map<String, List<Team>> teams = new HashMap<>();
        private final Map<String, List<ReportTemplate>> templates = new HashMap<>();
        private final Map<String, List<Campaign>> campaigns = new HashMap<>();
        private final Map<String, List<User>> users = new HashMap<>();
        private final Map<String, List<Application>> applications = new HashMap<>();
        private final Map<String, TypeTemplates> typeTemplates = new HashMap<>();
        private final boolean canCreateCampaigns;
        private Optional<Campaign> defaultCampaign;

        Planner(Header header, boolean canCreateCampaigns) {
            this.header = header;
            this.canCreateCampaigns = canCreateCampaigns;
        }

        void add(int line, List<String> cells) {
            List<String> errors = new ArrayList<>();
            CreateAssessmentRequest request = CreateAssessmentRequest.builder().build();
            AssessmentImportPreviewDto.Row.RowBuilder row = AssessmentImportPreviewDto.Row.builder().line(line);

            String name = value(cells, "name");
            if (name.isEmpty()) {
                errors.add("name is required");
            } else if (name.length() > 255) {
                errors.add("name must not exceed 255 characters");
            }
            request.setName(name);
            row.name(name);

            String pendingApplicationKey = resolveApplication(cells, request, row, errors);

            AssessmentType type = null;
            String typeName = value(cells, "assessmentType");
            if (typeName.isEmpty()) {
                errors.add("assessmentType is required");
            } else {
                type = single(cached(types, typeName, assessmentTypeRepository::findAllByNameIgnoreCase),
                        "assessment type", typeName, errors);
                if (type != null) {
                    request.setAssessmentTypeId(type.getId());
                    row.assessmentType(type.getName());
                }
            }

            resolveDates(cells, request, row, errors);
            resolvePeople(cells, request, row, errors);
            String pendingCampaignKey = resolveCampaign(cells, request, row, errors);

            String teamName = value(cells, "team");
            if (!teamName.isEmpty()) {
                Team team = single(cached(teams, teamName, teamRepository::findAllByNameIgnoreCase),
                        "team", teamName, errors);
                if (team != null) {
                    request.setTeamId(team.getId());
                    row.team(team.getName());
                }
            }

            ReportTemplate template = resolveTemplate(cells, type, request, errors);
            resolveCustomFields(cells, type, template, request, errors);

            String scope = value(cells, "scope");
            if (!scope.isEmpty()) {
                // Scope is rich text; a CSV cell is plain text, so escape it and keep its line breaks.
                String lines = scope.replace("\r\n", "\n").replace('\r', '\n');
                request.setScope("<p>" + HtmlUtils.htmlEscape(lines).replace("\n", "<br>") + "</p>");
            }

            previewRows.add(row.errors(errors).build());
            planned.add(new AssessmentImportPlan.PlannedRow(line, request, pendingApplicationKey, pendingCampaignKey));
        }

        /** Existing application by appId, else by name; otherwise a pending one. Returns its pending key. */
        private String resolveApplication(List<String> cells, CreateAssessmentRequest request,
                                          AssessmentImportPreviewDto.Row.RowBuilder row, List<String> errors) {
            String appId = value(cells, "appId");
            String appName = value(cells, "applicationName");
            if (appId.isEmpty() && appName.isEmpty()) {
                errors.add("Give an appId or an applicationName");
                return null;
            }

            List<Application> matches = !appId.isEmpty()
                    ? cached(applications, "appid:" + appId,
                            k -> applicationRepository.findAllByAppIdIgnoreCase(appId))
                    : cached(applications, "name:" + appName,
                            k -> applicationRepository.findAllByNameIgnoreCase(appName).stream()
                                    .filter(a -> a.getDeletedAt() == null).toList());
            String reference = !appId.isEmpty() ? appId : appName;

            if (matches.size() > 1) {
                errors.add("Ambiguous application '" + reference + "': " + matches.size() + " match ignoring case");
                return null;
            }
            if (matches.size() == 1) {
                Application app = matches.get(0);
                if (app.getDeletedAt() != null) {
                    errors.add("Application '" + reference + "' has been deleted");
                    return null;
                }
                request.setApplicationId(app.getId());
                row.application(label(app.getName(), app.getAppId()));
                return null;
            }

            String key = !appId.isEmpty()
                    ? "appid:" + appId.toLowerCase(Locale.ROOT)
                    : "name:" + appName.toLowerCase(Locale.ROOT);
            AssessmentImportPlan.PendingApplication pending = pendingApplications.computeIfAbsent(key,
                    k -> new AssessmentImportPlan.PendingApplication(
                            appId.isEmpty() ? null : appId, appName.isEmpty() ? appId : appName));
            request.setAppId(pending.appId());
            request.setApplicationName(pending.name());
            row.application(label(pending.name(), pending.appId())).newApplication(true);
            return key;
        }

        private void resolveDates(List<String> cells, CreateAssessmentRequest request,
                                  AssessmentImportPreviewDto.Row.RowBuilder row, List<String> errors) {
            LocalDate start = null;
            String startText = value(cells, "startDate");
            if (startText.isEmpty()) {
                errors.add("startDate is required");
            } else {
                start = date(startText, "startDate", errors);
            }

            LocalDate end = null;
            String endText = value(cells, "endDate");
            String durationText = value(cells, "durationDays");
            if (!endText.isEmpty()) {
                end = date(endText, "endDate", errors);
            } else if (!durationText.isEmpty()) {
                try {
                    int days = Integer.parseInt(durationText);
                    if (days < 0) {
                        errors.add("durationDays must not be negative");
                    } else if (days > MAX_DURATION_DAYS) {
                        errors.add("durationDays must be at most " + MAX_DURATION_DAYS);
                    } else if (start != null) {
                        end = start.plusDays(days);
                    }
                } catch (NumberFormatException e) {
                    errors.add("durationDays '" + durationText + "' is not a whole number");
                }
            } else {
                errors.add("Give an endDate or a durationDays");
            }

            if (start != null && end != null && end.isBefore(start)) {
                errors.add("endDate is before startDate");
            }
            if (start != null) {
                request.setStartDate(start.atStartOfDay());
                row.startDate(start);
            }
            if (end != null) {
                request.setPlannedEndDate(end.atStartOfDay());
                row.endDate(end);
            }
        }

        private void resolvePeople(List<String> cells, CreateAssessmentRequest request,
                                   AssessmentImportPreviewDto.Row.RowBuilder row, List<String> errors) {
            List<String> assessorIds = new ArrayList<>();
            List<String> assessorNames = new ArrayList<>();
            for (String reference : entries(value(cells, "assessors"))) {
                User user = user(reference, errors);
                if (user != null && !Boolean.TRUE.equals(user.getIsInternal())) {
                    // As on the create form: only the testing side can be assigned to do the testing.
                    errors.add("'" + reference + "' isn't an internal user, so can't be an assessor");
                } else if (user != null && !assessorIds.contains(user.getId())) {
                    assessorIds.add(user.getId());
                    assessorNames.add(displayName(user));
                }
            }
            request.setAssessorIds(assessorIds);
            row.assessors(assessorNames);

            String engagementManager = value(cells, "engagementManager");
            if (!engagementManager.isEmpty()) {
                User user = user(engagementManager, errors);
                if (user != null) {
                    request.setEngagementManagerId(user.getId());
                }
            }
            String remediationManager = value(cells, "remediationManager");
            if (!remediationManager.isEmpty()) {
                User user = user(remediationManager, errors);
                if (user != null) {
                    request.setRemediationManagerId(user.getId());
                }
            }
        }

        /** Existing campaign by name, the default when blank, otherwise a pending one. Returns its pending key. */
        private String resolveCampaign(List<String> cells, CreateAssessmentRequest request,
                                       AssessmentImportPreviewDto.Row.RowBuilder row, List<String> errors) {
            String campaignName = value(cells, "campaign");
            if (campaignName.isEmpty()) {
                if (defaultCampaign == null) {
                    defaultCampaign = campaignRepository.findByIsDefaultTrue();
                }
                defaultCampaign.ifPresent(c -> {
                    request.setCampaignId(c.getId());
                    row.campaign(c.getName());
                });
                return null;
            }

            List<Campaign> matches = cached(campaigns, campaignName, campaignRepository::findAllByNameIgnoreCase);
            if (matches.size() > 1) {
                errors.add("Ambiguous campaign '" + campaignName + "': " + matches.size() + " match ignoring case");
                return null;
            }
            if (matches.size() == 1) {
                request.setCampaignId(matches.get(0).getId());
                row.campaign(matches.get(0).getName());
                return null;
            }
            if (!canCreateCampaigns) {
                // POST /campaigns needs campaigns:create:all; an import must not be a way around it.
                errors.add("Campaign '" + campaignName
                        + "' doesn't exist, and you don't have permission to create campaigns");
                return null;
            }
            String key = campaignName.toLowerCase(Locale.ROOT);
            String spelling = pendingCampaigns.computeIfAbsent(key, k -> campaignName);
            row.campaign(spelling).newCampaign(true);
            return key;
        }

        /**
         * The named template (which must be live, active and of the row's type), or the type's
         * existing default. Null when there is none yet — creation then installs the default.
         */
        private ReportTemplate resolveTemplate(List<String> cells, AssessmentType type,
                                               CreateAssessmentRequest request, List<String> errors) {
            String templateName = value(cells, "reportTemplate");
            ReportTemplate template = null;
            if (!templateName.isEmpty()) {
                template = single(cached(templates, templateName,
                                reportTemplateRepository::findAllByNameIgnoreCaseAndDeletedAtIsNull),
                        "report template", templateName, errors);
                if (template != null && !Boolean.TRUE.equals(template.getActive())) {
                    errors.add("Report template '" + template.getName() + "' is not active");
                    template = null;
                } else if (template != null && type != null
                        && !type.getId().equals(template.getAssessmentTypeId())) {
                    errors.add("Report template '" + template.getName() + "' is not for assessment type '"
                            + type.getName() + "'");
                    template = null;
                }
            } else if (type != null) {
                TypeTemplates known = typeTemplates.computeIfAbsent(type.getId(), this::typeTemplates);
                template = known.defaultTemplate();
                if (template == null && known.activeCount() > 0) {
                    // Several templates and none carries the default name: creation would install
                    // another default rather than pick one, so the file has to say which.
                    errors.add("Assessment type '" + type.getName() + "' has " + known.activeCount()
                            + " report templates; name one in reportTemplate");
                }
            }
            if (template != null) {
                request.setReportTemplateId(template.getId());
            }
            return template;
        }

        private TypeTemplates typeTemplates(String typeId) {
            ReportTemplate existing = defaultReportTemplateService.findExistingForAssessmentType(typeId).orElse(null);
            int active = existing != null ? 0 : (int) reportTemplateRepository
                    .findByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNull(typeId, Pageable.unpaged())
                    .getTotalElements();
            return new TypeTemplates(existing, active);
        }

        private void resolveCustomFields(List<String> cells, AssessmentType type, ReportTemplate template,
                                         CreateAssessmentRequest request, List<String> errors) {
            Map<String, String> byVariable = new LinkedHashMap<>();
            header.custom().forEach((variable, index) -> {
                String cell = index < cells.size() && cells.get(index) != null ? cells.get(index).trim() : "";
                if (!cell.isEmpty()) {
                    byVariable.put(variable, cell);
                }
            });
            if (byVariable.isEmpty()) {
                return;
            }
            if (template == null) {
                TypeTemplates known = type == null ? null : typeTemplates.get(type.getId());
                if (known != null && known.activeCount() == 0 && value(cells, "reportTemplate").isEmpty()) {
                    errors.add("Assessment type '" + type.getName()
                            + "' has no report template yet, so custom fields can't be set");
                }
                return;
            }

            Map<String, UserDefinedField> fields = new HashMap<>();
            for (UserDefinedField field : assessmentFields(template)) {
                if (field.getVariableName() != null) {
                    fields.putIfAbsent(field.getVariableName().toLowerCase(Locale.ROOT), field);
                }
            }
            Map<String, String> values = new HashMap<>();
            boolean missing = false;
            for (Map.Entry<String, String> entry : byVariable.entrySet()) {
                UserDefinedField field = fields.get(entry.getKey().toLowerCase(Locale.ROOT));
                if (field == null) {
                    errors.add("Custom field '" + entry.getKey() + "' is not on report template '"
                            + template.getName() + "'");
                    missing = true;
                } else {
                    values.put(field.getId(), entry.getValue());
                }
            }
            if (missing) {
                return;
            }
            try {
                request.setInitialFieldValues(
                        assessmentService.validateFieldValues(values, new ArrayList<>(fields.values())));
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }

        private User user(String reference, List<String> errors) {
            List<User> matches = cached(users, reference, ref -> ref.contains("@")
                    ? userRepository.findAllByEmailIgnoreCaseAndDeletedAtIsNull(ref)
                    : userRepository.findAllByUsernameIgnoreCaseAndDeletedAtIsNull(ref));
            return single(matches, "user", reference, errors);
        }

        private String value(List<String> cells, String column) {
            Integer index = header.builtIn().get(column.toLowerCase(Locale.ROOT));
            if (index == null || index >= cells.size() || cells.get(index) == null) {
                return "";
            }
            return cells.get(index).trim();
        }

        AssessmentImportPlan finish() {
            int errorCount = (int) previewRows.stream().filter(r -> !r.getErrors().isEmpty()).count();
            AssessmentImportPreviewDto preview = AssessmentImportPreviewDto.builder()
                    .rows(previewRows)
                    .total(previewRows.size())
                    .validCount(previewRows.size() - errorCount)
                    .errorCount(errorCount)
                    .newApplicationCount(pendingApplications.size())
                    .newCampaignCount(pendingCampaigns.size())
                    .valid(errorCount == 0)
                    .build();
            return new AssessmentImportPlan(planned, pendingApplications, pendingCampaigns, preview);
        }
    }

    /**
     * What a type offers when a row names no template: its existing default (see
     * {@link DefaultReportTemplateService#findExistingForAssessmentType}), or, when there is none,
     * how many active templates it has — 0 means creation installs the default.
     */
    private record TypeTemplates(ReportTemplate defaultTemplate, int activeCount) {
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static <T> List<T> cached(Map<String, List<T>> cache, String key, Function<String, List<T>> lookup) {
        return cache.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> lookup.apply(key));
    }

    private static <T> T single(List<T> matches, String what, String value, List<String> errors) {
        if (matches.isEmpty()) {
            errors.add("Unknown " + what + " '" + value + "'");
            return null;
        }
        if (matches.size() > 1) {
            errors.add("Ambiguous " + what + " '" + value + "': " + matches.size() + " match ignoring case");
            return null;
        }
        return matches.get(0);
    }

    private static LocalDate date(String text, String column, List<String> errors) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            errors.add(column + " '" + text + "' is not a YYYY-MM-DD date");
            return null;
        }
    }

    private static List<String> entries(String cell) {
        if (cell.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(cell.split(LIST_SEPARATOR))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static String label(String name, String appId) {
        return appId == null || appId.isBlank() ? name : name + " (" + appId + ")";
    }

    private static String displayName(User user) {
        String full = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
