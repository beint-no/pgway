package no.beint.pgway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import no.beint.pgway.testsupport.Pg19TestCluster;
import org.junit.jupiter.api.Test;

class PgwayFlywayAdoptionTest {

    private static void createFlywayHistoryTable(Connection conn, String schema) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            s.execute("""
                CREATE TABLE "%s"."flyway_schema_history" (
                    installed_rank integer NOT NULL PRIMARY KEY,
                    version varchar(50),
                    description varchar(200) NOT NULL,
                    type varchar(20) NOT NULL,
                    script varchar(1000) NOT NULL,
                    checksum integer,
                    installed_by varchar(100) NOT NULL,
                    installed_on timestamp without time zone NOT NULL DEFAULT now(),
                    execution_time integer NOT NULL,
                    success boolean NOT NULL
                )
                """.formatted(schema));
        }
    }

    @Test
    void atomicallyAdoptsExistingFlywayHistoryAndAppliesOnlyNewMigrations() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("adopt_flyway");

        try (Connection c = pg.connect(db); Statement s = c.createStatement()) {
            createFlywayHistoryTable(c, "utin");
            s.execute("CREATE TABLE utin.posts (id int PRIMARY KEY)");
            s.execute("ALTER TABLE utin.posts ADD COLUMN title text");
            s.execute("""
                INSERT INTO utin.flyway_schema_history
                    (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
                VALUES
                    (0, NULL, '<< Flyway Schema Creation >>', 'SCHEMA', '"utin"', NULL, 'utin_app', '2026-11-15 17:53:14', 0, true),
                    (1, '1', 'utin schema', 'SQL', 'V1__utin_schema.sql', -1415563639, 'utin_app', '2026-11-15 17:53:15', 156, true),
                    (2, '2', 'add title', 'SQL', 'V2__add_title.sql', 1505532936, 'utin_app', '2026-11-15 17:53:16', 4, true)
                """);
        }

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .schemas("utin")
            .adoptFlywayHistory(true)
            .addSource(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__utin_schema.sql", "CREATE TABLE posts (id int PRIMARY KEY);",
                "V2__add_title.sql", "ALTER TABLE posts ADD COLUMN title text;",
                "V3__add_body.sql", "ALTER TABLE posts ADD COLUMN body text;"
            )))
            .create();

        MigrationResult res = pgway.migrate();
        assertTrue(res.adoptedFromFlyway());
        assertEquals(1, res.appliedCount());
        assertEquals(2, res.skippedCount());
        assertEquals(MigrationVersion.parse("3"), res.currentVersion());

        // Verify original Flyway history untouched, pgway history has 3 rows with preserved operator
        try (Connection c = pg.connect(db); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM utin.flyway_schema_history")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
            try (ResultSet rs = s.executeQuery(
                "SELECT version, installed_by, execution_ms, length(checksum) FROM utin.pgway_schema_history ORDER BY installed_rank"
            )) {
                assertTrue(rs.next());
                assertEquals("1", rs.getString(1));
                assertEquals("utin_app", rs.getString(2));
                assertEquals(156, rs.getInt(3));
                assertEquals(64, rs.getInt(4));

                assertTrue(rs.next());
                assertEquals("2", rs.getString(1));
                assertEquals("utin_app", rs.getString(2));
                assertEquals(4, rs.getInt(3));

                assertTrue(rs.next());
                assertEquals("3", rs.getString(1));
                assertEquals("pgway", rs.getString(2));
                assertFalse(rs.next());
            }
        }

        // Second startup is a clean no-op
        MigrationResult second = pgway.migrate();
        assertFalse(second.adoptedFromFlyway());
        assertEquals(0, second.appliedCount());
        assertEquals(3, second.skippedCount());
        assertTrue(pgway.validate().valid());
    }

    @Test
    void refusesAdoptionWhenFlywayHasFailedRowOrMissingScriptWithoutCreatingPgwayTable() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db1 = pg.createDatabase("adopt_failed_row");
        try (Connection c = pg.connect(db1); Statement s = c.createStatement()) {
            createFlywayHistoryTable(c, "public");
            s.execute("""
                INSERT INTO public.flyway_schema_history
                    (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES
                    (1, '1', 'init', 'SQL', 'V1__init.sql', 123, 'app', 10, false)
                """);
        }

        Pgway p1 = Pgway.configure()
            .dataSource(pg.url(db1), pg.user(), pg.password())
            .adoptFlywayHistory(true)
            .addSource(InMemoryMigrationSource.ofStrings(Map.of("V1__init.sql", "SELECT 1;")))
            .create();
        PgwayValidationException ex1 = assertThrows(PgwayValidationException.class, p1::migrate);
        assertEquals(ValidationErrorCode.FLYWAY_ADOPTION_FAILED, ex1.issues().get(0).code());

        try (Connection c = pg.connect(db1); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('public.pgway_schema_history')")) {
            assertTrue(rs.next());
            assertEquals(null, rs.getString(1), "pgway_schema_history must not exist after failed adoption");
        }

        // Missing script in Pgway locations
        String db2 = pg.createDatabase("adopt_missing_script");
        try (Connection c = pg.connect(db2); Statement s = c.createStatement()) {
            createFlywayHistoryTable(c, "public");
            s.execute("""
                INSERT INTO public.flyway_schema_history
                    (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES
                    (1, '1', 'init', 'SQL', 'V1__init.sql', 123, 'app', 10, true),
                    (2, '2', 'missing', 'SQL', 'V2__missing.sql', 456, 'app', 10, true)
                """);
        }

        Pgway p2 = Pgway.configure()
            .dataSource(pg.url(db2), pg.user(), pg.password())
            .adoptFlywayHistory(true)
            .addSource(InMemoryMigrationSource.ofStrings(Map.of("V1__init.sql", "SELECT 1;")))
            .create();
        PgwayValidationException ex2 = assertThrows(PgwayValidationException.class, p2::migrate);
        assertEquals(ValidationErrorCode.FLYWAY_ADOPTION_FAILED, ex2.issues().get(0).code());
    }
}
