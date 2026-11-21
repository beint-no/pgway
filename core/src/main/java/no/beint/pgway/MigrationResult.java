package no.beint.pgway;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link Pgway#migrate()}.
 *
 * <p>{@code currentVersion} is {@code 0} when no migrations have been applied.
 * {@code compactedFromVersion} is {@code 0} unless {@code pgway_history} was automatically
 * compacted from {@code V1..V<compactedFromVersion>} into a compacted {@code V1}.
 */
public record MigrationResult(
        List<AppliedMigration> applied,
        int skippedCount,
        int currentVersion,
        Duration totalDuration,
        int compactedFromVersion) {

    public MigrationResult {
        applied = List.copyOf(Objects.requireNonNull(applied, "applied must not be null"));
        Objects.requireNonNull(totalDuration, "totalDuration must not be null");
        if (compactedFromVersion < 0) {
            throw new IllegalArgumentException("compactedFromVersion must not be negative: " + compactedFromVersion);
        }
    }

    public int appliedCount() {
        return applied.size();
    }
}
