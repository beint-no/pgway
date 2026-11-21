package no.beint.pgway;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Convention-first PostgreSQL 19+ schema migration engine for JDK 27.
 *
 * <p>Pgway is immutable and thread-safe. Steady-state up-to-date startups complete via a
 * lock-free read-only check without lexing already-applied SQL scripts; pending migrations
 * are serialized via PostgreSQL session-level advisory locks.
 */
public final class Pgway {
    /**
     * Scoped value bound for the duration of each migration and its {@link MigrationListener} callbacks.
     */
    public static final ScopedValue<MigrationContext> CURRENT_MIGRATION = ScopedValue.newInstance();

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

    /** Returns the active migration context if called within a running migration or listener callback. */
    public static Optional<MigrationContext> currentMigration() {
        return CURRENT_MIGRATION.isBound() ? Optional.of(CURRENT_MIGRATION.get()) : Optional.empty();
    }

    /** Offline build-time validation of migration filenames, UTF-8 encoding, duplicates, directives, and SQL syntax. */
    public static ValidationResult validateSource(MigrationSource source) {
        Objects.requireNonNull(source, "source must not be null");
        MigrationLoader.LoadedScripts loaded = MigrationLoader.loadAndParseAll(source);
        return loaded.issues().isEmpty() ? ValidationResult.ok() : ValidationResult.failed(loaded.issues());
    }

    /** Offline build-time validation of all {@code .sql} scripts in a filesystem directory. */
    public static ValidationResult validateDirectory(Path directory) {
        return validateSource(new FilesystemMigrationSource(directory));
    }

    public PgwayConfig config() {
        return config;
    }

    /**
     * Validates PostgreSQL 19+, checks history integrity, and applies all pending versioned migrations
     * in strict order.
     *
     * <p>When {@code pgway_history} already exists, validates cleanly, and has zero pending migrations,
     * returns immediately via a lock-free read-only fast path without lexing already-applied SQL scripts.
     */
    public MigrationResult migrate() {
        long startNanos = System.nanoTime();
        MigrationLoader.LoadedHeaders loaded = MigrationLoader.loadHeaders(config.source());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }

        long lockKey = config.advisoryLockKey();
        try (Connection connection = config.dataSource().getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            LockedSession session = null;
            boolean lockAcquired = false;
            Throwable primaryError = null;
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }

                // 1. Optimistic lock-free fast path for steady-state up-to-date startups (0 locks, 0 SQL lexing).
                boolean historyExistsProbe = SchemaHistoryRepository.openReadOnlySession(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                if (historyExistsProbe) {
                    List<AppliedMigration> historyProbe = SchemaHistoryRepository.readHistory(
                        connection,
                        config.schema(),
                        PgwayConfig.HISTORY_TABLE
                    );
                    if (validateAgainstHistory(loaded.scripts(), historyProbe).isEmpty()) {
                        HistoryIndex index = indexHistory(historyProbe);
                        if (hasNoPending(loaded.scripts(), index)) {
                            Duration totalDuration = Duration.ofNanos(System.nanoTime() - startNanos);
                            return new MigrationResult(
                                List.of(),
                                loaded.scripts().size(),
                                index.highestVersion(),
                                totalDuration
                            );
                        }
                    }
                }

                // 2. Locked path (reusing the same JDBC connection): acquire advisory lock, then probe
                //    schema in a fresh post-lock snapshot so concurrent startups are strictly linearizable.
                SchemaHistoryRepository.SessionSettings baseSettings = SchemaHistoryRepository.openLockedSession(
                    connection,
                    lockKey,
                    config.lockTimeout()
                );
                lockAcquired = true;
                session = new LockedSession(baseSettings);

                SchemaHistoryRepository.SchemaProbe probe = SchemaHistoryRepository.probeSchemaAfterLock(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                if (!probe.historyExists()) {
                    if (probe.hasUserObjects()) {
                        throw new PgwayValidationException(nonEmptySchemaIssue());
                    }
                    connection.setAutoCommit(false);
                    try {
                        SchemaHistoryRepository.ensureSchemaAndHistoryTable(
                            connection,
                            config.schema(),
                            PgwayConfig.HISTORY_TABLE
                        );
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

                List<ValidationIssue> issues = validateAgainstHistory(loaded.scripts(), history);
                if (!issues.isEmpty()) {
                    throw new PgwayValidationException(issues);
                }

                HistoryIndex index = indexHistory(history);
                List<MigrationScript> pending = new ArrayList<>();
                List<ValidationIssue> parseIssues = new ArrayList<>();
                int skipped = 0;

                // Parse and validate ALL pending migrations before executing the first one.
                for (MigrationLoader.DiscoveredMigration discovered : loaded.scripts()) {
                    if (index.appliedByVersion().containsKey(discovered.version())) {
                        skipped++;
                        continue;
                    }
                    try {
                        pending.add(discovered.parse());
                    } catch (PgwaySqlParseException e) {
                        parseIssues.add(new ValidationIssue(
                            ValidationErrorCode.INVALID_SQL_SCRIPT,
                            discovered.scriptName(),
                            discovered.version(),
                            e.getMessage()
                        ));
                    }
                }
                if (!parseIssues.isEmpty()) {
                    throw new PgwayValidationException(parseIssues);
                }

                List<AppliedMigration> newlyApplied = new ArrayList<>(pending.size());
                int currentVersion = index.highestVersion();

                for (MigrationScript script : pending) {
                    session.markSettingsDirty();
                    AppliedMigration applied = executeMigration(connection, script, session.baseSettings());
                    newlyApplied.add(applied);
                    if (applied.version() > currentVersion) {
                        currentVersion = applied.version();
                    }
                }

                Duration totalDuration = Duration.ofNanos(System.nanoTime() - startNanos);
                return new MigrationResult(newlyApplied, skipped, currentVersion, totalDuration);
            } catch (Throwable t) {
                primaryError = t;
                throw t;
            } finally {
                cleanupSession(connection, originalAutoCommit, session, lockAcquired, lockKey, primaryError);
            }
        } catch (PgwayException e) {
            throw e;
        } catch (SQLException e) {
            throw new PgwayMigrationException("Database migration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Atomically replaces {@code pgway_history} with a single applied row for {@code V1}
     * (creating the target schema and history table if absent).
     *
     * <p>Use this when cutting over an existing database or after squashing {@code V1..Vn}
     * into a compacted {@code V1} schema script.
     */
    public AppliedMigration compactHistory() {
        MigrationLoader.LoadedHeaders loaded = MigrationLoader.loadHeaders(config.source());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }
        MigrationLoader.DiscoveredMigration v1 = loaded.scripts().stream()
            .filter(s -> s.version() == 1)
            .findFirst()
            .orElseThrow(() -> new PgwayValidationException(new ValidationIssue(
                ValidationErrorCode.MISSING_MIGRATION,
                null,
                1,
                "Cannot compact history: V1 migration script not found in migration source"
            )));

        return withLockedSession((connection, _) -> {
            connection.setAutoCommit(false);
            try {
                SchemaHistoryRepository.ensureSchemaAndHistoryTable(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                SchemaHistoryRepository.truncateHistory(connection, config.schema(), PgwayConfig.HISTORY_TABLE);
                AppliedMigration row = SchemaHistoryRepository.insertHistoryRow(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE,
                    v1.version(),
                    v1.scriptName(),
                    v1.checksum(),
                    0,
                    true
                );
                connection.commit();
                return row;
            } catch (SQLException e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    /**
     * Validates PostgreSQL 19+, migration scripts (including directives and SQL syntax),
     * and database schema history without mutating the database.
     */
    public ValidationResult validate() {
        MigrationLoader.LoadedHeaders headers = MigrationLoader.loadHeaders(config.source());
        List<ValidationIssue> issues = new ArrayList<>(headers.issues());
        for (MigrationLoader.DiscoveredMigration discovered : headers.scripts()) {
            try {
                discovered.parse();
            } catch (PgwaySqlParseException e) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_SQL_SCRIPT,
                    discovered.scriptName(),
                    discovered.version(),
                    e.getMessage()
                ));
            }
        }

        withReadOnlySession((connection, historyExists) -> {
            if (historyExists) {
                List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                issues.addAll(validateAgainstHistory(headers.scripts(), history));
            } else if (SchemaHistoryRepository.schemaHasUserObjects(connection, config.schema())) {
                issues.add(nonEmptySchemaIssue());
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
        MigrationLoader.LoadedHeaders loaded = MigrationLoader.loadHeaders(config.source());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }

        List<AppliedMigration> history = withReadOnlySession((connection, historyExists) ->
            historyExists
                ? SchemaHistoryRepository.readHistory(connection, config.schema(), PgwayConfig.HISTORY_TABLE)
                : List.of()
        );

        Map<Integer, MigrationLoader.DiscoveredMigration> scriptsByVersion = new LinkedHashMap<>();
        for (MigrationLoader.DiscoveredMigration script : loaded.scripts()) {
            scriptsByVersion.put(script.version(), script);
        }

        List<MigrationInfo> result = new ArrayList<>();
        Map<Integer, Boolean> emittedVersions = new HashMap<>();
        int highestApplied = 0;

        for (AppliedMigration row : history) {
            if (row.success() && row.version() > highestApplied) {
                highestApplied = row.version();
            }
            MigrationLoader.DiscoveredMigration script = scriptsByVersion.get(row.version());
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
                row.version(),
                script != null ? script.description() : MigrationLoader.descriptionFromScriptName(row.script()),
                row.script(),
                state,
                script != null ? script.checksum() : null,
                row.checksum(),
                row.installedOn(),
                row.executionMs()
            ));
            emittedVersions.put(row.version(), true);
        }

        for (MigrationLoader.DiscoveredMigration script : loaded.scripts()) {
            if (emittedVersions.containsKey(script.version())) {
                continue;
            }
            MigrationState state = (highestApplied > 0 && script.version() < highestApplied)
                ? MigrationState.OUT_OF_ORDER
                : MigrationState.PENDING;
            result.add(new MigrationInfo(
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

        return List.copyOf(result);
    }

    /** Removes failed ({@code success = false}) history rows without altering applied checksums. */
    public RepairResult repair() {
        return repair(false);
    }

    /**
     * Under advisory lock, deletes failed ({@code success = false}) history rows and optionally
     * realigns applied checksums and script names with the current migration scripts.
     */
    public RepairResult repair(boolean realignChecksums) {
        MigrationLoader.LoadedHeaders loaded = realignChecksums
            ? MigrationLoader.loadHeaders(config.source())
            : new MigrationLoader.LoadedHeaders(List.of(), List.of());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }

        return withLockedSession((connection, _) -> {
            SchemaHistoryRepository.SchemaProbe probe = SchemaHistoryRepository.probeSchemaAfterLock(
                connection,
                config.schema(),
                PgwayConfig.HISTORY_TABLE
            );
            if (!probe.historyExists()) {
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
                    Map<Integer, MigrationLoader.DiscoveredMigration> byVersion = new HashMap<>();
                    for (MigrationLoader.DiscoveredMigration script : loaded.scripts()) {
                        byVersion.put(script.version(), script);
                    }
                    List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                        connection,
                        config.schema(),
                        PgwayConfig.HISTORY_TABLE
                    );
                    for (AppliedMigration row : history) {
                        if (!row.success()) {
                            continue;
                        }
                        MigrationLoader.DiscoveredMigration script = byVersion.get(row.version());
                        if (script != null && (!script.checksum().equals(row.checksum())
                                || !script.scriptName().equals(row.script()))) {
                            SchemaHistoryRepository.updateChecksumAndScript(
                                connection,
                                config.schema(),
                                PgwayConfig.HISTORY_TABLE,
                                row.version(),
                                script.checksum(),
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

    private AppliedMigration executeMigration(
            Connection connection,
            MigrationScript script,
            SchemaHistoryRepository.SessionSettings baseSettings) throws SQLException {
        MigrationContext ctx = new MigrationContext(
            config.schema(),
            script.version(),
            script.scriptName(),
            script.checksum(),
            Instant.now()
        );
        return ScopedValue.where(CURRENT_MIGRATION, ctx).call(() -> {
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
        });
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
            SchemaHistoryRepository.applyMigrationSettings(connection, config.schema(), lockTimeout, statementTimeout, true);
            try (Statement stmt = connection.createStatement()) {
                for (SqlStatement sqlStmt : script.statements()) {
                    currentStmt = sqlStmt;
                    executeSingleStatement(stmt, sqlStmt);
                }
            }
            currentStmt = null;
            int elapsedMs = elapsedMillis(startNanos);
            AppliedMigration applied = SchemaHistoryRepository.insertHistoryRow(
                connection,
                config.schema(),
                PgwayConfig.HISTORY_TABLE,
                script.version(),
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
            } catch (SQLException _) {
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
            SchemaHistoryRepository.applyMigrationSettings(connection, config.schema(), lockTimeout, statementTimeout, false);
            try (Statement stmt = connection.createStatement()) {
                for (SqlStatement sqlStmt : script.statements()) {
                    currentStmt = sqlStmt;
                    executeSingleStatement(stmt, sqlStmt);
                }
            }
            currentStmt = null;
            int elapsedMs = elapsedMillis(startNanos);
            AppliedMigration applied = SchemaHistoryRepository.insertHistoryRow(
                connection,
                config.schema(),
                PgwayConfig.HISTORY_TABLE,
                script.version(),
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
            } catch (SQLException _) {
                // Handled by outer session cleanup or discarded if connection broke
            }
        }
    }

    private static void executeSingleStatement(Statement stmt, SqlStatement sqlStmt) throws SQLException {
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

    private static PgwayMigrationException buildMigrationException(
            MigrationScript script,
            SqlStatement failedStmt,
            SQLException cause) {
        int stmtIndex = failedStmt != null ? failedStmt.index() : 0;
        int line = failedStmt != null ? failedStmt.startLine() : 0;
        StringBuilder msg = new StringBuilder();
        msg.append("Migration ").append(script.scriptName())
            .append(" (V").append(script.version()).append(") failed");
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

    private record HistoryIndex(Map<Integer, AppliedMigration> appliedByVersion, int highestVersion) {}

    private static HistoryIndex indexHistory(List<AppliedMigration> history) {
        Map<Integer, AppliedMigration> appliedByVersion = new HashMap<>(history.size() * 2);
        int highest = 0;
        for (AppliedMigration row : history) {
            if (row.success()) {
                appliedByVersion.put(row.version(), row);
                if (row.version() > highest) {
                    highest = row.version();
                }
            }
        }
        return new HistoryIndex(appliedByVersion, highest);
    }

    private static boolean hasNoPending(List<MigrationLoader.DiscoveredMigration> scripts, HistoryIndex index) {
        for (MigrationLoader.DiscoveredMigration script : scripts) {
            if (!index.appliedByVersion().containsKey(script.version())) {
                return false;
            }
        }
        return true;
    }

    private List<ValidationIssue> validateAgainstHistory(
            List<MigrationLoader.DiscoveredMigration> scripts,
            List<AppliedMigration> history) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<Integer, AppliedMigration> appliedByVersion = new HashMap<>(history.size() * 2);
        int highestApplied = 0;

        for (AppliedMigration row : history) {
            if (!row.success()) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.FAILED_MIGRATION_IN_HISTORY,
                    row.script(),
                    row.version(),
                    "Failed migration in history (V" + row.version() + ", script='" + row.script()
                        + "'). Inspect the database for partial changes and run repair() before migrating."
                ));
                continue;
            }
            appliedByVersion.put(row.version(), row);
            if (row.version() > highestApplied) {
                highestApplied = row.version();
            }
        }

        Map<Integer, MigrationLoader.DiscoveredMigration> scriptsByVersion = new HashMap<>(scripts.size() * 2);
        for (MigrationLoader.DiscoveredMigration script : scripts) {
            scriptsByVersion.put(script.version(), script);
        }

        for (AppliedMigration row : history) {
            if (!row.success()) {
                continue;
            }
            MigrationLoader.DiscoveredMigration script = scriptsByVersion.get(row.version());
            if (script == null) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.MISSING_MIGRATION,
                    row.script(),
                    row.version(),
                    "Applied migration V" + row.version() + " (" + row.script()
                        + ") is missing from migration source"
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

        if (highestApplied > 0) {
            for (MigrationLoader.DiscoveredMigration script : scripts) {
                if (!appliedByVersion.containsKey(script.version()) && script.version() < highestApplied) {
                    issues.add(new ValidationIssue(
                        ValidationErrorCode.OUT_OF_ORDER_PENDING,
                        script.scriptName(),
                        script.version(),
                        "Pending migration V" + script.version() + " (" + script.scriptName()
                            + ") is lower than highest applied version V" + highestApplied
                    ));
                }
            }
        }

        return issues;
    }

    private ValidationIssue nonEmptySchemaIssue() {
        return new ValidationIssue(
            ValidationErrorCode.NON_EMPTY_SCHEMA_WITHOUT_HISTORY,
            null,
            0,
            "Schema '" + config.schema() + "' already contains database objects but has no "
                + PgIdentifiers.qualifiedTable(config.schema(), PgwayConfig.HISTORY_TABLE)
                + " table. Refusing to replay migrations against a non-empty schema; "
                + "seed pgway_history or call compactHistory() before migrating."
        );
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

    private static void cleanupSession(
            Connection connection,
            boolean originalAutoCommit,
            LockedSession session,
            boolean lockAcquired,
            long lockKey,
            Throwable primaryError) {
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
                if (session != null && session.settingsDirty()) {
                    SchemaHistoryRepository.restoreSessionSettings(connection, session.baseSettings());
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

    private static final class LockedSession {
        private final SchemaHistoryRepository.SessionSettings baseSettings;
        private boolean settingsDirty;

        LockedSession(SchemaHistoryRepository.SessionSettings baseSettings) {
            this.baseSettings = baseSettings;
        }

        SchemaHistoryRepository.SessionSettings baseSettings() {
            return baseSettings;
        }

        void markSettingsDirty() {
            this.settingsDirty = true;
        }

        boolean settingsDirty() {
            return settingsDirty;
        }
    }

    @FunctionalInterface
    private interface ReadOnlyWork<T> {
        T run(Connection connection, boolean historyExists) throws SQLException;
    }

    @FunctionalInterface
    private interface LockedWork<T> {
        T run(Connection connection, LockedSession session) throws SQLException;
    }

    private <T> T withReadOnlySession(ReadOnlyWork<T> work) {
        try (Connection connection = config.dataSource().getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                boolean historyExists = SchemaHistoryRepository.openReadOnlySession(
                    connection,
                    config.schema(),
                    PgwayConfig.HISTORY_TABLE
                );
                return work.run(connection, historyExists);
            } finally {
                try {
                    if (!connection.isClosed() && connection.getAutoCommit() != originalAutoCommit) {
                        connection.setAutoCommit(originalAutoCommit);
                    }
                } catch (SQLException _) {
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
            LockedSession session = null;
            boolean lockAcquired = false;
            Throwable primaryError = null;
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                SchemaHistoryRepository.SessionSettings baseSettings = SchemaHistoryRepository.openLockedSession(
                    connection,
                    lockKey,
                    config.lockTimeout()
                );
                lockAcquired = true;
                session = new LockedSession(baseSettings);
                return work.run(connection, session);
            } catch (Throwable t) {
                primaryError = t;
                throw t;
            } finally {
                cleanupSession(connection, originalAutoCommit, session, lockAcquired, lockKey, primaryError);
            }
        } catch (PgwayException e) {
            throw e;
        } catch (SQLException e) {
            throw new PgwayMigrationException("Database migration failed: " + e.getMessage(), e);
        }
    }
}
