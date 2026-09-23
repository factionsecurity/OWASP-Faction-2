package com.faction.clientportal.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 reader for the CSV importers: quoted fields may contain commas, newlines and
 * doubled quotes. Blank lines are skipped so a trailing newline (every editor adds one) isn't a
 * failed row, and the byte-order mark Excel writes on "CSV UTF-8" is dropped. Cells are returned
 * as written — callers trim.
 */
public final class CsvReader {

    private CsvReader() {
    }

    public static List<List<String>> read(InputStream in) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            int read;
            boolean first = true;
            while ((read = reader.read()) != -1) {
                char c = (char) read;
                if (first) {
                    first = false;
                    if (c == '﻿') {
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

    private static void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(cell -> cell != null && !cell.isBlank())) {
            rows.add(row);
        }
    }
}
