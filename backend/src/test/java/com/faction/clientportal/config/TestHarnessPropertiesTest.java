package com.faction.clientportal.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the property {@code faction.sla.recalculate-on-config-change} = {@code false} for the whole
 * test harness, not just core's own suite.
 *
 * <p>{@code src/test/resources/application-test.yml} sets the switch off so that a {@code PUT} to
 * the workflow config in one test does not race a background SLA recalculation against another
 * test's findings ({@code SlaRecalculationServiceTest} calls the recalculation synchronously
 * instead). But that file only ships in core's <em>test</em>-jar. The enterprise module re-runs
 * core's suite with core's <em>main</em> jar on the classpath ahead of core's test-jar
 * ({@code dependenciesToScan} in {@code enterprise/backend/pom.xml}), so Spring resolves
 * {@code application-test.yml} from core's <em>main</em> resources instead — where the switch
 * defaults to {@code true} ({@code src/main/resources/application.yml}) — and the async SLA
 * listener runs in the background in every enterprise test that edits the workflow config.
 *
 * <p>{@link TestContainersConfig} is published in core's test-jar and is the shared base every
 * {@code @SpringBootTest} in both builds extends, so registering the property there as a
 * {@code @DynamicPropertySource} makes it authoritative regardless of which {@code
 * application-test.yml} Spring happened to resolve.
 *
 * <p>This test extends {@link TestContainersConfig} with no other annotations and no
 * {@code @MockBean}, so it reuses the Spring context already cached by every other plain
 * {@code @SpringBootTest @ActiveProfiles("test")} test rather than starting a new one — the
 * enterprise build runs short on database connections if every test class stands up its own
 * context.
 */
@SpringBootTest
@ActiveProfiles("test")
class TestHarnessPropertiesTest extends TestContainersConfig {

    @Autowired
    private Environment environment;

    @Test
    void recalculateOnConfigChangeIsOffForEveryTestRun() {
        assertThat(environment.getProperty("faction.sla.recalculate-on-config-change"))
                .isEqualTo("false");
    }
}
