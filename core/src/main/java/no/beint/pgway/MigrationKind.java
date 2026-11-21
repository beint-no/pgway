package no.beint.pgway;

/** Classification of a migration entry. */
public enum MigrationKind {
    /** Versioned migration ({@code V<version>__<description>.sql}), applied at most once. */
    VERSIONED,
    /** Repeatable migration ({@code R__<description>.sql}), re-applied whenever its SHA-256 checksum changes. */
    REPEATABLE,
    /** Baseline marker establishing an initial schema version on a pre-existing database. */
    BASELINE
}
