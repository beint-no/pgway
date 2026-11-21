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
            .addSource(broken)
            .create();

        PgwayMigrationException ex = assertThrows(PgwayMigrationException.class, pgwayBroken::migrate);
        assertEquals("V2__concurrent_broken.sql", ex.script());
        assertEquals(2, ex.statementIndex());
        assertEquals(3, ex.line());

        // Validate must report FAILED_MIGRATION_IN_HISTORY
        ValidationResult validation = pgwayBroken.validate();
        assertFalse(validation.valid());
        assertTrue(validation.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.FAILED_MIGRATION_IN_HISTORY));

        // Info must report FAILED for V2
        var info = pgwayBroken.info();
        assertEquals(MigrationState.APPLIED, info.get(0).state());
        assertEquals(MigrationState.FAILED, info.get(1).state());

        // Subsequent migrate refuses to proceed until repaired!
        PgwayValidationException blocked = assertThrows(PgwayValidationException.class, pgwayBroken::migrate);
        assertTrue(blocked.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.FAILED_MIGRATION_IN_HISTORY));

        // Repair removes failed row
        RepairResult repair = pgwayBroken.repair();
        assertEquals(1, repair.failedRowsRemoved());
        assertEquals(0, repair.checksumsRealigned());

        // Now fix script (using IF NOT EXISTS for the already-committed first statement) and re-run
        Pgway pgwayFixed = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .addSource(InMemoryMigrationSource.ofStrings(Map.of(
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
            .addSource(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__one.sql", "CREATE TABLE t1 (id int);",
                "V2__two.sql", "CREATE TABLE t2 (id int);"
            )))
            .create();
        initial.migrate();

        // Mutate V1 and drop V2 -> both CHECKSUM_MISMATCH and MISSING_MIGRATION reported
        Pgway drifted = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .addSource(InMemoryMigrationSource.ofStrings(Map.of(
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

        // Restore V2 and realign V1 checksum with repair(true)
        Pgway realign = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .addSource(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__one.sql", "CREATE TABLE t1 (id int, extra text);",
                "V2__two.sql", "CREATE TABLE t2 (id int);"
            )))
            .create();

        RepairResult repaired = realign.repair(true);
        assertEquals(1, repaired.checksumsRealigned());
        assertTrue(realign.validate().valid());
    }

    @Test
    void rejectsOutOfOrderByDefaultAndAllowsWhenEnabled() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("out_of_order");

        Pgway initial = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .addSource(InMemoryMigrationSource.ofStrings(Map.of(
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
            .addSource(withV2)
            .create();
        assertThrows(PgwayValidationException.class, strict::migrate);
        assertEquals(MigrationState.OUT_OF_ORDER, strict.info().get(2).state());

        Pgway permissive = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .outOfOrder(true)
            .addSource(withV2)
            .create();
        MigrationResult res = permissive.migrate();
        assertEquals(1, res.appliedCount());
        assertEquals(MigrationVersion.parse("2"), res.applied().get(0).version());
        assertTrue(permissive.validate().valid());
    }

    @Test
    void supportsExplicitBaselineAndBaselineOnMigrate() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("baseline_existing");

        // Pre-existing table in schema
        try (Connection c = pg.connect(db); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE legacy_table (id int PRIMARY KEY)");
        }

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .baselineOnMigrate(true)
            .baselineVersion("2")
            .addSource(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__old.sql", "CREATE TABLE should_not_run_1 (id int);",
                "V2__old.sql", "CREATE TABLE should_not_run_2 (id int);",
                "V3__new.sql", "CREATE TABLE should_run_3 (id int);"
            )))
            .create();

        MigrationResult res = pgway.migrate();
        assertTrue(res.baselined());
        assertEquals(1, res.appliedCount());
        assertEquals(MigrationVersion.parse("3"), res.applied().get(0).version());

        // Verify V1 and V2 did not run and show as BELOW_BASELINE in info()
        try (Connection c = pg.connect(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('public.should_not_run_1'), to_regclass('public.should_run_3')")) {
            assertTrue(rs.next());
            assertEquals(null, rs.getString(1));
            assertEquals("should_run_3", rs.getString(2));
        }

        var info = pgway.info();
        assertTrue(info.stream().anyMatch(i -> i.state() == MigrationState.BASELINE));
        assertTrue(info.stream().anyMatch(i -> i.state() == MigrationState.BELOW_BASELINE));
        assertTrue(pgway.validate().valid());

        // Calling baseline() again when history is non-empty fails cleanly
        assertThrows(PgwayValidationException.class, () -> pgway.baseline("5"));
    }

    @Test
    void baselineOnMigrateDoesNotBaselineBrandNewEmptySchema() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("baseline_empty");

        Pgway pgway = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .baselineOnMigrate(true)
            .baselineVersion("2")
            .addSource(InMemoryMigrationSource.ofStrings(Map.of(
                "V1__one.sql", "CREATE TABLE from_v1 (id int PRIMARY KEY);",
                "V2__two.sql", "CREATE TABLE from_v2 (id int PRIMARY KEY);",
                "V3__three.sql", "CREATE TABLE from_v3 (id int PRIMARY KEY);"
            )))
            .create();

        MigrationResult res = pgway.migrate();
        assertFalse(res.baselined(), "Empty schema must migrate from V1 rather than baseline");
        assertEquals(3, res.appliedCount());
        assertTrue(pgway.validate().valid());
    }
}
