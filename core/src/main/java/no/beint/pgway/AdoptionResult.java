package no.beint.pgway;

/** Result of {@link Pgway#adoptFlywayHistory()}. */
public record AdoptionResult(boolean adopted, int migratedRowsCount, MigrationVersion highestVersion) {
}
