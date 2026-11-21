package no.beint.pgway;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link Pgway#migrate()}.
 *
 * <p>{@code currentVersion} is {@code 0} when no migrations have been applied.
 */
public record MigrationResult(
        List<AppliedMigration> applied,
        int skippedCount,
        int currentVersion,
        Duration totalDuration) {

    public MigrationResult {
        applied = List.copyOf(Objects.requireNonNull(applied, "applied must not be null"));
        Objects.requireNonNull(totalDuration, "totalDuration must not be null");
    }

    public int appliedCount() {
        return applied.size();
    }
}
