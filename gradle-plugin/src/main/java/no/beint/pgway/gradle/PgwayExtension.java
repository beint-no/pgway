package no.beint.pgway.gradle;

import org.gradle.api.provider.Property;

/**
 * Convention-first configuration for the {@code no.beint.pgway} Gradle plugin.
 *
 * <p>Migrations are always read from {@code src/main/resources/db/migration}.
 */
public abstract class PgwayExtension {
    /** Target PostgreSQL schema (default: {@code "public"}). */
    public abstract Property<String> getSchema();

    /** Optional path to the PostgreSQL 19+ {@code bin} directory when not on {@code PATH} or standard locations. */
    public abstract Property<String> getPostgresBin();
}
