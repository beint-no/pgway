package no.beint.pgway;

import java.util.List;
import java.util.Objects;

/** Parsed versioned migration script ready for validation or execution. */
public record MigrationScript(
        int version,
        String description,
        String scriptName,
        String location,
        String sql,
        String checksum,
        MigrationDirectives directives,
        List<SqlStatement> statements) {

    public MigrationScript {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(scriptName, "scriptName must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(checksum, "checksum must not be null");
        Objects.requireNonNull(directives, "directives must not be null");
        statements = List.copyOf(Objects.requireNonNull(statements, "statements must not be null"));
    }
}
