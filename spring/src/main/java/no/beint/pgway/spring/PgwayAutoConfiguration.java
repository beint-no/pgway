package no.beint.pgway.spring;

import java.util.List;
import javax.sql.DataSource;
import no.beint.pgway.MigrationListener;
import no.beint.pgway.MigrationSource;
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
 * Spring Boot 4 auto-configuration for {@link Pgway}.
 *
 * <p>Configure under the {@code pgway.*} property prefix; disable with {@code pgway.enabled=false}.
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
            ObjectProvider<MigrationListener> listenerProvider,
            ObjectProvider<PgwayConfigurationCustomizer> customizers) {
        PgwayConfig.Builder builder = Pgway.configure()
            .dataSource(dataSource)
            .createSchemas(properties.isCreateSchemas())
            .historyTable(properties.getHistoryTable())
            .lockTimeout(properties.getLockTimeout())
            .outOfOrder(properties.isOutOfOrder())
            .validateOnMigrate(properties.isValidateOnMigrate())
            .adoptFlywayHistory(properties.isAdoptFlywayHistory())
            .baselineOnMigrate(properties.isBaselineOnMigrate())
            .baselineVersion(properties.getBaselineVersion())
            .baselineDescription(properties.getBaselineDescription());

        if (!properties.getSchemas().isEmpty()) {
            builder.schemas(properties.getSchemas());
        }
        if (properties.getDefaultSchema() != null && !properties.getDefaultSchema().isBlank()) {
            builder.defaultSchema(properties.getDefaultSchema());
        }
        if (properties.getMigrationLockTimeout() != null) {
            builder.migrationLockTimeout(properties.getMigrationLockTimeout());
        }
        if (properties.getMigrationStatementTimeout() != null) {
            builder.migrationStatementTimeout(properties.getMigrationStatementTimeout());
        }
        if (properties.getAdvisoryLockKey() != null) {
            builder.advisoryLockKey(properties.getAdvisoryLockKey());
        }

        List<MigrationSource> sources = properties.getLocations().stream()
            .<MigrationSource>map(loc -> new SpringResourceMigrationSource(loc, resourcePatternResolver))
            .toList();
        builder.sources(sources);

        MigrationListener listener = listenerProvider.getIfAvailable();
        if (listener != null) {
            builder.listener(listener);
        }

        customizers.orderedStream().forEach(c -> c.customize(builder));
        return builder.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public PgwayMigrationInitializer pgwayMigrationInitializer(
            Pgway pgway,
            ObjectProvider<PgwayMigrationStrategy> migrationStrategy) {
        return new PgwayMigrationInitializer(pgway, migrationStrategy.getIfAvailable());
    }

    @Bean
    public PgwaySchemaManagementProvider pgwaySchemaManagementProvider(ObjectProvider<Pgway> pgwayInstances) {
        return new PgwaySchemaManagementProvider(pgwayInstances);
    }
}
