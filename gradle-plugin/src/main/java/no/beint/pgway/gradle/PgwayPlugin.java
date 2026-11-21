package no.beint.pgway.gradle;

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

        TaskProvider<VerifyPgwayMigrationsTask> verifyTask = project.getTasks().register(
            "verifyPgwayMigrations",
            VerifyPgwayMigrationsTask.class,
            task -> {
                task.setGroup("verification");
                task.setDescription("Validates and replays SQL migrations against PostgreSQL 19+.");
                task.getMigrationsDir().set(project.getLayout().getProjectDirectory().dir("src/main/resources/db/migration"));
                task.getSchema().set(extension.getSchema());
                task.getPostgresBin().set(extension.getPostgresBin());
                task.getReceiptFile().set(project.getLayout().getBuildDirectory().file("pgway/verified.txt"));
            }
        );

        project.getTasks().matching(t -> t.getName().equals("check") || t.getName().equals("processResources"))
            .configureEach(t -> t.dependsOn(verifyTask));
    }
}
