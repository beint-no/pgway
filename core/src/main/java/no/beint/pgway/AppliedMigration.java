package no.beint.pgway;

import java.time.Instant;
import java.util.Objects;

/** A single row stored in {@code pgway_schema_history}. */
public record AppliedMigration(
        int installedRank,
        MigrationVersion version,
        MigrationKind kind,
        String description,
        String script,
        String checksum,
        String installedBy,
        Instant installedOn,
        int executionMs,
        boolean success) {

    public AppliedMigration {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(script, "script must not be null");
        Objects.requireNonNull(checksum, "checksum must not be null");
        Objects.requireNonNull(installedBy, "installedBy must not be null");
        Objects.requireNonNull(installedOn, "installedOn must not be null");
    }
}
