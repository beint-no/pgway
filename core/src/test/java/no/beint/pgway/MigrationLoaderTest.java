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
    void enforcesPostgres19OrHigher() {
        SchemaHistoryRepository.validateServerVersion(190000, "PostgreSQL 19.0");
        SchemaHistoryRepository.validateServerVersion(190005, "PostgreSQL 19.5");
        SchemaHistoryRepository.validateServerVersion(200000, "PostgreSQL 20.0");
        SchemaHistoryRepository.validateServerVersion(250003, "PostgreSQL 25.3");

        assertThrows(
            PgwayUnsupportedDatabaseException.class,
            () -> SchemaHistoryRepository.validateServerVersion(189999, "PostgreSQL 18.99")
        );
        assertThrows(
            PgwayUnsupportedDatabaseException.class,
            () -> SchemaHistoryRepository.validateServerVersion(180005, "PostgreSQL 18.5")
        );
        assertThrows(
            PgwayUnsupportedDatabaseException.class,
            () -> SchemaHistoryRepository.validateServerVersion(170002, "PostgreSQL 17.2")
        );
    }

    @Test
    void validatesNamingAndIgnoresNonSqlFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), "# docs");
        Files.writeString(tempDir.resolve("V2__second.sql"), "SELECT 2;");
        Files.writeString(tempDir.resolve("V10__tenth.sql"), "SELECT 10;");
        Files.writeString(tempDir.resolve("V1__first.sql"), "SELECT 1;");

        var loaded = MigrationLoader.load(List.of(new FilesystemMigrationSource(tempDir)));
        assertTrue(loaded.issues().isEmpty());
        assertEquals(3, loaded.scripts().size());
        assertEquals(1, loaded.scripts().get(0).version());
        assertEquals(2, loaded.scripts().get(1).version());
        assertEquals(10, loaded.scripts().get(2).version());
        assertEquals("first", loaded.scripts().get(0).description());
    }

    @Test
    void rejectsInvalidFilenamesLeadingZerosNonIntegerVersionsAndDuplicates() {
        var loaded = MigrationLoader.load(List.of(InMemoryMigrationSource.ofStrings(Map.of(
            "V1__a.sql", "SELECT 1;",
            "sub/V1__dup.sql", "SELECT 1;",
            "V01__leading_zero.sql", "SELECT 1;",
            "V1.0__dotted.sql", "SELECT 1;",
            "R__repeatable_not_supported.sql", "SELECT 1;",
            "bad_migration.sql", "SELECT 1;",
            "V2_single_underscore.sql", "SELECT 2;"
        ))));
        assertFalse(loaded.issues().isEmpty());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.code() == ValidationErrorCode.DUPLICATE_VERSION));
        assertEquals(5, loaded.issues().stream().filter(i -> i.code() == ValidationErrorCode.INVALID_FILENAME).count());
    }

    @Test
    void normalizesLineEndingsAndBomForChecksumsAndRejectsInvalidUtf8() {
        var lf = MigrationLoader.load(List.of(InMemoryMigrationSource.ofStrings(Map.of(
            "V1__init.sql", "CREATE TABLE t (id int);\n"
        ))));
        var crlfWithBom = MigrationLoader.load(List.of(InMemoryMigrationSource.ofStrings(Map.of(
            "V1__init.sql", "\uFEFFCREATE TABLE t (id int);\r\n"
        ))));
        assertEquals(lf.scripts().get(0).checksum(), crlfWithBom.scripts().get(0).checksum());
        assertEquals(64, lf.scripts().get(0).checksum().length());

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
            assertEquals(2, loaded.scripts().size());
            assertEquals("V1__from_jar.sql", loaded.scripts().get(0).scriptName());
            assertEquals("sub/V2__nested.sql", loaded.scripts().get(1).scriptName());
        }

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
            assertEquals(1, loaded.scripts().size());
            assertEquals("V1__no_dir_entry.sql", loaded.scripts().get(0).scriptName());
        }
    }
}
