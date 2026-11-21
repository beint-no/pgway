package no.beint.pgway;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** In-memory {@link MigrationSource} for programmatic migrations and testing. */
public final class InMemoryMigrationSource implements MigrationSource {
    private final Map<String, byte[]> entries;

    private InMemoryMigrationSource(Map<String, byte[]> entries) {
        this.entries = entries;
    }

    public static InMemoryMigrationSource ofStrings(Map<String, String> scripts) {
        Objects.requireNonNull(scripts, "scripts must not be null");
        Map<String, byte[]> map = new LinkedHashMap<>();
        scripts.forEach((name, sql) -> {
            Objects.requireNonNull(name, "script name must not be null");
            Objects.requireNonNull(sql, "sql must not be null");
            map.put(name, sql.getBytes(StandardCharsets.UTF_8));
        });
        return new InMemoryMigrationSource(map);
    }

    public static InMemoryMigrationSource ofBytes(Map<String, byte[]> scripts) {
        Objects.requireNonNull(scripts, "scripts must not be null");
        Map<String, byte[]> map = new LinkedHashMap<>();
        scripts.forEach((name, bytes) -> {
            Objects.requireNonNull(name, "script name must not be null");
            Objects.requireNonNull(bytes, "bytes must not be null");
            map.put(name, bytes.clone());
        });
        return new InMemoryMigrationSource(map);
    }

    @Override
    public List<RawMigrationResource> load() {
        return entries.entrySet().stream()
            .filter(e -> e.getKey().endsWith(".sql"))
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new RawMigrationResource(e.getKey(), "memory:" + e.getKey(), e.getValue()))
            .toList();
    }
}
