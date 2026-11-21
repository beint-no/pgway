package no.beint.pgway;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Convention-first PostgreSQL 19+ schema migration engine for JDK 27.
 *
 * <p>Pgway is immutable and thread-safe. Concurrent callers targeting the same schema
 * are serialized via PostgreSQL session-level advisory locks.
 */
public final class Pgway {
    private static final String BASELINE_CHECKSUM = "baseline";
    private final PgwayConfig config;

    private Pgway(PgwayConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public static PgwayConfig.Builder configure() {
        return PgwayConfig.builder();
    }

    public static Pgway create(PgwayConfig config) {
        return new Pgway(config);
    }

    /** Offline build-time validation of migration filenames, UTF-8 encoding, duplicates, directives, and SQL syntax. */
    public static ValidationResult validateScripts(List<MigrationSource> sources) {
        MigrationLoader.LoadedMigrations loaded = MigrationLoader.load(sources);
        return loaded.issues().isEmpty() ? ValidationResult.ok() : ValidationResult.failed(loaded.issues());
    }

    /** Offline build-time validation of all {@code .sql} scripts in a filesystem directory. */
    public static ValidationResult validateDirectory(Path directory) {
        return validateScripts(List.of(new FilesystemMigrationSource(directory)));
    }

    public PgwayConfig config() {
        return config;
    }

    /**
     * Validates PostgreSQL 19+, acquires the advisory lock, ensures the schema and history table
     * (optionally baselining a pre-existing non-empty schema), validates history integrity, and applies
     * all pending versioned and repeatable migrations.
     */
    public MigrationResult migrate() {
        long startNanos = System.nanoTime();
        MigrationLoader.LoadedMigrations loaded = MigrationLoader.load(config.sources());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }

        return withLockedSession((connection, baseSettings) -> {
            SchemaHistoryRepository.ensureSchema(connection, config.schema());

            boolean baselined = false;
            boolean historyExists = SchemaHistoryRepository.tableExists(
                connection,
                config.schema(),
                PgwayConfig.HISTORY_TABLE
            );

            if (!historyExists) {
                boolean nonEmptySchema = config.baselineOnMigrate()
                    && SchemaHistoryRepository.schemaHasObjects(
                        connection,
                        config.schema(),
                        PgwayConfig.HISTORY_TABLE
                    );
                connection.setAutoCommit(false);
                try {
                    SchemaHistoryRepository.createHistoryTable(
                        connection,
                        config.schema(),
                        PgwayConfig.HISTORY_TABLE
                    );
                    if (nonEmptySchema) {
                        SchemaHistoryRepository.insertHistoryRow(
                            connection,
                            config.schema(),
                            PgwayConfig.HISTORY_TABLE,
                            config.baselineVersion(),
                            MigrationKind.BASELINE,
                            PgwayConfig.BASELINE_DESCRIPTION,
                            PgwayConfig.BASELINE_DESCRIPTION,
                            BASELINE_CHECKSUM,
                            0,
                            true
                        );
                        baselined = true;
                    }
                    connection.commit();
                } catch (SQLException e) {
                    rollbackQuietly(connection, e);
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            }

            List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                connection,
                config.schema(),
                PgwayConfig.HISTORY_TABLE
            );

            List<ValidationIssue> issues = validateAgainstHistory(loaded, history);
            if (!issues.isEmpty()) {
                throw new PgwayValidationException(issues);
            }

            HistorySummary summary = summarizeHistory(history);
            List<AppliedMigration> newlyApplied = new ArrayList<>();
            int skipped = 0;

            for (MigrationScript script : loaded.versioned()) {
                if (summary.appliedByVersion.containsKey(script.version())) {
                    skipped++;
                    continue;
                }
                if (summary.baselineVersion != null && script.version().compareTo(summary.baselineVersion) <= 0) {
                    skipped++;
                    continue;
                }
                newlyApplied.add(executeMigration(connection, script, baseSettings));
            }

            for (MigrationScript script : loaded.repeatable()) {
                AppliedMigration latest = summary.latestRepeatableByScript.get(script.scriptName());
                if (latest != null && latest.checksum().equals(script.checksum())) {
                    skipped++;
                    continue;
                }
                newlyApplied.add(executeMigration(connection, script, baseSettings));
            }

            MigrationVersion currentVersion = computeCurrentVersion(history, newlyApplied);
            Duration totalDuration = Duration.ofNanos(System.nanoTime() - startNanos);
            return new MigrationResult(newlyApplied, skipped, currentVersion, totalDuration, baselined);
        });
    }

    /**
     * Validates PostgreSQL 19+, migration scripts, and database schema history without mutating the database.
     */
    public ValidationResult validate() {
        MigrationLoader.LoadedMigrations loaded = MigrationLoader.load(config.sources());
        List<ValidationIssue> issues = new ArrayList<>(loaded.issues());

        withReadOnlySession(connection -> {
            if (SchemaHistoryRepository.tableExists(connection, config.schema(), PgwayConfig.HISTORY_TABLE)) {
                List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                issues.addAll(validateAgainstHistory(loaded, history));
            }
            return null;
        });

        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
    }

    /** Calls {@link #validate()} and throws {@link PgwayValidationException} if any issue is found. */
    public void validateOrThrow() {
        ValidationResult result = validate();
        if (!result.valid()) {
            throw new PgwayValidationException(result.issues());
        }
    }

    /** Returns the combined status of all applied and discovered migrations without mutating the database. */
    public List<MigrationInfo> info() {
        MigrationLoader.LoadedMigrations loaded = MigrationLoader.load(config.sources());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }

        List<AppliedMigration> history = withReadOnlySession(connection -> {
            if (!SchemaHistoryRepository.tableExists(connection, config.schema(), PgwayConfig.HISTORY_TABLE)) {
                return List.of();
            }
            return SchemaHistoryRepository.readHistory(connection, config.schema(), PgwayConfig.HISTORY_TABLE);
        });

        HistorySummary summary = summarizeHistory(history);
        Map<MigrationVersion, MigrationScript> scriptsByVersion = new LinkedHashMap<>();
        for (MigrationScript script : loaded.versioned()) {
            scriptsByVersion.put(script.version(), script);
        }

        List<MigrationInfo> result = new ArrayList<>();
        Map<MigrationVersion, Boolean> emittedVersions = new HashMap<>();

        for (AppliedMigration row : history) {
            if (row.kind() == MigrationKind.BASELINE) {
                result.add(new MigrationInfo(
                    MigrationKind.BASELINE,
                    row.version(),
                    row.description(),
                    row.script(),
                    row.success() ? MigrationState.BASELINE : MigrationState.FAILED,
                    row.checksum(),
                    row.checksum(),
                    row.installedOn(),
                    row.executionMs()
                ));
                if (row.version() != null) {
                    emittedVersions.put(row.version(), true);
                }
            } else if (row.kind() == MigrationKind.VERSIONED) {
                MigrationScript script = scriptsByVersion.get(row.version());
                MigrationState state;
                if (!row.success()) {
                    state = MigrationState.FAILED;
                } else if (script == null) {
                    state = MigrationState.MISSING;
                } else if (!script.checksum().equals(row.checksum())) {
                    state = MigrationState.CHECKSUM_MISMATCH;
                } else {
                    state = MigrationState.APPLIED;
                }
                result.add(new MigrationInfo(
                    MigrationKind.VERSIONED,
                    row.version(),
                    row.description(),
                    row.script(),
                    state,
                    script != null ? script.checksum() : null,
                    row.checksum(),
                    row.installedOn(),
                    row.executionMs()
                ));
                emittedVersions.put(row.version(), true);
            }
        }

        for (MigrationScript script : loaded.versioned()) {
            if (emittedVersions.containsKey(script.version())) {
                continue;
            }
            MigrationState state;
            if (summary.baselineVersion != null && script.version().compareTo(summary.baselineVersion) <= 0) {
                state = MigrationState.BELOW_BASELINE;
            } else if (summary.highestAppliedVersion != null
                    && script.version().compareTo(summary.highestAppliedVersion) < 0) {
                state = MigrationState.OUT_OF_ORDER;
            } else {
                state = MigrationState.PENDING;
            }
            result.add(new MigrationInfo(
                MigrationKind.VERSIONED,
                script.version(),
                script.description(),
                script.scriptName(),
                state,
                script.checksum(),
                null,
                null,
                null
            ));
        }

        for (MigrationScript script : loaded.repeatable()) {
            AppliedMigration latest = summary.latestRepeatableByScript.get(script.scriptName());
            if (latest == null) {
                result.add(new MigrationInfo(
                    MigrationKind.REPEATABLE,
                    null,
                    script.description(),
                    script.scriptName(),
                    MigrationState.PENDING,
                    script.checksum(),
                    null,
                    null,
                    null
                ));
            } else if (!latest.success()) {
                result.add(new MigrationInfo(
                    MigrationKind.REPEATABLE,
                    null,
                    script.description(),
                    script.scriptName(),
                    MigrationState.FAILED,
                    script.checksum(),
                    latest.checksum(),
                    latest.installedOn(),
                    latest.executionMs()
                ));
            } else if (!latest.checksum().equals(script.checksum())) {
                result.add(new MigrationInfo(
                    MigrationKind.REPEATABLE,
                    null,
                    script.description(),
                    script.scriptName(),
                    MigrationState.OUTDATED,
                    script.checksum(),
                    latest.checksum(),
                    latest.installedOn(),
                    latest.executionMs()
                ));
            } else {
                result.add(new MigrationInfo(
                    MigrationKind.REPEATABLE,
                    null,
                    script.description(),
                    script.scriptName(),
                    MigrationState.APPLIED,
                    script.checksum(),
                    latest.checksum(),
                    latest.installedOn(),
                    latest.executionMs()
                ));
            }
        }

        return List.copyOf(result);
    }

    /** Removes failed ({@code success = false}) history rows without altering applied checksums. */
    public RepairResult repair() {
        return repair(false);
    }

    /**
     * Under advisory lock, deletes failed ({@code success = false}) history rows and optionally
     * realigns applied versioned checksums and descriptions with the current migration scripts.
     */
    public RepairResult repair(boolean realignChecksums) {
        MigrationLoader.LoadedMigrations loaded = realignChecksums
            ? MigrationLoader.load(config.sources())
            : new MigrationLoader.LoadedMigrations(List.of(), List.of(), List.of());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }

        return withLockedSession((connection, ignored) -> {
            if (!SchemaHistoryRepository.tableExists(connection, config.schema(), PgwayConfig.HISTORY_TABLE)) {
                return new RepairResult(0, 0, List.of());
            }
            List<String> details = new ArrayList<>();
            int failedRemoved;
            int realigned = 0;

            connection.setAutoCommit(false);
            try {
                failedRemoved = SchemaHistoryRepository.deleteFailedRows(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                if (failedRemoved > 0) {
                    details.add("Removed " + failedRemoved + " failed history row(s)");
                }

                if (realignChecksums) {
                    Map<MigrationVersion, MigrationScript> byVersion = new HashMap<>();
                    for (MigrationScript script : loaded.versioned()) {
                        byVersion.put(script.version(), script);
                    }
                    List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                        connection,
                        config.schema(),
                        PgwayConfig.HISTORY_TABLE
                    );
                    for (AppliedMigration row : history) {
                        if (row.kind() != MigrationKind.VERSIONED || !row.success()) {
                            continue;
                        }
                        MigrationScript script = byVersion.get(row.version());
                        if (script != null && (!script.checksum().equals(row.checksum())
                                || !script.description().equals(row.description())
                                || !script.scriptName().equals(row.script()))) {
                            SchemaHistoryRepository.updateChecksumAndDescription(
                                connection,
                                config.schema(),
                                PgwayConfig.HISTORY_TABLE,
                                row.installedRank(),
                                script.checksum(),
                                script.description(),
                                script.scriptName()
                            );
                            realigned++;
                            details.add("Realigned V" + row.version() + " (" + script.scriptName() + ")");
                        }
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            return new RepairResult(failedRemoved, realigned, details);
        });
    }

    /** Baselines an existing database at {@code version}. Fails if the history table already contains rows. */
    public BaselineResult baseline(String version) {
        return baseline(MigrationVersion.parse(version));
    }

    /** Baselines an existing database at {@code version}. Fails if the history table already contains rows. */
    public BaselineResult baseline(MigrationVersion version) {
        Objects.requireNonNull(version, "version must not be null");

        return withLockedSession((connection, ignored) -> {
            SchemaHistoryRepository.ensureSchema(connection, config.schema());
            boolean exists = SchemaHistoryRepository.tableExists(
                connection,
                config.schema(),
                PgwayConfig.HISTORY_TABLE
            );
            if (exists) {
                List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                if (!history.isEmpty()) {
                    throw new PgwayValidationException(new ValidationIssue(
                        ValidationErrorCode.BASELINE_FAILED,
                        null,
                        version,
                        "Cannot baseline: history table "
                            + PgIdentifiers.qualifiedTable(config.schema(), PgwayConfig.HISTORY_TABLE)
                            + " already contains " + history.size() + " row(s)"
                    ));
                }
            }
            connection.setAutoCommit(false);
            try {
                SchemaHistoryRepository.createHistoryTable(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                SchemaHistoryRepository.insertHistoryRow(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE,
                    version,
                    MigrationKind.BASELINE,
                    PgwayConfig.BASELINE_DESCRIPTION,
                    PgwayConfig.BASELINE_DESCRIPTION,
                    BASELINE_CHECKSUM,
                    0,
                    true
                );
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            return new BaselineResult(true, version);
        });
    }

    private AppliedMigration executeMigration(
            Connection connection,
            MigrationScript script,
            SchemaHistoryRepository.SessionSettings baseSettings) throws SQLException {
        config.listener().beforeMigration(script);
        long startNanos = System.nanoTime();
        Duration effectiveLockTimeout = script.directives().lockTimeout() != null
            ? script.directives().lockTimeout()
            : config.migrationLockTimeout();
        Duration effectiveStmtTimeout = script.directives().statementTimeout() != null
            ? script.directives().statementTimeout()
            : config.migrationStatementTimeout();

        if (script.directives().transactional()) {
            return executeTransactional(connection, script, effectiveLockTimeout, effectiveStmtTimeout, baseSettings, startNanos);
        }
        return executeNonTransactional(connection, script, effectiveLockTimeout, effectiveStmtTimeout, baseSettings, startNanos);
    }

    private AppliedMigration executeTransactional(
            Connection connection,
            MigrationScript script,
            Duration lockTimeout,
            Duration statementTimeout,
            SchemaHistoryRepository.SessionSettings baseSettings,
            long startNanos) throws SQLException {
        connection.setAutoCommit(false);
        SqlStatement currentStmt = null;
        try {
            applyMigrationContext(connection, lockTimeout, statementTimeout, true);
            for (SqlStatement sqlStmt : script.statements()) {
                currentStmt = sqlStmt;
                executeSingleStatement(connection, sqlStmt);
            }
            currentStmt = null;
            int elapsedMs = elapsedMillis(startNanos);
            AppliedMigration applied = SchemaHistoryRepository.insertHistoryRow(
                connection,
                config.schema(),
                PgwayConfig.HISTORY_TABLE,
                script.version(),
                script.kind(),
                script.description(),
                script.scriptName(),
                script.checksum(),
                elapsedMs,
                true
            );
            connection.commit();
            config.listener().afterMigration(applied);
            return applied;
        } catch (SQLException e) {
            rollbackQuietly(connection, e);
            PgwayMigrationException ex = buildMigrationException(script, currentStmt, e);
            config.listener().onMigrationFailed(script, ex);
            throw ex;
        } finally {
            try {
                connection.setAutoCommit(true);
                SchemaHistoryRepository.restoreSessionSettings(connection, baseSettings);
            } catch (SQLException ignored) {
                // Handled by outer session cleanup or discarded if connection broke
            }
        }
    }

    private AppliedMigration executeNonTransactional(
            Connection connection,
            MigrationScript script,
            Duration lockTimeout,
            Duration statementTimeout,
            SchemaHistoryRepository.SessionSettings baseSettings,
            long startNanos) throws SQLException {
        connection.setAutoCommit(true);
        SqlStatement currentStmt = null;
        try {
            applyMigrationContext(connection, lockTimeout, statementTimeout, false);
            for (SqlStatement sqlStmt : script.statements()) {
                currentStmt = sqlStmt;
                executeSingleStatement(connection, sqlStmt);
            }
            currentStmt = null;
            int elapsedMs = elapsedMillis(startNanos);
            AppliedMigration applied = SchemaHistoryRepository.insertHistoryRow(
                connection,
                config.schema(),
                PgwayConfig.HISTORY_TABLE,
                script.version(),
                script.kind(),
                script.description(),
                script.scriptName(),
                script.checksum(),
                elapsedMs,
                true
            );
            config.listener().afterMigration(applied);
            return applied;
        } catch (SQLException e) {
            int elapsedMs = elapsedMillis(startNanos);
            try {
                SchemaHistoryRepository.restoreSessionSettings(connection, baseSettings);
                SchemaHistoryRepository.insertHistoryRow(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE,
                    script.version(),
                    script.kind(),
                    script.description(),
                    script.scriptName(),
                    script.checksum(),
                    elapsedMs,
                    false
                );
            } catch (SQLException recordFailure) {
                e.addSuppressed(recordFailure);
            }
            PgwayMigrationException ex = buildMigrationException(script, currentStmt, e);
            config.listener().onMigrationFailed(script, ex);
            throw ex;
        } finally {
            try {
                SchemaHistoryRepository.restoreSessionSettings(connection, baseSettings);
            } catch (SQLException ignored) {
                // Handled by outer session cleanup or discarded if connection broke
            }
        }
    }

    private void applyMigrationContext(
            Connection connection,
            Duration lockTimeout,
            Duration statementTimeout,
            boolean isLocal) throws SQLException {
        String prefix = isLocal ? "SET LOCAL search_path TO " : "SET search_path TO ";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(prefix + PgIdentifiers.quote(config.schema()));
        }
        if (lockTimeout != null) {
            SchemaHistoryRepository.setConfig(
                connection,
                "lock_timeout",
                PgIdentifiers.formatPgDuration(lockTimeout),
                isLocal
            );
        }
        if (statementTimeout != null) {
            SchemaHistoryRepository.setConfig(
                connection,
                "statement_timeout",
                PgIdentifiers.formatPgDuration(statementTimeout),
                isLocal
            );
        }
    }

    private static void executeSingleStatement(Connection connection, SqlStatement sqlStmt) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            boolean hasResultSet = stmt.execute(sqlStmt.sql());
            while (true) {
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        while (rs.next()) {
                            // Drain all rows so lazy SRF/function errors surface immediately.
                        }
                    }
                } else if (stmt.getUpdateCount() == -1) {
                    break;
                }
                hasResultSet = stmt.getMoreResults();
            }
        }
    }

    private static PgwayMigrationException buildMigrationException(
            MigrationScript script,
            SqlStatement failedStmt,
            SQLException cause) {
        int stmtIndex = failedStmt != null ? failedStmt.index() : 0;
        int line = failedStmt != null ? failedStmt.startLine() : 0;
        StringBuilder msg = new StringBuilder();
        msg.append("Migration ").append(script.scriptName());
        if (script.version() != null) {
            msg.append(" (V").append(script.version()).append(")");
        }
        msg.append(" failed");
        if (failedStmt != null) {
            msg.append(" at statement #").append(stmtIndex).append(" (line ").append(line).append(")");
        }
        if (cause.getSQLState() != null) {
            msg.append(" [SQLState ").append(cause.getSQLState()).append("]");
        }
        msg.append(": ").append(cause.getMessage());
        return new PgwayMigrationException(
            script.scriptName(),
            script.version(),
            stmtIndex,
            line,
            cause.getSQLState(),
            msg.toString(),
            cause
        );
    }

    private List<ValidationIssue> validateAgainstHistory(
            MigrationLoader.LoadedMigrations loaded,
            List<AppliedMigration> history) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (AppliedMigration row : history) {
            if (!row.success()) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.FAILED_MIGRATION_IN_HISTORY,
                    row.script(),
                    row.version(),
                    "Failed migration in history (rank=" + row.installedRank() + ", script='" + row.script()
                        + "'). Inspect the database for partial changes and run repair() before migrating."
                ));
            }
        }

        HistorySummary summary = summarizeHistory(history);
        Map<MigrationVersion, MigrationScript> scriptsByVersion = new HashMap<>();
        for (MigrationScript script : loaded.versioned()) {
            scriptsByVersion.put(script.version(), script);
        }

        for (AppliedMigration row : history) {
            if (!row.success() || row.kind() != MigrationKind.VERSIONED) {
                continue;
            }
            MigrationScript script = scriptsByVersion.get(row.version());
            if (script == null) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.MISSING_MIGRATION,
                    row.script(),
                    row.version(),
                    "Applied migration V" + row.version() + " (" + row.script()
                        + ") is missing from migration sources"
                ));
            } else if (!script.checksum().equals(row.checksum())) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.CHECKSUM_MISMATCH,
                    script.scriptName(),
                    row.version(),
                    "Checksum mismatch for V" + row.version() + " (" + script.scriptName()
                        + "): database has " + row.checksum() + ", script has " + script.checksum()
                ));
            }
        }

        if (summary.highestAppliedVersion != null) {
            for (MigrationScript script : loaded.versioned()) {
                if (summary.appliedByVersion.containsKey(script.version())) {
                    continue;
                }
                if (summary.baselineVersion != null && script.version().compareTo(summary.baselineVersion) <= 0) {
                    continue;
                }
                if (script.version().compareTo(summary.highestAppliedVersion) < 0) {
                    issues.add(new ValidationIssue(
                        ValidationErrorCode.OUT_OF_ORDER_PENDING,
                        script.scriptName(),
                        script.version(),
                        "Pending migration V" + script.version() + " (" + script.scriptName()
                            + ") is lower than highest applied version V" + summary.highestAppliedVersion
                    ));
                }
            }
        }

        return issues;
    }

    private record HistorySummary(
            Map<MigrationVersion, AppliedMigration> appliedByVersion,
            Map<String, AppliedMigration> latestRepeatableByScript,
            MigrationVersion highestAppliedVersion,
            MigrationVersion baselineVersion) {}

    private static HistorySummary summarizeHistory(List<AppliedMigration> history) {
        Map<MigrationVersion, AppliedMigration> byVersion = new HashMap<>();
        Map<String, AppliedMigration> latestRepeatable = new HashMap<>();
        MigrationVersion highest = null;
        MigrationVersion baseline = null;

        for (AppliedMigration row : history) {
            if (!row.success()) {
                if (row.kind() == MigrationKind.REPEATABLE) {
                    latestRepeatable.put(row.script(), row);
                }
                continue;
            }
            switch (row.kind()) {
                case VERSIONED -> {
                    byVersion.put(row.version(), row);
                    if (highest == null || row.version().compareTo(highest) > 0) {
                        highest = row.version();
                    }
                }
                case BASELINE -> {
                    baseline = row.version();
                    if (row.version() != null && (highest == null || row.version().compareTo(highest) > 0)) {
                        highest = row.version();
                    }
                }
                case REPEATABLE -> latestRepeatable.put(row.script(), row);
            }
        }
        return new HistorySummary(byVersion, latestRepeatable, highest, baseline);
    }

    private static MigrationVersion computeCurrentVersion(
            List<AppliedMigration> existing,
            List<AppliedMigration> newlyApplied) {
        List<MigrationVersion> versions = new ArrayList<>();
        for (AppliedMigration row : existing) {
            if (row.success() && row.version() != null) {
                versions.add(row.version());
            }
        }
        for (AppliedMigration row : newlyApplied) {
            if (row.success() && row.version() != null) {
                versions.add(row.version());
            }
        }
        return versions.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private static int elapsedMillis(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
    }

    private static void rollbackQuietly(Connection connection, Throwable primary) {
        try {
            connection.rollback();
        } catch (SQLException suppressed) {
            primary.addSuppressed(suppressed);
        }
    }

    @FunctionalInterface
    private interface ReadOnlyWork<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface LockedWork<T> {
        T run(Connection connection, SchemaHistoryRepository.SessionSettings baseSettings) throws SQLException;
    }

    private <T> T withReadOnlySession(ReadOnlyWork<T> work) {
        try (Connection connection = config.dataSource().getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                SchemaHistoryRepository.verifyPostgres19OrHigher(connection);
                return work.run(connection);
            } finally {
                try {
                    if (!connection.isClosed() && connection.getAutoCommit() != originalAutoCommit) {
                        connection.setAutoCommit(originalAutoCommit);
                    }
                } catch (SQLException ignored) {
                    // Connection already broken
                }
            }
        } catch (PgwayException e) {
            throw e;
        } catch (SQLException e) {
            throw new PgwayMigrationException("Database operation failed: " + e.getMessage(), e);
        }
    }

    private <T> T withLockedSession(LockedWork<T> work) {
        long lockKey = config.advisoryLockKey();
        try (Connection connection = config.dataSource().getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            SchemaHistoryRepository.SessionSettings baseSettings = null;
            boolean lockAcquired = false;
            Throwable primaryError = null;
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                SchemaHistoryRepository.verifyPostgres19OrHigher(connection);
                baseSettings = SchemaHistoryRepository.captureSessionSettings(connection);
                SchemaHistoryRepository.acquireAdvisoryLock(connection, lockKey, config.lockTimeout());
                lockAcquired = true;
                return work.run(connection, baseSettings);
            } catch (Throwable t) {
                primaryError = t;
                throw t;
            } finally {
                try {
                    if (!connection.isClosed()) {
                        if (!connection.getAutoCommit()) {
                            try {
                                connection.rollback();
                            } catch (SQLException rollbackEx) {
                                if (primaryError != null) {
                                    primaryError.addSuppressed(rollbackEx);
                                }
                            }
                            connection.setAutoCommit(true);
                        }
                        if (baseSettings != null) {
                            SchemaHistoryRepository.restoreSessionSettings(connection, baseSettings);
                        }
                        if (lockAcquired) {
                            SchemaHistoryRepository.releaseAdvisoryLock(connection, lockKey);
                        }
                        if (connection.getAutoCommit() != originalAutoCommit) {
                            connection.setAutoCommit(originalAutoCommit);
                        }
                    }
                } catch (SQLException cleanupEx) {
                    if (primaryError != null) {
                        primaryError.addSuppressed(cleanupEx);
                    } else {
                        throw new PgwayMigrationException("Failed to clean up PostgreSQL migration session", cleanupEx);
                    }
                }
            }
        } catch (PgwayException e) {
            throw e;
        } catch (SQLException e) {
            throw new PgwayMigrationException("Database migration failed: " + e.getMessage(), e);
        }
    }
}
