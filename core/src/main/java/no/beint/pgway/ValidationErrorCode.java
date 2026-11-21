package no.beint.pgway;

/** Machine-readable validation error codes reported by {@link ValidationIssue}. */
public enum ValidationErrorCode {
    INVALID_FILENAME,
    DUPLICATE_VERSION,
    INVALID_UTF8,
    FAILED_MIGRATION_IN_HISTORY,
    MISSING_MIGRATION,
    CHECKSUM_MISMATCH,
    OUT_OF_ORDER_PENDING,
    INVALID_SQL_SCRIPT
}
