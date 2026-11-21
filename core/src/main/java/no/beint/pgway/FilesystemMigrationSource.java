package no.beint.pgway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Loads {@code .sql} migration files recursively from a filesystem directory. */
public final class FilesystemMigrationSource implements MigrationSource {
    private final Path directory;

    public FilesystemMigrationSource(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
    }

    public Path directory() {
        return directory;
    }

    @Override
    public List<RawMigrationResource> load() throws IOException {
        if (!Files.exists(directory)) {
            throw new IOException("Migration directory does not exist: " + directory.toAbsolutePath());
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("Migration path is not a directory: " + directory.toAbsolutePath());
        }
        List<Path> sqlFiles;
        try (var stream = Files.walk(directory)) {
            sqlFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".sql"))
                .sorted(Comparator.comparing(p -> toSlashPath(directory.relativize(p))))
                .toList();
        }
        List<RawMigrationResource> resources = new ArrayList<>(sqlFiles.size());
        for (Path file : sqlFiles) {
            String relativeName = toSlashPath(directory.relativize(file));
            resources.add(new RawMigrationResource(
                relativeName,
                "filesystem:" + file.toAbsolutePath(),
                Files.readAllBytes(file)
            ));
        }
        return List.copyOf(resources);
    }

    private static String toSlashPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
