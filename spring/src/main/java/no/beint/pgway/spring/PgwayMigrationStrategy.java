package no.beint.pgway.spring;

import no.beint.pgway.Pgway;

/** Strategy hook to customize how {@link Pgway} executes during Spring Boot startup. */
@FunctionalInterface
public interface PgwayMigrationStrategy {
    void migrate(Pgway pgway);
}
