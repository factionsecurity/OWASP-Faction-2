package com.faction.clientportal.config;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class TestContainersConfig {

    static final DockerImageName timescaleImage = DockerImageName.parse("timescale/timescaledb:latest-pg16")
        .asCompatibleSubstituteFor("postgres");

    static final DockerImageName minioImage = DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-08-17T01-24-54Z")
            // MinIO no longer publishes to Docker Hub; the same image lives on quay.io.
            .asCompatibleSubstituteFor("minio/minio");

    static final PostgreSQLContainer<?> postgresqlContainer;

    /**
     * Object storage, for the same reason Postgres is here: several suites go through
     * {@code StorageService} for real (inline images, evidence export, retest screenshots),
     * and without a container they silently depend on a MinIO that happens to be running on
     * the developer's machine — green locally, connection-refused in CI.
     */
    static final MinIOContainer minioContainer;

    static {
        postgresqlContainer = new PostgreSQLContainer<>(timescaleImage)
            .withUsername("admin")
            .withPassword("admin123")
            .withDatabaseName("testdb");
        postgresqlContainer.start();

        minioContainer = new MinIOContainer(minioImage);
        minioContainer.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresqlContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresqlContainer::getUsername);
        registry.add("spring.datasource.password", postgresqlContainer::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.endpoint", minioContainer::getS3URL);
        registry.add("storage.access-key", minioContainer::getUserName);
        registry.add("storage.secret-key", minioContainer::getPassword);
    }

    /**
     * Off in tests: the recalculation runs on its own thread, so a PUT to the workflow config in one
     * test would rewrite other tests' findings in the background. SlaRecalculationServiceTest calls it
     * synchronously instead, and SlaRecalculationServiceListenerTest covers this switch.
     *
     * <p>Registered here, rather than left to {@code src/test/resources/application-test.yml}, because
     * the enterprise overlay re-runs this suite with core's main jar ahead of core's test-jar on its
     * classpath ({@code dependenciesToScan} in {@code enterprise/backend/pom.xml}): Spring then
     * resolves {@code application-test.yml} from core's main resources instead of the test one, where
     * this switch defaults to true. A {@code @DynamicPropertySource} on this shared base — published in
     * the test-jar and extended by every {@code @SpringBootTest} in both builds — applies regardless of
     * which {@code application-test.yml} got picked up.
     */
    @DynamicPropertySource
    static void slaProperties(DynamicPropertyRegistry registry) {
        registry.add("faction.sla.recalculate-on-config-change", () -> "false");
    }
}
