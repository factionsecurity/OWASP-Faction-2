package com.faction.clientportal.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvReaderTest {

    private List<List<String>> read(String csv) throws IOException {
        return CsvReader.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsPlainRows() throws IOException {
        assertThat(read("a,b,c\n1,2,3\n"))
                .containsExactly(List.of("a", "b", "c"), List.of("1", "2", "3"));
    }

    @Test
    void quotedFieldsKeepCommasNewlinesAndEscapedQuotes() throws IOException {
        assertThat(read("name,note\n\"Doe, Jane\",\"line1\nline2 \"\"quoted\"\"\"\n"))
                .containsExactly(List.of("name", "note"),
                        List.of("Doe, Jane", "line1\nline2 \"quoted\""));
    }

    @Test
    void skipsBomBlankLinesAndHandlesCrLf() throws IOException {
        assertThat(read("﻿a,b\r\n\r\n1,2\r\n,\r\n"))
                .containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    void keepsEmptyCellsAndALastLineWithoutNewline() throws IOException {
        assertThat(read("a,b,c\n1,,3"))
                .containsExactly(List.of("a", "b", "c"), List.of("1", "", "3"));
    }

    @Test
    void emptyInputHasNoRows() throws IOException {
        assertThat(read("")).isEmpty();
    }
}
