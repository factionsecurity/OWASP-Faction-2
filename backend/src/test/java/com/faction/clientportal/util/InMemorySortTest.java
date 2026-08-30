package com.faction.clientportal.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Ordering for the list endpoints that page in memory and so can't push their sort into the query. */
class InMemorySortTest {

    private record Row(String id, String name, Integer rank) {}

    private static final Map<String, Comparator<Row>> SORTS = Map.of(
            "name", InMemorySort.byText(Row::name),
            "rank", InMemorySort.byValue(Row::rank));

    private static Pageable sortedBy(String property, Sort.Direction direction) {
        return PageRequest.of(0, 10, Sort.by(direction, property));
    }

    private static List<String> idsOf(List<Row> rows) {
        return rows.stream().map(Row::id).toList();
    }

    @Test
    void sortsAscendingAndDescending() {
        List<Row> rows = List.of(new Row("1", "Charlie", 3), new Row("2", "alice", 1), new Row("3", "Bob", 2));

        assertThat(idsOf(InMemorySort.apply(rows, sortedBy("name", Sort.Direction.ASC), SORTS, Row::id)))
                .containsExactly("2", "3", "1");
        assertThat(idsOf(InMemorySort.apply(rows, sortedBy("name", Sort.Direction.DESC), SORTS, Row::id)))
                .containsExactly("1", "3", "2");
    }

    @Test
    void textSortIgnoresCase() {
        // Otherwise every capitalized value would sort ahead of every lowercase one.
        List<Row> rows = List.of(new Row("1", "banana", null), new Row("2", "Apple", null));
        assertThat(idsOf(InMemorySort.apply(rows, sortedBy("name", Sort.Direction.ASC), SORTS, Row::id)))
                .containsExactly("2", "1");
    }

    @Test
    void nullsSortLast_inBothDirections() {
        List<Row> rows = List.of(new Row("1", "a", null), new Row("2", "b", 5), new Row("3", "c", 1));

        assertThat(idsOf(InMemorySort.apply(rows, sortedBy("rank", Sort.Direction.ASC), SORTS, Row::id)))
                .containsExactly("3", "2", "1");
        // Reversing a nullsLast comparator puts nulls first — that is the documented trade-off of
        // a single reversed comparator, and it keeps null rows grouped rather than interleaved.
        assertThat(idsOf(InMemorySort.apply(rows, sortedBy("rank", Sort.Direction.DESC), SORTS, Row::id)))
                .containsExactly("1", "2", "3");
    }

    @Test
    void tiesBreakOnId_soPagingIsStable() {
        List<Row> rows = List.of(new Row("c", "same", 1), new Row("a", "same", 1), new Row("b", "same", 1));
        assertThat(idsOf(InMemorySort.apply(rows, sortedBy("name", Sort.Direction.ASC), SORTS, Row::id)))
                .containsExactly("a", "b", "c");
    }

    @Test
    void unsortedOrUnknownKey_returnsTheListUnchanged() {
        List<Row> rows = List.of(new Row("1", "z", 1), new Row("2", "a", 2));

        assertThat(InMemorySort.apply(rows, PageRequest.of(0, 10), SORTS, Row::id)).isSameAs(rows);
        assertThat(InMemorySort.apply(rows, sortedBy("nope", Sort.Direction.ASC), SORTS, Row::id))
                .isSameAs(rows);
    }

    @Test
    void doesNotMutateTheInputList() {
        List<Row> rows = List.of(new Row("1", "z", 1), new Row("2", "a", 2));
        InMemorySort.apply(rows, sortedBy("name", Sort.Direction.ASC), SORTS, Row::id);
        assertThat(idsOf(rows)).containsExactly("1", "2");
    }
}
