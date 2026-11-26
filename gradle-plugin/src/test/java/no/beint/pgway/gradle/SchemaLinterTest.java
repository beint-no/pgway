package no.beint.pgway.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import no.beint.pgway.RawMigrationResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SchemaLinterTest {

    private static DisposablePostgresCluster cluster;
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    @BeforeAll
    static void startCluster() throws Exception {
        cluster = new DisposablePostgresCluster(null);
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (cluster != null) {
            cluster.close();
        }
    }

    private static List<SchemaLinter.LintViolation> runCatalogLint(
            String ddl,
            SchemaLinter.LintConfig config) throws Exception {
        return runCatalogLint(ddl, "public", List.of(), config);
    }

    private static List<SchemaLinter.LintViolation> runCatalogLint(
            String ddl,
            String schema,
            List<RawMigrationResource> resources,
            SchemaLinter.LintConfig config) throws Exception {
        String db = "lint_test_" + DB_SEQ.incrementAndGet();
        cluster.createDatabase(db);
        try (Connection conn = cluster.dataSource(db).getConnection(); Statement stmt = conn.createStatement()) {
            if (!"public".equals(schema)) {
                stmt.execute("CREATE SCHEMA \"" + schema + "\"");
                stmt.execute("SET search_path TO \"" + schema + "\", public");
            }
            stmt.execute(ddl);
            return SchemaLinter.lint(conn, schema, resources, config);
        }
    }

    private static RawMigrationResource script(String name, String sql) {
        return new RawMigrationResource(name, "mem:" + name, sql.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Rule 1: Redundant indexes ----

    @Test
    void detectsPrefixAndDuplicateRedundantBtreeIndexes() throws Exception {
        List<SchemaLinter.LintViolation> violations = runCatalogLint("""
            CREATE TABLE events (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                tenant_id bigint NOT NULL,
                created_at timestamptz NOT NULL,
                code text NOT NULL UNIQUE
            );
            CREATE INDEX idx_tenant ON events (tenant_id);
            CREATE INDEX idx_tenant_created ON events (tenant_id, created_at);
            CREATE INDEX idx_dup_id ON events (id);
            CREATE INDEX idx_dup_code ON events (code);
            """, SchemaLinter.LintConfig.defaults());

        assertEquals(3, violations.size(), () -> "Unexpected violations: " + SchemaLinter.formatReport(violations));
        assertTrue(violations.stream().allMatch(v -> v.rule().equals(SchemaLinter.RULE_REDUNDANT_INDEX)));
        String report = SchemaLinter.formatReport(violations);
        assertTrue(report.contains("idx_tenant") && report.contains("idx_tenant_created"), report);
        assertTrue(report.contains("idx_dup_id") && report.contains("events_pkey"), report);
        assertTrue(report.contains("idx_dup_code") && report.contains("events_code_key"), report);
    }

    @Test
    void detectsDuplicateUniqueIndexesAndReportsExactDuplicatesOnce() throws Exception {
        List<SchemaLinter.LintViolation> violations = runCatalogLint("""
            CREATE TABLE users (
                id bigint PRIMARY KEY,
                email text NOT NULL UNIQUE,
                handle text NOT NULL
            );
            CREATE UNIQUE INDEX users_email_dup_idx ON users (email);
            CREATE INDEX users_handle_a ON users (handle);
            CREATE INDEX users_handle_b ON users (handle);
            """, SchemaLinter.LintConfig.defaults());

        assertEquals(2, violations.size(), () -> "Unexpected violations: " + SchemaLinter.formatReport(violations));
        String report = SchemaLinter.formatReport(violations);
        assertTrue(report.contains("users_email_dup_idx") && report.contains("users_email_key"), report);
        assertTrue(report.contains("users_handle_b") && report.contains("users_handle_a"), report);
    }

    @Test
    void respectsUniqueStricterKeysNullsNotDistinctPartialPredicatesOrderOpclassAndNonBtree() throws Exception {
        List<SchemaLinter.LintViolation> violations = runCatalogLint("""
            CREATE TABLE items (
                id bigint PRIMARY KEY,
                tenant_id bigint NOT NULL,
                email text,
                code text NOT NULL,
                active boolean NOT NULL,
                tags text[] NOT NULL
            );
            -- Shorter unique enforces stricter uniqueness than longer unique -> NOT redundant
            CREATE UNIQUE INDEX items_code_uq ON items (code);
            CREATE UNIQUE INDEX items_code_tenant_uq ON items (code, tenant_id);

            -- Different indnullsnotdistinct -> NOT redundant
            CREATE UNIQUE INDEX items_email_distinct ON items (email);
            CREATE UNIQUE INDEX items_email_nnd ON items (email) NULLS NOT DISTINCT;

            -- Different partial predicate -> NOT redundant
            CREATE INDEX items_tenant_active ON items (tenant_id) WHERE active;
            CREATE INDEX items_tenant_code ON items (tenant_id, code);

            -- Different sort direction -> NOT redundant
            CREATE INDEX items_code_asc ON items (code ASC);
            CREATE INDEX items_code_desc ON items (code DESC);

            -- Different operator class -> NOT redundant
            CREATE INDEX items_code_pattern ON items (code text_pattern_ops);

            -- Different access method (gin vs btree) -> NOT redundant
            CREATE INDEX items_tags_gin ON items USING gin (tags);
            CREATE INDEX items_tags_btree ON items (tags);
            """, SchemaLinter.LintConfig.defaults());

        // Note: items_code_asc IS redundant with items_code_uq (same column, ASC, non-unique covered by unique)!
        // Let's verify only items_code_asc is flagged, none of the others.
        assertEquals(1, violations.size(), () -> "Unexpected violations: " + SchemaLinter.formatReport(violations));
        assertTrue(violations.getFirst().location().contains("items_code_asc"), violations.getFirst().format());
    }

    @Test
    void checksIncludeColumnsPartialPredicatesAndExpressionsAccurately() throws Exception {
        List<SchemaLinter.LintViolation> violations = runCatalogLint("""
            CREATE TABLE accounts (
                id bigint PRIMARY KEY,
                tenant_id bigint NOT NULL,
                email text NOT NULL,
                status text NOT NULL,
                tier text NOT NULL,
                active boolean NOT NULL
            );
            -- INCLUDE covered by B's key columns -> REDUNDANT
            CREATE INDEX acc_inc_covered_by_key ON accounts (tenant_id) INCLUDE (status);
            CREATE INDEX acc_tenant_status ON accounts (tenant_id, status);

            -- INCLUDE covered by B's INCLUDE columns -> REDUNDANT
            CREATE INDEX acc_inc_covered_by_inc ON accounts (email) INCLUDE (status);
            CREATE INDEX acc_email_tier_inc ON accounts (email, tier) INCLUDE (status);

            -- INCLUDE NOT covered by B (tier missing) -> NOT redundant
            CREATE INDEX acc_inc_uncovered ON accounts (tenant_id) INCLUDE (status, tier);

            -- Same partial predicate -> REDUNDANT
            CREATE INDEX acc_partial_short ON accounts (tenant_id) WHERE active;
            CREATE INDEX acc_partial_long ON accounts (tenant_id, email) WHERE active;

            -- Expression prefix match -> REDUNDANT; different expression -> NOT redundant
            CREATE INDEX acc_lower_email ON accounts (lower(email));
            CREATE INDEX acc_lower_email_tenant ON accounts (lower(email), tenant_id);
            CREATE INDEX acc_upper_email ON accounts (upper(email));
            """, SchemaLinter.LintConfig.defaults());

        assertEquals(4, violations.size(), () -> "Unexpected violations: " + SchemaLinter.formatReport(violations));
        Set<String> locs = Set.of(
            violations.get(0).location(),
            violations.get(1).location(),
            violations.get(2).location(),
            violations.get(3).location()
        );
        assertEquals(
            Set.of(
                "accounts.acc_inc_covered_by_key",
                "accounts.acc_inc_covered_by_inc",
                "accounts.acc_partial_short",
                "accounts.acc_lower_email"
            ),
            locs
        );
    }

    @Test
    void doesNotDoubleReportInheritedIndexesOnPartitionedTables() throws Exception {
        List<SchemaLinter.LintViolation> violations = runCatalogLint("""
            CREATE TABLE logs (
                id bigint NOT NULL,
                tenant_id bigint NOT NULL,
                created_at timestamptz NOT NULL,
                PRIMARY KEY (id, created_at)
            ) PARTITION BY RANGE (created_at);
            CREATE TABLE logs_2026 PARTITION OF logs
                FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
            CREATE INDEX logs_tenant_idx ON logs (tenant_id);
            CREATE INDEX logs_tenant_created_idx ON logs (tenant_id, created_at);
            """, SchemaLinter.LintConfig.defaults());

        assertEquals(1, violations.size(), () -> "Expected single report on parent table: " + SchemaLinter.formatReport(violations));
        assertEquals("logs.logs_tenant_idx", violations.getFirst().location());
    }

    // ---- Rule 2: Unreferenced composite UNIQUE containing PK ----

    @Test
    void detectsUnreferencedCompositeUniqueContainingPkAndAllowsReferencedOnes() throws Exception {
        List<SchemaLinter.LintViolation> violations = runCatalogLint("""
            CREATE TABLE tenants (
                id bigint PRIMARY KEY
            );
            CREATE TABLE customers (
                id bigint PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                email text NOT NULL UNIQUE,
                UNIQUE (id, tenant_id)
            );
            CREATE TABLE orders (
                id bigint PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                customer_id bigint NOT NULL,
                UNIQUE (id, tenant_id),
                FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id)
            );
            """, SchemaLinter.LintConfig.defaults());

        // customers UNIQUE(id, tenant_id) IS referenced (even with reversed column order!) -> OK
        // customers UNIQUE(email) does not contain PK -> OK
        // orders UNIQUE(id, tenant_id) is NOT referenced by any FK -> VIOLATION
        assertEquals(1, violations.size(), () -> "Unexpected violations: " + SchemaLinter.formatReport(violations));
        SchemaLinter.LintViolation v = violations.getFirst();
        assertEquals(SchemaLinter.RULE_UNREFERENCED_COMPOSITE_UNIQUE, v.rule());
        assertTrue(v.location().startsWith("orders."), v.location());
        assertTrue(v.message().contains("Remove this redundant composite UNIQUE constraint"), v.message());
    }

    // ---- Rule 3: Non-composite tenant FKs ----

    @Test
    void enforcesCompositeTenantForeignKeysWithAllowlistSupport() throws Exception {
        String ddl = """
            CREATE TABLE tenants (
                id bigint PRIMARY KEY
            );
            CREATE TABLE currencies (
                code text PRIMARY KEY
            );
            CREATE TABLE customers (
                id bigint PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                UNIQUE (id, tenant_id)
            );
            CREATE TABLE categories (
                id bigint PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                parent_id bigint REFERENCES categories (id),
                UNIQUE (id, tenant_id)
            );
            CREATE TABLE orders (
                id bigint PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                currency_code text NOT NULL REFERENCES currencies (code),
                customer_id bigint NOT NULL,
                cross_customer_id bigint REFERENCES customers (id),
                FOREIGN KEY (customer_id, tenant_id) REFERENCES customers (id, tenant_id)
            );
            CREATE TABLE pair_targets (
                a bigint NOT NULL,
                b bigint NOT NULL,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                PRIMARY KEY (a, b)
            );
            CREATE TABLE pair_links (
                id bigint PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                ref_a bigint NOT NULL,
                ref_b bigint NOT NULL,
                FOREIGN KEY (ref_a, ref_b) REFERENCES pair_targets (a, b)
            );
            CREATE TABLE global_audit (
                id bigint PRIMARY KEY,
                order_id bigint REFERENCES orders (id)
            );
            """;

        // Without allowlist: categories.parent_id, orders.cross_customer_id, pair_links(ref_a, ref_b) fail
        // Also disable Rule 2 here so we isolate Rule 3 cleanly (categories UNIQUE(id, tenant_id) is unreferenced).
        SchemaLinter.LintConfig strictConfig = new SchemaLinter.LintConfig(true, false, "tenant_id", Set.of(), false);
        List<SchemaLinter.LintViolation> violations = runCatalogLint(ddl, strictConfig);
        assertEquals(3, violations.size(), () -> "Unexpected violations: " + SchemaLinter.formatReport(violations));
        assertTrue(violations.stream().allMatch(v -> v.rule().equals(SchemaLinter.RULE_NON_COMPOSITE_TENANT_FK)));

        // With allowlist covering orders.cross_customer_id and pair_links(ref_a, ref_b): only categories.parent_id fails
        SchemaLinter.LintConfig allowlistConfig = new SchemaLinter.LintConfig(
            true,
            false,
            "tenant_id",
            Set.of("orders.cross_customer_id", "pair_links(ref_a, ref_b)"),
            false
        );
        List<SchemaLinter.LintViolation> remaining = runCatalogLint(ddl, allowlistConfig);
        assertEquals(1, remaining.size(), () -> "Unexpected violations: " + SchemaLinter.formatReport(remaining));
        assertTrue(remaining.getFirst().location().startsWith("categories."), remaining.getFirst().format());
    }

    // ---- Rule 4: Forbid explicit CONSTRAINT / INDEX names ----

    @Test
    void forbidsExplicitConstraintAndIndexNamesWithAccurateFileAndLineDiagnostics() {
        String sql = """
            -- CONSTRAINT ignored_in_line_comment CHECK (true)
            /* outer /* CONSTRAINT ignored_in_nested_block */ CREATE INDEX ignored_idx ON t (a); */
            CREATE TABLE items (
                id bigint CONSTRAINT items_custom_pk PRIMARY KEY,
                code text NOT NULL DEFAULT 'CONSTRAINT ignored_in_string',
                note text DEFAULT E'\\'CONSTRAINT ignored_in_escape',
                CONSTRAINT items_code_uq UNIQUE (code)
            );
            ALTER TABLE items ADD CONSTRAINT "items_quoted_chk" CHECK (code <> '');
            ALTER TABLE items DROP CONSTRAINT items_quoted_chk;
            ALTER TABLE items VALIDATE CONSTRAINT items_code_uq;
            ALTER TABLE items RENAME CONSTRAINT old_name TO new_name;
            ALTER TABLE items ALTER CONSTRAINT items_code_uq DEFERRABLE;
            INSERT INTO items (id, code) VALUES (1, 'a') ON CONFLICT ON CONSTRAINT items_code_uq DO NOTHING;
            COMMENT ON CONSTRAINT items_code_uq ON items IS 'doc';
            CREATE CONSTRAINT TRIGGER items_trg AFTER INSERT ON items FOR EACH ROW EXECUTE FUNCTION noop();
            CREATE FUNCTION noop() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                -- CREATE INDEX ignored_inside_dollar_quote ON items (code);
                RETURN NEW;
            END;
            $$;
            CREATE INDEX ON items (code);
            CREATE UNIQUE INDEX ON items (lower(code));
            CREATE INDEX CONCURRENTLY ON items (note);
            CREATE INDEX CONCURRENTLY IF NOT EXISTS items_note_idx ON items (note);
            CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS items_code_idx ON items (code);
            CREATE INDEX bad_idx ON items (code);
            CREATE UNIQUE INDEX bad_uq_idx ON items (code);
            CREATE INDEX CONCURRENTLY bad_conc_idx ON items (code);
            CREATE INDEX IF NOT EXISTS bad_ine_idx ON items (code);
            """;

        List<SchemaLinter.LintViolation> violations = SchemaLinter.lintExplicitNamesInScript("V2__changes.sql", sql);
        assertEquals(7, violations.size(), () -> "Unexpected violations:\n" + SchemaLinter.formatReport(violations));

        assertEquals("V2__changes.sql:4", violations.get(0).location());
        assertTrue(violations.get(0).message().contains("items_custom_pk"));

        assertEquals("V2__changes.sql:7", violations.get(1).location());
        assertTrue(violations.get(1).message().contains("items_code_uq"));

        assertEquals("V2__changes.sql:9", violations.get(2).location());
        assertTrue(violations.get(2).message().contains("\"items_quoted_chk\""));

        assertEquals("V2__changes.sql:28", violations.get(3).location());
        assertTrue(violations.get(3).message().contains("bad_idx"));

        assertEquals("V2__changes.sql:29", violations.get(4).location());
        assertTrue(violations.get(4).message().contains("bad_uq_idx"));

        assertEquals("V2__changes.sql:30", violations.get(5).location());
        assertTrue(violations.get(5).message().contains("bad_conc_idx"));

        assertEquals("V2__changes.sql:31", violations.get(6).location());
        assertTrue(violations.get(6).message().contains("bad_ine_idx"));
    }

    @Test
    void skipsCompactedV1ButScansUncompactedV1AndAllLaterVersions() {
        String compactedV1 = """
            -- pgway: replaces=V5:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
            CREATE TABLE legacy (
                id bigint CONSTRAINT legacy_pk PRIMARY KEY
            );
            CREATE INDEX legacy_idx ON legacy (id);
            """;
        String uncompactedV1 = """
            CREATE TABLE fresh (
                id bigint CONSTRAINT fresh_pk PRIMARY KEY
            );
            """;
        String v2 = """
            CREATE INDEX v2_named_idx ON legacy (id);
            """;

        // Compacted V1 skipped, V2 scanned
        List<SchemaLinter.LintViolation> compactedResult = SchemaLinter.lintExplicitNames(List.of(
            script("V1__schema.sql", compactedV1),
            script("V2__add_idx.sql", v2)
        ));
        assertEquals(1, compactedResult.size(), () -> SchemaLinter.formatReport(compactedResult));
        assertEquals("V2__add_idx.sql:1", compactedResult.getFirst().location());

        // Uncompacted V1 scanned AND V2 scanned
        List<SchemaLinter.LintViolation> uncompactedResult = SchemaLinter.lintExplicitNames(List.of(
            script("V1__schema.sql", uncompactedV1),
            script("V2__add_idx.sql", v2)
        ));
        assertEquals(2, uncompactedResult.size(), () -> SchemaLinter.formatReport(uncompactedResult));
        assertEquals("V1__schema.sql:2", uncompactedResult.get(0).location());
        assertEquals("V2__add_idx.sql:1", uncompactedResult.get(1).location());
    }
}
