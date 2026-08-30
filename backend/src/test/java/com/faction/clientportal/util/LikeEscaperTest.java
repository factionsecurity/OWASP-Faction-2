package com.faction.clientportal.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikeEscaperTest {

    @Test
    void escapesWildcardsAndTheEscapeCharacter() {
        assertThat(LikeEscaper.escape("app_001")).isEqualTo("app!_001");   // underscore
        assertThat(LikeEscaper.escape("100%")).isEqualTo("100!%");         // percent
        assertThat(LikeEscaper.escape("a!b")).isEqualTo("a!!b");           // escape char itself
        assertThat(LikeEscaper.escape("a!%_b")).isEqualTo("a!!!%!_b");     // escape-char-first ordering
        assertThat(LikeEscaper.escape("plain-term")).isEqualTo("plain-term"); // nothing to escape
    }

    @Test
    void nullPassesThrough() {
        assertThat(LikeEscaper.escape(null)).isNull();
    }
}
