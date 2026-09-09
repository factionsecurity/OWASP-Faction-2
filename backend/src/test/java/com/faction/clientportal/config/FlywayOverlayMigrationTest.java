package com.faction.clientportal.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The open source build must start against a database the enterprise overlay has migrated.
 *
 * <p>The overlay ships its own migrations, for its own tables. Once one has run, the
 * history table records a version this build has no file for, and Flyway's default answer
 * to that is to refuse to start — which would strand anyone whose enterprise trial ended,
 * and anyone pointing both editions at one local database. Those "missing" migrations are
 * ignored; nothing else is, so a migration whose file changed still fails validation.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayOverlayMigrationTest extends TestContainersConfig {

    /**
     * Two overlay versions: one older than this build's newest migration (Flyway calls that
     * "missing") and one newer than anything it has ("future"). A real overlay migration can
     * be either, depending on which edition last added one.
     */
    private static final String OLDER_OVERLAY_VERSION = "20000101000000";
    private static final String NEWER_OVERLAY_VERSION = "99990101000000";

    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restoreHistory() {
        jdbc.update("DELETE FROM flyway_schema_history WHERE version IN (?, ?)", OLDER_OVERLAY_VERSION, NEWER_OVERLAY_VERSION);
    }

    @Test
    void aMigrationAppliedByTheOverlayDoesNotStopThisBuild() {
        recordApplied(OLDER_OVERLAY_VERSION, "sso config github", "V" + OLDER_OVERLAY_VERSION + "__sso_config_github.sql");
        recordApplied(NEWER_OVERLAY_VERSION, "overlay newer", "V" + NEWER_OVERLAY_VERSION + "__overlay_newer.sql");

        assertThatCode(() -> flyway.validate()).doesNotThrowAnyException();
    }

    @Test
    void everyOtherValidationStillHolds() {
        // A real, applied migration whose checksum no longer matches its file must still fail:
        // the leniency is for versions this build does not know, not for ones it does.
        Integer rank = jdbc.queryForObject(
                "SELECT installed_rank FROM flyway_schema_history WHERE version IS NOT NULL AND success ORDER BY installed_rank LIMIT 1",
                Integer.class);
        jdbc.update("UPDATE flyway_schema_history SET checksum = COALESCE(checksum, 0) + 1 WHERE installed_rank = ?", rank);
        try {
            assertThatThrownBy(() -> flyway.validate()).isInstanceOf(FlywayValidateException.class);
        } finally {
            jdbc.update("UPDATE flyway_schema_history SET checksum = checksum - 1 WHERE installed_rank = ?", rank);
        }
    }

    private void recordApplied(String version, String description, String script) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history", Integer.class);
        jdbc.update("INSERT INTO flyway_schema_history"
                        + " (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)"
                        + " VALUES (?, ?, ?, 'SQL', ?, 0, 'overlay', now(), 1, true)",
                next, version, description, script);
    }
}
