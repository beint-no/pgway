package no.beint.pgway.spring;

import java.time.Duration;
import no.beint.pgway.PgwayConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Minimal, immutable convention-first Spring Boot properties for Pgway ({@code pgway.*}).
 *
 * <p>Migrations are always loaded from {@code classpath*:db/migration} and tracked in {@code pgway_history}.
 */
@ConfigurationProperties(prefix = "pgway")
public record PgwayProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue(PgwayConfig.DEFAULT_SCHEMA) String schema,
        @DefaultValue("30s") Duration lockTimeout,
        Duration migrationLockTimeout,
        Duration migrationStatementTimeout) {}
