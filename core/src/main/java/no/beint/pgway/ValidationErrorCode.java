package no.beint.pgway;

/** Machine-readable validation error codes reported by {@link ValidationIssue}. */
public enum ValidationErrorCode {
    INVALID_FILENAME,
    DUPLICATE_VERSION,
    DUPLICATE_REPEATABLE,
    INVALID_UTF8,
    FAILED_MIGRATION_IN_HISTORY,
    MISSING_MIGRATION,
    CHECKSUM_MISMATCH,
    OUT_OF_ORDER_PENDING,
    BASELINE_FAILED,
    INVALID_SQL_SCRIPT
}
