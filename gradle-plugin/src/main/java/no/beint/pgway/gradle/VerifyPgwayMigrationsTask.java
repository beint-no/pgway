package no.beint.pgway.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import no.beint.pgway.FilesystemMigrationSource;
import no.beint.pgway.MigrationResult;
import no.beint.pgway.MigrationSource;
import no.beint.pgway.Pgway;
import no.beint.pgway.PgwayValidationException;
import no.beint.pgway.RawMigrationResource;
import no.beint.pgway.ValidationResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Verifies all SQL migrations in {@code src/main/resources/db/migration} by running strict offline
 * validation followed by a full replay against a disposable PostgreSQL 19+ database.
 */
@DisableCachingByDefault(because = "Verifies migrations against the PostgreSQL 19+ server binaries installed on this host")
public abstract class VerifyPgwayMigrationsTask extends DefaultTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getMigrationsDir();

    @Input
    public abstract Property<String> getSchema();

    @Input
    @Optional
    public abstract Property<String> getPostgresBin();

    @OutputFile
    public abstract RegularFileProperty getReceiptFile();

    @TaskAction
    public void verify() {
        Path migrationsPath = getMigrationsDir().get().getAsFile().toPath();
        String schema = getSchema().get();

        // Read migration files from disk once and reuse the snapshot across all verification steps.
        List<RawMigrationResource> snapshot;
        try {
            snapshot = new FilesystemMigrationSource(migrationsPath).load();
        } catch (IOException e) {
            throw new GradleException("Failed to read migrations from " + migrationsPath.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        MigrationSource cachedSource = () -> snapshot;

        // 1. Offline validation: filenames, UTF-8, duplicates, directives, SQL lexing
        ValidationResult offline = Pgway.validateSource(cachedSource);
        if (!offline.valid()) {
            throw new PgwayValidationException(offline.issues());
        }

        // 2. Full replay against a disposable PostgreSQL 19+ database + idempotency check
        try (var cluster = new DisposablePostgresCluster(getPostgresBin().getOrNull())) {
            Pgway pgway = Pgway.configure()
                .dataSource(cluster.dataSource())
                .schema(schema)
                .source(cachedSource)
                .create();

            MigrationResult first = pgway.migrate();
            if (first.appliedCount() == 0) {
                throw new GradleException(
                    "No SQL migrations found in " + migrationsPath.toAbsolutePath()
                        + " (remove the no.beint.pgway plugin from modules that do not own migrations)"
                );
            }
            pgway.validateOrThrow();

            MigrationResult second = pgway.migrate();
            if (second.appliedCount() != 0) {
                throw new GradleException(
                    "Expected 0 pending migrations on idempotency re-run, but applied " + second.appliedCount()
                );
            }

            Path receipt = getReceiptFile().get().getAsFile().toPath();
            Files.createDirectories(receipt.getParent());
            Files.writeString(
                receipt,
                "schema=" + schema + "\n"
                    + "migrations=" + first.appliedCount() + "\n"
                    + "version=V" + first.currentVersion() + "\n"
                    + "v1.checksum=" + first.applied().getFirst().checksum() + "\n"
            );
            getLogger().lifecycle(
                "Pgway verified {} migration(s) for schema '{}' against PostgreSQL 19+ ({} ms)",
                first.appliedCount(),
                schema,
                first.totalDuration().toMillis()
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GradleException("Pgway build-time verification failed: " + e.getMessage(), e);
        }
    }
}
