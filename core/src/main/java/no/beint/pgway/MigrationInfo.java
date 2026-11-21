package no.beint.pgway;

import java.time.Instant;
import java.util.Objects;

/** Combined status view of a migration across discovered scripts and database history. */
public record MigrationInfo(
        int version,
        String description,
        String script,
        MigrationState state,
        String expectedChecksum,
        String appliedChecksum,
        Instant installedOn,
        Integer executionMs) {

    public MigrationInfo {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(script, "script must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }
}
