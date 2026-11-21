package no.beint.pgway.gradle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PgwayPluginTest {

    @Test
    void verifiesMigrationsOnCheckAndIsUpToDateOnSecondRunWithConfigurationCache(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\n");
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.configuration-cache=true\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins {
                java
                id("no.beint.pgway")
            }
            pgway {
                schema.set("custom_app")
            }
            """);
        Path migrations = Files.createDirectories(project.resolve("src/main/resources/db/migration"));
        Files.writeString(migrations.resolve("V1__init.sql"), """
            CREATE TABLE accounts (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                name text NOT NULL
            );
            """);
        Files.writeString(migrations.resolve("V2__idx.sql"), """
            -- pgway: transaction=false
            CREATE INDEX CONCURRENTLY accounts_name_idx ON accounts (name);
            """);

        var first = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("check", "--console=plain")
            .build();
        assertTrue(first.getOutput().contains("Pgway verified 2 migration(s) for schema 'custom_app'"));
        assertTrue(first.getOutput().contains("BUILD SUCCESSFUL"));

        var second = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("check", "--console=plain")
            .build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"));
        assertTrue(second.getOutput().contains(":verifyPgwayMigrations UP-TO-DATE"));
    }

    @Test
    void failsBuildOnBrokenSqlBadFilenameOrInvalidDirective(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"broken\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins {
                java
                id("no.beint.pgway")
            }
            """);
        Path migrations = Files.createDirectories(project.resolve("src/main/resources/db/migration"));
        Files.writeString(migrations.resolve("V1__broken.sql"), "CREATE TABLE broken (id nonexistent_type);");

        var sqlFail = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("processResources", "--console=plain")
            .buildAndFail();
        assertTrue(sqlFail.getOutput().contains("nonexistent_type"));

        Files.delete(migrations.resolve("V1__broken.sql"));
        Files.writeString(migrations.resolve("bad_name.sql"), "SELECT 1;");
        var nameFail = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("check", "--console=plain")
            .buildAndFail();
        assertTrue(nameFail.getOutput().contains("INVALID_FILENAME"));

        Files.delete(migrations.resolve("bad_name.sql"));
        Files.writeString(migrations.resolve("V1__bad_dir.sql"), "-- pgway: transactional=false\nSELECT 1;");
        var dirFail = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("check", "--console=plain")
            .buildAndFail();
        assertTrue(dirFail.getOutput().contains("Unknown pgway directive"));
    }
}
