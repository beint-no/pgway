package no.beint.pgway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationLoaderTest {

    @Test
    void enforcesPostgres19ServerVersionExclusively() {
        // PG 19.0 .. 19.99 accepted
        SchemaHistoryRepository.validateServerVersion(190000, "PostgreSQL 19.0");
        SchemaHistoryRepository.validateServerVersion(190005, "PostgreSQL 19.5");
        SchemaHistoryRepository.validateServerVersion(199999, "PostgreSQL 19.9999");

        // PG 18 and PG 20 rejected
        assertThrows(
            PgwayUnsupportedDatabaseException.class,
            () -> SchemaHistoryRepository.validateServerVersion(180005, "PostgreSQL 18.5")
        );
        assertThrows(
            PgwayUnsupportedDatabaseException.class,
            () -> SchemaHistoryRepository.validateServerVersion(170002, "PostgreSQL 17.2")
        );
        assertThrows(
            PgwayUnsupportedDatabaseException.class,
            () -> SchemaHistoryRepository.validateServerVersion(200000, "PostgreSQL 20.0")
        );
    }

    @Test
    void validatesNamingAndIgnoresNonSqlFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), "# docs");
        Files.writeString(tempDir.resolve("V2__second.sql"), "SELECT 2;");
        Files.writeString(tempDir.resolve("V10__tenth.sql"), "SELECT 10;");
        Files.writeString(tempDir.resolve("V1__first.sql"), "SELECT 1;");
        Files.writeString(tempDir.resolve("R__refresh_views.sql"), "SELECT 99;");

        var loaded = MigrationLoader.load(List.of(new FilesystemMigrationSource(tempDir)));
        assertTrue(loaded.issues().isEmpty());
        assertEquals(3, loaded.versioned().size());
        assertEquals("1", loaded.versioned().get(0).version().value());
        assertEquals("2", loaded.versioned().get(1).version().value());
        assertEquals("10", loaded.versioned().get(2).version().value());
        assertEquals("first", loaded.versioned().get(0).description());
        assertEquals(1, loaded.repeatable().size());
        assertEquals("refresh views", loaded.repeatable().get(0).description());
    }

    @Test
    void rejectsInvalidSqlFilenamesAndDuplicateVersions() {
        var loaded = MigrationLoader.load(List.of(InMemoryMigrationSource.ofStrings(Map.of(
            "V1__a.sql", "SELECT 1;",
            "V01__b.sql", "SELECT 1;",
            "V1.0__c.sql", "SELECT 1;",
            "bad_migration.sql", "SELECT 1;",
            "V2_single_underscore.sql", "SELECT 2;"
        ))));
        assertFalse(loaded.issues().isEmpty());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.DUPLICATE_VERSION));
        assertTrue(loaded.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.INVALID_FILENAME));
    }

    @Test
    void normalizesLineEndingsAndBomForChecksumsAndRejectsInvalidUtf8() {
        var lf = MigrationLoader.load(List.of(InMemoryMigrationSource.ofStrings(Map.of(
            "V1__init.sql", "CREATE TABLE t (id int);\n"
        ))));
        var crlfWithBom = MigrationLoader.load(List.of(InMemoryMigrationSource.ofStrings(Map.of(
            "V1__init.sql", "\uFEFFCREATE TABLE t (id int);\r\n"
        ))));
        assertEquals(lf.versioned().get(0).checksum(), crlfWithBom.versioned().get(0).checksum());
        assertEquals(64, lf.versioned().get(0).checksum().length());

        // Malformed UTF-8 sequence must fail fast
        var invalidUtf8 = MigrationLoader.load(List.of(InMemoryMigrationSource.ofBytes(Map.of(
            "V1__bad_utf8.sql", new byte[] {(byte) 0xFF, (byte) 0xFE, 0x41}
        ))));
        assertEquals(1, invalidUtf8.issues().size());
        assertEquals(ValidationErrorCode.INVALID_UTF8, invalidUtf8.issues().get(0).code());
    }

    @Test
    void scansClasspathJarArchivesRecursively(@TempDir Path tempDir) throws Exception {
        Path jarPath = tempDir.resolve("migrations.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry("db/migration/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("db/migration/V1__from_jar.sql"));
            jos.write("CREATE TABLE jar_t(id int);".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("db/migration/sub/V2__nested.sql"));
            jos.write("ALTER TABLE jar_t ADD COLUMN name text;".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("db/migration/README.md"));
            jos.write("# ignore me".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, null)) {
            var source = new ClasspathMigrationSource("db/migration", loader);
            var loaded = MigrationLoader.load(List.of(source));
            assertTrue(loaded.issues().isEmpty());
            assertEquals(2, loaded.versioned().size());
            assertEquals("V1__from_jar.sql", loaded.versioned().get(0).scriptName());
            assertEquals("sub/V2__nested.sql", loaded.versioned().get(1).scriptName());
        }

        // Also verify JAR archives without explicit directory entries
        Path strippedJar = tempDir.resolve("stripped.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(strippedJar))) {
            jos.putNextEntry(new JarEntry("db/migration/V1__no_dir_entry.sql"));
            jos.write("SELECT 1;".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {strippedJar.toUri().toURL()}, null)) {
            var source = new ClasspathMigrationSource("db/migration", loader);
            var loaded = MigrationLoader.load(List.of(source));
            assertTrue(loaded.issues().isEmpty());
            assertEquals(1, loaded.versioned().size());
            assertEquals("V1__no_dir_entry.sql", loaded.versioned().get(0).scriptName());
        }
    }
}
