package com.faction.clientportal.service;

import com.faction.clientportal.dto.ApplicationImportResultDto;
import com.faction.clientportal.dto.SubOrganizationDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.ApplicationUrl;
import com.faction.clientportal.model.ApplicationStatus;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Stakeholder;
import com.faction.clientportal.model.SubOrganization;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.SubOrganizationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.util.CsvReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bulk application sync from a CSV upload.
 *
 * <p>Each row is an upsert: an existing application is matched by {@code appId} first and by
 * {@code name} second, and anything unmatched is inserted. Organizations and their divisions named
 * in a row are created on demand, so a spreadsheet can introduce structure the platform has never
 * seen without a separate setup pass.
 *
 * <p>A row that can't be applied is recorded with its line number and the run continues — a single
 * bad status or a missing name must not throw away the other 500 rows. Rows are applied as they
 * are read, so a failure partway through leaves the earlier rows written; the result says exactly
 * which lines to fix and re-upload.
 *
 * <p>The file may also be another system's export: an {@link ApplicationCsvFormat} that recognizes
 * its header rewrites it into the template's layout first, and the sync proceeds from there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationCsvImportService {

    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;
    private final SubOrganizationRepository subOrganizationRepository;
    private final SubOrganizationService subOrganizationService;
    private final EntityFieldConfigService entityFieldConfigService;
    private final UserRepository userRepository;
    private final ObjectProvider<ApplicationCsvFormat> formats;

    /** Header of the downloadable template, and the columns a row may set. */
    static final List<String> COLUMNS = List.of(
            "appId", "name", "description", "organization", "subOrganization", "status",
            "region", "applicationType", "assessmentFrequency", "ownerName", "ownerEmail",
            "technologies", "urls", "stakeholders", "remediationOwner");

    /**
     * A cell holding several entries separates them with ';', and the parts within one entry with
     * '|' — neither of which needs the field itself quoted, so the file stays readable in a
     * spreadsheet editor. A list cell replaces the whole list: the cell is the list, not an
     * addition to it.
     */
    private static final String LIST_SEPARATOR = ";";
    private static final String PART_SEPARATOR = "\\|";

    /** Guardrail on an accidental upload of something enormous. */
    private static final int MAX_ROWS = 10_000;

    private static final String TEMPLATE_EXAMPLE =
            "APP-001,Example Checkout,Customer facing checkout,Acme,Payments,PRODUCTION,"
            + "Global,Web Application,Yearly,Jane Doe,jane.doe@example.com,"
            + "\"Java;React;PostgreSQL\","
            + "\"https://checkout.example.com|Production Site;https://staging.checkout.example.com|Staging\","
            + "\"Jane Doe|jane.doe@example.com|Product Owner;Sam Lee|sam.lee@example.com|Security Champion\","
            + "security.lead@example.com";

    /**
     * The CSV users download to fill in. It carries one example row rather than a bare header, so
     * the accepted shape of every column — including the status and organization names — is
     * visible without reading documentation.
     */
    public String template() {
        return String.join(",", COLUMNS) + "\n" + TEMPLATE_EXAMPLE + "\n";
    }

    public ApplicationImportResultDto importCsv(MultipartFile file, String userId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No CSV file was uploaded");
        }
        List<List<String>> rows;
        try (java.io.InputStream in = file.getInputStream()) {
            rows = CsvReader.read(in);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }

        if (rows.size() - 1 > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Too many rows: " + (rows.size() - 1) + " (the limit is " + MAX_ROWS + ")");
        }

        ApplicationCsvFormat format = detectFormat(rows.get(0));
        List<UserDefinedField> fieldDefinitions;
        if (format != null) {
            fieldDefinitions = entityFieldConfigService.ensureFields(
                    FieldScope.APPLICATION, format.customFields(), userId);
            rows = format.toTemplate(rows);
        } else {
            fieldDefinitions = entityFieldConfigService.getFieldDefinitions(FieldScope.APPLICATION);
        }

        Sheet sheet = readHeader(rows.get(0), fieldDefinitions);
        Map<String, Integer> columns = sheet.columns();
        List<List<String>> dataRows = rows.subList(1, rows.size());

        ApplicationImportResultDto result = ApplicationImportResultDto.builder()
                .processed(dataRows.size())
                .format(format == null ? null : format.name())
                .build();

        for (int i = 0; i < dataRows.size(); i++) {
            List<String> row = dataRows.get(i);
            int line = i + 2; // 1-based, and the header is line 1
            try {
                applyRow(row, sheet, line, result, userId);
            } catch (IllegalArgumentException e) {
                result.setFailed(result.getFailed() + 1);
                result.addError(ApplicationImportResultDto.RowError.builder()
                        .line(line)
                        .identifier(rowIdentifier(row, columns))
                        .message(e.getMessage())
                        .build());
            }
        }

        log.info("CSV application sync by {}: {} created, {} updated, {} failed",
                userId, result.getCreated(), result.getUpdated(), result.getFailed());
        return result;
    }

    // ── One row ──────────────────────────────────────────────────────────────

    private void applyRow(List<String> row, Sheet sheet, int line,
                          ApplicationImportResultDto result, String userId) {
        Map<String, Integer> columns = sheet.columns();
        String appId = value(row, columns, "appId");
        String name = value(row, columns, "name");
        if (name.isEmpty() && appId.isEmpty()) {
            throw new IllegalArgumentException("Row has neither a name nor an appId");
        }

        Application application = findExisting(appId, name);
        boolean creating = application == null;
        if (creating) {
            if (name.isEmpty()) {
                throw new IllegalArgumentException(
                        "A new application needs a name (appId '" + appId + "' matched nothing)");
            }
            application = Application.builder()
                    .name(name)
                    .createdBy(userId)
                    .createdAt(LocalDateTime.now())
                    .build();
        } else if (!name.isEmpty()) {
            application.setName(name);
        }

        if (!appId.isEmpty()) {
            application.setAppId(appId);
        }

        // Only columns present in the file are touched: a sync file with three columns must not
        // blank out everything else on the applications it updates.
        setIfPresent(row, columns, "description", application::setDescription);
        setIfPresent(row, columns, "region", application::setRegion);
        setIfPresent(row, columns, "applicationType", application::setApplicationType);
        setIfPresent(row, columns, "assessmentFrequency", application::setAssessmentFrequency);
        setIfPresent(row, columns, "ownerName", application::setOwnerName);
        setIfPresent(row, columns, "ownerEmail", application::setOwnerEmail);

        applyLists(row, columns, application);

        String status = value(row, columns, "status");
        if (!status.isEmpty()) {
            application.setStatus(parseStatus(status));
        }

        applyCustomFields(row, sheet, application);
        applyRemediationOwner(row, sheet, line, application, result);

        applyOrganization(row, columns, application, result, userId);

        application.setLastUpdatedBy(userId);
        application.setUpdatedAt(LocalDateTime.now());
        applicationRepository.save(application);

        if (creating) {
            result.setCreated(result.getCreated() + 1);
        } else {
            result.setUpdated(result.getUpdated() + 1);
        }
    }

    /**
     * Technologies, URLs and stakeholders: multi-entry cells, each replacing the whole list when
     * the column is present and non-empty. An entry missing its required part (a URL, a
     * stakeholder name) is an error rather than a half-filled record.
     */
    private void applyLists(List<String> row, Map<String, Integer> columns, Application application) {
        if (columns.containsKey("technologies")) {
            List<String> technologies = entries(value(row, columns, "technologies"));
            if (!technologies.isEmpty()) {
                application.setTechnologies(new ArrayList<>(technologies));
            }
        }

        if (columns.containsKey("urls")) {
            List<String> cells = entries(value(row, columns, "urls"));
            if (!cells.isEmpty()) {
                List<ApplicationUrl> urls = new ArrayList<>();
                for (String cell : cells) {
                    String[] parts = cell.split(PART_SEPARATOR, -1);
                    String url = parts[0].trim();
                    if (url.isEmpty()) {
                        throw new IllegalArgumentException(
                                "URL entry '" + cell + "' has no address; expected url|title");
                    }
                    // ApplicationUrl carries no builder; its all-args constructor is (url, title).
                    urls.add(new ApplicationUrl(url,
                            parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : url));
                }
                application.setUrls(urls);
            }
        }

        if (columns.containsKey("stakeholders")) {
            List<String> cells = entries(value(row, columns, "stakeholders"));
            if (!cells.isEmpty()) {
                List<Stakeholder> stakeholders = new ArrayList<>();
                for (String cell : cells) {
                    String[] parts = cell.split(PART_SEPARATOR, -1);
                    String name = parts[0].trim();
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException("Stakeholder entry '" + cell
                                + "' has no name; expected name|email|role");
                    }
                    stakeholders.add(Stakeholder.builder()
                            .name(name)
                            .email(parts.length > 1 ? parts[1].trim() : null)
                            .role(parts.length > 2 ? parts[2].trim() : null)
                            .build());
                }
                application.setStakeHolders(stakeholders);
            }
        }
    }

    /** Split a multi-entry cell, dropping the blanks a trailing ';' leaves behind. */
    private List<String> entries(String cell) {
        if (cell.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(cell.split(LIST_SEPARATOR))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /**
     * appId wins over name: it is the stable identifier, while names get edited. A row whose appId
     * matches therefore updates that application — including renaming it — rather than colliding
     * with the platform-wide uniqueness of appIds.
     *
     * <p>The name is only a fallback. A row carrying an appId claims a same-named application only
     * if that application has no appId yet, so a new appId never takes over one already known by
     * another. Ambiguous matches fail the row instead of guessing.
     */
    private Application findExisting(String appId, String name) {
        if (!appId.isEmpty()) {
            List<Application> byAppId = applicationRepository.findAllByAppIdIgnoreCase(appId);
            if (byAppId.size() > 1) {
                throw new IllegalArgumentException(
                        "appId '" + appId + "' matches " + byAppId.size() + " applications");
            }
            if (byAppId.size() == 1) {
                return byAppId.get(0);
            }
        }
        if (name.isEmpty()) {
            return null;
        }
        List<Application> byName = applicationRepository.findAllByNameIgnoreCase(name).stream()
                .filter(app -> appId.isEmpty() || app.getAppId() == null || app.getAppId().isBlank())
                .toList();
        if (byName.size() > 1) {
            throw new IllegalArgumentException("Name '" + name + "' matches " + byName.size()
                    + " applications; add an appId to the row to pick one");
        }
        return byName.isEmpty() ? null : byName.get(0);
    }

    /**
     * Attach the row's organization and division, creating either if this is the first time it has
     * been named. A division without an organization has nothing to hang off, so that is an error
     * rather than a silently dropped value.
     */
    private void applyOrganization(List<String> row, Map<String, Integer> columns,
                                   Application application, ApplicationImportResultDto result,
                                   String userId) {
        String orgName = value(row, columns, "organization");
        String subName = value(row, columns, "subOrganization");

        if (orgName.isEmpty()) {
            if (!subName.isEmpty()) {
                throw new IllegalArgumentException(
                        "subOrganization '" + subName + "' needs an organization on the same row");
            }
            return;
        }

        Organization organization = organizationRepository.findByNameIgnoreCase(orgName)
                .orElseGet(() -> {
                    Organization created = organizationRepository.save(Organization.builder()
                            .name(orgName)
                            .description("Created by the application CSV import")
                            .build());
                    result.addCreatedOrganization(created.getName());
                    return created;
                });
        application.setOrganizationId(organization.getId());

        if (subName.isEmpty()) {
            // Moving between organizations invalidates a division belonging to the old one.
            application.setSubOrganizationId(null);
            return;
        }

        SubOrganization division = subOrganizationRepository
                .findByOrganizationIdAndNameIgnoreCase(organization.getId(), subName)
                .orElseGet(() -> {
                    SubOrganizationDto.Request request = new SubOrganizationDto.Request();
                    request.setName(subName);
                    request.setDescription("Created by the application CSV import");
                    String id = subOrganizationService
                            .create(organization.getId(), request, userId).getId();
                    result.addCreatedSubOrganization(organization.getName() + " / " + subName);
                    return subOrganizationRepository.findById(id).orElseThrow();
                });
        application.setSubOrganizationId(division.getId());
    }

    /**
     * Custom field columns: each non-empty cell sets that field. A dropdown value must be one of
     * the field's options — matched ignoring case and stored as the option is spelled — so a typo
     * fails the row instead of saving a value the edit form can't show.
     */
    private void applyCustomFields(List<String> row, Sheet sheet, Application application) {
        for (Map.Entry<String, UserDefinedField> column : sheet.fieldColumns().entrySet()) {
            String raw = value(row, sheet.columns(), column.getKey());
            if (raw.isEmpty()) {
                continue;
            }
            UserDefinedField field = column.getValue();
            String value = raw;
            if (field.getFieldType() == FieldType.DROPDOWN) {
                List<String> options = field.getDropdownOptions() == null ? List.of() : field.getDropdownOptions();
                value = options.stream()
                        .filter(raw::equalsIgnoreCase)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("'" + raw + "' is not an option for "
                                + field.getDisplayName() + ". Expected one of: " + String.join(", ", options)));
            }
            if (application.getFieldValues() == null) {
                application.setFieldValues(new HashMap<>());
            }
            application.getFieldValues().put(field.getId(), value);
        }
    }

    /**
     * The remediation owner is named by email, username or full name, and must be a live internal
     * user. One the file names but the platform can't pin down is a warning rather than a failed
     * row: the rest of the application is still worth syncing, and the owner can be set by hand.
     */
    private void applyRemediationOwner(List<String> row, Sheet sheet, int line, Application application,
                                       ApplicationImportResultDto result) {
        String owner = value(row, sheet.columns(), "remediationOwner");
        if (owner.isEmpty()) {
            return;
        }
        List<User> matches = sheet.owners().match(owner);
        if (matches.size() == 1) {
            application.setRemediationOwnerId(matches.get(0).getId());
            return;
        }
        result.addWarning(ApplicationImportResultDto.RowError.builder()
                .line(line)
                .identifier(rowIdentifier(row, sheet.columns()))
                .message(matches.isEmpty()
                        ? "Remediation owner '" + owner + "' matches no internal user; left unchanged"
                        : "Remediation owner '" + owner + "' matches " + matches.size()
                                + " internal users; left unchanged")
                .build());
    }

    private ApplicationStatus parseStatus(String status) {
        try {
            return ApplicationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status '" + status + "'. Expected one of: "
                    + Arrays.toString(ApplicationStatus.values()));
        }
    }

    // ── CSV reading ──────────────────────────────────────────────────────────

    /** The header as the rows are read against it: plain columns, custom field columns, owners. */
    private record Sheet(Map<String, Integer> columns, Map<String, UserDefinedField> fieldColumns,
                         RemediationOwners owners) {
    }

    /** Names of the alternate layouts this deployment accepts, for the upload dialog. */
    public List<String> availableFormats() {
        return formats.orderedStream()
                .filter(ApplicationCsvFormat::available)
                .map(ApplicationCsvFormat::name)
                .toList();
    }

    private ApplicationCsvFormat detectFormat(List<String> header) {
        List<String> trimmed = header.stream().map(String::trim).toList();
        return formats.orderedStream()
                .filter(ApplicationCsvFormat::available)
                .filter(format -> format.recognizes(trimmed))
                .findFirst()
                .orElse(null);
    }

    private Sheet readHeader(List<String> header, List<UserDefinedField> fieldDefinitions) {
        Map<String, Integer> columns = headerIndex(header);

        Map<String, UserDefinedField> fieldColumns = new LinkedHashMap<>();
        List<String> unknownFields = new ArrayList<>();
        String prefix = ApplicationCsvFormat.FIELD_COLUMN_PREFIX;
        for (String column : columns.keySet()) {
            if (!column.startsWith(prefix)) {
                continue;
            }
            String variable = column.substring(prefix.length());
            fieldDefinitions.stream()
                    .filter(f -> f.getVariableName() != null && f.getVariableName().equalsIgnoreCase(variable))
                    .findFirst()
                    .ifPresentOrElse(f -> fieldColumns.put(column, f), () -> unknownFields.add(variable));
        }
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException("Unknown application custom field(s): "
                    + String.join(", ", unknownFields));
        }

        RemediationOwners owners = columns.containsKey("remediationowner")
                ? new RemediationOwners(userRepository.findAll())
                : new RemediationOwners(List.of());
        return new Sheet(columns, fieldColumns, owners);
    }

    private Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = header.get(i).trim();
            if (!key.isEmpty()) {
                // Matched case-insensitively: spreadsheet editors love to capitalize headers.
                columns.put(key.toLowerCase(Locale.ROOT), i);
            }
        }
        List<String> unknown = columns.keySet().stream()
                .filter(c -> !c.startsWith(ApplicationCsvFormat.FIELD_COLUMN_PREFIX))
                .filter(c -> COLUMNS.stream().noneMatch(known -> known.equalsIgnoreCase(c)))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown column(s): " + String.join(", ", unknown)
                    + ". Expected any of: " + String.join(", ", COLUMNS));
        }
        if (!columns.containsKey("name") && !columns.containsKey("appid")) {
            throw new IllegalArgumentException(
                    "The file needs a 'name' or 'appId' column to match applications on");
        }
        return columns;
    }

    private String value(List<String> row, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column.toLowerCase(Locale.ROOT));
        if (index == null || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return row.get(index).trim();
    }

    /** Applies a column only when the file actually has it — see the note in {@code applyRow}. */
    private void setIfPresent(List<String> row, Map<String, Integer> columns, String column,
                              java.util.function.Consumer<String> setter) {
        if (columns.containsKey(column.toLowerCase(Locale.ROOT))) {
            String value = value(row, columns, column);
            if (!value.isEmpty()) {
                setter.accept(value);
            }
        }
    }

    private String rowIdentifier(List<String> row, Map<String, Integer> columns) {
        String name = value(row, columns, "name");
        return !name.isEmpty() ? name : value(row, columns, "appId");
    }

    /**
     * Live internal users keyed by email, username and full name, loaded once per upload rather
     * than queried per row. "Last, First" — how directory exports often write a person — is tried
     * as "First Last" when the cell matches nothing as written.
     */
    static final class RemediationOwners {

        private final Map<String, List<User>> byKey = new HashMap<>();

        RemediationOwners(List<User> users) {
            for (User user : users) {
                if (user.getDeletedAt() != null || !Boolean.TRUE.equals(user.getIsInternal())) {
                    continue;
                }
                add(user.getEmail(), user);
                add(user.getUsername(), user);
                add((nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())), user);
            }
        }

        /** Everyone the cell could mean: exactly one is a match, none or several is not. */
        List<User> match(String cell) {
            List<User> found = byKey.getOrDefault(normalize(cell), List.of());
            int comma = cell.indexOf(',');
            if (found.isEmpty() && comma > 0) {
                String swapped = cell.substring(comma + 1) + " " + cell.substring(0, comma);
                found = byKey.getOrDefault(normalize(swapped), List.of());
            }
            return found;
        }

        private void add(String key, User user) {
            String normalized = normalize(key);
            if (normalized.isEmpty()) {
                return;
            }
            List<User> users = byKey.computeIfAbsent(normalized, k -> new ArrayList<>());
            if (users.stream().noneMatch(u -> u.getId().equals(user.getId()))) {
                users.add(user);
            }
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

}
