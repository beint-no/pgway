package no.beint.pgway;

import java.util.List;
import java.util.Objects;

/** Parsed migration script ready for validation or execution. */
public record MigrationScript(
        MigrationKind kind,
        MigrationVersion version,
        String description,
        String scriptName,
        String location,
        String sql,
        String checksum,
        MigrationDirectives directives,
        List<SqlStatement> statements) {

    public MigrationScript {
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == MigrationKind.VERSIONED && version == null) {
            throw new IllegalArgumentException("Versioned migration requires a non-null version");
        }
        if (kind == MigrationKind.REPEATABLE && version != null) {
            throw new IllegalArgumentException("Repeatable migration must not have a version");
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
