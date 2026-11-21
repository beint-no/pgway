package no.beint.pgway;

import java.util.Objects;

/**
 * Unparsed migration resource bytes loaded from a {@link MigrationSource}.
 *
 * @param name relative path or filename (using {@code /} separators, e.g. {@code V1__init.sql} or {@code sub/V2__x.sql})
 * @param location human-readable source location for diagnostics
 * @param bytes raw UTF-8 file bytes
 */
public record RawMigrationResource(String name, String location, byte[] bytes) {
    public RawMigrationResource {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(bytes, "bytes must not be null");
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
