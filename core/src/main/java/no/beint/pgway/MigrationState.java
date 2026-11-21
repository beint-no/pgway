package no.beint.pgway;

/** Lifecycle state of a migration reported by {@link Pgway#info()}. */
public enum MigrationState {
    APPLIED,
    PENDING,
    OUTDATED,
    FAILED,
    MISSING,
    CHECKSUM_MISMATCH,
    OUT_OF_ORDER,
    BASELINE,
    BELOW_BASELINE
}
