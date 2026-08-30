package com.faction.clientportal.service;

import com.faction.clientportal.dto.ApplicationImportResultDto;
import com.faction.clientportal.dto.SubOrganizationDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.ApplicationUrl;
import com.faction.clientportal.model.ApplicationStatus;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Stakeholder;
import com.faction.clientportal.model.SubOrganization;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.SubOrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationCsvImportService {

    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;
    private final SubOrganizationRepository subOrganizationRepository;
    private final SubOrganizationService subOrganizationService;

    /** Header of the downloadable template, and the columns a row may set. */
    static final List<String> COLUMNS = List.of(
            "appId", "name", "description", "organization", "subOrganization", "status",
            "region", "applicationType", "assessmentFrequency", "ownerName", "ownerEmail",
            "technologies", "urls", "stakeholders");

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
            + "\"Jane Doe|jane.doe@example.com|Product Owner;Sam Lee|sam.lee@example.com|Security Champion\"";

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
        List<List<String>> rows = parse(file);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }

        Map<String, Integer> columns = headerIndex(rows.get(0));
        List<List<String>> dataRows = rows.subList(1, rows.size());
        if (dataRows.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Too many rows: " + dataRows.size() + " (the limit is " + MAX_ROWS + ")");
        }

        ApplicationImportResultDto result = ApplicationImportResultDto.builder()
                .processed(dataRows.size())
                .build();

        for (int i = 0; i < dataRows.size(); i++) {
            List<String> row = dataRows.get(i);
            int line = i + 2; // 1-based, and the header is line 1
            try {
                applyRow(row, columns, result, userId);
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

    private void applyRow(List<String> row, Map<String, Integer> columns,
                          ApplicationImportResultDto result, String userId) {
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
     */
    private Application findExisting(String appId, String name) {
        if (!appId.isEmpty()) {
            Application byAppId = applicationRepository.findByAppIdIgnoreCase(appId).orElse(null);
            if (byAppId != null) {
                return byAppId;
            }
        }
        return name.isEmpty() ? null : applicationRepository.findByNameIgnoreCase(name).orElse(null);
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

    private ApplicationStatus parseStatus(String status) {
        try {
            return ApplicationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status '" + status + "'. Expected one of: "
                    + Arrays.toString(ApplicationStatus.values()));
        }
    }

    // ── CSV reading ──────────────────────────────────────────────────────────

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
     * Minimal RFC 4180 reader: quoted fields may contain commas, newlines and doubled quotes.
     * Blank lines are skipped so a trailing newline (every editor adds one) isn't a failed row.
     */
    private List<List<String>> parse(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            int read;
            boolean first = true;
            while ((read = reader.read()) != -1) {
                char c = (char) read;
                if (first) {
                    first = false;
                    if (c == '﻿') { // byte-order mark Excel writes on "CSV UTF-8"
                        continue;
                    }
                }
                if (quoted) {
                    if (c == '"') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') {
                            field.append('"'); // an escaped quote inside a quoted field
                        } else {
                            quoted = false;
                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        field.append(c);
                    }
                } else if (c == '"' && !fieldStarted) {
                    quoted = true;
                    fieldStarted = true;
                } else if (c == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                } else if (c == '\n' || c == '\r') {
                    if (fieldStarted || field.length() > 0 || !row.isEmpty()) {
                        row.add(field.toString());
                        field.setLength(0);
                        fieldStarted = false;
                        addRow(rows, row);
                        row = new ArrayList<>();
                    }
                } else {
                    field.append(c);
                    fieldStarted = true;
                }
            }
        }
        if (fieldStarted || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            addRow(rows, row);
        }
        return rows;
    }

    private void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(cell -> cell != null && !cell.isBlank())) {
            rows.add(row);
        }
    }
}
