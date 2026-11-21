package no.beint.pgway;

import java.io.IOException;
import java.util.List;

/**
 * Supplies raw {@code .sql} migration resources from a directory, classpath, JAR, Spring resource resolver, or memory.
 */
@FunctionalInterface
public interface MigrationSource {
    /**
     * Loads all {@code .sql} resources from this source. Non-{@code .sql} files must be omitted.
     */
    List<RawMigrationResource> load() throws IOException;
}
