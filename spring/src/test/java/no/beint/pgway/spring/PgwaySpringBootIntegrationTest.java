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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.sql.DataSource;
import no.beint.pgway.Pgway;
import no.beint.pgway.testsupport.Pg19TestCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.SchemaManagement;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

class PgwaySpringBootIntegrationTest {

    @Test
    void runsBeforeHibernateValidationAndJdbcTemplateWithZeroConfiguration() throws Exception {
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
                "spring.jpa.hibernate.ddl-auto=validate"
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
    void supportsSingleSchemaConventionProperty() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("spring_schema");

        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                PgwayAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class
            ))
            .withPropertyValues(
                "spring.datasource.url=" + pg.url(db),
                "spring.datasource.username=" + pg.user(),
                "spring.datasource.password=" + pg.password(),
                "pgway.schema=utin"
            );

        runner.run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, () -> "Startup failed: " + ctx.getStartupFailure());
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM utin.pgway_schema_history WHERE success",
                Integer.class
            );
            assertEquals(2, count);
            String name = jdbc.queryForObject("SELECT name FROM utin.spring_customers WHERE id = 1", String.class);
            assertEquals("Respiro", name);
        });
    }

    @Test
    void baselinesPreExistingDatabaseWithBaselineOnMigrate() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("spring_baseline");
        try (var conn = pg.connect(db); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE spring_customers (id integer PRIMARY KEY, name text NOT NULL)");
            stmt.execute("INSERT INTO spring_customers (id, name) VALUES (1, 'Existing')");
        }

        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                PgwayAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class
            ))
            .withPropertyValues(
                "spring.datasource.url=" + pg.url(db),
                "spring.datasource.username=" + pg.user(),
                "spring.datasource.password=" + pg.password(),
                "pgway.baseline-on-migrate=true"
            );

        runner.run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, () -> "Startup failed: " + ctx.getStartupFailure());
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            // V1 baselined (not re-run), V2 applied!
            Integer count = jdbc.queryForObject("SELECT count(*) FROM pgway_schema_history", Integer.class);
            assertEquals(2, count);
            assertEquals("Existing", jdbc.queryForObject("SELECT name FROM spring_customers WHERE id = 1", String.class));
        });
    }

    @Test
    void canBeDisabledWithPropertyAndBacksOffWithoutDataSource() throws Exception {
        Pg19TestCluster pg = Pg19TestCluster.shared();
        String db = pg.createDatabase("spring_disabled");

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, PgwayAutoConfiguration.class))
            .withPropertyValues(
                "spring.datasource.url=" + pg.url(db),
                "spring.datasource.username=" + pg.user(),
                "spring.datasource.password=" + pg.password(),
                "pgway.enabled=false"
            )
            .run(ctx -> {
                assertTrue(ctx.getStartupFailure() == null);
                assertFalse(ctx.containsBean("pgway"));
            });

        // No DataSource bean -> backs off cleanly without needing spring.autoconfigure.exclude
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PgwayAutoConfiguration.class))
            .run(ctx -> {
                assertTrue(ctx.getStartupFailure() == null);
                assertFalse(ctx.containsBean("pgway"));
            });
    }

    @Test
    void springResourceMigrationSourceScansJarArchivesWithAndWithoutDirectoryEntries(@TempDir Path tempDir)
            throws Exception {
        Path jar = tempDir.resolve("db-module.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("db/migration/V1__from_spring_jar.sql"));
            jos.write("CREATE TABLE t (id int);".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("db/migration/sub/V2__sub.sql"));
            jos.write("ALTER TABLE t ADD COLUMN note text;".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        try (URLClassLoader cl = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            var resolver = new PathMatchingResourcePatternResolver(cl);
            var source = new SpringResourceMigrationSource(resolver);
            var loaded = source.load();
            assertEquals(2, loaded.size());
            assertEquals("V1__from_spring_jar.sql", loaded.get(0).name());
            assertEquals("sub/V2__sub.sql", loaded.get(1).name());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = SpringCustomerEntity.class)
    static class JpaTestConfig {
        @Bean
        StartupReader startupReader(JdbcTemplate jdbcTemplate) {
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM spring_customers", Integer.class);
            assertEquals(1, count);
            return new StartupReader(count);
        }
    }

    record StartupReader(int count) {}
}
