package no.beint.pgway;

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
 * Modern PostgreSQL 19 schema migration engine for JDK 27.
 *
 * <p>Pgway is immutable and thread-safe. Concurrent callers targeting the same schema and history table
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

    public PgwayConfig config() {
        return config;
    }

    /**
     * Validates PostgreSQL 19, acquires the advisory lock, optionally adopts Flyway history or baselines,
     * validates history integrity, and applies all pending versioned and repeatable migrations.
     */
    public MigrationResult migrate() {
        long startNanos = System.nanoTime();
        MigrationLoader.LoadedMigrations loaded = MigrationLoader.load(config.sources());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }

        return withLockedSession(connection -> {
            if (config.createSchemas()) {
                SchemaHistoryRepository.ensureSchemas(connection, config.schemas(), config.defaultSchema());
            }

            boolean adoptedFromFlyway = false;
            boolean baselined = false;

            boolean historyExists = SchemaHistoryRepository.tableExists(
                connection,
                config.defaultSchema(),
                config.historyTable()
            );

            if (!historyExists) {
                if (config.adoptFlywayHistory()
                        && SchemaHistoryRepository.tableExists(
                            connection,
                            config.defaultSchema(),
                            SchemaHistoryRepository.FLYWAY_HISTORY_TABLE
                        )) {
                    AdoptionResult adoption = performFlywayAdoption(connection, loaded);
                    adoptedFromFlyway = adoption.adopted();
                } else {
                    boolean nonEmptySchema = config.baselineOnMigrate()
                        && SchemaHistoryRepository.schemaHasObjects(
                            connection,
                            config.defaultSchema(),
                            config.historyTable()
                        );
                    SchemaHistoryRepository.createHistoryTable(
                        connection,
                        config.defaultSchema(),
                        config.historyTable()
                    );
                    if (nonEmptySchema) {
                        SchemaHistoryRepository.insertHistoryRow(
                            connection,
                            config.defaultSchema(),
                            config.historyTable(),
                            config.baselineVersion(),
                            MigrationKind.BASELINE,
                            config.baselineDescription(),
                            "<< Pgway Baseline >>",
                            BASELINE_CHECKSUM,
                            0,
                            true
                        );
                        baselined = true;
                    }
                }
            }

            List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                connection,
                config.defaultSchema(),
                config.historyTable()
            );

            if (config.validateOnMigrate()) {
                List<ValidationIssue> issues = validateAgainstHistory(loaded, history);
                if (!issues.isEmpty()) {
                    throw new PgwayValidationException(issues);
                }
            } else {
                // Always refuse to migrate on top of failed history rows even when validation is disabled.
                List<ValidationIssue> failedIssues = failedRowIssues(history);
                if (!failedIssues.isEmpty()) {
                    throw new PgwayValidationException(failedIssues);
                }
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
                AppliedMigration applied = executeMigration(connection, script);
                newlyApplied.add(applied);
            }

            for (MigrationScript script : loaded.repeatable()) {
                AppliedMigration latest = summary.latestRepeatableByScript.get(script.scriptName());
                if (latest != null && latest.checksum().equals(script.checksum())) {
                    skipped++;
                    continue;
                }
                AppliedMigration applied = executeMigration(connection, script);
                newlyApplied.add(applied);
            }

            MigrationVersion currentVersion = computeCurrentVersion(history, newlyApplied);
            Duration totalDuration = Duration.ofNanos(System.nanoTime() - startNanos);
            return new MigrationResult(
                newlyApplied,
                skipped,
                currentVersion,
                totalDuration,
                adoptedFromFlyway,
                baselined
            );
        });
    }

    /**
     * Validates PostgreSQL 19, migration scripts, and database schema history without mutating the database.
     */
    public ValidationResult validate() {
        MigrationLoader.LoadedMigrations loaded = MigrationLoader.load(config.sources());
        List<ValidationIssue> issues = new ArrayList<>(loaded.issues());

        withReadOnlySession(connection -> {
            if (SchemaHistoryRepository.tableExists(connection, config.defaultSchema(), config.historyTable())) {
                List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                    connection,
                    config.defaultSchema(),
                    config.historyTable()
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
            if (!SchemaHistoryRepository.tableExists(connection, config.defaultSchema(), config.historyTable())) {
                return List.of();
            }
            return SchemaHistoryRepository.readHistory(connection, config.defaultSchema(), config.historyTable());
        });

        HistorySummary summary = summarizeHistory(history);
        Map<MigrationVersion, MigrationScript> scriptsByVersion = new LinkedHashMap<>();
        for (MigrationScript script : loaded.versioned()) {
            scriptsByVersion.put(script.version(), script);
        }

        List<MigrationInfo> result = new ArrayList<>();
        Map<MigrationVersion, Boolean> emittedVersions = new HashMap<>();

        // Emit applied/baseline/failed history rows first in rank order
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

        // Emit pending / out-of-order / below-baseline versioned scripts
        for (MigrationScript script : loaded.versioned()) {
            if (emittedVersions.containsKey(script.version())) {
                continue;
            }
            MigrationState state;
            if (summary.baselineVersion != null && script.version().compareTo(summary.baselineVersion) <= 0) {
                state = MigrationState.BELOW_BASELINE;
            } else if (summary.highestAppliedVersion != null
                    && script.version().compareTo(summary.highestAppliedVersion) < 0
                    && !config.outOfOrder()) {
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

        // Emit repeatable migrations
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

        return withLockedSession(connection -> {
            if (!SchemaHistoryRepository.tableExists(connection, config.defaultSchema(), config.historyTable())) {
                return new RepairResult(0, 0, List.of());
            }
            List<String> details = new ArrayList<>();
            int failedRemoved;
            int realigned = 0;

            connection.setAutoCommit(false);
            try {
                failedRemoved = SchemaHistoryRepository.deleteFailedRows(
                    connection,
                    config.defaultSchema(),
                    config.historyTable()
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
                        config.defaultSchema(),
                        config.historyTable()
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
                                config.defaultSchema(),
                                config.historyTable(),
                                row.installedRank(),
                                script.checksum(),
                                script.description(),
                                script.scriptName()
                            );
                            realigned++;
                            details.add(
                                "Realigned V" + row.version() + " (" + script.scriptName() + ")"
                            );
                        }
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            return new RepairResult(failedRemoved, realigned, details);
        });
    }

    /** Baselines an existing database at the given version string. */
    public BaselineResult baseline(String version) {
        return baseline(MigrationVersion.parse(version), config.baselineDescription());
    }

    /**
     * Baselines an existing database at {@code version}. Fails if the history table already contains rows.
     */
    public BaselineResult baseline(MigrationVersion version, String description) {
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(description, "description must not be null");

        return withLockedSession(connection -> {
            if (config.createSchemas()) {
                SchemaHistoryRepository.ensureSchemas(connection, config.schemas(), config.defaultSchema());
            }
            boolean exists = SchemaHistoryRepository.tableExists(
                connection,
                config.defaultSchema(),
                config.historyTable()
            );
            if (exists) {
                List<AppliedMigration> history = SchemaHistoryRepository.readHistory(
                    connection,
                    config.defaultSchema(),
                    config.historyTable()
                );
                if (!history.isEmpty()) {
                    throw new PgwayValidationException(new ValidationIssue(
                        ValidationErrorCode.BASELINE_FAILED,
                        null,
                        version,
                        "Cannot baseline: history table "
                            + PgIdentifiers.qualifiedTable(config.defaultSchema(), config.historyTable())
                            + " already contains " + history.size() + " row(s)"
                    ));
                }
            }
            connection.setAutoCommit(false);
            try {
                SchemaHistoryRepository.createHistoryTable(
                    connection,
                    config.defaultSchema(),
                    config.historyTable()
                );
                SchemaHistoryRepository.insertHistoryRow(
                    connection,
                    config.defaultSchema(),
                    config.historyTable(),
                    version,
                    MigrationKind.BASELINE,
                    description,
                    "<< Pgway Baseline >>",
                    BASELINE_CHECKSUM,
                    0,
                    true
                );
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            return new BaselineResult(true, version);
        });
    }

    /**
     * Atomically imports an existing {@code flyway_schema_history} table in {@code defaultSchema}
     * into {@code pgway_schema_history}, preserving applied versions, timestamps, and operators while
     * computing fresh SHA-256 checksums from the configured migration scripts.
     *
     * <p>Never modifies or drops {@code flyway_schema_history}. Idempotent: returns {@code adopted=false}
     * if {@code pgway_schema_history} already exists.
     */
    public AdoptionResult adoptFlywayHistory() {
        MigrationLoader.LoadedMigrations loaded = MigrationLoader.load(config.sources());
        if (!loaded.issues().isEmpty()) {
            throw new PgwayValidationException(loaded.issues());
        }

        return withLockedSession(connection -> {
            if (SchemaHistoryRepository.tableExists(connection, config.defaultSchema(), config.historyTable())) {
                List<AppliedMigration> existing = SchemaHistoryRepository.readHistory(
                    connection,
                    config.defaultSchema(),
                    config.historyTable()
                );
                return new AdoptionResult(false, 0, computeCurrentVersion(existing, List.of()));
            }
            if (!SchemaHistoryRepository.tableExists(
                    connection,
                    config.defaultSchema(),
                    SchemaHistoryRepository.FLYWAY_HISTORY_TABLE)) {
                throw new PgwayValidationException(new ValidationIssue(
                    ValidationErrorCode.FLYWAY_ADOPTION_FAILED,
                    null,
                    null,
                    "No " + PgIdentifiers.qualifiedTable(
                        config.defaultSchema(),
                        SchemaHistoryRepository.FLYWAY_HISTORY_TABLE
                    ) + " table found to adopt"
                ));
            }
            return performFlywayAdoption(connection, loaded);
        });
    }

    private AdoptionResult performFlywayAdoption(
            Connection connection,
            MigrationLoader.LoadedMigrations loaded) throws SQLException {
        List<SchemaHistoryRepository.FlywayRow> flywayRows =
            SchemaHistoryRepository.readFlywayHistory(connection, config.defaultSchema());

        List<ValidationIssue> issues = new ArrayList<>();
        for (SchemaHistoryRepository.FlywayRow row : flywayRows) {
            if (!row.success()) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.FLYWAY_ADOPTION_FAILED,
                    row.script(),
                    row.version() != null ? safeParseVersion(row.version()) : null,
                    "Refusing to adopt Flyway history: row rank " + row.installedRank()
                        + " (" + row.script() + ") has success=false"
                ));
            }
        }
        if (!issues.isEmpty()) {
            throw new PgwayValidationException(issues);
        }

        Map<MigrationVersion, MigrationScript> versionedByVersion = new HashMap<>();
        for (MigrationScript script : loaded.versioned()) {
            versionedByVersion.put(script.version(), script);
        }
        Map<String, MigrationScript> repeatableByBaseName = new HashMap<>();
        for (MigrationScript script : loaded.repeatable()) {
            repeatableByBaseName.put(script.scriptName(), script);
            repeatableByBaseName.put(baseName(script.scriptName()), script);
        }

        record PlannedRow(
                MigrationVersion version,
                MigrationKind kind,
                String description,
                String script,
                String checksum,
                String installedBy,
                java.time.Instant installedOn,
                int executionMs) {}

        List<PlannedRow> planned = new ArrayList<>();
        MigrationVersion highest = null;

        for (SchemaHistoryRepository.FlywayRow row : flywayRows) {
            if ("SCHEMA".equals(row.type()) || "INIT".equals(row.type())) {
                continue;
            }
            if ("BASELINE".equals(row.type())) {
                MigrationVersion ver = MigrationVersion.parse(row.version());
                planned.add(new PlannedRow(
                    ver,
                    MigrationKind.BASELINE,
                    row.description() != null ? row.description() : "<< Flyway Baseline >>",
                    row.script() != null ? row.script() : "<< Flyway Baseline >>",
                    BASELINE_CHECKSUM,
                    row.installedBy() != null ? row.installedBy() : "flyway",
                    row.installedOn(),
                    row.executionTime()
                ));
                if (highest == null || ver.compareTo(highest) > 0) {
                    highest = ver;
                }
                continue;
            }
            if (row.version() != null && !row.version().isBlank()) {
                MigrationVersion ver;
                try {
                    ver = MigrationVersion.parse(row.version());
                } catch (IllegalArgumentException e) {
                    issues.add(new ValidationIssue(
                        ValidationErrorCode.FLYWAY_ADOPTION_FAILED,
                        row.script(),
                        null,
                        "Unsupported Flyway version '" + row.version() + "' in script '" + row.script() + "'"
                    ));
                    continue;
                }
                MigrationScript script = versionedByVersion.get(ver);
                if (script == null) {
                    issues.add(new ValidationIssue(
                        ValidationErrorCode.FLYWAY_ADOPTION_FAILED,
                        row.script(),
                        ver,
                        "Flyway applied version " + ver + " (" + row.script()
                            + "), but no matching Pgway migration script was found"
                    ));
                    continue;
                }
                planned.add(new PlannedRow(
                    ver,
                    MigrationKind.VERSIONED,
                    script.description(),
                    script.scriptName(),
                    script.checksum(),
                    row.installedBy() != null ? row.installedBy() : "flyway",
                    row.installedOn(),
                    row.executionTime()
                ));
                if (highest == null || ver.compareTo(highest) > 0) {
                    highest = ver;
                }
            } else if ("SQL".equals(row.type())) {
                MigrationScript script = repeatableByBaseName.get(row.script());
                if (script == null && row.script() != null) {
                    script = repeatableByBaseName.get(baseName(row.script()));
                }
                if (script != null) {
                    planned.add(new PlannedRow(
                        null,
                        MigrationKind.REPEATABLE,
                        script.description(),
                        script.scriptName(),
                        script.checksum(),
                        row.installedBy() != null ? row.installedBy() : "flyway",
                        row.installedOn(),
                        row.executionTime()
                    ));
                }
            }
        }

        if (!issues.isEmpty()) {
            throw new PgwayValidationException(issues);
        }

        connection.setAutoCommit(false);
        try {
            SchemaHistoryRepository.createHistoryTable(
                connection,
                config.defaultSchema(),
                config.historyTable()
            );
            for (PlannedRow row : planned) {
                SchemaHistoryRepository.insertAdoptedRow(
                    connection,
                    config.defaultSchema(),
                    config.historyTable(),
                    row.version(),
                    row.kind(),
                    row.description(),
                    row.script(),
                    row.checksum(),
                    row.installedBy(),
                    row.installedOn(),
                    row.executionMs()
                );
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }

        return new AdoptionResult(true, planned.size(), highest);
    }

    private AppliedMigration executeMigration(Connection connection, MigrationScript script) throws SQLException {
        config.listener().beforeMigration(script);
        long startNanos = System.nanoTime();
        Duration effectiveLockTimeout = script.directives().lockTimeout() != null
            ? script.directives().lockTimeout()
            : config.migrationLockTimeout();
        Duration effectiveStmtTimeout = script.directives().statementTimeout() != null
            ? script.directives().statementTimeout()
            : config.migrationStatementTimeout();

        if (script.directives().transactional()) {
            return executeTransactional(connection, script, effectiveLockTimeout, effectiveStmtTimeout, startNanos);
        }
        return executeNonTransactional(connection, script, effectiveLockTimeout, effectiveStmtTimeout, startNanos);
    }

    private AppliedMigration executeTransactional(
            Connection connection,
            MigrationScript script,
            Duration lockTimeout,
            Duration statementTimeout,
            long startNanos) throws SQLException {
        String savedSearchPath = SchemaHistoryRepository.querySetting(connection, "search_path");
        connection.setAutoCommit(false);
        SqlStatement currentStmt = null;
        try {
            if (!config.schemas().isEmpty()) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("SET LOCAL search_path TO " + PgIdentifiers.formatSearchPath(config.schemas()));
                }
            }
            if (lockTimeout != null) {
                SchemaHistoryRepository.setConfig(
                    connection,
                    "lock_timeout",
                    PgIdentifiers.formatPgDuration(lockTimeout),
                    true
                );
            }
            if (statementTimeout != null) {
                SchemaHistoryRepository.setConfig(
                    connection,
                    "statement_timeout",
                    PgIdentifiers.formatPgDuration(statementTimeout),
                    true
                );
            }
            for (SqlStatement sqlStmt : script.statements()) {
                currentStmt = sqlStmt;
                executeSingleStatement(connection, sqlStmt);
            }
            currentStmt = null;
            int elapsedMs = (int) Math.min(Integer.MAX_VALUE, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            AppliedMigration applied = SchemaHistoryRepository.insertHistoryRow(
                connection,
                config.defaultSchema(),
                config.historyTable(),
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
            try {
                connection.rollback();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            PgwayMigrationException ex = buildMigrationException(script, currentStmt, e);
            config.listener().onMigrationFailed(script, ex);
            throw ex;
        } finally {
            try {
                connection.setAutoCommit(true);
                SchemaHistoryRepository.setConfig(connection, "search_path", savedSearchPath, false);
            } catch (SQLException ignored) {
                // Restored on outer session close or discarded if connection broke
            }
        }
    }

    private AppliedMigration executeNonTransactional(
            Connection connection,
            MigrationScript script,
            Duration lockTimeout,
            Duration statementTimeout,
            long startNanos) throws SQLException {
        String savedSearchPath = SchemaHistoryRepository.querySetting(connection, "search_path");
        String savedLockTimeout = lockTimeout != null
            ? SchemaHistoryRepository.querySetting(connection, "lock_timeout")
            : null;
        String savedStmtTimeout = statementTimeout != null
            ? SchemaHistoryRepository.querySetting(connection, "statement_timeout")
            : null;

        connection.setAutoCommit(true);
        SqlStatement currentStmt = null;
        try {
            if (!config.schemas().isEmpty()) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("SET search_path TO " + PgIdentifiers.formatSearchPath(config.schemas()));
                }
            }
            if (lockTimeout != null) {
                SchemaHistoryRepository.setConfig(
                    connection,
                    "lock_timeout",
                    PgIdentifiers.formatPgDuration(lockTimeout),
                    false
                );
            }
            if (statementTimeout != null) {
                SchemaHistoryRepository.setConfig(
                    connection,
                    "statement_timeout",
                    PgIdentifiers.formatPgDuration(statementTimeout),
                    false
                );
            }
            for (SqlStatement sqlStmt : script.statements()) {
                currentStmt = sqlStmt;
                executeSingleStatement(connection, sqlStmt);
            }
            currentStmt = null;
            int elapsedMs = (int) Math.min(Integer.MAX_VALUE, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            AppliedMigration applied = SchemaHistoryRepository.insertHistoryRow(
                connection,
                config.defaultSchema(),
                config.historyTable(),
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
            int elapsedMs = (int) Math.min(Integer.MAX_VALUE, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            // Record failed non-transactional migration because earlier statements may have committed!
            try {
                if (savedLockTimeout != null) {
                    SchemaHistoryRepository.setConfig(connection, "lock_timeout", savedLockTimeout, false);
                }
                if (savedStmtTimeout != null) {
                    SchemaHistoryRepository.setConfig(connection, "statement_timeout", savedStmtTimeout, false);
                }
                SchemaHistoryRepository.insertHistoryRow(
                    connection,
                    config.defaultSchema(),
                    config.historyTable(),
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
                SchemaHistoryRepository.setConfig(connection, "search_path", savedSearchPath, false);
                if (savedLockTimeout != null) {
                    SchemaHistoryRepository.setConfig(connection, "lock_timeout", savedLockTimeout, false);
                }
                if (savedStmtTimeout != null) {
                    SchemaHistoryRepository.setConfig(connection, "statement_timeout", savedStmtTimeout, false);
                }
            } catch (SQLException ignored) {
                // Restored on outer session close or discarded if connection broke
            }
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
        List<ValidationIssue> issues = new ArrayList<>(failedRowIssues(history));

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
                        + ") is missing from configured migration locations"
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

        if (!config.outOfOrder() && summary.highestAppliedVersion != null) {
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
                            + " (enable outOfOrder=true to allow out-of-order execution)"
                    ));
                }
            }
        }

        return issues;
    }

    private static List<ValidationIssue> failedRowIssues(List<AppliedMigration> history) {
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

    private static MigrationVersion safeParseVersion(String raw) {
        try {
            return MigrationVersion.parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T withReadOnlySession(SqlWork<T> work) {
        try (Connection connection = config.dataSource().getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                SchemaHistoryRepository.verifyPostgres19(connection);
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

    private <T> T withLockedSession(SqlWork<T> work) {
        long lockKey = config.resolvedAdvisoryLockKey();
        try (Connection connection = config.dataSource().getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            String originalSearchPath = null;
            boolean lockAcquired = false;
            Throwable primaryError = null;
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                SchemaHistoryRepository.verifyPostgres19(connection);
                originalSearchPath = SchemaHistoryRepository.querySetting(connection, "search_path");
                SchemaHistoryRepository.acquireAdvisoryLock(connection, lockKey, config.lockTimeout());
                lockAcquired = true;
                return work.run(connection);
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
                        if (originalSearchPath != null) {
                            SchemaHistoryRepository.setConfig(connection, "search_path", originalSearchPath, false);
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
