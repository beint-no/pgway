package no.beint.pgway.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

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

    /** Fail build verification when a B-tree index is strictly redundant with another index on the same table (default: {@code true}). */
    public abstract Property<Boolean> getLintRedundantIndexes();

    /** Fail build verification when a composite {@code UNIQUE} constraint containing the table's primary key is not referenced by any foreign key (default: {@code true}). */
    public abstract Property<Boolean> getLintUnreferencedCompositeUnique();

    /** Optional tenant column name (e.g. {@code "tenant_id"}); when set, verifies every FK between two tables carrying this column includes it in its referencing columns. */
    public abstract Property<String> getTenantColumn();

    /** Foreign keys exempt from the {@code tenantColumn} composite-FK lint rule, formatted as {@code "table.column"} or {@code "table(col1,col2)"} (default: empty). */
    public abstract SetProperty<String> getAllowedCrossTenantForeignKeys();

    /** Fail build verification when migration scripts contain explicit {@code CONSTRAINT <name>} or {@code CREATE [UNIQUE] INDEX [CONCURRENTLY] <name> ON} names (default: {@code false}). */
    public abstract Property<Boolean> getForbidExplicitNames();
}
