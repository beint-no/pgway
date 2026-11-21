package no.beint.pgway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import no.beint.pgway.testsupport.Pg19TestCluster;
import org.junit.jupiter.api.Test;

class PgwayConcurrencyTest {

    @Test
    void serializesEightConcurrentRollingDeployMigrationsExactlyOnceOnVirtualThreads() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("concurrent_deploy");

        var source = InMemoryMigrationSource.ofStrings(Map.of(
            "V1__slow_init.sql", """
                SELECT pg_sleep(0.25);
                CREATE TABLE deploy_audit (id int PRIMARY KEY);
                INSERT INTO deploy_audit VALUES (1);
                """,
            "V2__second.sql", """
                INSERT INTO deploy_audit VALUES (2);
                """
        ));

        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<MigrationResult>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<MigrationResult> task = () -> {
                    Pgway pgway = Pgway.configure()
                        .dataSource(pg.url(db), pg.user(), pg.password())
                        .lockTimeout(Duration.ofSeconds(15))
                        .source(source)
                        .create();
                    ready.countDown();
                    go.await();
                    return pgway.migrate();
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();

            int totalApplied = 0;
            for (Future<MigrationResult> f : futures) {
                MigrationResult result = f.get();
                totalApplied += result.appliedCount();
                assertEquals(2, result.currentVersion());
            }
            assertEquals(2, totalApplied);
        }

        try (Connection c = pg.connect(db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM deploy_audit")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void throwsPgwayLockExceptionWhenAdvisoryLockTimesOutAndIsolatesSchemas() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("lock_timeout_test");

        Pgway schemaA = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .schema("schema_a")
            .lockTimeout(Duration.ofMillis(250))
            .source(InMemoryMigrationSource.ofStrings(Map.of("V1__a.sql", "CREATE TABLE a (id int);")))
            .create();

        Pgway schemaB = Pgway.configure()
            .dataSource(pg.url(db), pg.user(), pg.password())
            .schema("schema_b")
            .lockTimeout(Duration.ofMillis(250))
            .source(InMemoryMigrationSource.ofStrings(Map.of("V1__b.sql", "CREATE TABLE b (id int);")))
            .create();

        long keyA = schemaA.config().advisoryLockKey();
        try (Connection holder = pg.connect(db)) {
            try (PreparedStatement ps = holder.prepareStatement("SELECT pg_advisory_lock(?)")) {
                ps.setLong(1, keyA);
                ps.executeQuery().close();
            }

            long start = System.nanoTime();
            PgwayLockException ex = assertThrows(PgwayLockException.class, schemaA::migrate);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertEquals(keyA, ex.lockKey());
            assertTrue(elapsedMs >= 150 && elapsedMs < 5000, "Unexpected timeout elapsed: " + elapsedMs + "ms");

            assertEquals(1, schemaB.migrate().appliedCount());

            try (PreparedStatement ps = holder.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, keyA);
                ps.executeQuery().close();
            }
        }

        assertEquals(1, schemaA.migrate().appliedCount());
    }
}
