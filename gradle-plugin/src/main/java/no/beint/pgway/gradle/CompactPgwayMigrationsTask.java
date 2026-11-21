package no.beint.pgway.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import no.beint.pgway.FilesystemMigrationSource;
import no.beint.pgway.MigrationResult;
import no.beint.pgway.MigrationSource;
import no.beint.pgway.Pgway;
import no.beint.pgway.PgwayValidationException;
import no.beint.pgway.RawMigrationResource;
import no.beint.pgway.SqlScriptParser;
import no.beint.pgway.SqlStatement;
import no.beint.pgway.ValidationResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Compacts {@code V1..Vn} SQL migrations into a single, idiomatic, minimal {@code V1} migration
 * with an embedded {@code -- pgway: replaces=V<n>:<chain-sha256>} directive so existing databases
 * compact their {@code pgway_history} automatically on next startup.
 *
 * <p>Before touching any file on disk, this task proves against a disposable PostgreSQL 19+
 * instance that:
 * <ol>
 *   <li>No migration seeds table rows (refuses to compact data-seeding migrations).</li>
 *   <li>Replaying the compacted {@code V1} produces a 100% catalog-equivalent database.</li>
 *   <li>Running {@code Pgway.migrate()} with the compacted {@code V1} against an existing
 *       {@code V1..Vn} database seamlessly compacts {@code pgway_history} with zero errors.</li>
 * </ol>
 */
@DisableCachingByDefault(because = "Rewrites migration files in-place on explicit developer invocation")
public abstract class CompactPgwayMigrationsTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getMigrationsDir();

    @Input
    public abstract Property<String> getSchema();

    @Input
    @Optional
    public abstract Property<String> getPostgresBin();

    @TaskAction
    public void compact() {
        Path migrationsPath = getMigrationsDir().get().getAsFile().toPath();
        String schema = getSchema().get();
        String pgBin = getPostgresBin().getOrNull();

        try {
            CompactionSummary summary = compactInPlace(migrationsPath, schema, pgBin);
            if (!summary.preservedBlocks().isEmpty()) {
                getLogger().lifecycle(
                    "Pgway preserved {} conditional role-grant DO block(s) from {}",
                    summary.preservedBlocks().size(),
                    summary.preservedBlocks().stream().map(SchemaCompactor.PreservedBlock::sourceScript).distinct().collect(Collectors.joining(", "))
                );
            }
            getLogger().lifecycle(
                "Pgway compacted V1..V{} -> {} ({} tables, {} indexes, {} views, {} functions, {} triggers; {} -> {} bytes; 100% catalog-equivalent & auto-compaction verified)",
                summary.compactedFromVersion(),
                summary.targetV1ScriptName(),
                summary.output().tableCount(),
                summary.output().indexCount(),
                summary.output().viewCount(),
                summary.output().functionCount(),
                summary.output().triggerCount(),
                summary.originalBytes(),
                summary.compactedBytes()
            );
        } catch (IOException | SQLException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GradleException("Pgway compaction failed: " + e.getMessage(), e);
        }
    }

    record CompactionSummary(
        int compactedFromVersion,
        String targetV1ScriptName,
        SchemaCompactor.CompactionOutput output,
        List<SchemaCompactor.PreservedBlock> preservedBlocks,
        long originalBytes,
        long compactedBytes,
        int catalogObjectCount
    ) {}

    static CompactionSummary compactInPlace(Path migrationsPath, String schema, String postgresBin)
            throws IOException, SQLException, InterruptedException {
        CompactionPlan plan = buildAndVerifyCompaction(migrationsPath, schema, postgresBin);

        // Write compacted V1 first, then remove V2..Vn.
        Path targetV1Path = migrationsPath.resolve(plan.targetV1ScriptName());
        Files.writeString(targetV1Path, plan.summary().output().sql(), StandardCharsets.UTF_8);
        for (String obsoleteScript : plan.scriptsToDelete()) {
            Files.deleteIfExists(migrationsPath.resolve(obsoleteScript));
        }
        return plan.summary();
    }

    record CompactionPlan(
        String targetV1ScriptName,
        List<String> scriptsToDelete,
        CompactionSummary summary
    ) {}

    /**
     * Builds, verifies (100% catalog equivalence + live auto-compaction proof), and returns the
     * compacted {@code V1} plan without modifying any files on disk.
     */
    static CompactionPlan buildAndVerifyCompaction(Path migrationsPath, String schema, String postgresBin)
            throws IOException, SQLException, InterruptedException {
        List<RawMigrationResource> resources = new FilesystemMigrationSource(migrationsPath).load();
        if (resources.size() < 2) {
            throw new GradleException(
                "Need at least 2 migrations to compact (found " + resources.size() + " in "
                    + migrationsPath.toAbsolutePath() + ")"
            );
        }

        MigrationSource cachedSource = () -> resources;
        ValidationResult offline = Pgway.validateSource(cachedSource);
        if (!offline.valid()) {
            throw new PgwayValidationException(offline.issues());
        }

        // Sort resources by version to identify V1 filename and V2..Vn files to remove.
        List<RawMigrationResource> sorted = resources.stream()
            .sorted(Comparator.comparingInt(r -> extractVersion(r.name())))
            .toList();
        String v1Name = sorted.getFirst().name();
        List<String> toDelete = sorted.subList(1, sorted.size()).stream()
            .map(RawMigrationResource::name)
            .toList();
        long originalBytes = 0;
        for (RawMigrationResource r : sorted) {
            originalBytes += r.bytes().length;
        }

        List<SchemaCompactor.PreservedBlock> preservedBlocks = extractConditionalRoleGrantBlocks(sorted);

        try (var cluster = new DisposablePostgresCluster(postgresBin)) {
            cluster.createDatabase("orig");
            cluster.createDatabase("compact");

            // 1. Replay V1..Vn into `orig`
            Pgway origPgway = Pgway.configure()
                .dataSource(cluster.dataSource("orig"))
                .schema(schema)
                .source(cachedSource)
                .create();
            MigrationResult origResult = origPgway.migrate();
            int throughVersion = origResult.currentVersion();
            String chainHash = Pgway.historyChainChecksum(origResult.applied());

            // 2. Refuse if any user table contains seeded rows, capture snapshot, and generate compacted V1
            CatalogSnapshot origSnapshot;
            SchemaCompactor.CompactionOutput output;
            try (Connection origConn = cluster.dataSource("orig").getConnection()) {
                List<String> nonEmptyTables = CatalogSnapshot.findNonEmptyTables(origConn, schema);
                if (!nonEmptyTables.isEmpty()) {
                    throw new GradleException(
                        "Cannot compact migrations because table(s) contain seeded rows: "
                            + String.join(", ", nonEmptyTables)
                            + ". Schema compaction only compacts DDL; seed data would be lost."
                    );
                }
                origSnapshot = CatalogSnapshot.capture(origConn, schema);
                output = SchemaCompactor.compact(origConn, schema, throughVersion, chainHash, preservedBlocks);
            }

            byte[] compactedBytes = output.sql().getBytes(StandardCharsets.UTF_8);
            MigrationSource compactedSource = () -> List.of(
                new RawMigrationResource(v1Name, "compacted:" + v1Name, compactedBytes)
            );

            // 3. Offline validate the generated V1
            ValidationResult compactedOffline = Pgway.validateSource(compactedSource);
            if (!compactedOffline.valid()) {
                throw new GradleException(
                    "Generated compacted V1 failed offline validation: " + compactedOffline.issues()
                );
            }

            // 4. Replay compacted V1 into `compact` and prove 100% catalog equivalence
            Pgway compactPgway = Pgway.configure()
                .dataSource(cluster.dataSource("compact"))
                .schema(schema)
                .source(compactedSource)
                .create();
            compactPgway.migrate();
            compactPgway.validateOrThrow();

            CatalogSnapshot compactSnapshot;
            try (Connection compactConn = cluster.dataSource("compact").getConnection()) {
                compactSnapshot = CatalogSnapshot.capture(compactConn, schema);
            }
            List<String> diffs = origSnapshot.diff(compactSnapshot);
            if (!diffs.isEmpty()) {
                String preview = diffs.stream().limit(20).collect(Collectors.joining("\n"));
                throw new GradleException(
                    "Compacted V1 is NOT catalog-equivalent to V1..V" + throughVersion
                        + " (" + diffs.size() + " difference(s)):\n" + preview
                );
            }

            // 5. Prove live seamless history compaction against `orig` (which currently has V1..Vn in pgway_history)
            Pgway upgradePgway = Pgway.configure()
                .dataSource(cluster.dataSource("orig"))
                .schema(schema)
                .source(compactedSource)
                .create();
            upgradePgway.validateOrThrow();
            MigrationResult upgradeResult = upgradePgway.migrate();
            if (upgradeResult.compactedFromVersion() != throughVersion
                    || upgradeResult.appliedCount() != 0
                    || upgradeResult.currentVersion() != 1) {
                throw new GradleException(
                    "Seamless history compaction verification failed: expected compactedFromVersion="
                        + throughVersion + ", appliedCount=0, currentVersion=1, but got " + upgradeResult
                );
            }
            upgradePgway.validateOrThrow();

            CompactionSummary summary = new CompactionSummary(
                throughVersion,
                v1Name,
                output,
                preservedBlocks,
                originalBytes,
                compactedBytes.length,
                origSnapshot.objectCount()
            );
            return new CompactionPlan(v1Name, toDelete, summary);
        }
    }

    /**
     * Detects conditional external-role grant {@code DO} blocks (e.g. {@code IF EXISTS (SELECT FROM pg_roles WHERE ...)}
     * granting privileges to roles provisioned outside migrations) so compaction preserves them verbatim.
     */
    static List<SchemaCompactor.PreservedBlock> extractConditionalRoleGrantBlocks(List<RawMigrationResource> sorted) {
        List<SchemaCompactor.PreservedBlock> preserved = new ArrayList<>();
        for (RawMigrationResource r : sorted) {
            String normalized = SqlScriptParser.normalizeSql(new String(r.bytes(), StandardCharsets.UTF_8));
            SqlScriptParser.ParsedScript parsed = SqlScriptParser.parse(r.name(), normalized);
            for (SqlStatement stmt : parsed.statements()) {
                String sql = stmt.sql().strip();
                String upper = sql.toUpperCase(Locale.ROOT);
                if ((upper.startsWith("DO ") || upper.startsWith("DO$") || upper.startsWith("DO\n"))
                        && upper.contains("PG_ROLES")
                        && (upper.contains("GRANT ") || upper.contains("ALTER DEFAULT PRIVILEGES"))) {
                    preserved.add(new SchemaCompactor.PreservedBlock(r.name(), sql));
                }
            }
        }
        return preserved;
    }

    private static int extractVersion(String scriptName) {
        int slash = scriptName.lastIndexOf('/');
        String base = slash >= 0 ? scriptName.substring(slash + 1) : scriptName;
        int sep = base.indexOf("__");
        return Integer.parseInt(base.substring(1, sep));
    }
}
