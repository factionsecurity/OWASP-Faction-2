package com.faction.clientportal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the trigram indexes behind global search, concurrently, on every boot.
 *
 * <p>Global search matches text anywhere in a name ({@code LOWER(name) LIKE '%term%'}), which a
 * btree index cannot serve: every search would read the whole vulnerabilities table. A
 * {@code pg_trgm} GIN index on the same {@code LOWER(...)} expression the queries use answers it
 * from the index instead.
 *
 * <p>{@code pg_trgm} ships with PostgreSQL but creating it can need rights a locked-down database
 * user lacks. Search works without it — the same queries, just unindexed — so a failure here is
 * logged and startup carries on, like {@link ConcurrentIndexBuilder}.
 *
 * <p>Not a Flyway migration for the reasons given on {@link VulnerabilitySlaIndexInitializer}: a
 * concurrent build cannot run in a transaction, and fresh installs get their tables from Hibernate
 * after Flyway has run.
 */
@Component
@Slf4j
public class SearchIndexInitializer {

    /** Index name → everything after the name. Trusted constants, never user input. */
    static final String[][] INDEXES = {
            {"idx_vulnerabilities_name_trgm", "ON vulnerabilities USING gin (LOWER(name) gin_trgm_ops)"},
            {"idx_vulnerabilities_tracking_id_trgm", "ON vulnerabilities USING gin (LOWER(tracking_id) gin_trgm_ops)"},
            {"idx_assessments_name_trgm", "ON assessments USING gin (LOWER(name) gin_trgm_ops)"},
            {"idx_applications_name_trgm", "ON applications USING gin (LOWER(name) gin_trgm_ops)"},
            {"idx_applications_app_id_trgm", "ON applications USING gin (LOWER(app_id) gin_trgm_ops)"},
    };

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentIndexBuilder indexBuilder;
    private final boolean enabled;

    public SearchIndexInitializer(
            JdbcTemplate jdbcTemplate,
            ConcurrentIndexBuilder indexBuilder,
            @Value("${faction.search.index-initializer.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.indexBuilder = indexBuilder;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("Search index initializer disabled by configuration");
            return;
        }
        ensureIndexes();
    }

    /** Enables {@code pg_trgm} if it can, then builds or repairs the indexes. */
    public void ensureIndexes() {
        if (!ensureTrigramExtension()) {
            return;
        }
        for (String[] index : INDEXES) {
            indexBuilder.ensure(index[0], index[1]);
        }
    }

    private boolean ensureTrigramExtension() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            return true;
        } catch (Exception e) {
            log.warn("Could not enable pg_trgm ({}); global search still works but its text matching "
                    + "is unindexed. A database administrator can run CREATE EXTENSION pg_trgm to fix it.",
                    e.getMessage());
            return false;
        }
    }
}
