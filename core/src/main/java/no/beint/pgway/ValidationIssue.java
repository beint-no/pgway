package no.beint.pgway;

import java.util.Objects;

/**
 * A single validation finding from {@link Pgway#validate()}.
 *
 * <p>{@code version} is {@code 0} when the finding is not tied to a parsed version number.
 */
public record ValidationIssue(
        ValidationErrorCode code,
        String script,
        int version,
        String message) {

    public ValidationIssue {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
