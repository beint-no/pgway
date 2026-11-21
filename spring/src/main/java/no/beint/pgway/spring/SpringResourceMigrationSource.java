package no.beint.pgway.spring;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import no.beint.pgway.FilesystemMigrationSource;
import no.beint.pgway.MigrationSource;
import no.beint.pgway.RawMigrationResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * {@link MigrationSource} backed by Spring's {@link ResourcePatternResolver}.
 *
 * <p>Supports exploded classpath directories, multi-module dependency JARs, Spring Boot executable nested JARs
 * ({@code jar:nested:}), and {@code filesystem:} paths.
 */
public final class SpringResourceMigrationSource implements MigrationSource {
    private final String location;
    private final ResourcePatternResolver resolver;

    public SpringResourceMigrationSource(String location, ResourcePatternResolver resolver) {
        this.location = Objects.requireNonNull(location, "location must not be null").trim();
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    @Override
    public List<RawMigrationResource> load() throws IOException {
        if (location.startsWith("filesystem:")) {
            return new FilesystemMigrationSource(Path.of(location.substring("filesystem:".length()))).load();
        }
        String basePath;
        String pattern;
        if (location.startsWith("classpath*:")) {
            basePath = trimSlashes(location.substring("classpath*:".length()));
            pattern = "classpath*:" + (basePath.isEmpty() ? "" : basePath + "/") + "**/*.sql";
        } else if (location.startsWith("classpath:")) {
            basePath = trimSlashes(location.substring("classpath:".length()));
            pattern = "classpath*:" + (basePath.isEmpty() ? "" : basePath + "/") + "**/*.sql";
        } else if (location.startsWith("file:")) {
            String filePart = location.substring("file:".length());
            while (filePart.startsWith("//")) {
                filePart = filePart.substring(1);
            }
            return new FilesystemMigrationSource(Path.of(filePart)).load();
        } else {
            basePath = trimSlashes(location);
            pattern = "classpath*:" + (basePath.isEmpty() ? "" : basePath + "/") + "**/*.sql";
        }

        Resource[] found = resolver.getResources(pattern);
        if (found.length == 0 && !basePath.isEmpty()) {
            // Fallback for JAR archives packaged without explicit directory entries
            found = resolver.getResources("classpath*:**/" + basePath + "/**/*.sql");
        }
        Map<String, RawMigrationResource> byName = new LinkedHashMap<>();
        String marker = basePath.isEmpty() ? null : "/" + basePath + "/";
        String jarRootMarker = basePath.isEmpty() ? null : "!/" + basePath + "/";

        for (Resource resource : found) {
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".sql")) {
                continue;
            }
            String relativeName = extractRelativeName(resource, marker, jarRootMarker, filename);
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

    private static String extractRelativeName(
            Resource resource,
            String marker,
            String jarRootMarker,
            String fallbackFilename) {
        try {
            String url = URLDecoder.decode(resource.getURL().toString(), StandardCharsets.UTF_8);
            if (jarRootMarker != null) {
                int jarIdx = url.indexOf(jarRootMarker);
                if (jarIdx >= 0) {
                    String candidate = url.substring(jarIdx + jarRootMarker.length());
                    if (!candidate.isEmpty()) {
                        return candidate;
                    }
                }
            }
            if (marker != null) {
                int idx = url.lastIndexOf(marker);
                if (idx >= 0) {
                    String candidate = url.substring(idx + marker.length());
                    if (!candidate.isEmpty()) {
                        return candidate;
                    }
                }
                return null;
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Fall back to filename
        }
        return fallbackFilename;
    }

    private static String trimSlashes(String value) {
        String s = value.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
