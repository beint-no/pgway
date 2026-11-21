package no.beint.pgway.spring;

import javax.sql.DataSource;
import no.beint.pgway.MigrationListener;
import no.beint.pgway.Pgway;
import no.beint.pgway.PgwayConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Convention-first Spring Boot 4 auto-configuration for {@link Pgway}.
 *
 * <p>Migrations are loaded from {@code classpath*:db/migration}. Disable with {@code pgway.enabled=false}.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, Pgway.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnBooleanProperty(name = "pgway.enabled", matchIfMissing = true)
@EnableConfigurationProperties(PgwayProperties.class)
@Import(DatabaseInitializationDependencyConfigurer.class)
public final class PgwayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Pgway pgway(
            DataSource dataSource,
            PgwayProperties properties,
            ResourcePatternResolver resourcePatternResolver,
            ObjectProvider<MigrationListener> listenerProvider) {
        PgwayConfig.Builder builder = Pgway.configure()
            .dataSource(dataSource)
            .schema(properties.getSchema())
            .lockTimeout(properties.getLockTimeout())
            .baselineOnMigrate(properties.isBaselineOnMigrate())
            .baselineVersion(properties.getBaselineVersion())
            .source(new SpringResourceMigrationSource(resourcePatternResolver));

        if (properties.getMigrationLockTimeout() != null) {
            builder.migrationLockTimeout(properties.getMigrationLockTimeout());
        }
        if (properties.getMigrationStatementTimeout() != null) {
            builder.migrationStatementTimeout(properties.getMigrationStatementTimeout());
        }
        MigrationListener listener = listenerProvider.getIfAvailable();
        if (listener != null) {
            builder.listener(listener);
        }
        return builder.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public PgwayMigrationInitializer pgwayMigrationInitializer(Pgway pgway) {
        return new PgwayMigrationInitializer(pgway);
    }

    @Bean
    public PgwaySchemaManagementProvider pgwaySchemaManagementProvider(ObjectProvider<Pgway> pgwayInstances) {
        return new PgwaySchemaManagementProvider(pgwayInstances);
    }
}
