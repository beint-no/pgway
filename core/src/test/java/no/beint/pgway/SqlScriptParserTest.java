package no.beint.pgway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SqlScriptParserTest {

    @Test
    void splitsSimpleAndMultiLineStatementsWithAccurateLineNumbers() {
        String sql = """
            -- leading comment
            
            CREATE TABLE a (id int);
            
            /* block comment */
            INSERT INTO a VALUES (1);
            SELECT * FROM a
            """;
        var parsed = SqlScriptParser.parse("V1__test.sql", sql);
        assertTrue(parsed.directives().transactional());
        assertEquals(3, parsed.statements().size());
        assertEquals(3, parsed.statements().get(0).startLine());
        assertEquals("CREATE TABLE a (id int)", parsed.statements().get(0).sql());
        assertEquals(6, parsed.statements().get(1).startLine());
        assertEquals("INSERT INTO a VALUES (1)", parsed.statements().get(1).sql());
        assertEquals(7, parsed.statements().get(2).startLine());
        assertEquals("SELECT * FROM a", parsed.statements().get(2).sql());
    }

    @Test
    void handlesNestedBlockComments() {
        String sql = """
            /* outer /* inner ; still comment /* level 3 */ back to 2 ; */ still 1 ; */
            SELECT 1;
            /* another /* nested */ comment */
            SELECT 2;
            """;
        var parsed = SqlScriptParser.parse("V1__nested.sql", sql);
        assertEquals(2, parsed.statements().size());
        assertEquals("SELECT 1", parsed.statements().get(0).sql());
        assertEquals(2, parsed.statements().get(0).startLine());
        assertEquals("SELECT 2", parsed.statements().get(1).sql());
        assertEquals(4, parsed.statements().get(1).startLine());
    }

    @Test
    void handlesSingleQuotesEscapeStringsAndIdentifiersEndingInE() {
        String sql = """
            INSERT INTO t VALUES ('a;b''c;--not comment /*not*/');
            INSERT INTO t VALUES (E'escaped \\'; still in string \\\\');
            SELECT CASE'a;b' WHEN 'a;b' THEN 1 ELSE 0 END;
            SELECT col_e='x;y', e'\\\\' FROM t;
            """;
        var parsed = SqlScriptParser.parse("V1__quotes.sql", sql);
        assertEquals(4, parsed.statements().size());
        assertTrue(parsed.statements().get(1).sql().contains("E'escaped \\'; still in string \\\\'"));
        assertEquals("SELECT CASE'a;b' WHEN 'a;b' THEN 1 ELSE 0 END", parsed.statements().get(2).sql());
        assertEquals("SELECT col_e='x;y', e'\\\\' FROM t", parsed.statements().get(3).sql());
    }

    @Test
    void handlesDoubleQuotedIdentifiersAndDollarQuotesAndPositionalParams() {
        String sql = """
            CREATE TABLE "semi;colon""name" (id int);
            CREATE FUNCTION f(a int) RETURNS int LANGUAGE plpgsql AS $fn$
            BEGIN
                EXECUTE $inner$ SELECT $1 + 1; $inner$ USING a;
                RETURN a;
            END;
            $fn$;
            CREATE FUNCTION g(int) RETURNS int LANGUAGE sql AS $$ SELECT $1 + $2; $$;
            """;
        var parsed = SqlScriptParser.parse("V1__dollar.sql", sql);
        assertEquals(3, parsed.statements().size());
        assertEquals("CREATE TABLE \"semi;colon\"\"name\" (id int)", parsed.statements().get(0).sql());
        assertTrue(parsed.statements().get(1).sql().startsWith("CREATE FUNCTION f(a int)"));
        assertTrue(parsed.statements().get(1).sql().endsWith("$fn$"));
        assertEquals(
            "CREATE FUNCTION g(int) RETURNS int LANGUAGE sql AS $$ SELECT $1 + $2; $$",
            parsed.statements().get(2).sql()
        );
    }

    @Test
    void handlesSqlStandardBeginAtomicFunctionsWithCaseExpressions() {
        String sql = """
            CREATE FUNCTION classify(x int) RETURNS text
            LANGUAGE SQL
            IMMUTABLE
            BEGIN ATOMIC
                SELECT CASE
                    WHEN x > 0 THEN 'pos'
                    ELSE 'nonpos'
                END;
            END;
            CREATE FUNCTION multi_stmt(x int) RETURNS int
            LANGUAGE SQL
            BEGIN ATOMIC
                INSERT INTO audit(val) VALUES (x);
                SELECT x + 1;
            END;
            SELECT classify(5);
            """;
        var parsed = SqlScriptParser.parse("V1__atomic.sql", sql);
        assertEquals(3, parsed.statements().size());
        assertTrue(parsed.statements().get(0).sql().contains("BEGIN ATOMIC"));
        assertTrue(parsed.statements().get(0).sql().endsWith("END"));
        assertTrue(parsed.statements().get(1).sql().contains("INSERT INTO audit"));
        assertTrue(parsed.statements().get(1).sql().contains("SELECT x + 1;"));
        assertEquals("SELECT classify(5)", parsed.statements().get(2).sql());
    }

    @Test
    void handlesParenthesizedMultiStatementRule() {
        String sql = """
            CREATE RULE r_audit AS ON INSERT TO items
            DO ALSO (
                INSERT INTO audit(id) VALUES (NEW.id);
                UPDATE stats SET cnt = cnt + 1;
            );
            SELECT 1;
            """;
        var parsed = SqlScriptParser.parse("V1__rule.sql", sql);
        assertEquals(2, parsed.statements().size());
        assertTrue(parsed.statements().get(0).sql().startsWith("CREATE RULE r_audit"));
        assertEquals("SELECT 1", parsed.statements().get(1).sql());
    }

    @Test
    void parsesDirectivesAndRejectsInvalidOrLateDirectives() {
        String sql = """
            -- regular comment before directive
            -- pgway: transaction=false
            -- pgway: lock_timeout=10s
            -- pgway: statement_timeout=5min
            CREATE INDEX CONCURRENTLY idx_a ON a(id);
            CREATE INDEX CONCURRENTLY idx_b ON b(id);
            """;
        var parsed = SqlScriptParser.parse("V2__idx.sql", sql);
        assertFalse(parsed.directives().transactional());
        assertEquals(Duration.ofSeconds(10), parsed.directives().lockTimeout());
        assertEquals(Duration.ofMinutes(5), parsed.directives().statementTimeout());
        assertEquals(2, parsed.statements().size());

        // Zero duration allowed without unit
        var zero = SqlScriptParser.parse("V3__z.sql", "-- pgway: statement_timeout=0\nSELECT 1;");
        assertEquals(Duration.ZERO, zero.directives().statementTimeout());

        // Unknown directive rejected
        assertThrows(
            PgwaySqlParseException.class,
            () -> SqlScriptParser.parse("V1__bad.sql", "-- pgway: transactional=false\nSELECT 1;")
        );
        // Duplicate directive rejected
        assertThrows(
            PgwaySqlParseException.class,
            () -> SqlScriptParser.parse(
                "V1__dup.sql",
                "-- pgway: transaction=false\n-- pgway: transaction=true\nSELECT 1;"
            )
        );
        // Directive after SQL rejected
        assertThrows(
            PgwaySqlParseException.class,
            () -> SqlScriptParser.parse("V1__late.sql", "SELECT 1;\n-- pgway: transaction=false\nSELECT 2;")
        );
        // Invalid boolean rejected
        assertThrows(
            PgwaySqlParseException.class,
            () -> SqlScriptParser.parse("V1__badbool.sql", "-- pgway: transaction=maybe\nSELECT 1;")
        );
        // Missing unit on non-zero duration rejected
        assertThrows(
            PgwaySqlParseException.class,
            () -> SqlScriptParser.parse("V1__badunit.sql", "-- pgway: lock_timeout=10\nSELECT 1;")
        );
    }

    @Test
    void rejectsForbiddenTransactionControlInTransactionalMigrations() {
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__a.sql", "BEGIN; SELECT 1; COMMIT;"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__b.sql", "START TRANSACTION; SELECT 1;"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__c.sql", "SELECT 1; COMMIT;"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__d.sql", "SELECT 1; ROLLBACK;"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__e.sql", "SELECT 1; END WORK;"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__f.sql", "SELECT 1; ABORT;"));

        // Savepoints ARE valid inside a managed transaction
        var sp = SqlScriptParser.parse("V1__sp.sql", "SAVEPOINT s1; SELECT 1; ROLLBACK TO SAVEPOINT s1; RELEASE SAVEPOINT s1;");
        assertEquals(4, sp.statements().size());

        // DO $$ BEGIN ... END $$; is allowed
        var doBlock = SqlScriptParser.parse("V1__do.sql", "DO $$ BEGIN PERFORM 1; END $$;");
        assertEquals(1, doBlock.statements().size());

        // Explicit BEGIN/COMMIT is allowed if transaction=false
        var nonTx = SqlScriptParser.parse("V1__ntx.sql", "-- pgway: transaction=false\nBEGIN; SELECT 1; COMMIT;");
        assertEquals(3, nonTx.statements().size());
    }

    @Test
    void rejectsUnterminatedLexicalConstructs() {
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__a.sql", "SELECT 'unclosed"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__b.sql", "SELECT E'unclosed\\'"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__c.sql", "SELECT \"unclosed"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__d.sql", "/* outer /* inner */"));
        assertThrows(PgwaySqlParseException.class, () -> SqlScriptParser.parse("V1__e.sql", "DO $tag$ BEGIN END;"));
        assertThrows(
            PgwaySqlParseException.class,
            () -> SqlScriptParser.parse("V1__f.sql", "CREATE FUNCTION f() RETURNS int LANGUAGE SQL BEGIN ATOMIC SELECT 1;")
        );
        assertThrows(
            PgwaySqlParseException.class,
            () -> SqlScriptParser.parse("V1__g.sql", "CREATE TABLE broken (id int;")
        );
    }

    @Test
    void handlesEmptyAndCommentOnlyScriptsAndNormalizesBomAndCrlf() {
        String withBomAndCrlf = "\uFEFF-- comment\r\nSELECT 1;\r\n";
        String normalized = SqlScriptParser.normalizeSql(withBomAndCrlf);
        assertEquals("-- comment\nSELECT 1;\n", normalized);

        var empty = SqlScriptParser.parse("V1__empty.sql", "-- only comments\n/* block */\n   ;\n");
        assertTrue(empty.statements().isEmpty());
    }
}
