package no.beint.pgway;

import java.util.List;
import java.util.Objects;

/** Result of {@link Pgway#validate()}. */
public record ValidationResult(boolean valid, List<ValidationIssue> issues) {
    public ValidationResult {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues must not be null"));
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult failed(List<ValidationIssue> issues) {
        return new ValidationResult(false, issues);
    }
}
