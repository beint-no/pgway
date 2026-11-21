package no.beint.pgway;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads {@code .sql} migration resources recursively from classpath directories and standard JAR archives.
 *
 * <p>For Spring Boot executable nested JARs, use {@code pgway-spring}'s resource loader integration.
 */
public final class ClasspathMigrationSource implements MigrationSource {
    private final String resourcePath;
    private final ClassLoader classLoader;

    public ClasspathMigrationSource(String resourcePath) {
        this(resourcePath, defaultClassLoader());
    }

    public ClasspathMigrationSource(String resourcePath, ClassLoader classLoader) {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        this.resourcePath = normalizePrefix(resourcePath);
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
    }

    public String resourcePath() {
        return resourcePath;
    }

    @Override
    public List<RawMigrationResource> load() throws IOException {
        Map<String, RawMigrationResource> byName = new LinkedHashMap<>();
        Set<String> scannedJarNames = new HashSet<>();
        String prefix = resourcePath.isEmpty() ? "" : resourcePath + "/";

        Enumeration<URL> urls = classLoader.getResources(resourcePath);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                Path dir;
                try {
                    dir = Path.of(url.toURI());
                } catch (URISyntaxException e) {
                    throw new IOException("Invalid classpath file URL: " + url, e);
                }
                if (Files.isDirectory(dir)) {
                    for (RawMigrationResource res : new FilesystemMigrationSource(dir).load()) {
                        byName.putIfAbsent(
                            res.name(),
                            new RawMigrationResource(res.name(), "classpath:" + prefix + res.name(), res.bytes())
                        );
                    }
                }
            } else if ("jar".equals(protocol)) {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                // Do not close the shared JarFile returned by JarURLConnection when caches are enabled
                JarFile jarFile = connection.getJarFile();
                if (scannedJarNames.add(jarFile.getName())) {
                    scanJar(jarFile, prefix, byName);
                }
            }
        }

        // Fallback for JAR archives packaged without explicit directory entries
        if (classLoader instanceof URLClassLoader ucl) {
            for (URL url : ucl.getURLs()) {
                if (!"file".equals(url.getProtocol()) || !url.getPath().endsWith(".jar")) {
                    continue;
                }
                Path jarPath;
                try {
                    jarPath = Path.of(url.toURI());
                } catch (URISyntaxException e) {
                    continue;
                }
                if (Files.isRegularFile(jarPath) && scannedJarNames.add(jarPath.toString())) {
                    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                        scanJar(jarFile, prefix, byName);
                    }
                }
            }
        }

        return byName.values().stream()
            .sorted(Comparator.comparing(RawMigrationResource::name))
            .toList();
    }

    private static void scanJar(JarFile jarFile, String prefix, Map<String, RawMigrationResource> byName)
            throws IOException {
        List<JarEntry> entries = new ArrayList<>();
        Enumeration<JarEntry> jarEntries = jarFile.entries();
        while (jarEntries.hasMoreElements()) {
            JarEntry entry = jarEntries.nextElement();
            if (!entry.isDirectory()
                    && entry.getName().startsWith(prefix)
                    && entry.getName().endsWith(".sql")) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(JarEntry::getName));
        for (JarEntry entry : entries) {
            String relativeName = entry.getName().substring(prefix.length());
            byte[] bytes;
            try (var in = jarFile.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            byName.putIfAbsent(
                relativeName,
                new RawMigrationResource(relativeName, "classpath:" + entry.getName(), bytes)
            );
        }
    }

    private static String normalizePrefix(String path) {
        String p = path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static ClassLoader defaultClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ClasspathMigrationSource.class.getClassLoader();
    }
}
