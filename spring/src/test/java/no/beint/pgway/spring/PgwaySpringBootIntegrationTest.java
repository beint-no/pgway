package no.beint.pgway.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.sql.DataSource;
import no.beint.pgway.Pgway;
import no.beint.pgway.testsupport.Pg19TestCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.SchemaManagement;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

class PgwaySpringBootIntegrationTest {

    @Test
    void runsBeforeHibernateValidationAndJdbcTemplateAndMarksSchemaManaged() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("spring_jpa");

        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                PgwayAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class
            ))
            .withUserConfiguration(JpaTestConfig.class)
            .withPropertyValues(
                "spring.datasource.url=" + pg.url(db),
                "spring.datasource.username=" + pg.user(),
                "spring.datasource.password=" + pg.password(),
                "spring.datasource.hikari.auto-commit=false",
                "spring.datasource.hikari.data-source-properties.autosave=always",
                "spring.datasource.hikari.data-source-properties.cleanupSavepoints=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "pgway.migration-lock-timeout=5s"
            );

        runner.run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, () -> "Startup failed: " + ctx.getStartupFailure());
            assertNotNull(ctx.getBean(Pgway.class));
            assertNotNull(ctx.getBean(PgwayMigrationInitializer.class));
            assertNotNull(ctx.getBean(EntityManagerFactory.class));

            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            String name = jdbc.queryForObject("SELECT name FROM spring_customers WHERE id = 1", String.class);
            assertEquals("Respiro", name);

            PgwaySchemaManagementProvider provider = ctx.getBean(PgwaySchemaManagementProvider.class);
            assertEquals(SchemaManagement.MANAGED, provider.getSchemaManagement(ctx.getBean(DataSource.class)));
        });
    }

    @Test
    void canBeDisabledWithProperty() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("spring_disabled");

        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                PgwayAutoConfiguration.class
            ))
            .withPropertyValues(
                "spring.datasource.url=" + pg.url(db),
                "spring.datasource.username=" + pg.user(),
                "spring.datasource.password=" + pg.password(),
                "pgway.enabled=false"
            );

        runner.run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null);
            assertFalse(ctx.containsBean("pgway"));
            assertFalse(ctx.containsBean("pgwayMigrationInitializer"));
        });
    }

    @Test
    void supportsCustomStrategyAndCustomizerAndFilesystemLocations(@TempDir Path dir) throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("spring_custom");
        Files.writeString(dir.resolve("V1__custom.sql"), "CREATE TABLE custom_items (id int PRIMARY KEY);");

        AtomicBoolean strategyCalled = new AtomicBoolean();
        AtomicBoolean customizerCalled = new AtomicBoolean();

        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                PgwayAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class
            ))
            .withBean(PgwayConfigurationCustomizer.class, () -> builder -> {
                customizerCalled.set(true);
                builder.historyTable("custom_pgway_history");
            })
            .withBean(PgwayMigrationStrategy.class, () -> pgway -> {
                strategyCalled.set(true);
                pgway.migrate();
            })
            .withPropertyValues(
                "spring.datasource.url=" + pg.url(db),
                "spring.datasource.username=" + pg.user(),
                "spring.datasource.password=" + pg.password(),
                "pgway.locations=filesystem:" + dir.toAbsolutePath(),
                "pgway.schemas=app_schema"
            );

        runner.run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, () -> "Startup failed: " + ctx.getStartupFailure());
            assertTrue(strategyCalled.get());
            assertTrue(customizerCalled.get());

            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app_schema.custom_pgway_history WHERE success",
                Integer.class
            );
            assertEquals(1, count);
        });
    }

    @Test
    void springResourceMigrationSourceScansJarArchives(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("db-module.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("db/migration/V1__from_spring_jar.sql"));
            jos.write("CREATE TABLE t (id int);".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("db/migration/postgresql/V2__sub.sql"));
            jos.write("ALTER TABLE t ADD COLUMN note text;".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        try (URLClassLoader cl = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            var resolver = new PathMatchingResourcePatternResolver(cl);
            var source = new SpringResourceMigrationSource("classpath:db/migration", resolver);
            var loaded = source.load();
            assertEquals(2, loaded.size());
            assertEquals("V1__from_spring_jar.sql", loaded.get(0).name());
            assertEquals("postgresql/V2__sub.sql", loaded.get(1).name());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = SpringCustomerEntity.class)
    static class JpaTestConfig {
        @Bean
        StartupReader startupReader(JdbcTemplate jdbcTemplate) {
            // If PgwayDatabaseInitializerDetector failed to order Pgway before JdbcTemplate, this would fail!
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM spring_customers", Integer.class);
            assertEquals(1, count);
            return new StartupReader(count);
        }
    }

    record StartupReader(int count) {}
}
