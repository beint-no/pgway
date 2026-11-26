package no.beint.pgway.gradle;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.beint.pgway.PgwayConfig;
import no.beint.pgway.RawMigrationResource;
import no.beint.pgway.SqlScriptParser;

/**
 * Build-time PostgreSQL 19+ schema and migration linter invoked by {@link VerifyPgwayMigrationsTask}
 * right after replaying and validating migrations against a disposable PostgreSQL 19+ cluster.
 *
 * <p>Supported rules:
 * <ol>
 *   <li><b>Redundant indexes</b> ({@code lintRedundantIndexes}): detects B-tree indexes strictly
 *       covered by another index on the same table (same predicate, prefix key columns with matching
 *       opclass/collation/direction/nulls, and all {@code INCLUDE} columns covered).</li>
 *   <li><b>Unreferenced composite UNIQUE containing PK</b> ({@code lintUnreferencedCompositeUnique}):
 *       detects {@code UNIQUE} constraints whose column set is a strict superset of the table's
 *       {@code PRIMARY KEY} when no foreign key references that exact column set.</li>
 *   <li><b>Non-composite tenant FKs</b> ({@code tenantColumn}, {@code allowedCrossTenantForeignKeys}):
 *       when {@code tenantColumn} is set, verifies every FK between two tables that both carry
 *       {@code tenantColumn} includes {@code tenantColumn} in its referencing columns unless
 *       explicitly allowlisted.</li>
 *   <li><b>Explicit constraint/index names</b> ({@code forbidExplicitNames}): scans migration SQL
 *       scripts (versions {@code > 1}, or all scripts when {@code V1} is not compacted) for explicit
 *       {@code CONSTRAINT <name>} or {@code CREATE [UNIQUE] INDEX [CONCURRENTLY] <name> ON}
 *       clauses (exempting {@code CREATE [UNIQUE] INDEX CONCURRENTLY IF NOT EXISTS <name>}).</li>
 * </ol>
 */
final class SchemaLinter {

    static final String RULE_REDUNDANT_INDEX = "redundant-index";
    static final String RULE_UNREFERENCED_COMPOSITE_UNIQUE = "unreferenced-composite-unique";
    static final String RULE_NON_COMPOSITE_TENANT_FK = "non-composite-tenant-fk";
    static final String RULE_EXPLICIT_NAME = "forbid-explicit-names";

    private static final Pattern VERSIONED_PATTERN = Pattern.compile("^V([1-9][0-9]*)__(.+)\\.sql$");
    private static final Set<String> NON_DEFINING_CONSTRAINT_PREFIXES = Set.of(
        "DROP", "VALIDATE", "ALTER", "RENAME", "ON", "CREATE"
    );

    private SchemaLinter() {}

    record LintConfig(
            boolean lintRedundantIndexes,
            boolean lintUnreferencedCompositeUnique,
            String tenantColumn,
            Set<String> allowedCrossTenantForeignKeys,
            boolean forbidExplicitNames) {

        LintConfig {
            tenantColumn = (tenantColumn != null && !tenantColumn.isBlank()) ? tenantColumn.trim() : null;
            allowedCrossTenantForeignKeys = allowedCrossTenantForeignKeys == null
                ? Set.of()
                : Set.copyOf(allowedCrossTenantForeignKeys);
        }

        static LintConfig defaults() {
            return new LintConfig(true, true, null, Set.of(), false);
        }
    }

    record LintViolation(String rule, String location, String message) {
        String format() {
            return "[" + rule + "] " + location + ": " + message;
        }
    }

    static List<LintViolation> lint(
            Connection conn,
            String primarySchema,
            List<RawMigrationResource> resources,
            LintConfig config) throws SQLException {
        List<LintViolation> violations = new ArrayList<>();

        if (config.lintRedundantIndexes()) {
            violations.addAll(lintRedundantIndexes(conn, primarySchema));
        }

        if (config.lintUnreferencedCompositeUnique() || config.tenantColumn() != null) {
            Map<Long, TableInfo> tables = loadTablesAndConstraints(conn, primarySchema);
            List<ForeignKeyInfo> allForeignKeys = loadAllForeignKeys(conn);
            if (config.lintUnreferencedCompositeUnique()) {
                violations.addAll(lintUnreferencedCompositeUnique(tables, allForeignKeys, primarySchema));
            }
            if (config.tenantColumn() != null) {
                violations.addAll(lintTenantForeignKeys(
                    tables,
                    primarySchema,
                    config.tenantColumn(),
                    config.allowedCrossTenantForeignKeys()
                ));
            }
        }

        if (config.forbidExplicitNames() && resources != null && !resources.isEmpty()) {
            violations.addAll(lintExplicitNames(resources));
        }

        return List.copyOf(violations);
    }

    static String formatReport(List<LintViolation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pgway schema lint failed with ").append(violations.size()).append(" violation(s):");
        for (int i = 0; i < violations.size(); i++) {
            sb.append("\n  ").append(i + 1).append(". ").append(violations.get(i).format().replace("\n", "\n     "));
        }
        return sb.toString();
    }

    // ---- Rule 1: Redundant indexes ----

    private record KeyColumn(short attnum, String expression, long opclassOid, long collationOid, short option) {}

    private record IndexInfo(
            long tableOid,
            String schema,
            String tableName,
            long indexOid,
            String indexName,
            String accessMethod,
            boolean unique,
            boolean primary,
            boolean exclusion,
            boolean nullsNotDistinct,
            List<KeyColumn> keys,
            Set<Short> includeAttnums,
            Set<Short> coveredAttnums,
            String predicate,
            String indexDef,
            boolean constraintBacked,
            boolean inherited) {}

    static List<LintViolation> lintRedundantIndexes(Connection conn, String primarySchema) throws SQLException {
        Map<Long, List<IndexInfo>> indexesByTable = loadBtreeIndexes(conn, primarySchema);
        List<LintViolation> violations = new ArrayList<>();

        Comparator<IndexInfo> keepPriority = Comparator
            .comparing((IndexInfo i) -> !i.primary())
            .thenComparing(i -> !i.constraintBacked())
            .thenComparing(i -> !i.unique())
            .thenComparing(Comparator.comparingInt((IndexInfo i) -> i.keys().size()).reversed())
            .thenComparing(Comparator.comparingInt((IndexInfo i) -> i.includeAttnums().size()).reversed())
            .thenComparing(IndexInfo::indexName);

        for (List<IndexInfo> tableIndexes : indexesByTable.values()) {
            if (tableIndexes.size() < 2) {
                continue;
            }
            List<IndexInfo> sorted = new ArrayList<>(tableIndexes);
            sorted.sort(keepPriority);

            for (int aIdx = 0; aIdx < sorted.size(); aIdx++) {
                IndexInfo a = sorted.get(aIdx);
                if (a.exclusion()) {
                    continue;
                }
                for (int bIdx = 0; bIdx < sorted.size(); bIdx++) {
                    if (aIdx == bIdx) {
                        continue;
                    }
                    IndexInfo b = sorted.get(bIdx);
                    if (a.inherited() && b.inherited()) {
                        // Already checked on the partitioned parent table.
                        continue;
                    }
                    if (!isRedundantWith(a, b)) {
                        continue;
                    }
                    // If mutually redundant (exact duplicates), report only once: lower-priority index redundant with higher-priority index.
                    if (isRedundantWith(b, a) && bIdx > aIdx) {
                        continue;
                    }
                    String qualifiedTable = displayTable(a.schema(), a.tableName(), primarySchema);
                    violations.add(new LintViolation(
                        RULE_REDUNDANT_INDEX,
                        qualifiedTable + "." + a.indexName(),
                        "Index '" + a.indexName() + "' on " + qualifiedTable
                            + " is redundant with '" + b.indexName() + "'\n"
                            + "redundant:  " + a.indexDef() + "\n"
                            + "covered by: " + b.indexDef()
                    ));
                    break;
                }
            }
        }
        return violations;
    }

    static boolean isRedundantWith(IndexInfo a, IndexInfo b) {
        if (!"btree".equals(a.accessMethod()) || !"btree".equals(b.accessMethod())) {
            return false;
        }
        if (a.exclusion()) {
            return false;
        }
        if (!Objects.equals(a.predicate(), b.predicate())) {
            return false;
        }
        if (a.unique()) {
            if (!b.unique()) {
                return false;
            }
            if (a.keys().size() != b.keys().size()) {
                return false;
            }
            if (a.nullsNotDistinct() != b.nullsNotDistinct()) {
                return false;
            }
        }
        if (a.keys().size() > b.keys().size()) {
            return false;
        }
        for (int i = 0; i < a.keys().size(); i++) {
            if (!a.keys().get(i).equals(b.keys().get(i))) {
                return false;
            }
        }
        return b.coveredAttnums().containsAll(a.includeAttnums());
    }

    private static Map<Long, List<IndexInfo>> loadBtreeIndexes(Connection conn, String primarySchema) throws SQLException {
        String sql = """
            SELECT t.oid AS table_oid,
                   n.nspname AS schema_name,
                   t.relname AS table_name,
                   i.oid AS index_oid,
                   i.relname AS index_name,
                   am.amname AS access_method,
                   idx.indisunique,
                   idx.indisprimary,
                   idx.indisexclusion,
                   idx.indnullsnotdistinct,
                   idx.indnkeyatts,
                   idx.indnatts,
                   idx.indkey::int2[] AS indkey,
                   idx.indclass::oid[] AS indclass,
                   idx.indcollation::oid[] AS indcollation,
                   idx.indoption::int2[] AS indoption,
                   pg_catalog.pg_get_expr(idx.indpred, idx.indrelid) AS predicate,
                   pg_catalog.pg_get_indexdef(idx.indexrelid) AS index_def,
                   ARRAY(
                       SELECT CASE WHEN idx.indkey[k - 1] = 0
                                   THEN pg_catalog.pg_get_indexdef(idx.indexrelid, k, false)
                                   ELSE '' END
                       FROM generate_series(1, idx.indnkeyatts) AS k
                   ) AS key_exprs,
                   EXISTS (
                       SELECT 1 FROM pg_catalog.pg_constraint con
                       WHERE con.conindid = i.oid AND con.contype IN ('p', 'u', 'x')
                   ) AS is_constraint_backed,
                   EXISTS (
                       SELECT 1 FROM pg_catalog.pg_inherits inh
                       WHERE inh.inhrelid = i.oid
                   ) AS is_inherited
            FROM pg_catalog.pg_index idx
            JOIN pg_catalog.pg_class i ON i.oid = idx.indexrelid
            JOIN pg_catalog.pg_am am ON am.oid = i.relam
            JOIN pg_catalog.pg_class t ON t.oid = idx.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
            WHERE t.relkind IN ('r', 'p')
              AND am.amname = 'btree'
              AND idx.indisvalid
              AND idx.indisready
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
              AND n.nspname NOT LIKE 'pg_temp_%'
              AND NOT (n.nspname = ? AND t.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass
                    AND d.objid = t.oid
                    AND d.deptype = 'e'
              )
            ORDER BY n.nspname, t.relname, i.relname
            """;

        Map<Long, List<IndexInfo>> byTable = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, primarySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long tableOid = rs.getLong(1);
                    String schema = rs.getString(2);
                    String tableName = rs.getString(3);
                    long indexOid = rs.getLong(4);
                    String indexName = rs.getString(5);
                    String accessMethod = rs.getString(6);
                    boolean unique = rs.getBoolean(7);
                    boolean primary = rs.getBoolean(8);
                    boolean exclusion = rs.getBoolean(9);
                    boolean nullsNotDistinct = rs.getBoolean(10);
                    int nKeyAtts = rs.getInt(11);
                    int nAtts = rs.getInt(12);
                    short[] indkey = toShortArray(rs.getArray(13));
                    long[] indclass = toLongArray(rs.getArray(14));
                    long[] indcollation = toLongArray(rs.getArray(15));
                    short[] indoption = toShortArray(rs.getArray(16));
                    String predicate = rs.getString(17);
                    String indexDef = rs.getString(18);
                    String[] keyExprs = toStringArray(rs.getArray(19));
                    boolean constraintBacked = rs.getBoolean(20);
                    boolean inherited = rs.getBoolean(21);

                    List<KeyColumn> keys = new ArrayList<>(nKeyAtts);
                    Set<Short> coveredAttnums = new HashSet<>();
                    for (int k = 0; k < nKeyAtts; k++) {
                        short attnum = indkey[k];
                        String expr = attnum == 0 ? keyExprs[k] : "";
                        keys.add(new KeyColumn(attnum, expr, indclass[k], indcollation[k], indoption[k]));
                        if (attnum > 0) {
                            coveredAttnums.add(attnum);
                        }
                    }

                    Set<Short> includeAttnums = new HashSet<>();
                    for (int k = nKeyAtts; k < nAtts && k < indkey.length; k++) {
                        short attnum = indkey[k];
                        if (attnum > 0) {
                            includeAttnums.add(attnum);
                            coveredAttnums.add(attnum);
                        }
                    }

                    IndexInfo info = new IndexInfo(
                        tableOid,
                        schema,
                        tableName,
                        indexOid,
                        indexName,
                        accessMethod,
                        unique,
                        primary,
                        exclusion,
                        nullsNotDistinct,
                        List.copyOf(keys),
                        Set.copyOf(includeAttnums),
                        Set.copyOf(coveredAttnums),
                        predicate,
                        indexDef,
                        constraintBacked,
                        inherited
                    );
                    byTable.computeIfAbsent(tableOid, _ -> new ArrayList<>()).add(info);
                }
            }
        }
        return byTable;
    }

    // ---- Rules 2 & 3: Shared table/constraint metadata ----

    private record ConstraintInfo(
            long oid,
            String name,
            char type,
            short[] colNums,
            long refTableOid,
            short[] refColNums,
            String def) {}

    private record ForeignKeyInfo(long tableOid, long refTableOid, short[] refColNums) {}

    private static final class TableInfo {
        final long oid;
        final String schema;
        final String name;
        final Map<Short, String> columnsByNum = new HashMap<>();
        final Set<String> columnNames = new HashSet<>();
        ConstraintInfo primaryKey;
        final List<ConstraintInfo> uniqueConstraints = new ArrayList<>();
        final List<ConstraintInfo> foreignKeys = new ArrayList<>();

        TableInfo(long oid, String schema, String name) {
            this.oid = oid;
            this.schema = schema;
            this.name = name;
        }
    }

    private static Map<Long, TableInfo> loadTablesAndConstraints(Connection conn, String primarySchema) throws SQLException {
        Map<Long, TableInfo> tables = new LinkedHashMap<>();
        String tableSql = """
            SELECT c.oid, n.nspname, c.relname
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
              AND n.nspname NOT LIKE 'pg_temp_%'
              AND NOT (n.nspname = ? AND c.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass
                    AND d.objid = c.oid
                    AND d.deptype = 'e'
              )
            ORDER BY n.nspname, c.relname
            """;
        try (PreparedStatement ps = conn.prepareStatement(tableSql)) {
            ps.setString(1, primarySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long oid = rs.getLong(1);
                    tables.put(oid, new TableInfo(oid, rs.getString(2), rs.getString(3)));
                }
            }
        }
        if (tables.isEmpty()) {
            return tables;
        }

        String colSql = """
            SELECT a.attrelid, a.attnum, a.attname
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
            WHERE c.relkind IN ('r', 'p')
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY a.attrelid, a.attnum
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(colSql)) {
            while (rs.next()) {
                TableInfo t = tables.get(rs.getLong(1));
                if (t != null) {
                    short attnum = rs.getShort(2);
                    String name = rs.getString(3);
                    t.columnsByNum.put(attnum, name);
                    t.columnNames.add(name);
                }
            }
        }

        String conSql = """
            SELECT con.oid, con.conrelid, con.conname, con.contype::text,
                   con.conkey, con.confrelid, con.confkey,
                   pg_catalog.pg_get_constraintdef(con.oid, true)
            FROM pg_catalog.pg_constraint con
            WHERE con.conrelid <> 0
              AND con.contype IN ('p', 'u', 'f')
              AND con.conparentid = 0
            ORDER BY con.conrelid, con.contype, con.conname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(conSql)) {
            while (rs.next()) {
                TableInfo t = tables.get(rs.getLong(2));
                if (t == null) {
                    continue;
                }
                char type = rs.getString(4).charAt(0);
                ConstraintInfo info = new ConstraintInfo(
                    rs.getLong(1),
                    rs.getString(3),
                    type,
                    toShortArray(rs.getArray(5)),
                    rs.getLong(6),
                    toShortArray(rs.getArray(7)),
                    rs.getString(8)
                );
                switch (type) {
                    case 'p' -> t.primaryKey = info;
                    case 'u' -> t.uniqueConstraints.add(info);
                    case 'f' -> t.foreignKeys.add(info);
                    default -> {}
                }
            }
        }

        return tables;
    }

    private static List<ForeignKeyInfo> loadAllForeignKeys(Connection conn) throws SQLException {
        List<ForeignKeyInfo> fks = new ArrayList<>();
        String sql = """
            SELECT con.conrelid, con.confrelid, con.confkey
            FROM pg_catalog.pg_constraint con
            WHERE con.contype = 'f'
              AND con.confrelid <> 0
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                fks.add(new ForeignKeyInfo(rs.getLong(1), rs.getLong(2), toShortArray(rs.getArray(3))));
            }
        }
        return fks;
    }

    // ---- Rule 2: Unreferenced composite UNIQUE containing PK ----

    private static List<LintViolation> lintUnreferencedCompositeUnique(
            Map<Long, TableInfo> tables,
            List<ForeignKeyInfo> allForeignKeys,
            String primarySchema) {
        // Index referenced (tableOid -> set of referenced column-num sets)
        Map<Long, Set<Set<Short>>> referencedByTable = new HashMap<>();
        for (ForeignKeyInfo fk : allForeignKeys) {
            referencedByTable
                .computeIfAbsent(fk.refTableOid(), _ -> new HashSet<>())
                .add(toShortSet(fk.refColNums()));
        }

        List<LintViolation> violations = new ArrayList<>();
        for (TableInfo table : tables.values()) {
            if (table.primaryKey == null || table.uniqueConstraints.isEmpty()) {
                continue;
            }
            Set<Short> pkCols = toShortSet(table.primaryKey.colNums());
            if (pkCols.isEmpty()) {
                continue;
            }
            Set<Set<Short>> referencedSets = referencedByTable.getOrDefault(table.oid, Set.of());
            for (ConstraintInfo uq : table.uniqueConstraints) {
                Set<Short> uqCols = toShortSet(uq.colNums());
                if (uqCols.size() > pkCols.size() && uqCols.containsAll(pkCols)) {
                    if (!referencedSets.contains(uqCols)) {
                        String qualifiedTable = displayTable(table.schema, table.name, primarySchema);
                        violations.add(new LintViolation(
                            RULE_UNREFERENCED_COMPOSITE_UNIQUE,
                            qualifiedTable + "." + uq.name(),
                            "Composite UNIQUE constraint '" + uq.name() + "' (" + uq.def() + ") on " + qualifiedTable
                                + " is a strict superset of PRIMARY KEY (" + table.primaryKey.def()
                                + ") and is not referenced by any foreign key. "
                                + "Remove this redundant composite UNIQUE constraint (it only exists to support composite foreign keys)."
                        ));
                    }
                }
            }
        }
        return violations;
    }

    // ---- Rule 3: Non-composite tenant FKs ----

    private static List<LintViolation> lintTenantForeignKeys(
            Map<Long, TableInfo> tables,
            String primarySchema,
            String tenantColumn,
            Set<String> allowedRaw) {
        Set<String> normalizedAllowed = normalizeAllowedCrossTenantFks(allowedRaw);
        List<LintViolation> violations = new ArrayList<>();

        for (TableInfo table : tables.values()) {
            if (!table.columnNames.contains(tenantColumn)) {
                continue;
            }
            for (ConstraintInfo fk : table.foreignKeys) {
                TableInfo refTable = tables.get(fk.refTableOid());
                if (refTable == null || !refTable.columnNames.contains(tenantColumn)) {
                    continue;
                }
                List<String> refingCols = new ArrayList<>(fk.colNums().length);
                for (short attnum : fk.colNums()) {
                    String colName = table.columnsByNum.get(attnum);
                    if (colName != null) {
                        refingCols.add(colName);
                    }
                }
                if (refingCols.contains(tenantColumn)) {
                    continue;
                }
                if (isAllowedCrossTenantFk(table.schema, table.name, refingCols, normalizedAllowed)) {
                    continue;
                }
                String qualifiedTable = displayTable(table.schema, table.name, primarySchema);
                String qualifiedRef = displayTable(refTable.schema, refTable.name, primarySchema);
                String allowSuggestion = refingCols.size() == 1
                    ? table.name + "." + refingCols.getFirst()
                    : table.name + "(" + String.join(",", refingCols) + ")";
                violations.add(new LintViolation(
                    RULE_NON_COMPOSITE_TENANT_FK,
                    qualifiedTable + "." + fk.name(),
                    "Foreign key '" + fk.name() + "' (" + fk.def() + ") on tenant-scoped table " + qualifiedTable
                        + " references tenant-scoped table " + qualifiedRef
                        + " without including '" + tenantColumn + "' in its referencing columns. "
                        + "Use a composite foreign key including '" + tenantColumn
                        + "' or allowlist \"" + allowSuggestion + "\" in allowedCrossTenantForeignKeys."
                ));
            }
        }
        return violations;
    }

    private static Set<String> normalizeAllowedCrossTenantFks(Set<String> raw) {
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>(raw.size() * 2);
        for (String entry : raw) {
            if (entry != null && !entry.isBlank()) {
                out.add(normalizeFkSpec(entry));
            }
        }
        return out;
    }

    private static String normalizeFkSpec(String spec) {
        String trimmed = spec.trim();
        int lparen = trimmed.indexOf('(');
        int rparen = trimmed.lastIndexOf(')');
        if (lparen > 0 && rparen == trimmed.length() - 1) {
            String tablePart = trimmed.substring(0, lparen).trim();
            String[] cols = trimmed.substring(lparen + 1, rparen).split(",");
            StringBuilder sb = new StringBuilder(tablePart).append('(');
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cols[i].trim());
            }
            return sb.append(')').toString();
        }
        return trimmed;
    }

    private static boolean isAllowedCrossTenantFk(
            String schema,
            String table,
            List<String> cols,
            Set<String> normalizedAllowed) {
        if (normalizedAllowed.isEmpty()) {
            return false;
        }
        String joined = String.join(",", cols);
        if (normalizedAllowed.contains(table + "(" + joined + ")")
                || normalizedAllowed.contains(schema + "." + table + "(" + joined + ")")) {
            return true;
        }
        if (cols.size() == 1) {
            String col = cols.getFirst();
            return normalizedAllowed.contains(table + "." + col)
                || normalizedAllowed.contains(schema + "." + table + "." + col);
        }
        return false;
    }

    // ---- Rule 4: Forbid explicit CONSTRAINT / INDEX names ----

    private enum TokenKind { WORD, QUOTED_IDENT, SYMBOL }

    private record SqlToken(TokenKind kind, String text, String upper, int line) {}

    static List<LintViolation> lintExplicitNames(List<RawMigrationResource> resources) {
        record VersionedScript(int version, RawMigrationResource resource) {}

        List<VersionedScript> scripts = new ArrayList<>();
        for (RawMigrationResource r : resources) {
            String base = baseName(r.name());
            if (!base.endsWith(".sql")) {
                continue;
            }
            Matcher m = VERSIONED_PATTERN.matcher(base);
            if (!m.matches()) {
                continue;
            }
            scripts.add(new VersionedScript(Integer.parseInt(m.group(1)), r));
        }
        scripts.sort(Comparator.comparingInt(VersionedScript::version));

        List<LintViolation> violations = new ArrayList<>();
        for (VersionedScript vs : scripts) {
            String normalized = SqlScriptParser.normalizeSql(new String(vs.resource().bytes(), StandardCharsets.UTF_8));
            SqlScriptParser.ParsedScript parsed = SqlScriptParser.parse(vs.resource().name(), normalized);
            if (vs.version() == 1 && parsed.directives().replaces() != null) {
                // Skip compacted V1 (may retain legacy production constraint/index names).
                continue;
            }
            violations.addAll(lintExplicitNamesInScript(vs.resource().name(), normalized));
        }
        return violations;
    }

    static List<LintViolation> lintExplicitNamesInScript(String scriptName, String normalizedSql) {
        List<SqlToken> tokens = tokenize(normalizedSql);
        List<LintViolation> violations = new ArrayList<>();
        int n = tokens.size();

        for (int i = 0; i < n; i++) {
            SqlToken tok = tokens.get(i);
            if (tok.kind() != TokenKind.WORD) {
                continue;
            }

            // Pattern A: explicit CONSTRAINT <name>
            if ("CONSTRAINT".equals(tok.upper())) {
                SqlToken prev = i > 0 ? tokens.get(i - 1) : null;
                if (prev != null && prev.kind() == TokenKind.WORD && NON_DEFINING_CONSTRAINT_PREFIXES.contains(prev.upper())) {
                    continue;
                }
                SqlToken next = i + 1 < n ? tokens.get(i + 1) : null;
                if (next == null) {
                    continue;
                }
                if (next.kind() == TokenKind.WORD && "TRIGGER".equals(next.upper())) {
                    continue;
                }
                if (next.kind() == TokenKind.WORD || next.kind() == TokenKind.QUOTED_IDENT) {
                    violations.add(new LintViolation(
                        RULE_EXPLICIT_NAME,
                        scriptName + ":" + tok.line(),
                        "Explicit constraint name '" + next.text()
                            + "' is forbidden; omit 'CONSTRAINT " + next.text()
                            + "' so PostgreSQL generates the canonical name."
                    ));
                }
                continue;
            }

            // Pattern B: CREATE [UNIQUE] INDEX [CONCURRENTLY] [IF NOT EXISTS] <name> ON
            if ("CREATE".equals(tok.upper())) {
                int j = i + 1;
                if (j < n && isWord(tokens.get(j), "UNIQUE")) {
                    j++;
                }
                if (j >= n || !isWord(tokens.get(j), "INDEX")) {
                    continue;
                }
                j++; // past INDEX
                boolean concurrently = false;
                if (j < n && isWord(tokens.get(j), "CONCURRENTLY")) {
                    concurrently = true;
                    j++;
                }
                boolean ifNotExists = false;
                if (j + 2 < n
                        && isWord(tokens.get(j), "IF")
                        && isWord(tokens.get(j + 1), "NOT")
                        && isWord(tokens.get(j + 2), "EXISTS")) {
                    ifNotExists = true;
                    j += 3;
                }
                if (j >= n) {
                    continue;
                }
                SqlToken candidate = tokens.get(j);
                if (isWord(candidate, "ON")) {
                    // Unnamed index (CREATE [UNIQUE] INDEX [CONCURRENTLY] ON ...) — allowed.
                    continue;
                }
                if ((candidate.kind() == TokenKind.WORD || candidate.kind() == TokenKind.QUOTED_IDENT)
                        && j + 1 < n
                        && isWord(tokens.get(j + 1), "ON")) {
                    if (concurrently && ifNotExists) {
                        // PostgreSQL requires an index name with IF NOT EXISTS; allowed for CONCURRENTLY IF NOT EXISTS.
                        continue;
                    }
                    String hint = ifNotExists
                        ? "omit 'IF NOT EXISTS " + candidate.text()
                            + "' (only 'CREATE [UNIQUE] INDEX CONCURRENTLY IF NOT EXISTS <name>' may specify an explicit name)."
                        : "omit '" + candidate.text()
                            + "' in 'CREATE [UNIQUE] INDEX [CONCURRENTLY] ON ...' so PostgreSQL generates the canonical name.";
                    violations.add(new LintViolation(
                        RULE_EXPLICIT_NAME,
                        scriptName + ":" + candidate.line(),
                        "Explicit index name '" + candidate.text() + "' is forbidden; " + hint
                    ));
                }
            }
        }
        return violations;
    }

    private static List<SqlToken> tokenize(String sql) {
        List<SqlToken> tokens = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        int line = 1;

        while (i < len) {
            char c = sql.charAt(i);

            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    line++;
                }
                i++;
                continue;
            }

            // Single-line comment
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            // Nested block comment
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < len && depth > 0) {
                    char ch = sql.charAt(i);
                    if (ch == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (ch == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                continue;
            }

            // Escape string E'...'
            if ((c == 'E' || c == 'e')
                    && i + 1 < len
                    && sql.charAt(i + 1) == '\''
                    && (i == 0 || !isIdentCont(sql.charAt(i - 1)))) {
                i += 2;
                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '\\') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\n') {
                            line++;
                        }
                        i += 2;
                    } else if (ch == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                continue;
            }

            // Single-quoted string literal
            if (c == '\'') {
                i++;
                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                continue;
            }

            // Double-quoted identifier
            if (c == '"') {
                int start = i;
                int startLine = line;
                i++;
                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '"') {
                        if (i + 1 < len && sql.charAt(i + 1) == '"') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                String raw = sql.substring(start, i);
                tokens.add(new SqlToken(TokenKind.QUOTED_IDENT, raw, raw, startLine));
                continue;
            }

            // Dollar-quoted string $$...$$ or $tag$...$tag$
            if (c == '$') {
                String tag = matchDollarQuoteTag(sql, i);
                if (tag != null) {
                    i += tag.length();
                    int closeIdx = sql.indexOf(tag, i);
                    if (closeIdx < 0) {
                        break;
                    }
                    for (int k = i; k < closeIdx; k++) {
                        if (sql.charAt(k) == '\n') {
                            line++;
                        }
                    }
                    i = closeIdx + tag.length();
                    continue;
                }
                tokens.add(new SqlToken(TokenKind.SYMBOL, "$", "$", line));
                i++;
                continue;
            }

            // Unquoted word/identifier
            if (isIdentStart(c)) {
                int start = i;
                i++;
                while (i < len && isIdentCont(sql.charAt(i))) {
                    i++;
                }
                String word = sql.substring(start, i);
                tokens.add(new SqlToken(TokenKind.WORD, word, word.toUpperCase(Locale.ROOT), line));
                continue;
            }

            // Single-character punctuation/operator
            String sym = String.valueOf(c);
            tokens.add(new SqlToken(TokenKind.SYMBOL, sym, sym, line));
            i++;
        }

        return tokens;
    }

    private static boolean isWord(SqlToken tok, String upper) {
        return tok.kind() == TokenKind.WORD && upper.equals(tok.upper());
    }

    private static String matchDollarQuoteTag(String sql, int start) {
        int len = sql.length();
        if (start + 1 >= len) {
            return null;
        }
        char next = sql.charAt(start + 1);
        if (next == '$') {
            return "$$";
        }
        if (!isDollarTagStart(next)) {
            return null;
        }
        int j = start + 2;
        while (j < len) {
            char ch = sql.charAt(j);
            if (ch == '$') {
                return sql.substring(start, j + 1);
            }
            if (!isDollarTagCont(ch)) {
                return null;
            }
            j++;
        }
        return null;
    }

    private static boolean isDollarTagStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c >= 0x80;
    }

    private static boolean isDollarTagCont(char c) {
        return isDollarTagStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c >= 0x80;
    }

    private static boolean isIdentCont(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '$';
    }

    // ---- Helpers ----

    private static String displayTable(String schema, String tableName, String primarySchema) {
        return schema.equals(primarySchema) ? tableName : schema + "." + tableName;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static Set<Short> toShortSet(short[] arr) {
        Set<Short> set = new HashSet<>(arr.length * 2);
        for (short s : arr) {
            set.add(s);
        }
        return set;
    }

    private static short[] toShortArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return new short[0];
        }
        Object raw = sqlArray.getArray();
        if (raw instanceof Short[] boxed) {
            short[] res = new short[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                res[i] = boxed[i];
            }
            return res;
        }
        if (raw instanceof Object[] objs) {
            short[] res = new short[objs.length];
            for (int i = 0; i < objs.length; i++) {
                res[i] = ((Number) objs[i]).shortValue();
            }
            return res;
        }
        return new short[0];
    }

    private static long[] toLongArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return new long[0];
        }
        Object raw = sqlArray.getArray();
        if (raw instanceof Long[] boxed) {
            long[] res = new long[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                res[i] = boxed[i];
            }
            return res;
        }
        if (raw instanceof Object[] objs) {
            long[] res = new long[objs.length];
            for (int i = 0; i < objs.length; i++) {
                res[i] = ((Number) objs[i]).longValue();
            }
            return res;
        }
        return new long[0];
    }

    private static String[] toStringArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return new String[0];
        }
        Object raw = sqlArray.getArray();
        if (raw instanceof String[] strs) {
            return strs;
        }
        if (raw instanceof Object[] objs) {
            String[] res = new String[objs.length];
            for (int i = 0; i < objs.length; i++) {
                res[i] = objs[i] != null ? objs[i].toString() : "";
            }
            return res;
        }
        return new String[0];
    }
}
