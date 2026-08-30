package com.faction.clientportal.util;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Ordering for the list endpoints that cannot push their sort into the query — either because
 * row-level visibility is a per-row check (the scoped branches of the application/organization/user
 * searches) or because the column being sorted is a display value resolved after the fetch.
 *
 * <p>These paths already materialize the full result set before slicing it into a page, so sorting
 * here costs nothing extra. Where a query <em>can</em> do the ordering, it should — this is the
 * fallback, not the default.
 */
public final class InMemorySort {

    private InMemorySort() {}

    /**
     * Returns {@code rows} ordered by {@code pageable}'s sort, or the list unchanged when it is
     * unsorted or names a key {@code comparators} does not cover. The input is never mutated.
     *
     * @param idAccessor a unique per-row value used as the final tiebreaker, so paging over rows
     *                   with equal sort keys can't repeat or skip one between requests
     */
    public static <T> List<T> apply(List<T> rows, Pageable pageable,
                                    Map<String, Comparator<T>> comparators,
                                    Function<T, String> idAccessor) {
        if (rows.size() < 2 || pageable.getSort().isUnsorted()) return rows;

        Sort.Order order = pageable.getSort().iterator().next();
        Comparator<T> comparator = comparators.get(order.getProperty());
        if (comparator == null) return rows;

        comparator = comparator.thenComparing(idAccessor, Comparator.nullsLast(Comparator.naturalOrder()));
        List<T> sorted = new ArrayList<>(rows);
        sorted.sort(order.isDescending() ? comparator.reversed() : comparator);
        return sorted;
    }

    /** Case-insensitive, nulls-last comparator over a text column. */
    public static <T> Comparator<T> byText(Function<T, String> accessor) {
        return Comparator.comparing(accessor, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /** Natural-order, nulls-last comparator over a comparable column (dates, numbers, enums). */
    public static <T, U extends Comparable<? super U>> Comparator<T> byValue(Function<T, U> accessor) {
        return Comparator.comparing(accessor, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
