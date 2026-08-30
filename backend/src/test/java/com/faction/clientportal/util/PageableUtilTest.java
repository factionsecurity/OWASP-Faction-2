package com.faction.clientportal.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.faction.clientportal.util.PageableUtil.SortField;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sort-string parsing for the shared list-endpoint {@link PageableUtil}. */
class PageableUtilTest {

    private static final Sort DEFAULT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void nullOrBlank_usesDefault() {
        assertThat(PageableUtil.parseSort(null, DEFAULT)).isEqualTo(DEFAULT);
        assertThat(PageableUtil.parseSort("", DEFAULT)).isEqualTo(DEFAULT);
        assertThat(PageableUtil.parseSort("   ", DEFAULT)).isEqualTo(DEFAULT);
    }

    @Test
    void parsesFieldAndDirection() {
        assertThat(PageableUtil.parseSort("name,asc", DEFAULT)).isEqualTo(Sort.by(Sort.Direction.ASC, "name"));
        assertThat(PageableUtil.parseSort("name,desc", DEFAULT)).isEqualTo(Sort.by(Sort.Direction.DESC, "name"));
    }

    @Test
    void defaultsToAscending_whenDirectionMissingOrUnknown() {
        assertThat(PageableUtil.parseSort("name", DEFAULT)).isEqualTo(Sort.by(Sort.Direction.ASC, "name"));
        assertThat(PageableUtil.parseSort("name,bogus", DEFAULT)).isEqualTo(Sort.by(Sort.Direction.ASC, "name"));
    }

    @Test
    void trimsWhitespaceAroundFieldAndDirection() {
        assertThat(PageableUtil.parseSort("  name  ,  DESC ", DEFAULT)).isEqualTo(Sort.by(Sort.Direction.DESC, "name"));
    }

    @Test
    void malformedEmptyField_usesDefault_ratherThanThrowing() {
        // ",desc" → empty field name (Sort.by would throw IllegalArgumentException → 400);
        // "," → empty split array (parts[0] would throw ArrayIndexOutOfBounds → 500).
        assertThat(PageableUtil.parseSort(",desc", DEFAULT)).isEqualTo(DEFAULT);
        assertThat(PageableUtil.parseSort(",", DEFAULT)).isEqualTo(DEFAULT);
        assertThat(PageableUtil.parseSort(" ,asc", DEFAULT)).isEqualTo(DEFAULT);
    }

    // ── Whitelisted sorting (the table headers send these keys) ──────────────────

    /** Public sort key → the entity property path actually ordered by. */
    private static final Map<String, SortField> ALLOWED = Map.of(
            "name", SortField.text("name"),
            "applicationName", SortField.text("application.name"),
            "createdAt", SortField.value("createdAt"));

    @Test
    void allowedKey_resolvesToItsEntityPropertyPath() {
        Sort.Order order = PageableUtil.parseSort("applicationName,desc", DEFAULT, ALLOWED).iterator().next();
        assertThat(order.getProperty()).isEqualTo("application.name");
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void textColumnsSortCaseInsensitively_butValueColumnsDoNot() {
        // The database collates byte-wise, so a raw text sort would list every capitalized value
        // ahead of every lowercase one. Case folding renders as LOWER(col), which Postgres has no
        // overload for on timestamp/numeric/boolean columns — hence the per-field opt-in.
        assertThat(PageableUtil.parseSort("name,asc", DEFAULT, ALLOWED).iterator().next().isIgnoreCase())
                .isTrue();
        assertThat(PageableUtil.parseSort("createdAt,asc", DEFAULT, ALLOWED).iterator().next().isIgnoreCase())
                .isFalse();
    }

    @Test
    void unknownKey_is400_notAnUnhandledPropertyReferenceException() {
        assertThatThrownBy(() -> PageableUtil.parseSort("passwordHash,asc", DEFAULT, ALLOWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordHash")
                // The message lists what IS sortable, so the caller can correct the request.
                .hasMessageContaining("applicationName");
    }

    @Test
    void blankSort_skipsWhitelistAndUsesDefault() {
        // The default is set by the endpoint, not the caller, so it is not subject to the whitelist.
        assertThat(PageableUtil.parseSort(null, DEFAULT, ALLOWED)).isEqualTo(DEFAULT);
        assertThat(PageableUtil.parseSort("", DEFAULT, ALLOWED)).isEqualTo(DEFAULT);
        assertThat(PageableUtil.parseSort(",desc", DEFAULT, ALLOWED)).isEqualTo(DEFAULT);
    }

    @Test
    void blankSort_withUnsortedDefault_staysUnsorted() {
        // The endpoints whose repository owns the canonical order (remediation queue, vulnerability
        // list) pass Sort.unsorted() as the default — that must survive the whitelist untouched.
        assertThat(PageableUtil.parseSort(null, Sort.unsorted(), ALLOWED).isUnsorted()).isTrue();
        assertThat(PageableUtil.parseSort("", Sort.unsorted(), ALLOWED).isUnsorted()).isTrue();
    }

    @Test
    void dynamicKey_passesThroughUnchanged_whenThePredicateAcceptsIt() {
        // The per-remediation-stage vulnerability columns can't be enumerated up front.
        assertThat(PageableUtil.parseSort("stage:abc-123,desc", DEFAULT, ALLOWED,
                key -> key.startsWith("stage:")))
                .isEqualTo(Sort.by(Sort.Direction.DESC, "stage:abc-123"));
    }

    @Test
    void dynamicKey_stillRejectsWhatThePredicateDeclines() {
        assertThatThrownBy(() -> PageableUtil.parseSort("other:abc,asc", DEFAULT, ALLOWED,
                key -> key.startsWith("stage:")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_appliesPageAndSizeAlongsideTheResolvedSort() {
        Pageable pageable = PageableUtil.of(2, 25, "applicationName,desc", DEFAULT, ALLOWED);
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort().iterator().next().getProperty()).isEqualTo("application.name");
    }
}
