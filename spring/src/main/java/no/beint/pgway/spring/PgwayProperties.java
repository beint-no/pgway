package no.beint.pgway.spring;

import java.time.Duration;
import no.beint.pgway.PgwayConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Minimal convention-first Spring Boot properties for Pgway ({@code pgway.*}).
 *
 * <p>Migrations are always loaded from {@code classpath*:db/migration} and tracked in {@code pgway_schema_history}.
 */
@ConfigurationProperties(prefix = "pgway")
public class PgwayProperties {
    private boolean enabled = true;
    private String schema = PgwayConfig.DEFAULT_SCHEMA;
    private Duration lockTimeout = PgwayConfig.DEFAULT_LOCK_TIMEOUT;
    private Duration migrationLockTimeout;
    private Duration migrationStatementTimeout;
    private boolean baselineOnMigrate = false;
    private String baselineVersion = "1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }

    public void setLockTimeout(Duration lockTimeout) {
        this.lockTimeout = lockTimeout;
    }

    public Duration getMigrationLockTimeout() {
        return migrationLockTimeout;
    }

    public void setMigrationLockTimeout(Duration migrationLockTimeout) {
        this.migrationLockTimeout = migrationLockTimeout;
    }

    public Duration getMigrationStatementTimeout() {
        return migrationStatementTimeout;
    }

    public void setMigrationStatementTimeout(Duration migrationStatementTimeout) {
        this.migrationStatementTimeout = migrationStatementTimeout;
    }

    public boolean isBaselineOnMigrate() {
        return baselineOnMigrate;
    }

    public void setBaselineOnMigrate(boolean baselineOnMigrate) {
        this.baselineOnMigrate = baselineOnMigrate;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }

    public void setBaselineVersion(String baselineVersion) {
        this.baselineVersion = baselineVersion;
    }
}
