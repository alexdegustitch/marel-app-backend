package com.aleksandarparipovic.marel_app.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * Base for integration tests: a real PostgreSQL 18, schema built the way
 * production is.
 *
 * <p>H2 is deliberately not used. Almost everything worth testing here is
 * PostgreSQL-specific — partial unique indexes, {@code jsonb} columns and their
 * check constraints, {@code FOR UPDATE SKIP LOCKED}, generated columns, and the
 * audit triggers. An in-memory database would happily accept statements the real
 * one rejects.
 *
 * <p>The container is started once for the whole suite. Tests are transactional
 * and roll back, so they do not observe each other's writes.
 *
 * <p>The schema itself is Flyway's job, not this class's. {@code @DynamicPropertySource}
 * publishes the container's JDBC URL before the Spring context refreshes, so
 * Spring Boot's Flyway auto-configuration migrates it at startup — same
 * {@code src/main/resources/db/migration} scripts, same mechanism a real
 * deployment uses. Before Flyway, this class copied a schema snapshot and 73
 * migration scripts into the container and ran them by hand through psql; that
 * code is gone because Flyway now does the one thing it existed to prove.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"))
                    .withDatabaseName("marel_test")
                    .withUsername("marel")
                    .withPassword("marel");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Re-run ONE archived migration script against the data a test has just seeded.
     *
     * <p>For the migrations whose whole job was to transform existing rows. Their
     * own {@code DO $$} verification blocks check whatever they find in the
     * database they run against — which on a Flyway-built schema is nothing they
     * were meant to transform — so the only way left to test that logic is to give
     * it a known input and read back what it produced.
     *
     * <p>Reads from {@code src/main/resources/sql/archive}, not from any active
     * migration path — these scripts do not run on their own again. Runs through
     * psql on its own connection, so the seeded rows must be COMMITTED: a test
     * using this cannot be {@code @Transactional} and has to clean up after itself.
     */
    protected static void runMigrationScript(String fileName) {
        try {
            copyAndRun(Path.of("src/main/resources/sql/archive", fileName), "rerun-" + fileName);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to re-run " + fileName, ex);
        }
    }

    private static void copyAndRun(Path hostPath, String name) throws Exception {
        String target = "/tmp/" + name;
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(hostPath), target);

        Container.ExecResult result = POSTGRES.execInContainer(
                "psql", "-v", "ON_ERROR_STOP=1", "-q",
                "-U", POSTGRES.getUsername(),
                "-d", POSTGRES.getDatabaseName(),
                "-f", target);

        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    "Script " + name + " failed:\n" + result.getStderr());
        }
    }
}
