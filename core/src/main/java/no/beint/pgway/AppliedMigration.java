package no.beint.pgway;

import java.time.Instant;
import java.util.Objects;

/** A single row stored in {@code pgway_history}. */
public record AppliedMigration(
        int version,
        String script,
        String checksum,
        String installedBy,
        Instant installedOn,
        int executionMs,
        boolean success) {

    public AppliedMigration {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(script, "script must not be null");
        Objects.requireNonNull(checksum, "checksum must not be null");
        Objects.requireNonNull(installedBy, "installedBy must not be null");
        Objects.requireNonNull(installedOn, "installedOn must not be null");
    }
}
