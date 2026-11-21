package no.beint.pgway;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when migration discovery, naming, checksums, ordering, or schema history validation fails. */
public final class PgwayValidationException extends PgwayException {
    private static final long serialVersionUID = 1L;
    private final transient List<ValidationIssue> issues;

    public PgwayValidationException(List<ValidationIssue> issues) {
        super(formatMessage(issues));
        this.issues = List.copyOf(issues);
    }

    public PgwayValidationException(ValidationIssue issue) {
        this(List.of(issue));
    }

    public List<ValidationIssue> issues() {
        return issues;
    }

    private static String formatMessage(List<ValidationIssue> issues) {
        if (issues.isEmpty()) {
            return "Pgway validation failed";
        }
        return "Pgway validation failed with " + issues.size() + " issue(s):\n"
            + issues.stream()
                .map(i -> "  - [" + i.code() + "] " + i.message())
                .collect(Collectors.joining("\n"));
    }
}
