package no.beint.pgway;

import java.util.Objects;

/**
 * A single top-level PostgreSQL statement parsed from a migration script.
 *
 * @param index 1-based statement index within the script
 * @param startLine 1-based line number of the first SQL token in the script
 * @param sql statement text without trailing semicolon
 */
public record SqlStatement(int index, int startLine, String sql) {
    public SqlStatement {
        if (index < 1) {
            throw new IllegalArgumentException("statement index must be >= 1");
        }
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        Objects.requireNonNull(sql, "sql must not be null");
    }
}
