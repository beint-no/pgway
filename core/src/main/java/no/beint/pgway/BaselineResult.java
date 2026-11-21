package no.beint.pgway;

import java.util.Objects;

/** Result of {@link Pgway#baseline(MigrationVersion, String)}. */
public record BaselineResult(boolean baselined, MigrationVersion version) {
    public BaselineResult {
        Objects.requireNonNull(version, "version must not be null");
    }
}
