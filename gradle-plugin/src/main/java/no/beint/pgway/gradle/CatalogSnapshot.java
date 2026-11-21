package no.beint.pgway.gradle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import no.beint.pgway.PgwayConfig;

/**
 * Canonical PostgreSQL 19+ catalog snapshot used to prove 100% semantic equivalence between
 * an original {@code V1..Vn} migration chain and its compacted {@code V1} schema.
 */
final class CatalogSnapshot {
    private final Map<String, String> objects;

    private CatalogSnapshot(Map<String, String> objects) {
        this.objects = objects;
    }

    static List<String> findNonEmptyTables(Connection conn, String historySchema) throws SQLException {
        List<String[]> tables = new ArrayList<>();
        String sql = """
            SELECT n.nspname, c.relname
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
              AND NOT (n.nspname = ? AND c.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass
                    AND d.objid = c.oid
                    AND d.deptype = 'e'
              )
            ORDER BY n.nspname, c.relname
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, historySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        List<String> nonEmpty = new ArrayList<>();
        try (Statement stmt = conn.createStatement()) {
            for (String[] t : tables) {
                String q = "\"" + t[0].replace("\"", "\"\"") + "\".\"" + t[1].replace("\"", "\"\"") + "\"";
                try (ResultSet rs = stmt.executeQuery("SELECT EXISTS (SELECT 1 FROM " + q + " LIMIT 1)")) {
                    if (rs.next() && rs.getBoolean(1)) {
                        nonEmpty.add(t[0] + "." + t[1]);
                    }
                }
            }
        }
        return nonEmpty;
    }

    static CatalogSnapshot capture(Connection conn, String historySchema) throws SQLException {
        Map<String, String> out = new TreeMap<>();
        try (Statement stmt = conn.createStatement()) {
            String quotedSchema = "\"" + historySchema.replace("\"", "\"\"") + "\"";
            if ("public".equals(historySchema)) {
                stmt.execute("SET search_path TO public");
            } else {
                stmt.execute("SET search_path TO " + quotedSchema + ", public");
            }
        }

        // 1. Schemas
        queryInto(conn, """
            SELECT nspname
            FROM pg_catalog.pg_namespace
            WHERE nspname NOT IN ('pg_catalog', 'information_schema', 'public')
              AND nspname NOT LIKE 'pg_toast%'
              AND nspname NOT LIKE 'pg_temp_%'
            ORDER BY nspname
            """, rs -> out.put("schema:" + rs.getString(1), "EXISTS"));

        // 2. Extensions
        queryInto(conn, """
            SELECT e.extname, n.nspname
            FROM pg_catalog.pg_extension e
            JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
            WHERE e.extname <> 'plpgsql'
            ORDER BY e.extname
            """, rs -> out.put("extension:" + rs.getString(1), rs.getString(2)));

        // 3. Enums & Domains
        queryInto(conn, """
            SELECT n.nspname, t.typname, string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder)
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_catalog.pg_enum e ON e.enumtypid = t.oid
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e'
              )
            GROUP BY n.nspname, t.typname
            """, rs -> out.put("enum:" + rs.getString(1) + "." + rs.getString(2), rs.getString(3)));

        queryInto(conn, """
            SELECT n.nspname, t.typname,
                   pg_catalog.format_type(t.typbasetype, t.typtypmod),
                   t.typnotnull,
                   coalesce(t.typdefault, ''),
                   coalesce((
                       SELECT string_agg(pg_catalog.pg_get_constraintdef(c.oid, true), '; ' ORDER BY c.conname)
                       FROM pg_catalog.pg_constraint c WHERE c.contypid = t.oid
                   ), '')
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE t.typtype = 'd'
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e'
              )
            """, rs -> out.put(
                "domain:" + rs.getString(1) + "." + rs.getString(2),
                rs.getString(3) + "|notnull=" + rs.getBoolean(4) + "|def=" + rs.getString(5) + "|chk=" + rs.getString(6)
            ));

        // 4. All user sequences (standalone, column-owned, and identity-backed)
        queryInto(conn, """
            SELECT n.nspname, c.relname,
                   pg_catalog.format_type(s.seqtypid, NULL),
                   s.seqstart, s.seqincrement, s.seqmin, s.seqmax, s.seqcache, s.seqcycle,
                   coalesce((
                       SELECT tn.nspname || '.' || tc.relname || '.' || ta.attname || ':' || d.deptype::text
                       FROM pg_catalog.pg_depend d
                       JOIN pg_catalog.pg_class tc ON tc.oid = d.refobjid
                       JOIN pg_catalog.pg_namespace tn ON tn.oid = tc.relnamespace
                       JOIN pg_catalog.pg_attribute ta ON ta.attrelid = tc.oid AND ta.attnum = d.refobjsubid
                       WHERE d.classid = 'pg_catalog.pg_class'::regclass
                         AND d.objid = c.oid
                         AND d.refclassid = 'pg_catalog.pg_class'::regclass
                         AND d.deptype IN ('a', 'i')
                       LIMIT 1
                   ), '')
            FROM pg_catalog.pg_sequence s
            JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass
                    AND d.objid = c.oid
                    AND d.deptype = 'e'
              )
            """, rs -> out.put(
                "sequence:" + rs.getString(1) + "." + rs.getString(2),
                rs.getString(3) + "|start=" + rs.getLong(4) + "|inc=" + rs.getLong(5)
                    + "|min=" + rs.getLong(6) + "|max=" + rs.getLong(7)
                    + "|cache=" + rs.getLong(8) + "|cycle=" + rs.getBoolean(9)
                    + "|owner=" + rs.getString(10)
            ));

        // 5. Tables, reloptions & partitioning
        String tableSql = """
            SELECT n.nspname, c.relname, c.relkind::text, c.relpersistence::text,
                   coalesce(array_to_string(c.reloptions, ','), ''),
                   coalesce(pg_catalog.pg_get_partkeydef(c.oid), ''),
                   c.relispartition,
                   coalesce(pg_catalog.pg_get_expr(c.relpartbound, c.oid), '')
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
              AND NOT (n.nspname = ? AND c.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'
              )
            """;
        try (PreparedStatement ps = conn.prepareStatement(tableSql)) {
            ps.setString(1, historySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(
                        "table:" + rs.getString(1) + "." + rs.getString(2),
                        "kind=" + rs.getString(3) + "|persist=" + rs.getString(4)
                            + "|opts=" + rs.getString(5) + "|part=" + rs.getString(6)
                            + "|ispart=" + rs.getBoolean(7) + "|bound=" + rs.getString(8)
                    );
                }
            }
        }

        // 6. Columns (exact order via row_number ignoring dropped cols, types, defaults, identity, generated, storage)
        String colSql = """
            SELECT n.nspname, c.relname,
                   row_number() OVER (PARTITION BY c.oid ORDER BY a.attnum) AS pos,
                   a.attname,
                   pg_catalog.format_type(a.atttypid, a.atttypmod),
                   a.attnotnull,
                   a.attidentity::text,
                   a.attgenerated::text,
                   coalesce(pg_catalog.pg_get_expr(ad.adbin, ad.adrelid), ''),
                   a.attstorage::text,
                   coalesce(col.collname, '')
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
            LEFT JOIN pg_catalog.pg_collation col ON col.oid = a.attcollation AND a.attcollation <> 0
            WHERE c.relkind IN ('r', 'p')
              AND a.attnum > 0
              AND NOT a.attisdropped
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
              AND NOT (n.nspname = ? AND c.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'
              )
            """;
        try (PreparedStatement ps = conn.prepareStatement(colSql)) {
            ps.setString(1, historySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = "col:" + rs.getString(1) + "." + rs.getString(2) + "." + rs.getString(4);
                    out.put(
                        key,
                        "pos=" + rs.getInt(3)
                            + "|type=" + rs.getString(5)
                            + "|notnull=" + rs.getBoolean(6)
                            + "|ident=" + rs.getString(7)
                            + "|gen=" + rs.getString(8)
                            + "|expr=" + rs.getString(9)
                            + "|storage=" + rs.getString(10)
                            + "|coll=" + rs.getString(11)
                    );
                }
            }
        }

        // 7. Constraints (PK, UNIQUE, FK, CHECK, EXCLUDE — skip PG18+ NOT NULL catalog rows 'n' already checked via attnotnull)
        String conSql = """
            SELECT n.nspname, c.relname, con.conname, con.contype::text,
                   pg_catalog.pg_get_constraintdef(con.oid, true)
            FROM pg_catalog.pg_constraint con
            JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE con.conrelid <> 0
              AND con.contype IN ('p', 'u', 'f', 'c', 'x')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
              AND NOT (n.nspname = ? AND c.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'
              )
            """;
        try (PreparedStatement ps = conn.prepareStatement(conSql)) {
            ps.setString(1, historySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(
                        "constraint:" + rs.getString(1) + "." + rs.getString(2) + "." + rs.getString(3),
                        rs.getString(4) + ":" + rs.getString(5)
                    );
                }
            }
        }

        // 8. Non-constraint indexes
        String idxSql = """
            SELECT n.nspname, t.relname, i.relname, pg_catalog.pg_get_indexdef(i.oid)
            FROM pg_catalog.pg_index idx
            JOIN pg_catalog.pg_class i ON i.oid = idx.indexrelid
            JOIN pg_catalog.pg_class t ON t.oid = idx.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
              AND NOT (n.nspname = ? AND t.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_constraint con
                  WHERE con.conindid = i.oid AND con.contype IN ('p', 'u', 'x')
              )
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = t.oid AND d.deptype = 'e'
              )
            """;
        try (PreparedStatement ps = conn.prepareStatement(idxSql)) {
            ps.setString(1, historySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put("index:" + rs.getString(1) + "." + rs.getString(2) + "." + rs.getString(3), rs.getString(4));
                }
            }
        }

        // 9. Views & Materialized views
        queryInto(conn, """
            SELECT n.nspname, c.relname, c.relkind::text, pg_catalog.pg_get_viewdef(c.oid, true)
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('v', 'm')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'
              )
            """, rs -> out.put(
                "view:" + rs.getString(1) + "." + rs.getString(2),
                rs.getString(3) + ":" + rs.getString(4).strip()
            ));

        // 10. Functions & Procedures
        queryInto(conn, """
            SELECT n.nspname, p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid),
                   pg_catalog.pg_get_functiondef(p.oid)
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e'
              )
            """, rs -> out.put(
                "func:" + rs.getString(1) + "." + rs.getString(2) + "(" + rs.getString(3) + ")",
                rs.getString(4).strip()
            ));

        // 11. Triggers
        queryInto(conn, """
            SELECT n.nspname, c.relname, t.tgname, pg_catalog.pg_get_triggerdef(t.oid, true)
            FROM pg_catalog.pg_trigger t
            JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE NOT t.tgisinternal
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'
              )
            """, rs -> out.put(
                "trigger:" + rs.getString(1) + "." + rs.getString(2) + "." + rs.getString(3),
                rs.getString(4)
            ));

        // 12. Comments on tables & columns
        String commentSql = """
            SELECT n.nspname, c.relname, coalesce(a.attname, ''), d.description
            FROM pg_catalog.pg_description d
            JOIN pg_catalog.pg_class c ON c.oid = d.objoid AND d.classoid = 'pg_catalog.pg_class'::regclass
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.objsubid AND d.objsubid > 0
            WHERE c.relkind IN ('r', 'p', 'v', 'm')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT (n.nspname = ? AND c.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend dep
                  WHERE dep.classid = 'pg_catalog.pg_class'::regclass AND dep.objid = c.oid AND dep.deptype = 'e'
              )
            """;
        try (PreparedStatement ps = conn.prepareStatement(commentSql)) {
            ps.setString(1, historySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString(3);
                    String key = col.isEmpty()
                        ? "comment:table:" + rs.getString(1) + "." + rs.getString(2)
                        : "comment:col:" + rs.getString(1) + "." + rs.getString(2) + "." + col;
                    out.put(key, rs.getString(4));
                }
            }
        }

        return new CatalogSnapshot(out);
    }

    int objectCount() {
        return objects.size();
    }

    List<String> diff(CatalogSnapshot other) {
        List<String> diffs = new ArrayList<>();
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(this.objects.keySet());
        allKeys.addAll(other.objects.keySet());
        for (String key : allKeys) {
            String a = this.objects.get(key);
            String b = other.objects.get(key);
            if (a == null) {
                diffs.add("EXTRA in compacted: " + key + " = " + b);
            } else if (b == null) {
                diffs.add("MISSING in compacted: " + key + " = " + a);
            } else if (!a.equals(b)) {
                diffs.add("MISMATCH " + key + "\n  expected: " + a + "\n  actual:   " + b);
            }
        }
        return diffs;
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private static void queryInto(Connection conn, String sql, RowConsumer consumer) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                consumer.accept(rs);
            }
        }
    }
}
