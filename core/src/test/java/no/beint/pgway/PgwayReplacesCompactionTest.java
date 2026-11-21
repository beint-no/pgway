package no.beint.pgway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.beint.pgway.testsupport.Pg19TestCluster;
import org.junit.jupiter.api.Test;

class PgwayReplacesCompactionTest {

    @Test
    void seamlesslyCompactsExistingV1ThroughV5HistoryAndRunsPendingV2() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("replaces_happy");

        Map<String, String> v1ToV5 = new LinkedHashMap<>();
        v1ToV5.put("V1__init.sql", "CREATE TABLE users (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, email text NOT NULL);");
        v1ToV5.put("V2__add_name.sql", "ALTER TABLE users ADD COLUMN display_name text;");
        v1ToV5.put("V3__add_active.sql", "ALTER TABLE users ADD COLUMN active bool NOT NULL DEFAULT true;");
        v1ToV5.put("V4__idx.sql", "CREATE UNIQUE INDEX ON users (email);");
        v1ToV5.put("V5__orgs.sql", "CREATE TABLE orgs (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name text NOT NULL);");

        Pgway original = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(v1ToV5))
            .create();
        MigrationResult origResult = original.migrate();
        assertEquals(5, origResult.appliedCount());
        assertEquals(0, origResult.compactedFromVersion());

        String chainHash = Pgway.historyChainChecksum(origResult.applied());
        assertEquals(64, chainHash.length());

        String compactedV1 = """
            -- pgway: replaces=V5:%s
            CREATE TABLE orgs (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                name text NOT NULL
            );

            CREATE TABLE users (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                email text NOT NULL,
                display_name text,
                active bool NOT NULL DEFAULT true
            );
            CREATE UNIQUE INDEX ON users (email);
            """.formatted(chainHash);

        // Include a brand-new V2 right alongside the compacted V1.
        Map<String, String> afterCompaction = new LinkedHashMap<>();
        afterCompaction.put("V1__init.sql", compactedV1);
        afterCompaction.put("V2__add_bio.sql", "ALTER TABLE users ADD COLUMN bio text;");

        Pgway upgraded = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(afterCompaction))
            .create();

        // validate() recognizes the pending seamless compaction before migrate() runs.
        assertTrue(upgraded.validate().valid());

        MigrationResult upgradeResult = upgraded.migrate();
        assertEquals(5, upgradeResult.compactedFromVersion());
        assertEquals(1, upgradeResult.appliedCount()); // only new V2 ran!
        assertEquals(1, upgradeResult.skippedCount()); // compacted V1 counted as skipped
        assertEquals(2, upgradeResult.currentVersion());
        assertEquals("V2__add_bio.sql", upgradeResult.applied().getFirst().script());

        // Subsequent run hits the lock-free fast path cleanly.
        MigrationResult steady = upgraded.migrate();
        assertEquals(0, steady.compactedFromVersion());
        assertEquals(0, steady.appliedCount());
        assertEquals(2, steady.skippedCount());
        assertEquals(2, steady.currentVersion());
        assertTrue(upgraded.validate().valid());

        // Verify pgway_history now has exactly 2 rows (V1 + V2) and bio column exists.
        try (Connection conn = pg.connect(db); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pgway_history")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
            st.execute("INSERT INTO users (email, display_name, bio) VALUES ('a@b.no', 'Alice', 'hi')");
        }

        // Also verify a fresh empty database ignores the replaces directive and runs compacted V1 + V2 normally.
        String freshDb = pg.createDatabase("replaces_fresh");
        Pgway fresh = Pgway.configure()
            .dataSource(pg.url(freshDb), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(afterCompaction))
            .create();
        MigrationResult freshResult = fresh.migrate();
        assertEquals(0, freshResult.compactedFromVersion());
        assertEquals(2, freshResult.appliedCount());
        assertEquals(2, freshResult.currentVersion());
    }

    @Test
    void failsClosedWhenDatabaseIsBehindOrHashDoesNotMatch() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("replaces_behind");

        // DB only has V1..V3 (behind V5!)
        Map<String, String> v1ToV3 = new LinkedHashMap<>();
        v1ToV3.put("V1__init.sql", "CREATE TABLE t (id int PRIMARY KEY);");
        v1ToV3.put("V2__a.sql", "ALTER TABLE t ADD COLUMN a text;");
        v1ToV3.put("V3__b.sql", "ALTER TABLE t ADD COLUMN b text;");

        Pgway orig = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(v1ToV3))
            .create();
        MigrationResult origRes = orig.migrate();
        String realV3Hash = Pgway.historyChainChecksum(origRes.applied());

        // Compacted V1 claims replaces=V5 (DB is behind at V3) -> MUST fail closed!
        String wrongVersionV1 = "-- pgway: replaces=V5:" + realV3Hash + "\nCREATE TABLE t (id int PRIMARY KEY, a text, b text);";
        Pgway behindCheck = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of("V1__init.sql", wrongVersionV1)))
            .create();
        assertFalse(behindCheck.validate().valid());
        assertThrows(PgwayValidationException.class, behindCheck::migrate);

        // Compacted V1 claims replaces=V3 with tampered hash -> MUST fail closed!
        String tamperedHash = "0".repeat(64);
        String wrongHashV1 = "-- pgway: replaces=V3:" + tamperedHash + "\nCREATE TABLE t (id int PRIMARY KEY, a text, b text);";
        Pgway hashCheck = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .source(InMemoryMigrationSource.ofStrings(Map.of("V1__init.sql", wrongHashV1)))
            .create();
        assertFalse(hashCheck.validate().valid());
        assertThrows(PgwayValidationException.class, hashCheck::migrate);
    }

    @Test
    void rejectsReplacesDirectiveOnVersionOtherThanV1AndInvalidSyntax() {
        String validHash = "a".repeat(64);
        ValidationResult v2Replaces = Pgway.validateSource(InMemoryMigrationSource.ofStrings(Map.of(
            "V1__init.sql", "CREATE TABLE t (id int PRIMARY KEY);",
            "V2__bad.sql", "-- pgway: replaces=V5:" + validHash + "\nALTER TABLE t ADD COLUMN x int;"
        )));
        assertFalse(v2Replaces.valid());
        assertTrue(v2Replaces.issues().stream().anyMatch(i -> i.message().contains("only allowed on V1")));

        ValidationResult badSyntax = Pgway.validateSource(InMemoryMigrationSource.ofStrings(Map.of(
            "V1__init.sql", "-- pgway: replaces=not-a-valid-spec\nCREATE TABLE t (id int PRIMARY KEY);"
        )));
        assertFalse(badSyntax.valid());
        assertTrue(badSyntax.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.INVALID_SQL_SCRIPT));
    }
}
