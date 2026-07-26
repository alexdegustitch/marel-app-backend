package com.aleksandarparipovic.marel_app.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
        loadSchema();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Builds the schema the way a human does: the baseline, then every migration
     * script in filename order, each through {@code psql}.
     *
     * <p>psql rather than JDBC because these scripts are not single statements —
     * they use dollar-quoted {@code DO $$ ... $$} blocks and function bodies that a
     * plain {@code Statement.execute} mangles. {@code ON_ERROR_STOP=1} means a
     * broken script fails the suite loudly instead of leaving a half-built schema.
     *
     * <p>The baseline is a snapshot of the schema AFTER the 2026-07-21 migrations,
     * so re-applying them proves they are valid against the real schema and safely
     * re-runnable. It does not re-prove the forward migration from the older
     * schema — that was verified against a clone of the dev database and would need
     * a pre-migration baseline to automate.
     */
    private static void loadSchema() {
        try {
            copyAndRun(Path.of("src/test/resources/db/baseline-schema.sql"), "baseline.sql");
            // audit_trigger_fn resolves table_id/action_id by name, so without these
            // rows every insert into an audited table fails on a NOT NULL violation.
            copyAndRun(Path.of("src/test/resources/db/reference-data.sql"), "reference-data.sql");

            for (Path script : migrationScripts()) {
                copyAndRun(script, script.getFileName().toString());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build the test schema", ex);
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

    /**
     * Migration scripts in filename order — which IS their required application
     * order. The 2026-07-21-01..09 numeric prefixes exist for exactly this reason.
     */
    private static List<Path> migrationScripts() throws Exception {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/sql"))) {
            return files
                    .filter(path -> path.getFileName().toString().startsWith("2026-07-21-"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}
