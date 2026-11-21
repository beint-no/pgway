package no.beint.pgway;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable context bound to {@link Pgway#CURRENT_MIGRATION} via {@link ScopedValue}
 * while a migration and its {@link MigrationListener} callbacks are executing.
 */
public record MigrationContext(
        String schema,
        int version,
        String script,
        String checksum,
        Instant startedAt) {

    public MigrationContext {
        Objects.requireNonNull(schema, "schema must not be null");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(script, "script must not be null");
        Objects.requireNonNull(checksum, "checksum must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
