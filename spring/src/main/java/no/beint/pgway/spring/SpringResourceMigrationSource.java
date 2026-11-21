package no.beint.pgway.spring;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import no.beint.pgway.MigrationSource;
import no.beint.pgway.PgwayConfig;
import no.beint.pgway.RawMigrationResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Loads {@code .sql} migrations from the single convention location {@code classpath*:db/migration}
 * across exploded directories, multi-module JARs, and Spring Boot executable nested JARs.
 */
public final class SpringResourceMigrationSource implements MigrationSource {
    private static final String BASE_PATH = PgwayConfig.DEFAULT_CLASSPATH_LOCATION;
    private static final String PATTERN = "classpath*:" + BASE_PATH + "/**/*.sql";
    private static final String FALLBACK_PATTERN = "classpath*:**/" + BASE_PATH + "/**/*.sql";
    private static final String MARKER = "/" + BASE_PATH + "/";
    private static final String JAR_ROOT_MARKER = "!/" + BASE_PATH + "/";

    private final ResourcePatternResolver resolver;

    public SpringResourceMigrationSource(ResourcePatternResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    @Override
    public List<RawMigrationResource> load() throws IOException {
        Resource[] found = resolver.getResources(PATTERN);
        if (found.length == 0) {
            // Fallback for JAR archives packaged without explicit directory entries
            found = resolver.getResources(FALLBACK_PATTERN);
        }
        Map<String, RawMigrationResource> byName = new LinkedHashMap<>();

        for (Resource resource : found) {
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".sql")) {
                continue;
            }
            String relativeName = extractRelativeName(resource, filename);
            if (relativeName == null) {
                continue;
            }
            byte[] bytes;
            try (InputStream in = resource.getInputStream()) {
                bytes = in.readAllBytes();
            }
            byName.putIfAbsent(
                relativeName,
                new RawMigrationResource(relativeName, resource.getDescription(), bytes)
            );
        }

        return byName.values().stream()
            .sorted(Comparator.comparing(RawMigrationResource::name))
            .toList();
    }

    private static String extractRelativeName(Resource resource, String fallbackFilename) {
        try {
            String url = URLDecoder.decode(resource.getURL().toString(), StandardCharsets.UTF_8);
            int jarIdx = url.indexOf(JAR_ROOT_MARKER);
            if (jarIdx >= 0) {
                String candidate = url.substring(jarIdx + JAR_ROOT_MARKER.length());
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
            int idx = url.lastIndexOf(MARKER);
            if (idx >= 0) {
                String candidate = url.substring(idx + MARKER.length());
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
            return null;
        } catch (IOException | IllegalArgumentException ignored) {
            return fallbackFilename;
        }
    }
}
