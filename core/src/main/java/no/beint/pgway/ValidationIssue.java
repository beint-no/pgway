package no.beint.pgway;

import java.util.Objects;

/** A single validation finding from {@link Pgway#validate()}. */
public record ValidationIssue(
        ValidationErrorCode code,
        String script,
        MigrationVersion version,
        String message) {

    public ValidationIssue {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
