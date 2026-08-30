package com.faction.clientportal.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Builds a {@link Pageable} from the {@code page}/{@code size}/{@code sort} request
 * parameters our list endpoints expose. Centralizes the {@code "field,dir"} sort parsing
 * that was otherwise copy-pasted across every paginated controller.
 *
 * <p>The table headers in the UI send the {@code sort} key, so the value is untrusted
 * input. Prefer the {@code allowedFields} overloads: they map a public sort key onto the
 * entity property path to order by, which both keeps DTO field names decoupled from the
 * persistence model and stops a caller ordering by an arbitrary (possibly sensitive)
 * column. An unmapped key is rejected with {@link IllegalArgumentException}, which
 * {@code GlobalExceptionHandler} renders as a 400 rather than the 500 that Spring Data's
 * {@code PropertyReferenceException} would produce.
 */
public final class PageableUtil {

    private PageableUtil() {}

    /**
     * A whitelisted sort target: the entity property path to order by, and whether that column is
     * text.
     *
     * <p>Text columns are ordered case-insensitively. The database sorts byte-wise — {@code Apple,
     * Zebra, banana, cherry} — so ordering a name column raw groups every capitalized value ahead
     * of every lowercase one, which reads as broken. Case folding has to be opt-in per field
     * because it renders as {@code LOWER(col)}, and Postgres has no {@code lower()} for timestamp,
     * numeric, or boolean columns — applying it blanket would turn every date sort into an error.
     */
    public record SortField(String path, boolean caseInsensitive) {

        /** A text column: ordered case-insensitively. */
        public static SortField text(String path) {
            return new SortField(path, true);
        }

        /** A date, number, boolean, or enum column: ordered by its natural value. */
        public static SortField value(String path) {
            return new SortField(path, false);
        }
    }

    /** {@code page}/{@code size} with a parsed sort; blank/absent sort → unsorted (repository default order applies). */
    public static Pageable of(int page, int size, String sort) {
        return of(page, size, sort, Sort.unsorted());
    }

    /** {@code page}/{@code size} with a parsed sort; blank/absent sort → {@code defaultSort}. */
    public static Pageable of(int page, int size, String sort, Sort defaultSort) {
        return PageRequest.of(page, size, parseSort(sort, defaultSort));
    }

    /**
     * {@code page}/{@code size} with a sort restricted to {@code allowedFields}, a map of
     * public sort key → entity property path (e.g. {@code "applicationName" → "application.name"}).
     * Blank/absent sort yields {@code defaultSort}; an unrecognized key is a 400.
     */
    public static Pageable of(int page, int size, String sort, Sort defaultSort,
                              Map<String, SortField> allowedFields) {
        return of(page, size, sort, defaultSort, allowedFields, key -> false);
    }

    /**
     * As above, but {@code dynamicKey} additionally admits keys that cannot be enumerated up front
     * — the per-remediation-stage vulnerability columns, whose names carry a configured stage id.
     * A key it accepts is passed through to the query unchanged, so the query must bind it rather
     * than interpolate it.
     */
    public static Pageable of(int page, int size, String sort, Sort defaultSort,
                              Map<String, SortField> allowedFields, Predicate<String> dynamicKey) {
        return PageRequest.of(page, size, parseSort(sort, defaultSort, allowedFields, dynamicKey));
    }

    /**
     * Parse a {@code "field,dir"} sort string, resolving the field through
     * {@code allowedFields}. A key that is not in the map throws
     * {@link IllegalArgumentException}.
     */
    public static Sort parseSort(String sort, Sort defaultSort, Map<String, SortField> allowedFields) {
        return parseSort(sort, defaultSort, allowedFields, key -> false);
    }

    public static Sort parseSort(String sort, Sort defaultSort, Map<String, SortField> allowedFields,
                                 Predicate<String> dynamicKey) {
        Sort parsed = parseSort(sort, defaultSort);
        if (parsed == defaultSort || parsed.isUnsorted()) return parsed;

        Sort.Order order = parsed.iterator().next();
        if (dynamicKey.test(order.getProperty())) {
            return Sort.by(order.getDirection(), order.getProperty());
        }
        SortField field = allowedFields.get(order.getProperty());
        if (field == null) {
            throw new IllegalArgumentException(
                    "Cannot sort by '" + order.getProperty() + "'. Sortable fields: "
                            + String.join(", ", new TreeSet<>(allowedFields.keySet())));
        }
        Sort.Order resolved = new Sort.Order(order.getDirection(), field.path());
        return Sort.by(field.caseInsensitive() ? resolved.ignoreCase() : resolved);
    }

    /**
     * Parse a {@code "field,dir"} sort string (e.g. {@code "name,asc"}, {@code "createdAt,desc"}).
     * Direction is ascending unless the second token is {@code "desc"} (case-insensitive).
     * A null/blank string yields {@code defaultSort}.
     */
    public static Sort parseSort(String sort, Sort defaultSort) {
        if (sort == null || sort.isBlank()) return defaultSort;
        String[] parts = sort.split(",");
        // A malformed value like ",desc" leaves an empty field name, and "," splits to an empty
        // array entirely — both would make Sort.by throw, so fall back to the default order.
        if (parts.length == 0 || parts[0].trim().isEmpty()) return defaultSort;
        String field = parts[0].trim();
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, field);
    }
}
