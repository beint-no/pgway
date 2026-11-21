package no.beint.pgway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.beint.pgway.testsupport.Pg19TestCluster;
import org.junit.jupiter.api.Test;

class PgwayIntegrationTest {

    @Test
    void migratesFreshDatabaseInNumericOrderBindsScopedValueAndIsIdempotent() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("fresh");

        var source = InMemoryMigrationSource.ofStrings(Map.of(
            "V1__init.sql", "CREATE TABLE items (id int PRIMARY KEY, name text NOT NULL);",
            "V2__seed.sql", "INSERT INTO items (id, name) VALUES (1, 'one');",
            "V10__ten.sql", "INSERT INTO items (id, name) VALUES (10, 'ten');"
        ));

        assertTrue(Pgway.currentMigration().isEmpty());
        List<String> events = new ArrayList<>();
        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(source)
            .listener(new MigrationListener() {
                @Override
                public void beforeMigration(MigrationScript script) {
                    MigrationContext ctx = Pgway.currentMigration().orElseThrow();
                    assertEquals("public", ctx.schema());
                    assertEquals(script.version(), ctx.version());
                    assertEquals(script.scriptName(), ctx.script());
                    events.add("before:" + ctx.version());
                }

                @Override
                public void afterMigration(AppliedMigration applied) {
                    MigrationContext ctx = Pgway.currentMigration().orElseThrow();
                    assertEquals(applied.version(), ctx.version());
                    events.add("after:" + ctx.version());
                }
            })
            .create();

        MigrationResult first = pgway.migrate();
        assertTrue(Pgway.currentMigration().isEmpty());
        assertEquals(3, first.appliedCount());
        assertEquals(0, first.skippedCount());
        assertEquals(10, first.currentVersion());
        assertEquals(List.of("before:1", "after:1", "before:2", "after:2", "before:10", "after:10"), events);

        // Verify 32-byte raw SHA-256 bytea storage in pgway_history
        try (Connection c = pg.connect(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT version, octet_length(checksum) FROM pgway_history ORDER BY version")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(32, rs.getInt(2));
        }

        // Second run is a clean no-op
        MigrationResult second = pgway.migrate();
        assertEquals(0, second.appliedCount());
        assertEquals(3, second.skippedCount());
        assertEquals(10, second.currentVersion());

        // Validate & info
        assertTrue(pgway.validate().valid());
        List<MigrationInfo> info = pgway.info();
        assertEquals(3, info.size());
        assertTrue(info.stream().allMatch(i -> i.state() == MigrationState.APPLIED));
    }

    @Test
    void supportsPostgres19FeaturesAndNonTransactionalConcurrentIndexes() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("pg19_features");

        var source = InMemoryMigrationSource.ofStrings(Map.of(
            "V1__pg19_ddl.sql", """
                CREATE EXTENSION IF NOT EXISTS citext;
                CREATE TABLE users (
                    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    first_name text NOT NULL,
                    last_name text NOT NULL,
                    email citext NOT NULL,
                    full_name text GENERATED ALWAYS AS (first_name || ' ' || last_name) VIRTUAL
                );
                CREATE FUNCTION normalize_label(v text) RETURNS text
                LANGUAGE SQL
                IMMUTABLE
                BEGIN ATOMIC
                    SELECT CASE
                        WHEN v IS NULL THEN ''
                        ELSE lower(btrim(v))
                    END;
                END;
                DO $outer$
                BEGIN
                    EXECUTE $inner$
                        INSERT INTO users (first_name, last_name, email)
                        VALUES ('Ada', 'Lovelace', 'Ada@Example.NO');
                    $inner$;
                END $outer$;
                """,
            "V2__concurrent_indexes.sql", """
                -- pgway: transaction=false
                -- pgway: lock_timeout=5s
                CREATE UNIQUE INDEX CONCURRENTLY users_email_idx ON users (email);
                CREATE INDEX CONCURRENTLY users_name_idx ON users (last_name);
                VACUUM ANALYZE users;
                """
        ));

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(source)
            .create();

        MigrationResult result = pgway.migrate();
        assertEquals(2, result.appliedCount());

        try (Connection conn = pg.connect(db); Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT full_name, normalize_label('  PG19  ') FROM users")) {
                assertTrue(rs.next());
                assertEquals("Ada Lovelace", rs.getString(1));
                assertEquals("pg19", rs.getString(2));
            }
            try (ResultSet rs = stmt.executeQuery(
                "SELECT indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'users_email_idx'"
            )) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1));
            }
        }
    }

    @Test
    void supportsCustomSchemaAndRestoresSearchPath() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("custom_schema");

        var source = InMemoryMigrationSource.ofStrings(Map.of(
            "V1__utin_tables.sql", """
                CREATE TABLE campaigns (id int PRIMARY KEY);
                SET search_path TO public;
                """,
            "V2__second_in_utin.sql", """
                CREATE TABLE ads (id int PRIMARY KEY REFERENCES campaigns(id));
                """
        ));

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .schema("utin")
            .source(source)
            .create();

        MigrationResult res = pgway.migrate();
        assertEquals(2, res.appliedCount());

        try (Connection conn = pg.connect(db); Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'utin' AND tablename IN ('campaigns', 'ads', 'pgway_history')"
            )) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    @Test
    void worksWithReaiProductionHikariSettingsAutoCommitFalseAndAutosaveAlways() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("reai_hikari");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(pg.url(db));
        hc.setUsername(pg.user());
        hc.setPassword(pg.password());
        hc.setAutoCommit(false);
        hc.setMaximumPoolSize(1);
        hc.addDataSourceProperty("autosave", "always");
        hc.addDataSourceProperty("cleanupSavepoints", "true");
        hc.addDataSourceProperty("options", "-c jit=off");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            Pgway failing = Pgway.configure()
                .dataSource(ds)
                .source(InMemoryMigrationSource.ofStrings(Map.of(
                    "V1__partial.sql", """
                        CREATE TABLE must_rollback (id int PRIMARY KEY);
                        INSERT INTO nonexistent_table VALUES (1);
                        """
                )))
                .create();

            PgwayMigrationException ex = assertThrows(PgwayMigrationException.class, failing::migrate);
            assertEquals("V1__partial.sql", ex.script());
            assertEquals(1, ex.version());
            assertEquals(2, ex.statementIndex());
            assertEquals(2, ex.line());
            assertEquals("42P01", ex.sqlState());

            try (Connection c = ds.getConnection()) {
                assertFalse(c.getAutoCommit(), "HikariCP autoCommit=false must be preserved");
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT to_regclass('public.must_rollback')")) {
                    assertTrue(rs.next());
                    assertEquals(null, rs.getString(1), "First statement must have been rolled back");
                }
                c.rollback();
            }

            Pgway fixed = Pgway.configure()
                .dataSource(ds)
                .source(InMemoryMigrationSource.ofStrings(Map.of(
                    "V1__partial.sql", "CREATE TABLE must_rollback (id int PRIMARY KEY);"
                )))
                .create();

            MigrationResult res = fixed.migrate();
            assertEquals(1, res.appliedCount());
            assertEquals(1, res.currentVersion());

            try (Connection c = ds.getConnection()) {
                assertFalse(c.getAutoCommit());
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT current_setting('jit')")) {
                    assertTrue(rs.next());
                    assertEquals("off", rs.getString(1));
                }
                c.rollback();
            }
        }
    }

    @Test
    void enforcesPerMigrationStatementTimeoutDirective() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("stmt_timeout");

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__slow.sql", """
                    -- pgway: statement_timeout=150ms
                    SELECT pg_sleep(2);
                    """
            )))
            .create();

        PgwayMigrationException ex = assertThrows(PgwayMigrationException.class, pgway::migrate);
        assertEquals("57014", ex.sqlState());
    }

    @Test
    void enforcesDefaultMigrationLockTimeout() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("lock_timeout");

        Pgway setup = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__init.sql", "CREATE TABLE locked_t (id int PRIMARY KEY);"
            )))
            .create();
        setup.migrate();

        try (Connection blocker = pg.connect(db)) {
            blocker.setAutoCommit(false);
            try (Statement s = blocker.createStatement()) {
                s.execute("LOCK TABLE locked_t IN ACCESS EXCLUSIVE MODE");
            }

            Pgway alter = Pgway.configure()
                .dataSource(pg.url(db), pg.user(), pg.password())
                .migrationLockTimeout(Duration.ofMillis(150))
                .source(InMemoryMigrationSource.ofStrings(Map.of(
                    "V1__init.sql", "CREATE TABLE locked_t (id int PRIMARY KEY);",
                    "V2__add_col.sql", "ALTER TABLE locked_t ADD COLUMN note text;"
                )))
                .create();

            PgwayMigrationException ex = assertThrows(PgwayMigrationException.class, alter::migrate);
            assertEquals("55P03", ex.sqlState());
            blocker.rollback();
        }
    }

    @Test
    void handlesCommentOnlyMigrationReturningResultSetsAndMultiSchemaCreation() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("edge_cases");

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__multi_schema_and_returning.sql", """
                    SET LOCAL check_function_bodies = false;
                    CREATE SCHEMA bud;
                    CREATE TABLE bud.messages (
                        id int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        body text NOT NULL
                    );
                    INSERT INTO bud.messages (body) VALUES ('hello') RETURNING id;
                    """,
                "V2__noop_comment_only.sql", """
                    -- Applied out-of-band on production; marker migration only.
                    /* Nothing to execute */
                    """
            )))
            .create();

        MigrationResult res = pgway.migrate();
        assertEquals(2, res.appliedCount());
        assertEquals(2, res.currentVersion());
        assertTrue(pgway.validate().valid());
    }

    @Test
    void isolatesSessionLevelSettingsAndRollsBackResultSetReturningStatement() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("session_isolation");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(pg.url(db));
        hc.setUsername(pg.user());
        hc.setPassword(pg.password());
        hc.setMaximumPoolSize(1);

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            String initialLockTimeout;
            String initialStmtTimeout;
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT current_setting('lock_timeout'), current_setting('statement_timeout')")) {
                rs.next();
                initialLockTimeout = rs.getString(1);
                initialStmtTimeout = rs.getString(2);
            }

            Pgway pgway = Pgway.configure()
                .dataSource(ds)
                .source(InMemoryMigrationSource.ofStrings(Map.of(
                    "V1__mutates_session.sql", """
                        CREATE TABLE t (id int PRIMARY KEY);
                        SET lock_timeout = '123ms';
                        SET statement_timeout = '456ms';
                        """,
                    "V2__verifies_clean.sql", """
                        DO $$
                        BEGIN
                            IF current_setting('lock_timeout') = '123ms'
                               OR current_setting('statement_timeout') = '456ms' THEN
                                RAISE EXCEPTION 'Session timeouts leaked across migrations!';
                            END IF;
                        END $$;
                        """
                )))
                .create();

            assertEquals(2, pgway.migrate().appliedCount());

            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT current_setting('lock_timeout'), current_setting('statement_timeout')")) {
                rs.next();
                assertEquals(initialLockTimeout, rs.getString(1));
                assertEquals(initialStmtTimeout, rs.getString(2));
            }

            Pgway failingV3 = Pgway.configure()
                .dataSource(ds)
                .source(InMemoryMigrationSource.ofStrings(Map.of(
                    "V1__mutates_session.sql", """
                        CREATE TABLE t (id int PRIMARY KEY);
                        SET lock_timeout = '123ms';
                        SET statement_timeout = '456ms';
                        """,
                    "V2__verifies_clean.sql", """
                        DO $$
                        BEGIN
                            IF current_setting('lock_timeout') = '123ms'
                               OR current_setting('statement_timeout') = '456ms' THEN
                                RAISE EXCEPTION 'Session timeouts leaked across migrations!';
                            END IF;
                        END $$;
                        """,
                    "V3__returning_then_fail.sql", """
                        INSERT INTO t (id) VALUES (99) RETURNING id;
                        SELECT 1 / 0;
                        """
                )))
                .create();

            assertThrows(PgwayMigrationException.class, failingV3::migrate);
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM t")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "INSERT ... RETURNING must be rolled back when statement 2 fails");
            }
        }
    }
}
