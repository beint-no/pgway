package no.beint.pgway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 19 SQL script lexer, directive parser, and statement splitter.
 *
 * <p>Implements PostgreSQL's lexical rules:
 * <ul>
 *   <li>Single-line comments ({@code -- ...}) and arbitrarily nested block comments ({@code /* ... /* ... *&#47; ... *&#47;})</li>
 *   <li>Standard single-quoted strings ({@code '...'}), C-style escape strings ({@code E'...'}), Unicode/bit/hex literals</li>
 *   <li>Double-quoted identifiers ({@code "..."})</li>
 *   <li>Dollar-quoted strings ({@code $$...$$} and {@code $tag$...$tag$}) without confusing positional parameters ({@code $1})</li>
 *   <li>SQL-standard {@code BEGIN ATOMIC ... END} function bodies (including inner {@code CASE ... END} expressions)</li>
 *   <li>Parenthesized multi-statement blocks (e.g. {@code CREATE RULE ... DO (...; ...)})</li>
 * </ul>
 */
public final class SqlScriptParser {
    private static final Pattern DURATION_PATTERN = Pattern.compile("^([0-9]+)(ms|s|m|min|h)?$");

    private SqlScriptParser() {}

    public record ParsedScript(MigrationDirectives directives, List<SqlStatement> statements) {}

    /**
     * Normalizes line endings ({@code \r\n} and {@code \r} to {@code \n}) and strips a leading UTF-8 BOM if present.
     */
    public static String normalizeSql(String raw) {
        String s = raw;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        if (s.indexOf('\r') >= 0) {
            s = s.replace("\r\n", "\n").replace('\r', '\n');
        }
        return s;
    }

    public static ParsedScript parse(String scriptName, String normalizedSql) {
        int len = normalizedSql.length();
        int i = 0;
        int line = 1;

        boolean seenAnySql = false;
        boolean transactional = true;
        Duration lockTimeout = null;
        Duration statementTimeout = null;
        Set<String> seenDirectives = new HashSet<>();

        List<SqlStatement> statements = new ArrayList<>();
        int stmtFirstTokenStart = -1;
        int stmtFirstTokenLine = -1;
        String firstKeyword = null;
        String secondKeyword = null;
        boolean prevWasBegin = false;

        int parenDepth = 0;
        int atomicDepth = 0;
        int caseDepth = 0;

        while (i < len) {
            char c = normalizedSql.charAt(i);

            // Whitespace
            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    line++;
                }
                i++;
                continue;
            }

            // Single-line comment
            if (c == '-' && i + 1 < len && normalizedSql.charAt(i + 1) == '-') {
                int commentLine = line;
                int contentStart = i + 2;
                i += 2;
                while (i < len && normalizedSql.charAt(i) != '\n') {
                    i++;
                }
                String commentText = normalizedSql.substring(contentStart, i).trim();
                if (isPgwayDirective(commentText)) {
                    if (seenAnySql) {
                        throw new PgwaySqlParseException(
                            scriptName,
                            commentLine,
                            "Pgway directive must appear before the first SQL statement: -- " + commentText
                        );
                    }
                    String body = commentText.substring("pgway:".length()).trim();
                    int eq = body.indexOf('=');
                    if (eq <= 0 || eq == body.length() - 1) {
                        throw new PgwaySqlParseException(
                            scriptName,
                            commentLine,
                            "Invalid pgway directive syntax (expected 'key=value'): " + body
                        );
                    }
                    String key = body.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String val = body.substring(eq + 1).trim();
                    if (!seenDirectives.add(key)) {
                        throw new PgwaySqlParseException(
                            scriptName,
                            commentLine,
                            "Duplicate pgway directive: " + key
                        );
                    }
                    switch (key) {
                        case "transaction" -> {
                            if ("true".equalsIgnoreCase(val)) {
                                transactional = true;
                            } else if ("false".equalsIgnoreCase(val)) {
                                transactional = false;
                            } else {
                                throw new PgwaySqlParseException(
                                    scriptName,
                                    commentLine,
                                    "Invalid value for pgway directive 'transaction' (expected true or false): " + val
                                );
                            }
                        }
                        case "lock_timeout" -> lockTimeout = parseDuration(scriptName, commentLine, key, val);
                        case "statement_timeout" -> statementTimeout = parseDuration(scriptName, commentLine, key, val);
                        default -> throw new PgwaySqlParseException(
                            scriptName,
                            commentLine,
                            "Unknown pgway directive: '" + key + "' (supported: transaction, lock_timeout, statement_timeout)"
                        );
                    }
                }
                continue;
            }

            // Nested block comment /* ... /* ... */ ... */
            if (c == '/' && i + 1 < len && normalizedSql.charAt(i + 1) == '*') {
                int commentStartLine = line;
                int depth = 1;
                i += 2;
                while (i < len && depth > 0) {
                    char ch = normalizedSql.charAt(i);
                    if (ch == '/' && i + 1 < len && normalizedSql.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (ch == '*' && i + 1 < len && normalizedSql.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                if (depth > 0) {
                    throw new PgwaySqlParseException(scriptName, commentStartLine, "Unterminated block comment");
                }
                continue;
            }

            // From here on, this is an actual SQL token!
            if (stmtFirstTokenStart == -1) {
                stmtFirstTokenStart = i;
                stmtFirstTokenLine = line;
            }
            seenAnySql = true;

            // Escape string E'...' / e'...'
            if ((c == 'E' || c == 'e')
                    && i + 1 < len
                    && normalizedSql.charAt(i + 1) == '\''
                    && (i == 0 || !isIdentCont(normalizedSql.charAt(i - 1)))) {
                prevWasBegin = false;
                int strStartLine = line;
                i += 2; // skip E'
                boolean closed = false;
                while (i < len) {
                    char ch = normalizedSql.charAt(i);
                    if (ch == '\\') {
                        if (i + 1 < len && normalizedSql.charAt(i + 1) == '\n') {
                            line++;
                        }
                        i += 2;
                    } else if (ch == '\'') {
                        if (i + 1 < len && normalizedSql.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                if (!closed) {
                    throw new PgwaySqlParseException(scriptName, strStartLine, "Unterminated escape string literal");
                }
                continue;
            }

            // Standard single-quoted string literal '...'
            if (c == '\'') {
                prevWasBegin = false;
                int strStartLine = line;
                i++;
                boolean closed = false;
                while (i < len) {
                    char ch = normalizedSql.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < len && normalizedSql.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                if (!closed) {
                    throw new PgwaySqlParseException(scriptName, strStartLine, "Unterminated string literal");
                }
                continue;
            }

            // Double-quoted identifier "..."
            if (c == '"') {
                prevWasBegin = false;
                int idStartLine = line;
                i++;
                boolean closed = false;
                while (i < len) {
                    char ch = normalizedSql.charAt(i);
                    if (ch == '"') {
                        if (i + 1 < len && normalizedSql.charAt(i + 1) == '"') {
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        if (ch == '\n') {
                            line++;
                        }
                        i++;
                    }
                }
                if (!closed) {
                    throw new PgwaySqlParseException(scriptName, idStartLine, "Unterminated quoted identifier");
                }
                continue;
            }

            // Dollar-quoted string $$...$$ or $tag$...$tag$
            if (c == '$') {
                String tag = matchDollarQuoteTag(normalizedSql, i);
                if (tag != null) {
                    prevWasBegin = false;
                    int dqStartLine = line;
                    i += tag.length();
                    int closeIdx = normalizedSql.indexOf(tag, i);
                    if (closeIdx < 0) {
                        throw new PgwaySqlParseException(
                            scriptName,
                            dqStartLine,
                            "Unterminated dollar-quoted string starting with " + tag
                        );
                    }
                    for (int k = i; k < closeIdx; k++) {
                        if (normalizedSql.charAt(k) == '\n') {
                            line++;
                        }
                    }
                    i = closeIdx + tag.length();
                    continue;
                }
                prevWasBegin = false;
                i++;
                continue;
            }

            // Parentheses
            if (c == '(') {
                prevWasBegin = false;
                parenDepth++;
                i++;
                continue;
            }
            if (c == ')') {
                prevWasBegin = false;
                if (parenDepth > 0) {
                    parenDepth--;
                }
                i++;
                continue;
            }

            // Statement separator
            if (c == ';') {
                prevWasBegin = false;
                if (parenDepth == 0 && atomicDepth == 0) {
                    String stmtText = normalizedSql.substring(stmtFirstTokenStart, i).trim();
                    if (!stmtText.isEmpty()) {
                        validateStatement(
                            scriptName,
                            stmtFirstTokenLine,
                            transactional,
                            firstKeyword,
                            secondKeyword
                        );
                        statements.add(new SqlStatement(statements.size() + 1, stmtFirstTokenLine, stmtText));
                    }
                    i++;
                    stmtFirstTokenStart = -1;
                    stmtFirstTokenLine = -1;
                    firstKeyword = null;
                    secondKeyword = null;
                    continue;
                }
                i++;
                continue;
            }

            // Unquoted keyword / identifier
            if (isIdentStart(c)) {
                int wordStart = i;
                i++;
                while (i < len && isIdentCont(normalizedSql.charAt(i))) {
                    i++;
                }
                String upper = normalizedSql.substring(wordStart, i).toUpperCase(Locale.ROOT);
                if (firstKeyword == null) {
                    firstKeyword = upper;
                } else if (secondKeyword == null) {
                    secondKeyword = upper;
                }

                if (parenDepth == 0) {
                    if (prevWasBegin && "ATOMIC".equals(upper)) {
                        atomicDepth++;
                        prevWasBegin = false;
                    } else if ("BEGIN".equals(upper)) {
                        prevWasBegin = true;
                    } else {
                        prevWasBegin = false;
                        if (atomicDepth > 0) {
                            if ("CASE".equals(upper)) {
                                caseDepth++;
                            } else if ("END".equals(upper)) {
                                if (caseDepth > 0) {
                                    caseDepth--;
                                } else {
                                    atomicDepth--;
                                }
                            }
                        }
                    }
                } else {
                    prevWasBegin = false;
                }
                continue;
            }

            // Any other punctuation/operator/digit
            prevWasBegin = false;
            i++;
        }

        if (atomicDepth > 0) {
            throw new PgwaySqlParseException(
                scriptName,
                stmtFirstTokenLine > 0 ? stmtFirstTokenLine : line,
                "Unterminated BEGIN ATOMIC block"
            );
        }
        if (parenDepth > 0) {
            throw new PgwaySqlParseException(
                scriptName,
                stmtFirstTokenLine > 0 ? stmtFirstTokenLine : line,
                "Unterminated parenthesized expression ('(' without matching ')')"
            );
        }

        // Trailing statement without final semicolon
        if (stmtFirstTokenStart != -1 && stmtFirstTokenStart < len) {
            String stmtText = normalizedSql.substring(stmtFirstTokenStart, len).trim();
            if (!stmtText.isEmpty()) {
                validateStatement(scriptName, stmtFirstTokenLine, transactional, firstKeyword, secondKeyword);
                statements.add(new SqlStatement(statements.size() + 1, stmtFirstTokenLine, stmtText));
            }
        }

        return new ParsedScript(
            new MigrationDirectives(transactional, lockTimeout, statementTimeout),
            List.copyOf(statements)
        );
    }

    private static void validateStatement(
            String scriptName,
            int line,
            boolean transactional,
            String firstKeyword,
            String secondKeyword) {
        if (!transactional || firstKeyword == null) {
            return;
        }
        boolean forbidden = switch (firstKeyword) {
            case "BEGIN" -> !"ATOMIC".equals(secondKeyword);
            case "START" -> "TRANSACTION".equals(secondKeyword);
            case "COMMIT", "END", "ABORT" -> true;
            case "ROLLBACK" -> !"TO".equals(secondKeyword);
            case "PREPARE" -> "TRANSACTION".equals(secondKeyword);
            default -> false;
        };
        if (forbidden) {
            throw new PgwaySqlParseException(
                scriptName,
                line,
                "Explicit transaction control ('"
                    + firstKeyword
                    + (secondKeyword != null ? " " + secondKeyword : "")
                    + "') is not allowed in a transactional migration. "
                    + "Pgway already wraps the migration in a single transaction; "
                    + "if you need non-transactional execution, add '-- pgway: transaction=false' at the top of the file."
            );
        }
    }

    private static boolean isPgwayDirective(String trimmedComment) {
        return trimmedComment.length() >= 6
            && trimmedComment.regionMatches(true, 0, "pgway:", 0, 6);
    }

    static Duration parseDuration(String scriptName, int line, String key, String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        Matcher m = DURATION_PATTERN.matcher(s);
        if (!m.matches()) {
            throw new PgwaySqlParseException(
                scriptName,
                line,
                "Invalid duration for pgway directive '" + key + "': '" + raw
                    + "' (examples: 0, 500ms, 10s, 5min, 1h)"
            );
        }
        long amount;
        try {
            amount = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new PgwaySqlParseException(scriptName, line, "Duration overflow for '" + key + "': " + raw);
        }
        String unit = m.group(2);
        if (unit == null) {
            if (amount == 0L) {
                return Duration.ZERO;
            }
            throw new PgwaySqlParseException(
                scriptName,
                line,
                "Duration for '" + key + "' requires a unit (ms, s, m, min, h): " + raw
            );
        }
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m", "min" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> throw new PgwaySqlParseException(scriptName, line, "Unknown duration unit: " + unit);
        };
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
}
