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

    @Test
    void enforcesSchemaLintRulesViaExtensionAndInvalidatesOnOptionChange(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"lint-sample\"\n");
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.configuration-cache=true\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins {
                java
                id("no.beint.pgway")
            }
            pgway {
                tenantColumn.set("tenant_id")
                forbidExplicitNames.set(true)
            }
            """);
        Path migrations = Files.createDirectories(project.resolve("src/main/resources/db/migration"));
        Files.writeString(migrations.resolve("V1__init.sql"), """
            CREATE TABLE tenants (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY
            );
            CREATE TABLE customers (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                UNIQUE (id, tenant_id)
            );
            CREATE TABLE orders (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                customer_id bigint NOT NULL REFERENCES customers (id),
                created_at timestamptz NOT NULL
            );
            CREATE INDEX orders_tenant_idx ON orders (tenant_id);
            CREATE INDEX ON orders (tenant_id, created_at);
            """);

        // All 4 rules fire in one build:
        // 1. redundant-index: orders_tenant_idx covered by (tenant_id, created_at)
        // 2. unreferenced-composite-unique: customers UNIQUE(id, tenant_id) not referenced by any FK
        // 3. non-composite-tenant-fk: orders.customer_id -> customers(id) without tenant_id
        // 4. forbid-explicit-names: orders_tenant_idx explicit index name
        var lintFail = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("check", "--console=plain")
            .buildAndFail();
        assertTrue(lintFail.getOutput().contains("[redundant-index]"), lintFail.getOutput());
        assertTrue(lintFail.getOutput().contains("[unreferenced-composite-unique]"), lintFail.getOutput());
        assertTrue(lintFail.getOutput().contains("[non-composite-tenant-fk]"), lintFail.getOutput());
        assertTrue(lintFail.getOutput().contains("[forbid-explicit-names]"), lintFail.getOutput());

        // Fix schema cleanly (composite FK + remove redundant/explicit index) and verify pass + configuration cache
        Files.writeString(migrations.resolve("V1__init.sql"), """
            CREATE TABLE tenants (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY
            );
            CREATE TABLE customers (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                UNIQUE (id, tenant_id)
            );
            CREATE TABLE orders (
                id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                tenant_id bigint NOT NULL REFERENCES tenants (id),
                customer_id bigint NOT NULL,
                cross_customer_id bigint REFERENCES customers (id),
                created_at timestamptz NOT NULL,
                FOREIGN KEY (customer_id, tenant_id) REFERENCES customers (id, tenant_id)
            );
            CREATE INDEX ON orders (tenant_id, created_at);
            """);
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins {
                java
                id("no.beint.pgway")
            }
            pgway {
                tenantColumn.set("tenant_id")
                allowedCrossTenantForeignKeys.add("orders.cross_customer_id")
                forbidExplicitNames.set(true)
            }
            """);

        var pass = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("check", "--console=plain")
            .build();
        assertTrue(pass.getOutput().contains("BUILD SUCCESSFUL"), pass.getOutput());

        // Removing allowlist entry invalidates UP-TO-DATE and fails immediately
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins {
                java
                id("no.beint.pgway")
            }
            pgway {
                tenantColumn.set("tenant_id")
                forbidExplicitNames.set(true)
            }
            """);
        var invalidated = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("check", "--console=plain")
            .buildAndFail();
        assertTrue(invalidated.getOutput().contains("[non-composite-tenant-fk]"), invalidated.getOutput());
    }
}
