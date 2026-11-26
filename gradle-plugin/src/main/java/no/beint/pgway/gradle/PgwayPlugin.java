package no.beint.pgway.gradle;

import java.util.Set;
import no.beint.pgway.PgwayConfig;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * Gradle plugin ({@code no.beint.pgway}) that verifies {@code src/main/resources/db/migration}
 * against a disposable PostgreSQL 19+ instance during {@code processResources} and {@code check}.
 */
public final class PgwayPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        PgwayExtension extension = project.getExtensions().create("pgway", PgwayExtension.class);
        extension.getSchema().convention(PgwayConfig.DEFAULT_SCHEMA);
        extension.getLintRedundantIndexes().convention(true);
        extension.getLintUnreferencedCompositeUnique().convention(true);
        extension.getAllowedCrossTenantForeignKeys().convention(Set.of());
        extension.getForbidExplicitNames().convention(false);

        TaskProvider<VerifyPgwayMigrationsTask> verifyTask = project.getTasks().register(
            "verifyPgwayMigrations",
            VerifyPgwayMigrationsTask.class,
            task -> {
                task.setGroup("verification");
                task.setDescription("Validates, replays, and lints SQL migrations against PostgreSQL 19+.");
                task.getMigrationsDir().set(project.getLayout().getProjectDirectory().dir("src/main/resources/db/migration"));
                task.getSchema().set(extension.getSchema());
                task.getPostgresBin().set(extension.getPostgresBin());
                task.getLintRedundantIndexes().set(extension.getLintRedundantIndexes());
                task.getLintUnreferencedCompositeUnique().set(extension.getLintUnreferencedCompositeUnique());
                task.getTenantColumn().set(extension.getTenantColumn());
                task.getAllowedCrossTenantForeignKeys().set(extension.getAllowedCrossTenantForeignKeys());
                task.getForbidExplicitNames().set(extension.getForbidExplicitNames());
                task.getReceiptFile().set(project.getLayout().getBuildDirectory().file("pgway/verified.txt"));
            }
        );

        project.getTasks().register(
            "compactPgwayMigrations",
            CompactPgwayMigrationsTask.class,
            task -> {
                task.setGroup("pgway");
                task.setDescription("Compacts V1..Vn SQL migrations into a single verified V1 schema with seamless history compaction.");
                task.getMigrationsDir().set(project.getLayout().getProjectDirectory().dir("src/main/resources/db/migration"));
                task.getSchema().set(extension.getSchema());
                task.getPostgresBin().set(extension.getPostgresBin());
            }
        );

        project.getTasks().matching(t -> t.getName().equals("check") || t.getName().equals("processResources"))
            .configureEach(t -> t.dependsOn(verifyTask));
    }
}
