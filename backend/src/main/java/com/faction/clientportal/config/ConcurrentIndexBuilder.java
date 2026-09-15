package com.faction.clientportal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

/**
 * Builds one index with {@code CREATE INDEX CONCURRENTLY}, for the startup index initializers.
 *
 * <p>A no-op once the index exists and is valid. An index left invalid by an interrupted concurrent
 * build is dropped and rebuilt. Never throws: without the index the queries are slower, not wrong, so
 * a failure is logged and startup carries on. Statements run on an autocommit connection, outside any
 * transaction, which {@code CONCURRENTLY} requires.
 */
@Component
@Slf4j
public class ConcurrentIndexBuilder {

    private static final ResultSetExtractor<Boolean> VALIDITY =
            rs -> rs.next() ? rs.getBoolean(1) : null;

    private final JdbcTemplate jdbcTemplate;

    public ConcurrentIndexBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param name       the index name; a trusted constant, never user input
     * @param definition everything after the name, e.g. {@code "ON assessments (workflow_id, status)"};
     *                   a trusted constant, never user input
     */
    public void ensure(String name, String definition) {
        try {
            Boolean valid = jdbcTemplate.query(
                    "SELECT i.indisvalid FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid"
                            + " WHERE c.relname = ?",
                    VALIDITY, name);
            if (Boolean.TRUE.equals(valid)) {
                return;
            }
            if (Boolean.FALSE.equals(valid)) {
                log.warn("Index {} is invalid (an earlier concurrent build was interrupted); rebuilding it", name);
                jdbcTemplate.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name);
            }
            jdbcTemplate.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + name + " " + definition);
            log.info("Built index {}", name);
        } catch (Exception e) {
            log.warn("Could not build index {}: {}", name, e.getMessage(), e);
        }
    }
}
