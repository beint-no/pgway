package no.beint.pgway;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * PostgreSQL 19+ session setup, advisory locking, and {@code pgway_history} persistence.
 *
 * <p>Combines version verification, session setting capture, bounded advisory lock acquisition,
 * and history table existence checks into single round-trips wherever possible.
 */
final class SchemaHistoryRepository {
    private static final int PG19_MIN_VERSION_NUM = 190000;
    private static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";
    private static final HexFormat HEX = HexFormat.of();

    private SchemaHistoryRepository() {}

    record SessionSettings(String searchPath, String lockTimeout, String statementTimeout) {}

    record SchemaProbe(boolean historyExists, boolean hasUserObjects) {}

    static boolean openReadOnlySession(Connection connection, String schema, String table) {
        String sql = """
            SELECT current_setting('server_version_num')::int,
                   version(),
                   to_regclass(?) IS NOT NULL
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, PgIdentifiers.qualifiedTable(schema, table));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new PgwayUnsupportedDatabaseException(-1, "", "Unable to determine PostgreSQL server version");
                }
                validateServerVersion(rs.getInt(1), rs.getString(2));
                return rs.getBoolean(3);
            }
        } catch (PgwayUnsupportedDatabaseException e) {
            throw e;
        } catch (SQLException e) {
            throw new PgwayUnsupportedDatabaseException(
                "Pgway requires PostgreSQL 19 or higher, but version query failed: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * In a single atomic round-trip: checks PostgreSQL 19+, captures baseline session settings,
     * bounds lock acquisition with {@code SET LOCAL lock_timeout}, and acquires the session advisory lock.
     */
    static SessionSettings openLockedSession(
            Connection connection,
            long lockKey,
            Duration lockTimeout) throws SQLException {
        String sql = """
            WITH cfg AS MATERIALIZED (
                SELECT current_setting('server_version_num')::int AS ver_num,
                       version()                                  AS ver_str,
                       current_setting('search_path')             AS search_path,
                       current_setting('lock_timeout')            AS lock_timeout,
                       current_setting('statement_timeout')       AS statement_timeout,
                       set_config('lock_timeout', ?, true)        AS applied_timeout
            )
            SELECT ver_num,
                   ver_str,
                   search_path,
                   lock_timeout,
                   statement_timeout,
                   CASE WHEN ver_num >= 190000 THEN pg_advisory_lock(?) END
            FROM cfg
            """;
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, PgIdentifiers.formatPgDuration(lockTimeout));
                ps.setLong(2, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new PgwayUnsupportedDatabaseException(-1, "", "Unable to determine PostgreSQL server version");
                    }
                    int versionNum = rs.getInt(1);
                    String versionStr = rs.getString(2);
                    validateServerVersion(versionNum, versionStr);
                    SessionSettings settings = new SessionSettings(
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5)
                    );
                    connection.commit();
                    return settings;
                }
            }
        } catch (PgwayUnsupportedDatabaseException e) {
            try {
                connection.rollback();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            if (SQLSTATE_LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                throw new PgwayLockException(
                    lockKey,
                    lockTimeout,
                    "Timed out after " + lockTimeout + " waiting for Pgway advisory lock (" + lockKey + ")",
                    e
                );
            }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Probes history table existence (and non-extension user objects only if absent) in a fresh snapshot
     * AFTER the advisory lock is held.
     */
    static SchemaProbe probeSchemaAfterLock(Connection connection, String schema, String table) throws SQLException {
        String sql = """
            WITH reg AS (
                SELECT to_regclass(?) IS NOT NULL AS history_exists
            )
            SELECT history_exists,
                   CASE WHEN NOT history_exists THEN (
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_class c
                           JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                           WHERE n.nspname = ?
                             AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                             AND NOT EXISTS (
                                 SELECT 1 FROM pg_catalog.pg_depend d
                                 WHERE d.classid = 'pg_catalog.pg_class'::regclass
                                   AND d.objid = c.oid
                                   AND d.deptype = 'e'
                             )
                       ) OR EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_proc p
                           JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                           WHERE n.nspname = ?
                             AND NOT EXISTS (
                                 SELECT 1 FROM pg_catalog.pg_depend d
                                 WHERE d.classid = 'pg_catalog.pg_proc'::regclass
                                   AND d.objid = p.oid
                                   AND d.deptype = 'e'
                             )
                       )
                   ) ELSE false END AS has_user_objects
            FROM reg
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, PgIdentifiers.qualifiedTable(schema, table));
            ps.setString(2, schema);
            ps.setString(3, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new SchemaProbe(rs.getBoolean(1), rs.getBoolean(2));
            }
        }
    }

    static void validateServerVersion(int serverVersionNum, String serverVersion) {
        if (serverVersionNum < PG19_MIN_VERSION_NUM) {
            int major = serverVersionNum / 10000;
            throw new PgwayUnsupportedDatabaseException(
                serverVersionNum,
                serverVersion == null ? "" : serverVersion,
                "Pgway requires PostgreSQL 19 or higher (found PostgreSQL "
                    + major + ", server_version_num=" + serverVersionNum + "): " + serverVersion
            );
        }
    }

    static void applyMigrationSettings(
            Connection connection,
            String schema,
            Duration lockTimeout,
            Duration statementTimeout,
            boolean isLocal) throws SQLException {
        String sql = """
            SELECT set_config('search_path', ?, ?),
                   CASE WHEN ?::text IS NOT NULL THEN set_config('lock_timeout', ?, ?) END,
                   CASE WHEN ?::text IS NOT NULL THEN set_config('statement_timeout', ?, ?) END
            """;
        String lockVal = lockTimeout != null ? PgIdentifiers.formatPgDuration(lockTimeout) : null;
        String stmtVal = statementTimeout != null ? PgIdentifiers.formatPgDuration(statementTimeout) : null;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, PgIdentifiers.quote(schema));
            ps.setBoolean(2, isLocal);
            ps.setString(3, lockVal);
            ps.setString(4, lockVal);
            ps.setBoolean(5, isLocal);
            ps.setString(6, stmtVal);
            ps.setString(7, stmtVal);
            ps.setBoolean(8, isLocal);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    static void restoreSessionSettings(Connection connection, SessionSettings settings) throws SQLException {
        String sql = """
            SELECT set_config('search_path', ?, false),
                   set_config('lock_timeout', ?, false),
                   set_config('statement_timeout', ?, false)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, settings.searchPath());
            ps.setString(2, settings.lockTimeout());
            ps.setString(3, settings.statementTimeout());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    static void releaseAdvisoryLock(Connection connection, long lockKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, lockKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    static boolean schemaHasUserObjects(Connection connection, String schema) throws SQLException {
        String sql = """
            SELECT EXISTS (
                SELECT 1
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_catalog.pg_depend d
                      WHERE d.classid = 'pg_catalog.pg_class'::regclass
                        AND d.objid = c.oid
                        AND d.deptype = 'e'
                  )
            ) OR EXISTS (
                SELECT 1
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_catalog.pg_depend d
                      WHERE d.classid = 'pg_catalog.pg_proc'::regclass
                        AND d.objid = p.oid
                        AND d.deptype = 'e'
                  )
            )
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    static void ensureSchemaAndHistoryTable(Connection connection, String schema, String table) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        try (Statement stmt = connection.createStatement()) {
            if (!PgwayConfig.DEFAULT_SCHEMA.equals(schema)) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + PgIdentifiers.quote(schema));
            }
            // Column-Tetris aligned layout: 8-byte timestamptz, 4-byte version/execution_ms, 1-byte boolean, varlenas.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    installed_on timestamptz NOT NULL DEFAULT clock_timestamp(),
                    version      integer     PRIMARY KEY CHECK (version > 0),
                    execution_ms integer     NOT NULL CHECK (execution_ms >= 0),
                    success      boolean     NOT NULL,
                    checksum     bytea       NOT NULL CHECK (octet_length(checksum) = 32),
                    script       text        NOT NULL,
                    installed_by text        NOT NULL DEFAULT current_user
                )
                """.formatted(qualified));
        }
    }

    static List<AppliedMigration> readHistory(Connection connection, String schema, String table) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        String sql = """
            SELECT version, installed_on, execution_ms, success, checksum, script, installed_by
            FROM %s
            ORDER BY version ASC
            """.formatted(qualified);
        List<AppliedMigration> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp(2);
                Instant installedOn = ts != null ? ts.toInstant() : Instant.EPOCH;
                result.add(new AppliedMigration(
                    rs.getInt(1),
                    rs.getString(6),
                    HEX.formatHex(rs.getBytes(5)),
                    rs.getString(7),
                    installedOn,
                    rs.getInt(3),
                    rs.getBoolean(4)
                ));
            }
        }
        return List.copyOf(result);
    }

    static AppliedMigration insertHistoryRow(
            Connection connection,
            String schema,
            String table,
            int version,
            String script,
            String checksumHex,
            int executionMs,
            boolean success) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        String sql = """
            INSERT INTO %s (version, execution_ms, success, checksum, script)
            VALUES (?, ?, ?, ?, ?)
            RETURNING installed_by, installed_on
            """.formatted(qualified);
        int clampedMs = Math.max(0, executionMs);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, version);
            ps.setInt(2, clampedMs);
            ps.setBoolean(3, success);
            ps.setBytes(4, HEX.parseHex(checksumHex));
            ps.setString(5, script);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String installedBy = rs.getString(1);
                Instant installedOn = rs.getTimestamp(2).toInstant();
                return new AppliedMigration(
                    version,
                    script,
                    checksumHex,
                    installedBy,
                    installedOn,
                    clampedMs,
                    success
                );
            }
        }
    }

    static void truncateHistory(Connection connection, String schema, String table) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("TRUNCATE TABLE " + qualified);
        }
    }

    static int deleteFailedRows(Connection connection, String schema, String table) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        try (Statement stmt = connection.createStatement()) {
            return stmt.executeUpdate("DELETE FROM " + qualified + " WHERE NOT success");
        }
    }

    static void updateChecksumAndScript(
            Connection connection,
            String schema,
            String table,
            int version,
            String checksumHex,
            String script) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        String sql = "UPDATE " + qualified + " SET checksum = ?, script = ? WHERE version = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setBytes(1, HEX.parseHex(checksumHex));
            ps.setString(2, script);
            ps.setInt(3, version);
            ps.executeUpdate();
        }
    }
}
