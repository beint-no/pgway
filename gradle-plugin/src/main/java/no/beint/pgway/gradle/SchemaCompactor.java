package no.beint.pgway.gradle;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.beint.pgway.PgwayConfig;

/**
 * Generates compact, idiomatic, human-readable PostgreSQL 19+ DDL from live system catalogs.
 *
 * <p>Unlike {@code pg_dump}, this compactor folds column additions, constraints, defaults,
 * identity/generated expressions, and storage settings directly into clean {@code CREATE TABLE}
 * statements, omits redundant PostgreSQL-default names and {@code USING btree} clauses, keeps
 * indexes and comments right beneath their owning table, and orders tables/views/functions by
 * dependency so the resulting {@code V1} migration reads as if hand-authored.
 */
final class SchemaCompactor {

    private static final int PG_NAMEDATALEN = 64;
    private static final Pattern SIMPLE_IDENT = Pattern.compile("^[a-z_][a-z0-9_]*$");
    private static final Pattern SIMPLE_TEXT_LITERAL = Pattern.compile("^('(?:[^']|'')*')::(?:text|character varying|bpchar)$");
    private static final Pattern ANY_ARRAY_SUBEXPR = Pattern.compile(
        "(?:\\(([a-z_][a-z0-9_]*|\"[^\"]+\")\\)|\\b([a-z_][a-z0-9_]*|\"[^\"]+\"))(?:::text)?\\s*(=\\s*ANY|<>\\s*ALL)\\s*"
            + "(?:\\(\\(ARRAY\\[([^]]+)]\\)(?:::text\\[])?\\)|\\(ARRAY\\[([^]]+)](?:::text\\[])?\\))"
    );
    private static final Pattern ARRAY_TEXT_ELEMENT = Pattern.compile("^'(?:[^']|'')*'::(?:text|character varying|bpchar)$");
    private static final Pattern TEXT_COL_CAST = Pattern.compile("(?:\\(([a-z_][a-z0-9_]*)\\)|\\b([a-z_][a-z0-9_]*))::text\\b");
    private static final Pattern CMP_NUMERIC_LITERAL = Pattern.compile("(=|<>|!=|<=|>=|<|>)\\s*(?:\\((\\d+(?:\\.\\d+)?)\\)|(\\d+(?:\\.\\d+)?))::numeric\\b");

    private static final Set<String> RESERVED_WORDS = Set.of(
        "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
        "authorization", "binary", "both", "case", "cast", "check", "collate", "collation",
        "column", "concurrently", "constraint", "create", "cross", "current_catalog",
        "current_date", "current_role", "current_schema", "current_time", "current_timestamp",
        "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end",
        "except", "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant",
        "group", "having", "ilike", "in", "initially", "inner", "intersect", "into", "is",
        "isnull", "join", "lateral", "leading", "left", "like", "limit", "localtime",
        "localtimestamp", "natural", "not", "notnull", "null", "offset", "on", "only", "or",
        "order", "outer", "overlaps", "placing", "primary", "references", "returning", "right",
        "select", "session_user", "similar", "some", "symmetric", "system_user", "table",
        "tablesample", "then", "to", "trailing", "true", "union", "unique", "user", "using",
        "variadic", "verbose", "when", "where", "window", "with"
    );

    private SchemaCompactor() {}

    record CompactionOutput(String sql, int tableCount, int indexCount, int viewCount, int functionCount, int triggerCount) {}

    record PreservedBlock(String sourceScript, String sql) {}

    static CompactionOutput compact(
            Connection conn,
            String primarySchema,
            int replacesThroughVersion,
            String replacesChainChecksum,
            List<PreservedBlock> preservedBlocks) throws SQLException {
        setSearchPath(conn, primarySchema);

        List<String> extraSchemas = loadExtraSchemas(conn, primarySchema);
        List<ExtensionDef> extensions = loadExtensions(conn);
        List<EnumDef> enums = loadEnums(conn);
        List<DomainDef> domains = loadDomains(conn);
        Map<Long, SequenceDef> allSequences = loadSequences(conn);
        Map<Long, TableDef> tablesByOid = loadTables(conn, primarySchema);
        loadColumns(conn, tablesByOid, allSequences);
        loadConstraints(conn, tablesByOid);
        loadIndexes(conn, tablesByOid);
        loadComments(conn, tablesByOid);
        Map<Long, ViewDef> viewsByOid = loadViews(conn);
        loadMatViewIndexesAndComments(conn, viewsByOid);
        Map<Long, FunctionDef> functionsByOid = loadFunctions(conn);
        List<TriggerDef> triggers = loadTriggers(conn);

        // Identify sequences that are handled automatically by serial/identity columns vs explicit CREATE SEQUENCE.
        Set<Long> implicitSequenceOids = new HashSet<>();
        Map<Long, List<SequenceDef>> ownedExplicitSequencesByTable = new HashMap<>();
        for (TableDef table : tablesByOid.values()) {
            for (ColumnDef col : table.columns) {
                if (col.identitySeq != null) {
                    implicitSequenceOids.add(col.identitySeq.oid);
                } else if (col.serialKeyword != null && col.ownedSeq != null) {
                    implicitSequenceOids.add(col.ownedSeq.oid);
                } else if (col.ownedSeq != null) {
                    ownedExplicitSequencesByTable.computeIfAbsent(table.oid, _ -> new ArrayList<>()).add(col.ownedSeq);
                }
            }
        }
        List<SequenceDef> explicitSequences = allSequences.values().stream()
            .filter(seq -> !implicitSequenceOids.contains(seq.oid))
            .sorted(Comparator
                .comparingInt((SequenceDef s) -> schemaRank(s.schema, primarySchema))
                .thenComparing(s -> s.schema)
                .thenComparing(s -> s.name))
            .toList();

        // Partition functions into:
        //  1. pre-table (needed by defaults, generated cols, checks, or expression indexes)
        //  2. post-table / pre-view (trigger functions, table helpers, and functions called by views)
        //  3. post-view (functions directly depending on a view/matview)
        Set<Long> preTableFuncOids = findPreTableFunctions(conn, functionsByOid);
        Set<Long> postViewFuncOids = findPostViewFunctions(conn, functionsByOid, viewsByOid.keySet(), preTableFuncOids);
        List<FunctionDef> preTableFuncs = topoSortFunctions(
            functionsByOid.values().stream().filter(f -> preTableFuncOids.contains(f.oid)).toList(),
            conn
        );
        List<FunctionDef> postTablePreViewFuncs = topoSortFunctions(
            functionsByOid.values().stream()
                .filter(f -> !preTableFuncOids.contains(f.oid) && !postViewFuncOids.contains(f.oid))
                .toList(),
            conn
        );
        List<FunctionDef> postViewFuncs = topoSortFunctions(
            functionsByOid.values().stream().filter(f -> postViewFuncOids.contains(f.oid)).toList(),
            conn
        );

        // Topologically sort tables by FK + partition-parent dependencies, deferring cyclic FKs.
        TableOrderResult tableOrder = orderTables(tablesByOid, primarySchema);

        // Topologically sort views/matviews by pg_depend dependencies.
        List<ViewDef> orderedViews = orderViews(viewsByOid, conn, primarySchema);

        StringBuilder sb = new StringBuilder(32_768);
        if (replacesThroughVersion > 0 && replacesChainChecksum != null) {
            sb.append("-- pgway: replaces=V").append(replacesThroughVersion).append(':').append(replacesChainChecksum).append('\n');
        }

        // 1. Extra schemas
        for (String schema : extraSchemas) {
            appendBlankLineIfNeeded(sb);
            sb.append("CREATE SCHEMA ").append(quoteIdent(schema)).append(";\n");
        }

        // 2. Extensions
        for (ExtensionDef ext : extensions) {
            appendBlankLineIfNeeded(sb);
            sb.append("CREATE EXTENSION IF NOT EXISTS ").append(quoteIdent(ext.name));
            if (!ext.schema.equals(primarySchema) && !"public".equals(ext.schema)) {
                sb.append(" WITH SCHEMA ").append(quoteIdent(ext.schema));
            }
            sb.append(";\n");
        }

        // 3. Enums
        for (EnumDef e : enums) {
            appendBlankLineIfNeeded(sb);
            sb.append("CREATE TYPE ").append(qualify(e.schema, e.name, primarySchema)).append(" AS ENUM (");
            for (int i = 0; i < e.labels.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(quoteLiteral(e.labels.get(i)));
            }
            sb.append(");\n");
        }

        // 4. Domains
        for (DomainDef d : domains) {
            appendBlankLineIfNeeded(sb);
            sb.append("CREATE DOMAIN ").append(qualify(d.schema, d.name, primarySchema))
                .append(" AS ").append(shortenType(d.baseType, primarySchema));
            if (d.defaultExpr != null && !d.defaultExpr.isEmpty()) {
                sb.append(" DEFAULT ").append(d.defaultExpr);
            }
            if (d.notNull) {
                sb.append(" NOT NULL");
            }
            for (DomainConstraint dc : d.constraints) {
                sb.append(' ');
                if (!dc.name.equals(pgDefaultName(d.name, null, "check"))) {
                    sb.append("CONSTRAINT ").append(quoteIdent(dc.name)).append(' ');
                }
                sb.append(cleanCheckDef(dc.def, Set.of()));
            }
            sb.append(";\n");
        }

        // 5. Explicit sequences (standalone + custom-owned)
        for (SequenceDef seq : explicitSequences) {
            appendBlankLineIfNeeded(sb);
            sb.append(renderCreateSequence(seq, primarySchema)).append(";\n");
        }

        // 6. Pre-table functions
        for (FunctionDef fn : preTableFuncs) {
            appendBlankLineIfNeeded(sb);
            sb.append(renderFunction(fn, primarySchema)).append('\n');
        }

        // 7. Tables (+ storage settings, NOT VALID constraints, custom owned-sequence bindings, indexes, and comments right below each table)
        int totalIndexes = 0;
        for (TableDef table : tableOrder.ordered) {
            appendBlankLineIfNeeded(sb);
            sb.append(renderTable(table, tablesByOid, tableOrder.deferredFkOids, primarySchema));

            // NOT VALID constraints must be added via ALTER TABLE (PostgreSQL silently drops NOT VALID inside CREATE TABLE)
            Set<String> tableTextCols = textLikeColumns(table);
            for (ConstraintDef con : table.constraints) {
                if (!con.validated && !tableOrder.deferredFkOids.contains(con.oid)) {
                    sb.append("ALTER TABLE ").append(qualify(table.schema, table.name, primarySchema)).append(" ADD ");
                    if (!isDefaultConstraintName(table, con, tablesByOid)) {
                        sb.append("CONSTRAINT ").append(quoteIdent(con.name)).append(' ');
                    }
                    sb.append(renderTableConstraint(con, tablesByOid, primarySchema, tableTextCols)).append(";\n");
                }
            }

            // Non-default column storage (e.g. SET STORAGE EXTERNAL)
            for (ColumnDef col : table.columns) {
                if (col.storage != col.defaultTypeStorage) {
                    sb.append("ALTER TABLE ").append(qualify(table.schema, table.name, primarySchema))
                        .append(" ALTER COLUMN ").append(quoteIdent(col.name))
                        .append(" SET STORAGE ").append(storageKeyword(col.storage)).append(";\n");
                }
            }

            // Custom owned sequence bindings
            List<SequenceDef> ownedSeqs = ownedExplicitSequencesByTable.getOrDefault(table.oid, List.of());
            for (SequenceDef seq : ownedSeqs) {
                sb.append("ALTER SEQUENCE ").append(qualify(seq.schema, seq.name, primarySchema))
                    .append(" OWNED BY ").append(qualify(table.schema, table.name, primarySchema))
                    .append('.').append(quoteIdent(seq.ownerCol)).append(";\n");
            }

            // Non-constraint indexes right below the table
            Set<String> allTableRelNames = new HashSet<>();
            for (ConstraintDef con : table.constraints) {
                allTableRelNames.add(con.name);
            }
            for (IndexDef idx : table.indexes) {
                allTableRelNames.add(idx.name);
            }
            for (IndexDef idx : table.indexes) {
                sb.append(renderIndex(idx, table.name, table.schema, primarySchema, allTableRelNames, tableTextCols)).append('\n');
                totalIndexes++;
            }

            // Table and column comments
            if (table.comment != null) {
                sb.append("COMMENT ON TABLE ").append(qualify(table.schema, table.name, primarySchema))
                    .append(" IS ").append(quoteLiteral(table.comment)).append(";\n");
            }
            for (ColumnDef col : table.columns) {
                if (col.comment != null) {
                    sb.append("COMMENT ON COLUMN ").append(qualify(table.schema, table.name, primarySchema))
                        .append('.').append(quoteIdent(col.name))
                        .append(" IS ").append(quoteLiteral(col.comment)).append(";\n");
                }
            }
        }

        // 8. Deferred cyclic FK constraints
        if (!tableOrder.deferredFks.isEmpty()) {
            for (DeferredFk dfk : tableOrder.deferredFks) {
                appendBlankLineIfNeeded(sb);
                TableDef t = dfk.table;
                ConstraintDef con = dfk.constraint;
                sb.append("ALTER TABLE ").append(qualify(t.schema, t.name, primarySchema)).append(" ADD ");
                if (!isDefaultConstraintName(t, con, tablesByOid)) {
                    sb.append("CONSTRAINT ").append(quoteIdent(con.name)).append(' ');
                }
                sb.append(cleanFkDef(con, tablesByOid, primarySchema)).append(";\n");
            }
        }

        // 9. Post-table functions & procedures (trigger functions + functions used by views)
        for (FunctionDef fn : postTablePreViewFuncs) {
            appendBlankLineIfNeeded(sb);
            sb.append(renderFunction(fn, primarySchema)).append('\n');
        }

        // 10. Views & Materialized views
        for (ViewDef view : orderedViews) {
            appendBlankLineIfNeeded(sb);
            sb.append(view.isMaterialized ? "CREATE MATERIALIZED VIEW " : "CREATE VIEW ")
                .append(qualify(view.schema, view.name, primarySchema))
                .append(" AS\n")
                .append(view.definition)
                .append(";\n");
            Set<String> viewIdxNames = new HashSet<>();
            for (IndexDef idx : view.indexes) {
                viewIdxNames.add(idx.name);
            }
            for (IndexDef idx : view.indexes) {
                sb.append(renderIndex(idx, view.name, view.schema, primarySchema, viewIdxNames, Set.of())).append('\n');
                totalIndexes++;
            }
            if (view.comment != null) {
                sb.append("COMMENT ON ")
                    .append(view.isMaterialized ? "MATERIALIZED VIEW " : "VIEW ")
                    .append(qualify(view.schema, view.name, primarySchema))
                    .append(" IS ").append(quoteLiteral(view.comment)).append(";\n");
            }
        }

        // 11. Post-view functions & procedures (if any function directly depends on a view)
        for (FunctionDef fn : postViewFuncs) {
            appendBlankLineIfNeeded(sb);
            sb.append(renderFunction(fn, primarySchema)).append('\n');
        }

        // 12. Triggers
        for (TriggerDef tr : triggers) {
            appendBlankLineIfNeeded(sb);
            sb.append(renderTrigger(tr, primarySchema)).append('\n');
        }

        // 13. Preserved conditional role-grant DO blocks (e.g. external roles absent in disposable PG)
        for (PreservedBlock block : preservedBlocks) {
            appendBlankLineIfNeeded(sb);
            String body = block.sql().strip();
            sb.append(body);
            if (!body.endsWith(";")) {
                sb.append(';');
            }
            sb.append('\n');
        }

        return new CompactionOutput(
            sb.toString(),
            tableOrder.ordered.size(),
            totalIndexes,
            orderedViews.size(),
            preTableFuncs.size() + postTablePreViewFuncs.size() + postViewFuncs.size(),
            triggers.size()
        );
    }

    private static void appendBlankLineIfNeeded(StringBuilder sb) {
        if (!sb.isEmpty() && !sb.toString().endsWith("\n\n")) {
            sb.append('\n');
        }
    }

    private static void setSearchPath(Connection conn, String primarySchema) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if ("public".equals(primarySchema)) {
                stmt.execute("SET search_path TO public");
            } else {
                stmt.execute("SET search_path TO " + quoteIdent(primarySchema) + ", public");
            }
        }
    }

    // ---- Rendering helpers ----

    private static String renderTable(
            TableDef table,
            Map<Long, TableDef> tablesByOid,
            Set<Long> deferredFkOids,
            String primarySchema) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE ");
        if (table.persistence == 'u') {
            sb.append("UNLOGGED ");
        }
        sb.append("TABLE ").append(qualify(table.schema, table.name, primarySchema));

        if (table.isPartition && table.partitionParentOid > 0) {
            TableDef parent = tablesByOid.get(table.partitionParentOid);
            sb.append(" PARTITION OF ")
                .append(parent != null ? qualify(parent.schema, parent.name, primarySchema) : table.partitionParentName)
                .append(' ').append(table.partitionBound);
            if (!table.reloptions.isEmpty()) {
                sb.append(" WITH (").append(table.reloptions).append(')');
            }
            sb.append(";\n");
            return sb.toString();
        }

        // Decide which constraints can be cleanly inlined on a single column vs placed at table level.
        Map<Short, List<ConstraintDef>> inlinedByCol = new HashMap<>();
        List<ConstraintDef> tableLevelConstraints = new ArrayList<>();

        // Count single-column constraints per (attnum, contype) to avoid auto-name collisions when inlining.
        Map<String, Integer> colTypeCounts = new HashMap<>();
        for (ConstraintDef con : table.constraints) {
            if (deferredFkOids.contains(con.oid)) {
                continue;
            }
            if (con.colNums.length == 1) {
                String k = con.colNums[0] + ":" + con.type;
                colTypeCounts.merge(k, 1, Integer::sum);
            }
        }

        Set<String> textLikeCols = textLikeColumns(table);
        for (ConstraintDef con : table.constraints) {
            if (deferredFkOids.contains(con.oid) || !con.validated) {
                continue;
            }
            if (canInlineConstraint(table, con, tablesByOid, colTypeCounts, textLikeCols)) {
                inlinedByCol.computeIfAbsent(con.colNums[0], _ -> new ArrayList<>()).add(con);
            } else {
                tableLevelConstraints.add(con);
            }
        }

        // Order table-level constraints cleanly: PK, UNIQUE, CHECK, EXCLUDE, FK.
        tableLevelConstraints.sort(Comparator
            .comparingInt((ConstraintDef c) -> constraintSortRank(c.type))
            .thenComparing(c -> c.name));

        sb.append(" (\n");
        int totalEntries = table.columns.size() + tableLevelConstraints.size();
        int idx = 0;

        for (ColumnDef col : table.columns) {
            idx++;
            List<ConstraintDef> inlined = inlinedByCol.getOrDefault(col.attnum, List.of());
            if (inlined.size() > 1) {
                inlined.sort(Comparator.comparingInt(c -> inlineOrderRank(c.type)));
            }
            boolean hasInlinePk = inlined.stream().anyMatch(c -> c.type == 'p');

            sb.append("    ").append(quoteIdent(col.name)).append(' ');
            if (col.serialKeyword != null) {
                sb.append(col.serialKeyword);
            } else {
                sb.append(shortenType(col.typeName, primarySchema));
            }

            if (col.collation != null && !col.collation.isEmpty()) {
                sb.append(" COLLATE ").append(quoteIdent(col.collation));
            }

            if (col.identity == 'a') {
                sb.append(" GENERATED ALWAYS AS IDENTITY").append(renderIdentityOptions(col.identitySeq));
            } else if (col.identity == 'd') {
                sb.append(" GENERATED BY DEFAULT AS IDENTITY").append(renderIdentityOptions(col.identitySeq));
            } else if (col.generated == 's') {
                sb.append(" GENERATED ALWAYS AS (").append(col.defaultExpr).append(") STORED");
            } else if (col.generated == 'v') {
                sb.append(" GENERATED ALWAYS AS (").append(col.defaultExpr).append(") VIRTUAL");
            }

            if (col.notNull && !hasInlinePk && col.identity == 0 && col.serialKeyword == null) {
                sb.append(" NOT NULL");
            }

            if (col.identity == 0 && col.generated == 0 && col.serialKeyword == null
                    && col.defaultExpr != null && !col.defaultExpr.isEmpty()) {
                sb.append(" DEFAULT ").append(cleanDefaultExpr(col.defaultExpr, col.typeName, primarySchema));
            }

            for (ConstraintDef con : inlined) {
                sb.append(' ').append(renderInlineConstraint(con, tablesByOid, primarySchema, textLikeCols));
            }

            if (idx < totalEntries) {
                sb.append(',');
            }
            sb.append('\n');
        }

        for (ConstraintDef con : tableLevelConstraints) {
            idx++;
            sb.append("    ");
            if (!isDefaultConstraintName(table, con, tablesByOid)) {
                sb.append("CONSTRAINT ").append(quoteIdent(con.name)).append(' ');
            }
            sb.append(renderTableConstraint(con, tablesByOid, primarySchema, textLikeCols));
            if (idx < totalEntries) {
                sb.append(',');
            }
            sb.append('\n');
        }

        sb.append(')');
        if (!table.partKey.isEmpty()) {
            sb.append(" PARTITION BY ").append(table.partKey);
        }
        if (!table.reloptions.isEmpty()) {
            sb.append(" WITH (").append(table.reloptions).append(')');
        }
        sb.append(";\n");
        return sb.toString();
    }

    private static boolean canInlineConstraint(
            TableDef table,
            ConstraintDef con,
            Map<Long, TableDef> tablesByOid,
            Map<String, Integer> colTypeCounts,
            Set<String> textLikeCols) {
        if (con.colNums.length != 1) {
            return false;
        }
        if (con.deferrable || !con.validated || (con.type == 'c' && con.noInherit)) {
            return false;
        }
        if (!isDefaultConstraintName(table, con, tablesByOid)) {
            return false;
        }
        String countKey = con.colNums[0] + ":" + con.type;
        if (colTypeCounts.getOrDefault(countKey, 0) > 1) {
            return false;
        }
        return switch (con.type) {
            case 'p' -> con.def.startsWith("PRIMARY KEY (") && con.def.endsWith(")");
            case 'u' -> (con.def.startsWith("UNIQUE (") || con.def.startsWith("UNIQUE NULLS NOT DISTINCT ("))
                && con.def.endsWith(")")
                && !con.def.contains(" INCLUDE ")
                && !con.def.contains(" WITH (");
            case 'f' -> !con.def.contains(" MATCH FULL") && !con.def.contains(" MATCH PARTIAL");
            case 'c' -> {
                // Only inline short single-line CHECK constraints so column lines stay crisp.
                String cleaned = cleanCheckDef(con.def, textLikeCols);
                yield cleaned.length() <= 72 && !cleaned.contains("\n");
            }
            default -> false;
        };
    }

    private static String renderInlineConstraint(
            ConstraintDef con,
            Map<Long, TableDef> tablesByOid,
            String primarySchema,
            Set<String> textLikeCols) {
        return switch (con.type) {
            case 'p' -> "PRIMARY KEY";
            case 'u' -> con.def.startsWith("UNIQUE NULLS NOT DISTINCT") ? "UNIQUE NULLS NOT DISTINCT" : "UNIQUE";
            case 'f' -> {
                String cleaned = cleanFkDef(con, tablesByOid, primarySchema);
                int refIdx = cleaned.indexOf("REFERENCES ");
                yield refIdx >= 0 ? cleaned.substring(refIdx) : cleaned;
            }
            case 'c' -> cleanCheckDef(con.def, textLikeCols);
            default -> con.def;
        };
    }

    private static String renderTableConstraint(
            ConstraintDef con,
            Map<Long, TableDef> tablesByOid,
            String primarySchema,
            Set<String> textLikeCols) {
        return switch (con.type) {
            case 'f' -> cleanFkDef(con, tablesByOid, primarySchema);
            case 'c' -> cleanCheckDef(con.def, textLikeCols);
            default -> con.def;
        };
    }

    private static String cleanFkDef(ConstraintDef con, Map<Long, TableDef> tablesByOid, String primarySchema) {
        String def = con.def;
        // If FK references the target table's exact PRIMARY KEY columns, omit redundant target column list.
        TableDef refTable = tablesByOid.get(con.refTableOid);
        if (refTable != null && refTable.pkColNums != null
                && con.refColNums.length == refTable.pkColNums.length
                && java.util.Arrays.equals(con.refColNums, refTable.pkColNums)) {
            String refTarget = qualify(refTable.schema, refTable.name, primarySchema);
            StringBuilder colList = new StringBuilder();
            for (int i = 0; i < con.refColNums.length; i++) {
                if (i > 0) {
                    colList.append(", ");
                }
                ColumnDef refCol = refTable.columnByNum(con.refColNums[i]);
                if (refCol != null) {
                    colList.append(quoteIdent(refCol.name));
                }
            }
            String fullRef = "REFERENCES " + refTarget + "(" + colList + ")";
            int pos = def.indexOf(fullRef);
            if (pos >= 0) {
                def = def.substring(0, pos) + "REFERENCES " + refTarget + def.substring(pos + fullRef.length());
            }
        }
        return def;
    }

    private static boolean isDefaultConstraintName(TableDef table, ConstraintDef con, Map<Long, TableDef> tablesByOid) {
        // Check if any OTHER constraint on the same table shares the same default base name (collision -> keep explicit name).
        String expected = expectedDefaultConstraintName(table, con);
        if (expected == null || !expected.equals(con.name)) {
            return false;
        }
        // For CHECK constraints, PG chooses <table>_<col>_check if single-column or <table>_check if multi-column,
        // and appends 1, 2, ... for subsequent unnamed CHECKs in declaration order. Only omit the name if this is
        // the unique constraint matching that base name!
        for (ConstraintDef other : table.constraints) {
            if (other.oid == con.oid) {
                continue;
            }
            if (other.name.equals(expected) || other.name.startsWith(expected)) {
                String suffix = other.name.substring(expected.length());
                if (suffix.matches("^[0-9]+$")) {
                    // Keeping explicit name on the numbered ones is already automatic because their names end in digits;
                    // the unnumbered first one stays safe ONLY if it's emitted first in declaration order.
                    // For CHECK constraints, emit explicit name if another check on the same column also exists,
                    // just to guarantee exact name stability regardless of ordering.
                    if (con.type == 'c') {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String expectedDefaultConstraintName(TableDef table, ConstraintDef con) {
        return switch (con.type) {
            case 'p' -> pgDefaultName(table.name, null, "pkey");
            case 'u' -> {
                String cols = joinColumnNames(table, con.colNums);
                yield cols != null ? pgDefaultName(table.name, cols, "key") : null;
            }
            case 'f' -> {
                String cols = joinColumnNames(table, con.colNums);
                yield cols != null ? pgDefaultName(table.name, cols, "fkey") : null;
            }
            case 'c' -> {
                if (con.colNums.length == 1) {
                    ColumnDef col = table.columnByNum(con.colNums[0]);
                    yield col != null ? pgDefaultName(table.name, col.name, "check") : null;
                }
                yield pgDefaultName(table.name, null, "check");
            }
            case 'x' -> {
                String cols = joinColumnNames(table, con.colNums);
                yield cols != null ? pgDefaultName(table.name, cols, "excl") : null;
            }
            default -> null;
        };
    }

    private static String joinColumnNames(TableDef table, short[] colNums) {
        if (colNums.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colNums.length; i++) {
            ColumnDef col = table.columnByNum(colNums[i]);
            if (col == null) {
                return null;
            }
            if (i > 0) {
                sb.append('_');
            }
            sb.append(col.name);
        }
        return sb.toString();
    }

    /**
     * Implements PostgreSQL's {@code makeObjectName(name1, name2, label)} 63-byte identifier rule.
     */
    static String pgDefaultName(String name1, String name2, String label) {
        int overhead = label.length() + 1 + (name2 != null ? 1 : 0);
        int avail = (PG_NAMEDATALEN - 1) - overhead;
        String n1 = name1;
        String n2 = name2;

        if (n2 == null) {
            n1 = truncateUtf8(n1, avail);
            return n1 + "_" + label;
        }
        int len1 = utf8Len(n1);
        int len2 = utf8Len(n2);
        while (len1 + len2 > avail) {
            if (len1 > len2) {
                len1--;
            } else {
                len2--;
            }
        }
        n1 = truncateUtf8(n1, len1);
        n2 = truncateUtf8(n2, len2);
        return n1 + "_" + n2 + "_" + label;
    }

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String truncateUtf8(String s, int maxBytes) {
        if (utf8Len(s) <= maxBytes) {
            return s;
        }
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpBytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + cpBytes > maxBytes) {
                break;
            }
            bytes += cpBytes;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    private static String renderIndex(
            IndexDef idx,
            String tableName,
            String tableSchema,
            String primarySchema,
            Set<String> allTableRelNames,
            Set<String> textLikeCols) {
        String def = idx.def;
        // Strip default USING btree
        def = def.replaceFirst(" USING btree \\(", " (");

        // Strip redundant primary schema qualification on table name (ON public.foo -> ON foo)
        String schemaPrefix = " ON " + quoteIdent(primarySchema) + ".";
        String unquotedSchemaPrefix = " ON " + primarySchema + ".";
        if (tableSchema.equals(primarySchema)) {
            if (def.contains(schemaPrefix)) {
                def = def.replaceFirst(Pattern.quote(schemaPrefix), " ON ");
            } else if (def.contains(unquotedSchemaPrefix)) {
                def = def.replaceFirst(Pattern.quote(unquotedSchemaPrefix), " ON ");
            }
        }

        // If the index name matches PG's auto-generated default name for simple column indexes
        // and has no collision with another relation/constraint name on the table, omit the name.
        if (idx.simpleColNames != null && !idx.simpleColNames.isEmpty()) {
            String colsJoined = String.join("_", idx.simpleColNames);
            String defaultIdxName = pgDefaultName(tableName, colsJoined, "idx");
            if (idx.name.equals(defaultIdxName) && !hasNumberedSibling(defaultIdxName, allTableRelNames)) {
                String createUnique = "CREATE UNIQUE INDEX " + quoteIdent(idx.name) + " ON ";
                String createNormal = "CREATE INDEX " + quoteIdent(idx.name) + " ON ";
                if (def.startsWith(createUnique)) {
                    def = "CREATE UNIQUE INDEX ON " + def.substring(createUnique.length());
                } else if (def.startsWith(createNormal)) {
                    def = "CREATE INDEX ON " + def.substring(createNormal.length());
                }
            }
        }
        // Clean up verbose WHERE predicate (= ANY (ARRAY[...]) -> IN, redundant ::text/::bpchar/::numeric casts)
        int whereIdx = def.indexOf(" WHERE ");
        if (whereIdx > 0) {
            String pred = def.substring(whereIdx + " WHERE ".length());
            pred = cleanSqlExpression(pred, textLikeCols);
            while (isWrappedInSingleParenPair(pred)) {
                pred = pred.substring(1, pred.length() - 1);
            }
            def = def.substring(0, whereIdx) + " WHERE " + pred;
        }
        return def + ";";
    }

    private static boolean hasNumberedSibling(String baseName, Set<String> names) {
        for (String n : names) {
            if (n.length() > baseName.length() && n.startsWith(baseName)) {
                if (n.substring(baseName.length()).matches("^[0-9]+$")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String renderFunction(FunctionDef fn, String primarySchema) {
        String def = fn.def.strip();
        if (def.startsWith("CREATE OR REPLACE FUNCTION ")) {
            def = "CREATE FUNCTION " + def.substring("CREATE OR REPLACE FUNCTION ".length());
        } else if (def.startsWith("CREATE OR REPLACE PROCEDURE ")) {
            def = "CREATE PROCEDURE " + def.substring("CREATE OR REPLACE PROCEDURE ".length());
        }
        if (fn.schema.equals(primarySchema)) {
            String pref1 = "CREATE FUNCTION " + primarySchema + ".";
            String pref2 = "CREATE FUNCTION " + quoteIdent(primarySchema) + ".";
            String pref3 = "CREATE PROCEDURE " + primarySchema + ".";
            String pref4 = "CREATE PROCEDURE " + quoteIdent(primarySchema) + ".";
            if (def.startsWith(pref1)) {
                def = "CREATE FUNCTION " + def.substring(pref1.length());
            } else if (def.startsWith(pref2)) {
                def = "CREATE FUNCTION " + def.substring(pref2.length());
            } else if (def.startsWith(pref3)) {
                def = "CREATE PROCEDURE " + def.substring(pref3.length());
            } else if (def.startsWith(pref4)) {
                def = "CREATE PROCEDURE " + def.substring(pref4.length());
            }
        }
        // Replace verbose $function$ delimiter with clean $$ when the body does not contain $$
        int firstDelim = def.indexOf("$function$");
        int lastDelim = def.lastIndexOf("$function$");
        if (firstDelim > 0 && lastDelim > firstDelim) {
            String body = def.substring(firstDelim + "$function$".length(), lastDelim);
            if (!body.contains("$$")) {
                def = def.substring(0, firstDelim) + "$$" + body + "$$"
                    + def.substring(lastDelim + "$function$".length());
            }
        }
        if (!def.endsWith(";")) {
            def = def + ";";
        }
        return def;
    }

    private static String renderTrigger(TriggerDef tr, String primarySchema) {
        String def = tr.def.strip();
        if (tr.tableSchema.equals(primarySchema)) {
            def = def.replace(" ON " + primarySchema + ".", " ON ");
            def = def.replace(" ON " + quoteIdent(primarySchema) + ".", " ON ");
        }
        if (tr.funcSchema.equals(primarySchema)) {
            def = def.replace(" EXECUTE FUNCTION " + primarySchema + ".", " EXECUTE FUNCTION ");
            def = def.replace(" EXECUTE FUNCTION " + quoteIdent(primarySchema) + ".", " EXECUTE FUNCTION ");
        }
        return def + ";";
    }

    private static String renderCreateSequence(SequenceDef seq, String primarySchema) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE SEQUENCE ").append(qualify(seq.schema, seq.name, primarySchema));
        if (!"bigint".equals(seq.dataType)) {
            sb.append(" AS ").append(shortenType(seq.dataType, primarySchema));
        }
        if (seq.increment != 1L) {
            sb.append(" INCREMENT BY ").append(seq.increment);
        }
        long defaultMin = seq.increment > 0 ? 1L : defaultMinForType(seq.dataType);
        long defaultMax = seq.increment > 0 ? defaultMaxForType(seq.dataType) : -1L;
        if (seq.minValue != defaultMin) {
            sb.append(" MINVALUE ").append(seq.minValue);
        }
        if (seq.maxValue != defaultMax) {
            sb.append(" MAXVALUE ").append(seq.maxValue);
        }
        long defaultStart = seq.increment > 0 ? seq.minValue : seq.maxValue;
        if (seq.startValue != defaultStart) {
            sb.append(" START WITH ").append(seq.startValue);
        }
        if (seq.cache != 1L) {
            sb.append(" CACHE ").append(seq.cache);
        }
        if (seq.cycle) {
            sb.append(" CYCLE");
        }
        return sb.toString();
    }

    private static String renderIdentityOptions(SequenceDef seq) {
        if (seq == null) {
            return "";
        }
        StringBuilder opts = new StringBuilder();
        if (seq.increment != 1L) {
            opts.append("INCREMENT BY ").append(seq.increment).append(' ');
        }
        long defaultMin = seq.increment > 0 ? 1L : defaultMinForType(seq.dataType);
        long defaultMax = seq.increment > 0 ? defaultMaxForType(seq.dataType) : -1L;
        if (seq.minValue != defaultMin) {
            opts.append("MINVALUE ").append(seq.minValue).append(' ');
        }
        if (seq.maxValue != defaultMax) {
            opts.append("MAXVALUE ").append(seq.maxValue).append(' ');
        }
        long defaultStart = seq.increment > 0 ? seq.minValue : seq.maxValue;
        if (seq.startValue != defaultStart) {
            opts.append("START WITH ").append(seq.startValue).append(' ');
        }
        if (seq.cache != 1L) {
            opts.append("CACHE ").append(seq.cache).append(' ');
        }
        if (seq.cycle) {
            opts.append("CYCLE ");
        }
        if (opts.isEmpty()) {
            return "";
        }
        return " (" + opts.toString().trim() + ")";
    }

    private static long defaultMinForType(String pgType) {
        return switch (pgType) {
            case "smallint" -> Short.MIN_VALUE;
            case "integer" -> Integer.MIN_VALUE;
            default -> Long.MIN_VALUE;
        };
    }

    private static long defaultMaxForType(String pgType) {
        return switch (pgType) {
            case "smallint" -> Short.MAX_VALUE;
            case "integer" -> Integer.MAX_VALUE;
            default -> Long.MAX_VALUE;
        };
    }

    static String shortenType(String pgType, String primarySchema) {
        String t = pgType;
        if (t.startsWith(primarySchema + ".")) {
            t = t.substring(primarySchema.length() + 1);
        }
        boolean isArray = false;
        StringBuilder arraySuffix = new StringBuilder();
        while (t.endsWith("[]")) {
            isArray = true;
            arraySuffix.append("[]");
            t = t.substring(0, t.length() - 2);
        }
        String shortened;
        if ("integer".equals(t)) {
            shortened = "int";
        } else if ("boolean".equals(t)) {
            shortened = "bool";
        } else if ("timestamp with time zone".equals(t)) {
            shortened = "timestamptz";
        } else if (t.startsWith("timestamp(") && t.endsWith(") with time zone")) {
            shortened = "timestamptz(" + t.substring("timestamp(".length(), t.length() - ") with time zone".length()) + ")";
        } else if ("timestamp without time zone".equals(t)) {
            shortened = "timestamp";
        } else if (t.startsWith("timestamp(") && t.endsWith(") without time zone")) {
            shortened = "timestamp(" + t.substring("timestamp(".length(), t.length() - ") without time zone".length()) + ")";
        } else if ("character varying".equals(t)) {
            shortened = "varchar";
        } else if (t.startsWith("character varying(")) {
            shortened = "varchar(" + t.substring("character varying(".length());
        } else if (t.startsWith("character(")) {
            shortened = "char(" + t.substring("character(".length());
        } else if ("time without time zone".equals(t)) {
            shortened = "time";
        } else if (t.startsWith("time(") && t.endsWith(") without time zone")) {
            shortened = "time(" + t.substring("time(".length(), t.length() - ") without time zone".length()) + ")";
        } else if ("time with time zone".equals(t)) {
            shortened = "timetz";
        } else {
            shortened = t;
        }
        return isArray ? shortened + arraySuffix : shortened;
    }

    private static String cleanDefaultExpr(String expr, String pgType, String primarySchema) {
        if (pgType.equals("text") || pgType.startsWith("character varying") || pgType.startsWith("character(")) {
            Matcher m = SIMPLE_TEXT_LITERAL.matcher(expr);
            if (m.matches()) {
                return m.group(1);
            }
        }
        // Strip redundant cast matching the column's own enum/custom type: 'active'::account_status -> 'active'
        String shortType = shortenType(pgType, primarySchema);
        String suffix1 = "::" + pgType;
        String suffix2 = "::" + shortType;
        if (expr.startsWith("'") && (expr.endsWith(suffix1) || expr.endsWith(suffix2))) {
            String suffix = expr.endsWith(suffix1) ? suffix1 : suffix2;
            String lit = expr.substring(0, expr.length() - suffix.length());
            if (lit.length() >= 2 && lit.endsWith("'") && !lit.substring(1, lit.length() - 1).contains("'")) {
                return lit;
            }
        }
        return expr;
    }

    private static Set<String> textLikeColumns(TableDef table) {
        Set<String> cols = new HashSet<>();
        for (ColumnDef col : table.columns) {
            if ("text".equals(col.typeName) || col.typeName.startsWith("character varying")) {
                cols.add(col.name);
            }
        }
        return cols;
    }

    private static String cleanCheckDef(String def, Set<String> textLikeCols) {
        String suffix = "";
        String core = def;
        if (core.endsWith(" NOT VALID")) {
            suffix = " NOT VALID";
            core = core.substring(0, core.length() - " NOT VALID".length());
        }
        if (core.endsWith(" NO INHERIT")) {
            suffix = " NO INHERIT" + suffix;
            core = core.substring(0, core.length() - " NO INHERIT".length());
        }
        if (!core.startsWith("CHECK (") || !core.endsWith(")")) {
            return def;
        }
        // Strip redundant outer parens: CHECK ((a > 0)) -> CHECK (a > 0)
        String inner = core.substring("CHECK (".length(), core.length() - 1);
        while (isWrappedInSingleParenPair(inner)) {
            inner = inner.substring(1, inner.length() - 1);
        }
        inner = cleanSqlExpression(inner, textLikeCols);
        return "CHECK (" + inner + ")" + suffix;
    }

    /**
     * Simplifies PostgreSQL's canonicalized {@code CHECK} / index {@code WHERE} expressions while
     * preserving exact AST semantics (verified against PostgreSQL 19+ during compaction):
     * <ul>
     *   <li>{@code col = ANY (ARRAY['A'::text, ...])} → {@code col IN ('A', ...)}</li>
     *   <li>{@code col::text} / {@code (col)::text} → {@code col} when {@code col} is {@code text} or {@code varchar}</li>
     *   <li>{@code 'lit'::text} / {@code 'lit'::bpchar} → {@code 'lit'} outside {@code ARRAY[...]}</li>
     *   <li>{@code >= 0::numeric} / {@code >= (0)::numeric} → {@code >= 0} on comparison RHS</li>
     * </ul>
     */
    static String cleanSqlExpression(String expr, Set<String> textLikeCols) {
        String out = rewriteAnyArrayToIn(expr);
        if (!textLikeCols.isEmpty()) {
            out = stripTextColCasts(out, textLikeCols);
        }
        out = stripTextLiteralCasts(out);
        out = stripNumericComparisonLiteralCasts(out);
        return out;
    }

    private static String stripTextColCasts(String expr, Set<String> textLikeCols) {
        // Walk outside single-quoted string literals so we never mutate string contents.
        StringBuilder sb = new StringBuilder(expr.length());
        int n = expr.length();
        int segStart = 0;
        boolean inQuote = false;
        for (int i = 0; i < n; i++) {
            char c = expr.charAt(i);
            if (c == '\'') {
                if (!inQuote) {
                    appendCleanedColSegment(sb, expr.substring(segStart, i), textLikeCols);
                    segStart = i;
                    inQuote = true;
                } else if (i + 1 < n && expr.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    sb.append(expr, segStart, i + 1);
                    segStart = i + 1;
                    inQuote = false;
                }
            }
        }
        if (segStart < n) {
            if (inQuote) {
                sb.append(expr, segStart, n);
            } else {
                appendCleanedColSegment(sb, expr.substring(segStart), textLikeCols);
            }
        }
        return sb.toString();
    }

    private static void appendCleanedColSegment(StringBuilder out, String segment, Set<String> textLikeCols) {
        Matcher m = TEXT_COL_CAST.matcher(segment);
        int last = 0;
        while (m.find()) {
            String col = m.group(1) != null ? m.group(1) : m.group(2);
            if (textLikeCols.contains(col)) {
                out.append(segment, last, m.start());
                out.append(col);
                last = m.end();
            }
        }
        out.append(segment, last, segment.length());
    }

    private static String stripTextLiteralCasts(String expr) {
        StringBuilder sb = new StringBuilder(expr.length());
        int n = expr.length();
        int arrayDepth = 0;
        for (int i = 0; i < n; ) {
            char c = expr.charAt(i);
            if (c == '[') {
                arrayDepth++;
                sb.append(c);
                i++;
            } else if (c == ']') {
                if (arrayDepth > 0) {
                    arrayDepth--;
                }
                sb.append(c);
                i++;
            } else if (c == '\'') {
                int start = i;
                i++;
                while (i < n) {
                    if (expr.charAt(i) == '\'') {
                        if (i + 1 < n && expr.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                sb.append(expr, start, i);
                if (arrayDepth == 0) {
                    if (expr.startsWith("::text", i) && !isIdentOrArrayContinue(expr, i + "::text".length())) {
                        i += "::text".length();
                    } else if (expr.startsWith("::bpchar", i) && !isIdentOrArrayContinue(expr, i + "::bpchar".length())) {
                        i += "::bpchar".length();
                    }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isIdentOrArrayContinue(String s, int pos) {
        if (pos >= s.length()) {
            return false;
        }
        char c = s.charAt(pos);
        return Character.isLetterOrDigit(c) || c == '_' || c == '[' || c == ':';
    }

    private static String stripNumericComparisonLiteralCasts(String expr) {
        // Only outside quotes: rewrite `>= 0::numeric` or `>= (0)::numeric` -> `>= 0`.
        StringBuilder sb = new StringBuilder(expr.length());
        int n = expr.length();
        int segStart = 0;
        boolean inQuote = false;
        for (int i = 0; i < n; i++) {
            char c = expr.charAt(i);
            if (c == '\'') {
                if (!inQuote) {
                    appendCleanedNumericSegment(sb, expr.substring(segStart, i));
                    segStart = i;
                    inQuote = true;
                } else if (i + 1 < n && expr.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    sb.append(expr, segStart, i + 1);
                    segStart = i + 1;
                    inQuote = false;
                }
            }
        }
        if (segStart < n) {
            if (inQuote) {
                sb.append(expr, segStart, n);
            } else {
                appendCleanedNumericSegment(sb, expr.substring(segStart));
            }
        }
        return sb.toString();
    }

    private static void appendCleanedNumericSegment(StringBuilder out, String segment) {
        Matcher m = CMP_NUMERIC_LITERAL.matcher(segment);
        int last = 0;
        while (m.find()) {
            String op = m.group(1);
            String num = m.group(2) != null ? m.group(2) : m.group(3);
            out.append(segment, last, m.start());
            out.append(op).append(' ').append(num);
            last = m.end();
        }
        out.append(segment, last, segment.length());
    }

    private static String rewriteAnyArrayToIn(String expr) {
        Matcher m = ANY_ARRAY_SUBEXPR.matcher(expr);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String fullMatch = m.group(0);
            String lhs = m.group(1) != null ? m.group(1) : m.group(2);
            String op = m.group(3);
            String arrBody = m.group(4) != null ? m.group(4) : m.group(5);
            List<String> elems = splitTopLevelComma(arrBody);
            List<String> cleaned = new ArrayList<>(elems.size());
            boolean allSimpleText = !elems.isEmpty();
            for (String e : elems) {
                String trimmed = e.trim();
                if (ARRAY_TEXT_ELEMENT.matcher(trimmed).matches()) {
                    cleaned.add(trimmed.substring(0, trimmed.lastIndexOf("::")));
                } else {
                    allSimpleText = false;
                    break;
                }
            }
            if (allSimpleText) {
                String keyword = op.startsWith("=") ? " IN (" : " NOT IN (";
                String replacement = lhs + keyword + String.join(", ", cleaned) + ")";
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(fullMatch));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isWrappedInSingleParenPair(String s) {
        if (s.length() < 2 || s.charAt(0) != '(' || s.charAt(s.length() - 1) != ')') {
            return false;
        }
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0 && i < s.length() - 1) {
                        return false;
                    }
                }
            }
        }
        return depth == 0;
    }

    private static List<String> splitTopLevelComma(String s) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        boolean inQuote = false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                if (inQuote && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (!inQuote) {
                if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(s.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static String storageKeyword(char storage) {
        return switch (storage) {
            case 'p' -> "PLAIN";
            case 'e' -> "EXTERNAL";
            case 'm' -> "MAIN";
            case 'x' -> "EXTENDED";
            default -> throw new IllegalStateException("Unknown pg_attribute.attstorage: " + storage);
        };
    }

    private static int inlineOrderRank(char type) {
        return switch (type) {
            case 'p' -> 0;
            case 'u' -> 1;
            case 'f' -> 2;
            case 'c' -> 3;
            default -> 4;
        };
    }

    private static int constraintSortRank(char type) {
        return switch (type) {
            case 'p' -> 0;
            case 'u' -> 1;
            case 'c' -> 2;
            case 'x' -> 3;
            case 'f' -> 4;
            default -> 5;
        };
    }

    private static int schemaRank(String schema, String primarySchema) {
        return schema.equals(primarySchema) ? 0 : 1;
    }

    static String quoteIdent(String ident) {
        if (SIMPLE_IDENT.matcher(ident).matches() && !RESERVED_WORDS.contains(ident)) {
            return ident;
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    static String qualify(String schema, String name, String primarySchema) {
        if (schema.equals(primarySchema)) {
            return quoteIdent(name);
        }
        return quoteIdent(schema) + "." + quoteIdent(name);
    }

    private static String quoteLiteral(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ---- Dependency ordering ----

    private record DeferredFk(TableDef table, ConstraintDef constraint) {}

    private record TableOrderResult(List<TableDef> ordered, Set<Long> deferredFkOids, List<DeferredFk> deferredFks) {}

    private static TableOrderResult orderTables(Map<Long, TableDef> tablesByOid, String primarySchema) {
        Comparator<TableDef> tieBreaker = Comparator
            .comparingInt((TableDef t) -> schemaRank(t.schema, primarySchema))
            .thenComparing(t -> t.schema)
            .thenComparing(t -> t.name);

        // Build dependency edges: table -> tables it depends on (FK targets + partition parent, excluding self).
        Map<Long, Set<Long>> deps = new HashMap<>();
        Map<Long, Set<Long>> rdeps = new HashMap<>();
        for (TableDef t : tablesByOid.values()) {
            deps.put(t.oid, new LinkedHashSet<>());
            rdeps.put(t.oid, new LinkedHashSet<>());
        }
        for (TableDef t : tablesByOid.values()) {
            if (t.isPartition && t.partitionParentOid > 0 && tablesByOid.containsKey(t.partitionParentOid)) {
                deps.get(t.oid).add(t.partitionParentOid);
                rdeps.get(t.partitionParentOid).add(t.oid);
            }
            for (ConstraintDef con : t.constraints) {
                if (con.type == 'f' && con.refTableOid != t.oid && tablesByOid.containsKey(con.refTableOid)) {
                    deps.get(t.oid).add(con.refTableOid);
                    rdeps.get(con.refTableOid).add(t.oid);
                }
            }
        }

        // Kahn's algorithm with deterministic alphabetical tie-breaking; break true cycles cleanly.
        Map<Long, Integer> inDegree = new HashMap<>();
        TreeSet<TableDef> ready = new TreeSet<>(tieBreaker);
        for (TableDef t : tablesByOid.values()) {
            int deg = deps.get(t.oid).size();
            inDegree.put(t.oid, deg);
            if (deg == 0) {
                ready.add(t);
            }
        }

        List<TableDef> ordered = new ArrayList<>(tablesByOid.size());
        Set<Long> emitted = new HashSet<>();
        while (ordered.size() < tablesByOid.size()) {
            TableDef next;
            if (!ready.isEmpty()) {
                next = ready.pollFirst();
            } else {
                // Break cycles only inside source strongly-connected components (never pick a leaf
                // table that is merely waiting on a cycle), preferring the hub table with the
                // fewest FKs to unemitted tables and the most unemitted dependents.
                Set<Long> candidates = findSourceSccCandidates(tablesByOid, deps, emitted);
                next = candidates.stream()
                    .map(tablesByOid::get)
                    .filter(t -> !t.isPartition || emitted.contains(t.partitionParentOid))
                    .min(Comparator
                        .comparingInt((TableDef t) -> countUnemittedFks(t, emitted, tablesByOid))
                        .thenComparingInt(t -> -countUnemittedDependents(t.oid, rdeps, emitted))
                        .thenComparing(tieBreaker))
                    .orElseGet(() -> tablesByOid.values().stream()
                        .filter(t -> !emitted.contains(t.oid))
                        .filter(t -> !t.isPartition || emitted.contains(t.partitionParentOid))
                        .min(Comparator
                            .comparingInt((TableDef t) -> countUnemittedFks(t, emitted, tablesByOid))
                            .thenComparingInt(t -> -countUnemittedDependents(t.oid, rdeps, emitted))
                            .thenComparing(tieBreaker))
                        .orElseThrow());
            }
            ordered.add(next);
            emitted.add(next.oid);
            for (Long dependentOid : rdeps.get(next.oid)) {
                if (!emitted.contains(dependentOid)) {
                    int remaining = inDegree.merge(dependentOid, -1, Integer::sum);
                    if (remaining == 0) {
                        ready.add(tablesByOid.get(dependentOid));
                    }
                }
            }
        }

        // Any FK whose target appears strictly AFTER its owning table in `ordered` is deferred.
        // Also defer self-referencing FKs that target a standalone CREATE UNIQUE INDEX (which is
        // created immediately after CREATE TABLE rather than inside CREATE TABLE).
        Map<Long, Integer> position = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            position.put(ordered.get(i).oid, i);
        }
        Set<Long> deferredFkOids = new HashSet<>();
        List<DeferredFk> deferredFks = new ArrayList<>();
        for (TableDef t : ordered) {
            int pos = position.get(t.oid);
            Set<Long> ownConstraintIndexOids = new HashSet<>();
            for (ConstraintDef c : t.constraints) {
                if ((c.type == 'p' || c.type == 'u') && c.indexOid != 0) {
                    ownConstraintIndexOids.add(c.indexOid);
                }
            }
            for (ConstraintDef con : t.constraints) {
                if (con.type == 'f') {
                    if (con.refTableOid == t.oid) {
                        if (con.indexOid != 0 && !ownConstraintIndexOids.contains(con.indexOid)) {
                            deferredFkOids.add(con.oid);
                            deferredFks.add(new DeferredFk(t, con));
                        }
                    } else {
                        Integer refPos = position.get(con.refTableOid);
                        if (refPos != null && refPos > pos) {
                            deferredFkOids.add(con.oid);
                            deferredFks.add(new DeferredFk(t, con));
                        }
                    }
                }
            }
        }
        return new TableOrderResult(ordered, deferredFkOids, deferredFks);
    }

    /**
     * Finds all nodes belonging to source strongly-connected components of the unemitted dependency
     * graph (SCCs that have zero dependencies on any other unemitted SCC). Breaking a cycle inside
     * a source SCC unlocks every downstream table without deferring their FKs.
     */
    private static Set<Long> findSourceSccCandidates(
            Map<Long, TableDef> tablesByOid,
            Map<Long, Set<Long>> deps,
            Set<Long> emitted) {
        Map<Long, Integer> index = new HashMap<>();
        Map<Long, Integer> lowlink = new HashMap<>();
        Set<Long> onStack = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        List<Set<Long>> sccs = new ArrayList<>();
        int[] nextIndex = {0};

        for (Long oid : tablesByOid.keySet()) {
            if (!emitted.contains(oid) && !index.containsKey(oid)) {
                tarjanScc(oid, deps, emitted, index, lowlink, onStack, stack, sccs, nextIndex);
            }
        }

        // Map each unemitted node to its SCC id
        Map<Long, Integer> sccOf = new HashMap<>();
        for (int i = 0; i < sccs.size(); i++) {
            for (Long oid : sccs.get(i)) {
                sccOf.put(oid, i);
            }
        }

        // Source SCCs in the dependency graph are SCCs with no dependency edge leaving the SCC.
        Set<Long> candidates = new LinkedHashSet<>();
        for (int i = 0; i < sccs.size(); i++) {
            Set<Long> scc = sccs.get(i);
            boolean hasExternalDep = false;
            for (Long oid : scc) {
                for (Long dep : deps.getOrDefault(oid, Set.of())) {
                    if (!emitted.contains(dep) && !sccOf.get(dep).equals(i)) {
                        hasExternalDep = true;
                        break;
                    }
                }
                if (hasExternalDep) {
                    break;
                }
            }
            if (!hasExternalDep) {
                candidates.addAll(scc);
            }
        }
        return candidates;
    }

    private static void tarjanScc(
            long v,
            Map<Long, Set<Long>> deps,
            Set<Long> emitted,
            Map<Long, Integer> index,
            Map<Long, Integer> lowlink,
            Set<Long> onStack,
            Deque<Long> stack,
            List<Set<Long>> sccs,
            int[] nextIndex) {
        int idx = nextIndex[0]++;
        index.put(v, idx);
        lowlink.put(v, idx);
        stack.push(v);
        onStack.add(v);

        for (Long w : deps.getOrDefault(v, Set.of())) {
            if (emitted.contains(w)) {
                continue;
            }
            if (!index.containsKey(w)) {
                tarjanScc(w, deps, emitted, index, lowlink, onStack, stack, sccs, nextIndex);
                lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w)));
            } else if (onStack.contains(w)) {
                lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
            }
        }

        if (lowlink.get(v).equals(index.get(v))) {
            Set<Long> scc = new LinkedHashSet<>();
            while (true) {
                long w = stack.pop();
                onStack.remove(w);
                scc.add(w);
                if (w == v) {
                    break;
                }
            }
            sccs.add(scc);
        }
    }

    private static int countUnemittedFks(TableDef t, Set<Long> emitted, Map<Long, TableDef> tablesByOid) {
        int count = 0;
        for (ConstraintDef con : t.constraints) {
            if (con.type == 'f' && con.refTableOid != t.oid
                    && tablesByOid.containsKey(con.refTableOid)
                    && !emitted.contains(con.refTableOid)) {
                count++;
            }
        }
        return count;
    }

    private static int countUnemittedDependents(long oid, Map<Long, Set<Long>> rdeps, Set<Long> emitted) {
        int count = 0;
        for (Long dep : rdeps.getOrDefault(oid, Set.of())) {
            if (!emitted.contains(dep)) {
                count++;
            }
        }
        return count;
    }

    private static List<ViewDef> orderViews(Map<Long, ViewDef> viewsByOid, Connection conn, String primarySchema)
            throws SQLException {
        if (viewsByOid.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<Long>> deps = new HashMap<>();
        for (Long oid : viewsByOid.keySet()) {
            deps.put(oid, new HashSet<>());
        }
        String sql = """
            SELECT rw.ev_class AS view_oid, d.refobjid AS dep_oid
            FROM pg_catalog.pg_rewrite rw
            JOIN pg_catalog.pg_depend d ON d.classid = 'pg_catalog.pg_rewrite'::regclass AND d.objid = rw.oid
            WHERE d.refclassid = 'pg_catalog.pg_class'::regclass
              AND d.refobjid <> rw.ev_class
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long vOid = rs.getLong(1);
                long depOid = rs.getLong(2);
                if (viewsByOid.containsKey(vOid) && viewsByOid.containsKey(depOid)) {
                    deps.get(vOid).add(depOid);
                }
            }
        }
        Comparator<ViewDef> tieBreaker = Comparator
            .comparingInt((ViewDef v) -> schemaRank(v.schema, primarySchema))
            .thenComparing(v -> v.schema)
            .thenComparing(v -> v.name);

        List<ViewDef> ordered = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        while (ordered.size() < viewsByOid.size()) {
            ViewDef next = viewsByOid.values().stream()
                .filter(v -> !emitted.contains(v.oid))
                .filter(v -> emitted.containsAll(deps.get(v.oid)))
                .min(tieBreaker)
                .orElseGet(() -> viewsByOid.values().stream()
                    .filter(v -> !emitted.contains(v.oid))
                    .min(tieBreaker)
                    .orElseThrow());
            ordered.add(next);
            emitted.add(next.oid);
        }
        return ordered;
    }

    private static Set<Long> findPreTableFunctions(Connection conn, Map<Long, FunctionDef> functionsByOid)
            throws SQLException {
        if (functionsByOid.isEmpty()) {
            return Set.of();
        }
        Set<Long> pre = new HashSet<>();
        // Functions directly depended upon by column defaults, generated expressions, constraints, or expression indexes.
        String sql = """
            SELECT DISTINCT d.refobjid
            FROM pg_catalog.pg_depend d
            WHERE d.refclassid = 'pg_catalog.pg_proc'::regclass
              AND d.classid IN (
                  'pg_catalog.pg_attrdef'::regclass,
                  'pg_catalog.pg_constraint'::regclass,
                  'pg_catalog.pg_class'::regclass
              )
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long fnOid = rs.getLong(1);
                if (functionsByOid.containsKey(fnOid)) {
                    pre.add(fnOid);
                }
            }
        }
        // Transitive closure over pg_proc -> pg_proc dependencies
        Map<Long, Set<Long>> funcDeps = loadFunctionDeps(conn, functionsByOid.keySet());
        Deque<Long> queue = new ArrayDeque<>(pre);
        while (!queue.isEmpty()) {
            long cur = queue.poll();
            for (Long dep : funcDeps.getOrDefault(cur, Set.of())) {
                if (pre.add(dep)) {
                    queue.add(dep);
                }
            }
        }
        return pre;
    }

    private static Set<Long> findPostViewFunctions(
            Connection conn,
            Map<Long, FunctionDef> functionsByOid,
            Set<Long> viewOids,
            Set<Long> preTableFuncOids) throws SQLException {
        if (functionsByOid.isEmpty() || viewOids.isEmpty()) {
            return Set.of();
        }
        Set<Long> actual = new HashSet<>();
        String sql = """
            SELECT DISTINCT d.objid, d.refobjid
            FROM pg_catalog.pg_depend d
            WHERE d.classid = 'pg_catalog.pg_proc'::regclass
              AND d.refclassid = 'pg_catalog.pg_class'::regclass
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long fnOid = rs.getLong(1);
                long refOid = rs.getLong(2);
                if (functionsByOid.containsKey(fnOid) && !preTableFuncOids.contains(fnOid) && viewOids.contains(refOid)) {
                    actual.add(fnOid);
                }
            }
        }
        return actual;
    }

    private static List<FunctionDef> topoSortFunctions(List<FunctionDef> funcs, Connection conn) throws SQLException {
        if (funcs.size() <= 1) {
            return funcs;
        }
        Map<Long, FunctionDef> byOid = new HashMap<>();
        for (FunctionDef f : funcs) {
            byOid.put(f.oid, f);
        }
        Map<Long, Set<Long>> deps = loadFunctionDeps(conn, byOid.keySet());
        Comparator<FunctionDef> tieBreaker = Comparator
            .comparing((FunctionDef f) -> f.schema)
            .thenComparing(f -> f.name)
            .thenComparing(f -> f.identityArgs);

        List<FunctionDef> ordered = new ArrayList<>(funcs.size());
        Set<Long> emitted = new HashSet<>();
        while (ordered.size() < funcs.size()) {
            FunctionDef next = funcs.stream()
                .filter(f -> !emitted.contains(f.oid))
                .filter(f -> emitted.containsAll(deps.getOrDefault(f.oid, Set.of())))
                .min(tieBreaker)
                .orElseGet(() -> funcs.stream()
                    .filter(f -> !emitted.contains(f.oid))
                    .min(tieBreaker)
                    .orElseThrow());
            ordered.add(next);
            emitted.add(next.oid);
        }
        return ordered;
    }

    private static Map<Long, Set<Long>> loadFunctionDeps(Connection conn, Set<Long> fnOids) throws SQLException {
        Map<Long, Set<Long>> deps = new HashMap<>();
        String sql = """
            SELECT d.objid, d.refobjid
            FROM pg_catalog.pg_depend d
            WHERE d.classid = 'pg_catalog.pg_proc'::regclass
              AND d.refclassid = 'pg_catalog.pg_proc'::regclass
              AND d.objid <> d.refobjid
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long obj = rs.getLong(1);
                long ref = rs.getLong(2);
                if (fnOids.contains(obj) && fnOids.contains(ref)) {
                    deps.computeIfAbsent(obj, _ -> new HashSet<>()).add(ref);
                }
            }
        }
        return deps;
    }

    // ---- Catalog loaders ----

    private static List<String> loadExtraSchemas(Connection conn, String primarySchema) throws SQLException {
        List<String> schemas = new ArrayList<>();
        String sql = """
            SELECT nspname
            FROM pg_catalog.pg_namespace
            WHERE nspname NOT IN ('pg_catalog', 'information_schema', 'public')
              AND nspname NOT LIKE 'pg_toast%'
              AND nspname NOT LIKE 'pg_temp_%'
              AND nspname <> ?
            ORDER BY nspname
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, primarySchema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
        }
        return schemas;
    }

    private record ExtensionDef(String name, String schema) {}

    private static List<ExtensionDef> loadExtensions(Connection conn) throws SQLException {
        List<ExtensionDef> list = new ArrayList<>();
        String sql = """
            SELECT e.extname, n.nspname
            FROM pg_catalog.pg_extension e
            JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
            WHERE e.extname <> 'plpgsql'
            ORDER BY e.extname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ExtensionDef(rs.getString(1), rs.getString(2)));
            }
        }
        return list;
    }

    private record EnumDef(String schema, String name, List<String> labels) {}

    private static List<EnumDef> loadEnums(Connection conn) throws SQLException {
        Map<String, EnumDef> map = new TreeMap<>();
        String sql = """
            SELECT n.nspname, t.typname, e.enumlabel
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_catalog.pg_enum e ON e.enumtypid = t.oid
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e'
              )
            ORDER BY n.nspname, t.typname, e.enumsortorder
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String schema = rs.getString(1);
                String name = rs.getString(2);
                String key = schema + "." + name;
                map.computeIfAbsent(key, _ -> new EnumDef(schema, name, new ArrayList<>()))
                    .labels.add(rs.getString(3));
            }
        }
        return new ArrayList<>(map.values());
    }

    private record DomainConstraint(String name, String def) {}

    private record DomainDef(
        long oid, String schema, String name, String baseType,
        boolean notNull, String defaultExpr, List<DomainConstraint> constraints
    ) {}

    private static List<DomainDef> loadDomains(Connection conn) throws SQLException {
        Map<Long, DomainDef> map = new LinkedHashMap<>();
        String sql = """
            SELECT t.oid, n.nspname, t.typname,
                   pg_catalog.format_type(t.typbasetype, t.typtypmod),
                   t.typnotnull, t.typdefault
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE t.typtype = 'd'
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e'
              )
            ORDER BY n.nspname, t.typname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long oid = rs.getLong(1);
                map.put(oid, new DomainDef(
                    oid, rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getBoolean(5), rs.getString(6), new ArrayList<>()
                ));
            }
        }
        if (!map.isEmpty()) {
            String conSql = """
                SELECT c.contypid, c.conname, pg_catalog.pg_get_constraintdef(c.oid, true)
                FROM pg_catalog.pg_constraint c
                WHERE c.contypid <> 0
                ORDER BY c.conname
                """;
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(conSql)) {
                while (rs.next()) {
                    DomainDef d = map.get(rs.getLong(1));
                    if (d != null) {
                        d.constraints.add(new DomainConstraint(rs.getString(2), rs.getString(3)));
                    }
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    private static final class SequenceDef {
        final long oid;
        final String schema;
        final String name;
        final String dataType;
        final long startValue;
        final long increment;
        final long minValue;
        final long maxValue;
        final long cache;
        final boolean cycle;
        long ownerTableOid;
        short ownerAttnum;
        String ownerCol;
        char ownerDeptype; // 'a' = auto/owned (serial), 'i' = internal (identity), 0 = standalone

        SequenceDef(
                long oid, String schema, String name, String dataType,
                long startValue, long increment, long minValue, long maxValue, long cache, boolean cycle) {
            this.oid = oid;
            this.schema = schema;
            this.name = name;
            this.dataType = dataType;
            this.startValue = startValue;
            this.increment = increment;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.cache = cache;
            this.cycle = cycle;
        }

        boolean isStandardSerialOptions() {
            return startValue == 1L
                && increment == 1L
                && minValue == 1L
                && maxValue == defaultMaxForType(dataType)
                && cache == 1L
                && !cycle;
        }
    }

    private static Map<Long, SequenceDef> loadSequences(Connection conn) throws SQLException {
        Map<Long, SequenceDef> map = new LinkedHashMap<>();
        String sql = """
            SELECT c.oid, n.nspname, c.relname,
                   pg_catalog.format_type(s.seqtypid, NULL),
                   s.seqstart, s.seqincrement, s.seqmin, s.seqmax, s.seqcache, s.seqcycle,
                   d.refobjid, d.refobjsubid, ta.attname, d.deptype::text
            FROM pg_catalog.pg_sequence s
            JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_depend d
                   ON d.classid = 'pg_catalog.pg_class'::regclass
                  AND d.objid = c.oid
                  AND d.refclassid = 'pg_catalog.pg_class'::regclass
                  AND d.deptype IN ('a', 'i')
            LEFT JOIN pg_catalog.pg_attribute ta
                   ON ta.attrelid = d.refobjid AND ta.attnum = d.refobjsubid
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend de
                  WHERE de.classid = 'pg_catalog.pg_class'::regclass AND de.objid = c.oid AND de.deptype = 'e'
              )
            ORDER BY n.nspname, c.relname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                SequenceDef seq = new SequenceDef(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getBoolean(10)
                );
                long ownerOid = rs.getLong(11);
                if (!rs.wasNull()) {
                    seq.ownerTableOid = ownerOid;
                    seq.ownerAttnum = rs.getShort(12);
                    seq.ownerCol = rs.getString(13);
                    String dep = rs.getString(14);
                    seq.ownerDeptype = (dep != null && !dep.isEmpty()) ? dep.charAt(0) : 0;
                }
                map.put(seq.oid, seq);
            }
        }
        return map;
    }

    private static final class TableDef {
        final long oid;
        final String schema;
        final String name;
        final char relkind;
        final char persistence;
        final String reloptions;
        final String partKey;
        final boolean isPartition;
        final long partitionParentOid;
        final String partitionParentName;
        final String partitionBound;
        final List<ColumnDef> columns = new ArrayList<>();
        final Map<Short, ColumnDef> columnsByNum = new HashMap<>();
        final List<ConstraintDef> constraints = new ArrayList<>();
        final List<IndexDef> indexes = new ArrayList<>();
        short[] pkColNums;
        String comment;

        TableDef(
                long oid, String schema, String name, char relkind, char persistence,
                String reloptions, String partKey, boolean isPartition,
                long partitionParentOid, String partitionParentName, String partitionBound) {
            this.oid = oid;
            this.schema = schema;
            this.name = name;
            this.relkind = relkind;
            this.persistence = persistence;
            this.reloptions = reloptions;
            this.partKey = partKey;
            this.isPartition = isPartition;
            this.partitionParentOid = partitionParentOid;
            this.partitionParentName = partitionParentName;
            this.partitionBound = partitionBound;
        }

        ColumnDef columnByNum(short attnum) {
            return columnsByNum.get(attnum);
        }
    }

    private static Map<Long, TableDef> loadTables(Connection conn, String primarySchema) throws SQLException {
        Map<Long, TableDef> map = new LinkedHashMap<>();
        String sql = """
            SELECT c.oid, n.nspname, c.relname, c.relkind::text, c.relpersistence::text,
                   coalesce(array_to_string(c.reloptions, ', '), ''),
                   coalesce(pg_catalog.pg_get_partkeydef(c.oid), ''),
                   c.relispartition,
                   coalesce(i.inhparent, 0),
                   coalesce(pn.nspname || '.' || pc.relname, ''),
                   coalesce(pg_catalog.pg_get_expr(c.relpartbound, c.oid), '')
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_inherits i ON i.inhrelid = c.oid
            LEFT JOIN pg_catalog.pg_class pc ON pc.oid = i.inhparent
            LEFT JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace
            WHERE c.relkind IN ('r', 'p')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
              AND NOT (n.nspname = ? AND c.relname = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'
              )
            ORDER BY n.nspname, c.relname
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, primarySchema);
            ps.setString(2, PgwayConfig.HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long oid = rs.getLong(1);
                    map.put(oid, new TableDef(
                        oid,
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4).charAt(0),
                        rs.getString(5).charAt(0),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getBoolean(8),
                        rs.getLong(9),
                        rs.getString(10),
                        rs.getString(11)
                    ));
                }
            }
        }
        return map;
    }

    private static final class ColumnDef {
        final short attnum;
        final String name;
        final String typeName;
        final boolean notNull;
        final char identity;
        final char generated;
        final String defaultExpr;
        final char storage;
        final char defaultTypeStorage;
        final String collation;
        SequenceDef identitySeq;
        SequenceDef ownedSeq;
        String serialKeyword;
        String comment;

        ColumnDef(
                short attnum, String name, String typeName, boolean notNull,
                char identity, char generated, String defaultExpr,
                char storage, char defaultTypeStorage, String collation) {
            this.attnum = attnum;
            this.name = name;
            this.typeName = typeName;
            this.notNull = notNull;
            this.identity = identity;
            this.generated = generated;
            this.defaultExpr = defaultExpr;
            this.storage = storage;
            this.defaultTypeStorage = defaultTypeStorage;
            this.collation = collation;
        }
    }

    private static void loadColumns(
            Connection conn,
            Map<Long, TableDef> tablesByOid,
            Map<Long, SequenceDef> allSequences) throws SQLException {
        // Index owned/identity sequences by (ownerTableOid, ownerAttnum)
        Map<String, SequenceDef> seqByOwner = new HashMap<>();
        for (SequenceDef seq : allSequences.values()) {
            if (seq.ownerTableOid != 0) {
                seqByOwner.put(seq.ownerTableOid + ":" + seq.ownerAttnum, seq);
            }
        }

        String sql = """
            SELECT a.attrelid, a.attnum, a.attname,
                   pg_catalog.format_type(a.atttypid, a.atttypmod),
                   a.attnotnull,
                   a.attidentity::text,
                   a.attgenerated::text,
                   pg_catalog.pg_get_expr(ad.adbin, ad.adrelid),
                   a.attstorage::text,
                   t.typstorage::text,
                   CASE WHEN a.attcollation <> 0 AND a.attcollation <> t.typcollation
                        THEN col.collname ELSE NULL END
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
            JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
            LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
            LEFT JOIN pg_catalog.pg_collation col ON col.oid = a.attcollation
            WHERE c.relkind IN ('r', 'p')
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY a.attrelid, a.attnum
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long tableOid = rs.getLong(1);
                TableDef table = tablesByOid.get(tableOid);
                if (table == null) {
                    continue;
                }
                String identStr = rs.getString(6);
                String genStr = rs.getString(7);
                ColumnDef col = new ColumnDef(
                    rs.getShort(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBoolean(5),
                    (identStr != null && !identStr.isEmpty()) ? identStr.charAt(0) : 0,
                    (genStr != null && !genStr.isEmpty()) ? genStr.charAt(0) : 0,
                    rs.getString(8),
                    rs.getString(9).charAt(0),
                    rs.getString(10).charAt(0),
                    rs.getString(11)
                );

                SequenceDef seq = seqByOwner.get(tableOid + ":" + col.attnum);
                if (seq != null) {
                    if (seq.ownerDeptype == 'i' && col.identity != 0) {
                        col.identitySeq = seq;
                    } else if (seq.ownerDeptype == 'a') {
                        col.ownedSeq = seq;
                        col.serialKeyword = detectSerialKeyword(table, col, seq);
                    }
                }
                table.columns.add(col);
                table.columnsByNum.put(col.attnum, col);
            }
        }
    }

    private static String detectSerialKeyword(TableDef table, ColumnDef col, SequenceDef seq) {
        if (!col.notNull || col.identity != 0 || col.generated != 0 || col.defaultExpr == null) {
            return null;
        }
        if (!seq.schema.equals(table.schema) || !seq.isStandardSerialOptions()) {
            return null;
        }
        String expectedSeqName = pgDefaultName(table.name, col.name, "seq");
        if (!seq.name.equals(expectedSeqName)) {
            return null;
        }
        // Check default is nextval('<seq>'::regclass) matching this sequence
        String unqualified = "nextval('" + seq.name + "'::regclass)";
        String qualified = "nextval('" + seq.schema + "." + seq.name + "'::regclass)";
        String quotedQualified = "nextval('" + quoteIdent(seq.schema) + "." + quoteIdent(seq.name) + "'::regclass)";
        if (!col.defaultExpr.equals(unqualified)
                && !col.defaultExpr.equals(qualified)
                && !col.defaultExpr.equals(quotedQualified)) {
            return null;
        }
        if ("integer".equals(col.typeName) && "integer".equals(seq.dataType)) {
            return "serial";
        }
        if ("bigint".equals(col.typeName) && "bigint".equals(seq.dataType)) {
            return "bigserial";
        }
        if ("smallint".equals(col.typeName) && "smallint".equals(seq.dataType)) {
            return "smallserial";
        }
        return null;
    }

    private static final class ConstraintDef {
        final long oid;
        final String name;
        final char type;
        final short[] colNums;
        final long refTableOid;
        final short[] refColNums;
        final long indexOid;
        final boolean deferrable;
        final boolean validated;
        final boolean noInherit;
        final String def;

        ConstraintDef(
                long oid, String name, char type, short[] colNums,
                long refTableOid, short[] refColNums, long indexOid,
                boolean deferrable, boolean validated, boolean noInherit, String def) {
            this.oid = oid;
            this.name = name;
            this.type = type;
            this.colNums = colNums;
            this.refTableOid = refTableOid;
            this.refColNums = refColNums;
            this.indexOid = indexOid;
            this.deferrable = deferrable;
            this.validated = validated;
            this.noInherit = noInherit;
            this.def = def;
        }
    }

    private static void loadConstraints(Connection conn, Map<Long, TableDef> tablesByOid) throws SQLException {
        String sql = """
            SELECT con.oid, con.conrelid, con.conname, con.contype::text,
                   con.conkey, con.confrelid, con.confkey, con.conindid,
                   con.condeferrable, con.convalidated, con.connoinherit,
                   pg_catalog.pg_get_constraintdef(con.oid, true)
            FROM pg_catalog.pg_constraint con
            WHERE con.conrelid <> 0
              AND con.contype IN ('p', 'u', 'f', 'c', 'x')
              AND con.conparentid = 0
            ORDER BY con.conrelid, con.contype, con.conname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                TableDef table = tablesByOid.get(rs.getLong(2));
                if (table == null) {
                    continue;
                }
                char type = rs.getString(4).charAt(0);
                short[] conkey = toShortArray(rs.getArray(5));
                short[] confkey = toShortArray(rs.getArray(7));
                ConstraintDef con = new ConstraintDef(
                    rs.getLong(1),
                    rs.getString(3),
                    type,
                    conkey,
                    rs.getLong(6),
                    confkey,
                    rs.getLong(8),
                    rs.getBoolean(9),
                    rs.getBoolean(10),
                    rs.getBoolean(11),
                    rs.getString(12)
                );
                table.constraints.add(con);
                if (type == 'p') {
                    table.pkColNums = conkey;
                }
            }
        }
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

    private record IndexDef(String name, String def, List<String> simpleColNames) {}

    private static void loadIndexes(Connection conn, Map<Long, TableDef> tablesByOid) throws SQLException {
        String sql = """
            SELECT idx.indrelid, i.relname, pg_catalog.pg_get_indexdef(i.oid),
                   idx.indnkeyatts, idx.indkey::int2[], idx.indexprs IS NOT NULL
            FROM pg_catalog.pg_index idx
            JOIN pg_catalog.pg_class i ON i.oid = idx.indexrelid
            WHERE NOT EXISTS (
                SELECT 1 FROM pg_catalog.pg_constraint con
                WHERE con.conindid = i.oid AND con.contype IN ('p', 'u', 'x')
            )
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_inherits inh WHERE inh.inhrelid = i.oid
              )
            ORDER BY idx.indrelid, i.relname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                TableDef table = tablesByOid.get(rs.getLong(1));
                if (table == null) {
                    continue;
                }
                int nKeyAtts = rs.getInt(4);
                short[] indKey = toShortArray(rs.getArray(5));
                boolean hasExprs = rs.getBoolean(6);
                List<String> simpleCols = null;
                if (!hasExprs && nKeyAtts > 0 && indKey.length >= nKeyAtts) {
                    List<String> cols = new ArrayList<>(nKeyAtts);
                    boolean allReal = true;
                    for (int k = 0; k < nKeyAtts; k++) {
                        ColumnDef col = table.columnByNum(indKey[k]);
                        if (col == null) {
                            allReal = false;
                            break;
                        }
                        cols.add(col.name);
                    }
                    if (allReal) {
                        simpleCols = cols;
                    }
                }
                table.indexes.add(new IndexDef(rs.getString(2), rs.getString(3), simpleCols));
            }
        }
    }

    private static void loadComments(Connection conn, Map<Long, TableDef> tablesByOid) throws SQLException {
        String sql = """
            SELECT d.objoid, d.objsubid, d.description
            FROM pg_catalog.pg_description d
            WHERE d.classoid = 'pg_catalog.pg_class'::regclass
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                TableDef table = tablesByOid.get(rs.getLong(1));
                if (table == null) {
                    continue;
                }
                int subid = rs.getInt(2);
                String desc = rs.getString(3);
                if (subid == 0) {
                    table.comment = desc;
                } else {
                    ColumnDef col = table.columnByNum((short) subid);
                    if (col != null) {
                        col.comment = desc;
                    }
                }
            }
        }
    }

    private static final class ViewDef {
        final long oid;
        final String schema;
        final String name;
        final boolean isMaterialized;
        final String definition;
        final List<IndexDef> indexes = new ArrayList<>();
        String comment;

        ViewDef(long oid, String schema, String name, boolean isMaterialized, String definition) {
            this.oid = oid;
            this.schema = schema;
            this.name = name;
            this.isMaterialized = isMaterialized;
            this.definition = definition;
        }
    }

    private static Map<Long, ViewDef> loadViews(Connection conn) throws SQLException {
        Map<Long, ViewDef> map = new LinkedHashMap<>();
        String sql = """
            SELECT c.oid, n.nspname, c.relname, c.relkind::text, pg_catalog.pg_get_viewdef(c.oid, true)
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('v', 'm')
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'
              )
            ORDER BY n.nspname, c.relname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long oid = rs.getLong(1);
                String def = rs.getString(5).strip();
                if (def.endsWith(";")) {
                    def = def.substring(0, def.length() - 1).stripTrailing();
                }
                map.put(oid, new ViewDef(
                    oid, rs.getString(2), rs.getString(3), "m".equals(rs.getString(4)), def
                ));
            }
        }
        return map;
    }

    private static void loadMatViewIndexesAndComments(Connection conn, Map<Long, ViewDef> viewsByOid)
            throws SQLException {
        if (viewsByOid.isEmpty()) {
            return;
        }
        String idxSql = """
            SELECT idx.indrelid, i.relname, pg_catalog.pg_get_indexdef(i.oid)
            FROM pg_catalog.pg_index idx
            JOIN pg_catalog.pg_class i ON i.oid = idx.indexrelid
            JOIN pg_catalog.pg_class v ON v.oid = idx.indrelid
            WHERE v.relkind = 'm'
            ORDER BY idx.indrelid, i.relname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(idxSql)) {
            while (rs.next()) {
                ViewDef v = viewsByOid.get(rs.getLong(1));
                if (v != null) {
                    v.indexes.add(new IndexDef(rs.getString(2), rs.getString(3), null));
                }
            }
        }
        String commentSql = """
            SELECT d.objoid, d.description
            FROM pg_catalog.pg_description d
            WHERE d.classoid = 'pg_catalog.pg_class'::regclass AND d.objsubid = 0
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(commentSql)) {
            while (rs.next()) {
                ViewDef v = viewsByOid.get(rs.getLong(1));
                if (v != null) {
                    v.comment = rs.getString(2);
                }
            }
        }
    }

    private record FunctionDef(long oid, String schema, String name, String identityArgs, String def) {}

    private static Map<Long, FunctionDef> loadFunctions(Connection conn) throws SQLException {
        Map<Long, FunctionDef> map = new LinkedHashMap<>();
        String sql = """
            SELECT p.oid, n.nspname, p.proname,
                   pg_catalog.pg_get_function_identity_arguments(p.oid),
                   pg_catalog.pg_get_functiondef(p.oid)
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e'
              )
            ORDER BY n.nspname, p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid)
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long oid = rs.getLong(1);
                map.put(oid, new FunctionDef(oid, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        }
        return map;
    }

    private record TriggerDef(String tableSchema, String tableName, String funcSchema, String name, String def) {}

    private static List<TriggerDef> loadTriggers(Connection conn) throws SQLException {
        List<TriggerDef> list = new ArrayList<>();
        String sql = """
            SELECT tn.nspname, tc.relname, fn.nspname, t.tgname, pg_catalog.pg_get_triggerdef(t.oid, true)
            FROM pg_catalog.pg_trigger t
            JOIN pg_catalog.pg_class tc ON tc.oid = t.tgrelid
            JOIN pg_catalog.pg_namespace tn ON tn.oid = tc.relnamespace
            JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid
            JOIN pg_catalog.pg_namespace fn ON fn.oid = p.pronamespace
            WHERE NOT t.tgisinternal
              AND tn.nspname NOT IN ('pg_catalog', 'information_schema')
              AND NOT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = tc.oid AND d.deptype = 'e'
              )
            ORDER BY tn.nspname, tc.relname, t.tgname
            """;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new TriggerDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        }
        return list;
    }
}
