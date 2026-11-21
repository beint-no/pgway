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

class PgwayFailureAndValidationTest {

    @Test
    void nonTransactionalFailureRecordsFailedRowBlocksFutureMigrateUntilRepaired() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("nontx_fail");

        var broken = InMemoryMigrationSource.ofStrings(Map.of(
            "V1__base.sql", "CREATE TABLE items (id int PRIMARY KEY);",
            "V2__concurrent_broken.sql", """
                -- pgway: transaction=false
                CREATE INDEX CONCURRENTLY idx_items_id ON items(id);
                CREATE INDEX CONCURRENTLY idx_bad ON items(nonexistent_col);
                """
        ));

        Pgway pgwayBroken = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(broken)
            .create();

        PgwayMigrationException ex = assertThrows(PgwayMigrationException.class, pgwayBroken::migrate);
        assertEquals("V2__concurrent_broken.sql", ex.script());
        assertEquals(2, ex.version());
        assertEquals(2, ex.statementIndex());
        assertEquals(3, ex.line());

        ValidationResult validation = pgwayBroken.validate();
        assertFalse(validation.valid());
        assertTrue(validation.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.FAILED_MIGRATION_IN_HISTORY));

        var info = pgwayBroken.info();
        assertEquals(MigrationState.APPLIED, info.get(0).state());
        assertEquals(MigrationState.FAILED, info.get(1).state());

        PgwayValidationException blocked = assertThrows(PgwayValidationException.class, pgwayBroken::migrate);
        assertTrue(blocked.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.FAILED_MIGRATION_IN_HISTORY));

        RepairResult repair = pgwayBroken.repair();
        assertEquals(1, repair.failedRowsRemoved());
        assertEquals(0, repair.checksumsRealigned());

        Pgway pgwayFixed = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__base.sql", "CREATE TABLE items (id int PRIMARY KEY);",
                "V2__concurrent_broken.sql", """
                    -- pgway: transaction=false
                    CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_items_id ON items(id);
                    """
            )))
            .create();

        MigrationResult res = pgwayFixed.migrate();
        assertEquals(1, res.appliedCount());
        assertTrue(pgwayFixed.validate().valid());
    }

    @Test
    void detectsChecksumMismatchMissingMigrationAndSupportsRealignRepair() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("drift");

        Pgway initial = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__one.sql", "CREATE TABLE t1 (id int);",
                "V2__two.sql", "CREATE TABLE t2 (id int);"
            )))
            .create();
        initial.migrate();

        Pgway drifted = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__one.sql", "CREATE TABLE t1 (id int, extra text);"
            )))
            .create();

        ValidationResult val = drifted.validate();
        assertFalse(val.valid());
        assertTrue(val.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.CHECKSUM_MISMATCH));
        assertTrue(val.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.MISSING_MIGRATION));
        assertThrows(PgwayValidationException.class, drifted::migrate);

        var info = drifted.info();
        assertEquals(MigrationState.CHECKSUM_MISMATCH, info.get(0).state());
        assertEquals(MigrationState.MISSING, info.get(1).state());

        Pgway realign = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__one.sql", "CREATE TABLE t1 (id int, extra text);",
                "V2__two.sql", "CREATE TABLE t2 (id int);"
            )))
            .create();

        RepairResult repaired = realign.repair(true);
        assertEquals(1, repaired.checksumsRealigned());
        assertTrue(realign.validate().valid());
    }

    @Test
    void alwaysRejectsOutOfOrderMigrations() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("out_of_order");

        Pgway initial = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__one.sql", "CREATE TABLE t1 (id int);",
                "V3__three.sql", "CREATE TABLE t3 (id int);"
            )))
            .create();
        initial.migrate();

        var withV2 = InMemoryMigrationSource.ofStrings(Map.of(
            "V1__one.sql", "CREATE TABLE t1 (id int);",
            "V2__two.sql", "CREATE TABLE t2 (id int);",
            "V3__three.sql", "CREATE TABLE t3 (id int);"
        ));

        Pgway strict = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(withV2)
            .create();
        assertThrows(PgwayValidationException.class, strict::migrate);
        assertEquals(MigrationState.OUT_OF_ORDER, strict.info().get(2).state());
    }

    @Test
    void compactHistorySeedsExistingDatabaseAndSquashesOldHistoryToSingleV1() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("compact_history");

        // Start with V1, V2, V3 applied
        Pgway beforeSquash = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__t1.sql", "CREATE TABLE t1 (id int PRIMARY KEY);",
                "V2__t2.sql", "ALTER TABLE t1 ADD COLUMN name text;",
                "V3__t3.sql", "CREATE TABLE t2 (id int PRIMARY KEY);"
            )))
            .create();
        assertEquals(3, beforeSquash.migrate().appliedCount());

        // Squash into a single compacted V1 + a new V2
        Pgway afterSquash = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__app_schema.sql", """
                    CREATE TABLE t1 (id int PRIMARY KEY, name text);
                    CREATE TABLE t2 (id int PRIMARY KEY);
                    """,
                "V2__after_squash.sql", "CREATE TABLE t3 (id int PRIMARY KEY);"
            )))
            .create();

        AppliedMigration compacted = afterSquash.compactHistory();
        assertEquals(1, compacted.version());
        assertEquals("V1__app_schema.sql", compacted.script());

        // Verify pgway_history now has exactly 1 row (V1), and migrate() applies only V2
        try (Connection c = pg.connect(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM pgway_history")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }

        MigrationResult res = afterSquash.migrate();
        assertEquals(1, res.appliedCount());
        assertEquals(2, res.applied().get(0).version());
        assertEquals(2, res.currentVersion());
        assertTrue(afterSquash.validate().valid());
    }

    @Test
    void refusesToReplayMigrationsAgainstNonEmptySchemaWithoutHistoryUntilSeeded() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("non_empty_guard");

        // Pre-existing table (e.g. production DB before cutover or pre-Pgway backup restore)
        try (Connection c = pg.connect(db); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE existing_data (id int PRIMARY KEY)");
            s.execute("INSERT INTO existing_data VALUES (42)");
        }

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__init.sql", "DELETE FROM existing_data; CREATE TABLE existing_data (id int PRIMARY KEY);",
                "V2__new_table.sql", "CREATE TABLE added_after_cutover (id int PRIMARY KEY);"
            )))
            .create();

        // Both validate() and migrate() fail closed
        ValidationResult validation = pgway.validate();
        assertFalse(validation.valid());
        assertEquals(1, validation.issues().size());
        assertEquals(ValidationErrorCode.NON_EMPTY_SCHEMA_WITHOUT_HISTORY, validation.issues().get(0).code());

        PgwayValidationException ex = assertThrows(PgwayValidationException.class, pgway::migrate);
        assertEquals(ValidationErrorCode.NON_EMPTY_SCHEMA_WITHOUT_HISTORY, ex.issues().get(0).code());

        // Existing row untouched and pgway_history was NOT created
        try (Connection c = pg.connect(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT (SELECT count(*) FROM existing_data), to_regclass('public.pgway_history')")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "No migration SQL may execute when fail-closed guard trips");
            assertEquals(null, rs.getString(2), "pgway_history must not be created when guard trips");
        }

        // After explicitly compacting/seeding history to V1, validate() passes and migrate() applies only V2
        pgway.compactHistory();
        assertTrue(pgway.validate().valid());
        MigrationResult res = pgway.migrate();
        assertEquals(1, res.appliedCount());
        assertEquals(2, res.applied().get(0).version());
    }

    @Test
    void allowsFreshMigrationWhenSchemaContainsOnlyExtensionObjects() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("ext_only_empty");

        // Pre-installed extension in public (adds extension-owned functions/types, no user objects)
        try (Connection c = pg.connect(db); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION citext");
        }

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__init.sql", "CREATE TABLE accounts (id int PRIMARY KEY, email citext NOT NULL);"
            )))
            .create();

        assertTrue(pgway.validate().valid());
        MigrationResult res = pgway.migrate();
        assertEquals(1, res.appliedCount());
        assertEquals(1, res.currentVersion());
    }
}
