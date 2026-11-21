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
import java.util.List;

/**
 * PostgreSQL 19+ metadata, session settings, advisory locking, and schema history persistence.
 */
final class SchemaHistoryRepository {
    private static final int PG19_MIN_VERSION_NUM = 190000;
    private static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";

    private SchemaHistoryRepository() {}

    record SessionSettings(String searchPath, String lockTimeout, String statementTimeout) {}

    static void verifyPostgres19OrHigher(Connection connection) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_setting('server_version_num')::int, version()")) {
            if (!rs.next()) {
                throw new PgwayUnsupportedDatabaseException(-1, "", "Unable to determine PostgreSQL server version");
            }
            int versionNum = rs.getInt(1);
            String versionStr = rs.getString(2);
            validateServerVersion(versionNum, versionStr);
        } catch (PgwayUnsupportedDatabaseException e) {
            throw e;
        } catch (SQLException e) {
            throw new PgwayUnsupportedDatabaseException(
                "Pgway requires PostgreSQL 19 or higher, but version query failed: " + e.getMessage(),
                e
            );
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

    static SessionSettings captureSessionSettings(Connection connection) throws SQLException {
        String sql = """
            SELECT current_setting('search_path'),
                   current_setting('lock_timeout'),
                   current_setting('statement_timeout')
            """;
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return new SessionSettings(rs.getString(1), rs.getString(2), rs.getString(3));
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

    static void setConfig(Connection connection, String settingName, String value, boolean isLocal) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config(?, ?, ?)")) {
            ps.setString(1, settingName);
            ps.setString(2, value);
            ps.setBoolean(3, isLocal);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    static void acquireAdvisoryLock(Connection connection, long lockKey, Duration timeout) throws SQLException {
        // Acquire session-level advisory lock inside a tiny transaction so SET LOCAL lock_timeout
        // bounds the wait and automatically resets at commit, while the session lock remains held.
        connection.setAutoCommit(false);
        try {
            setConfig(connection, "lock_timeout", PgIdentifiers.formatPgDuration(timeout), true);
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            if (SQLSTATE_LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                throw new PgwayLockException(
                    lockKey,
                    timeout,
                    "Timed out after " + timeout + " waiting for Pgway advisory lock (" + lockKey + ")",
                    e
                );
            }
            throw e;
        } finally {
            connection.setAutoCommit(true);
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

    static void ensureSchema(Connection connection, String schema) throws SQLException {
        if (PgwayConfig.DEFAULT_SCHEMA.equals(schema)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + PgIdentifiers.quote(schema));
        }
    }

    static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        String sql = """
            SELECT 1
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relname = ?
              AND c.relkind IN ('r', 'p')
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static boolean schemaHasObjects(Connection connection, String schema, String excludedTable) throws SQLException {
        String relationSql = """
            SELECT 1
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
              AND c.relname <> ?
            LIMIT 1
            """;
        try (PreparedStatement ps = connection.prepareStatement(relationSql)) {
            ps.setString(1, schema);
            ps.setString(2, excludedTable);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        String procSql = """
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
            LIMIT 1
            """;
        try (PreparedStatement ps = connection.prepareStatement(procSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static void createHistoryTable(Connection connection, String schema, String table) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        String idxName = PgIdentifiers.quote(table + "_ver_uniq_idx");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    installed_rank integer      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    version        text,
                    kind           text         NOT NULL CHECK (kind IN ('VERSIONED', 'REPEATABLE', 'BASELINE')),
                    description    text         NOT NULL,
                    script         text         NOT NULL,
                    checksum       text         NOT NULL,
                    installed_by   text         NOT NULL DEFAULT current_user,
                    installed_on   timestamptz  NOT NULL DEFAULT clock_timestamp(),
                    execution_ms   integer      NOT NULL CHECK (execution_ms >= 0),
                    success        boolean      NOT NULL
                )
                """.formatted(qualified));
            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS %s
                    ON %s (version)
                    WHERE version IS NOT NULL AND success
                """.formatted(idxName, qualified));
        }
    }

    static List<AppliedMigration> readHistory(Connection connection, String schema, String table) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        String sql = """
            SELECT installed_rank, version, kind, description, script, checksum,
                   installed_by, installed_on, execution_ms, success
            FROM %s
            ORDER BY installed_rank ASC
            """.formatted(qualified);
        List<AppliedMigration> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String rawVersion = rs.getString("version");
                MigrationVersion version = rawVersion != null ? MigrationVersion.parse(rawVersion) : null;
                MigrationKind kind = MigrationKind.valueOf(rs.getString("kind"));
                Timestamp ts = rs.getTimestamp("installed_on");
                Instant installedOn = ts != null ? ts.toInstant() : Instant.EPOCH;
                result.add(new AppliedMigration(
                    rs.getInt("installed_rank"),
                    version,
                    kind,
                    rs.getString("description"),
                    rs.getString("script"),
                    rs.getString("checksum"),
                    rs.getString("installed_by"),
                    installedOn,
                    rs.getInt("execution_ms"),
                    rs.getBoolean("success")
                ));
            }
        }
        return List.copyOf(result);
    }

    static AppliedMigration insertHistoryRow(
            Connection connection,
            String schema,
            String table,
            MigrationVersion version,
            MigrationKind kind,
            String description,
            String script,
            String checksum,
            int executionMs,
            boolean success) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        String sql = """
            INSERT INTO %s (version, kind, description, script, checksum, execution_ms, success)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING installed_rank, installed_by, installed_on
            """.formatted(qualified);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, version != null ? version.value() : null);
            ps.setString(2, kind.name());
            ps.setString(3, description);
            ps.setString(4, script);
            ps.setString(5, checksum);
            ps.setInt(6, Math.max(0, executionMs));
            ps.setBoolean(7, success);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int rank = rs.getInt("installed_rank");
                String installedBy = rs.getString("installed_by");
                Instant installedOn = rs.getTimestamp("installed_on").toInstant();
                return new AppliedMigration(
                    rank,
                    version,
                    kind,
                    description,
                    script,
                    checksum,
                    installedBy,
                    installedOn,
                    Math.max(0, executionMs),
                    success
                );
            }
        }
    }

    static int deleteFailedRows(Connection connection, String schema, String table) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        try (Statement stmt = connection.createStatement()) {
            return stmt.executeUpdate("DELETE FROM " + qualified + " WHERE NOT success");
        }
    }

    static void updateChecksumAndDescription(
            Connection connection,
            String schema,
            String table,
            int installedRank,
            String checksum,
            String description,
            String script) throws SQLException {
        String qualified = PgIdentifiers.qualifiedTable(schema, table);
        String sql = "UPDATE " + qualified + " SET checksum = ?, description = ?, script = ? WHERE installed_rank = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, checksum);
            ps.setString(2, description);
            ps.setString(3, script);
            ps.setInt(4, installedRank);
            ps.executeUpdate();
        }
    }
}
