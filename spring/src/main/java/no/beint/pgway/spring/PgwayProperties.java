package no.beint.pgway.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import no.beint.pgway.PgwayConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spring Boot configuration properties for Pgway ({@code pgway.*}). */
@ConfigurationProperties(prefix = "pgway")
public class PgwayProperties {
    private boolean enabled = true;
    private List<String> locations = new ArrayList<>(List.of("classpath*:db/migration"));
    private List<String> schemas = new ArrayList<>();
    private String defaultSchema;
    private boolean createSchemas = true;
    private String historyTable = PgwayConfig.DEFAULT_HISTORY_TABLE;
    private Duration lockTimeout = PgwayConfig.DEFAULT_LOCK_TIMEOUT;
    private Duration migrationLockTimeout;
    private Duration migrationStatementTimeout;
    private Long advisoryLockKey;
    private boolean outOfOrder = false;
    private boolean validateOnMigrate = true;
    private boolean adoptFlywayHistory = false;
    private boolean baselineOnMigrate = false;
    private String baselineVersion = "1";
    private String baselineDescription = PgwayConfig.DEFAULT_BASELINE_DESCRIPTION;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations == null ? new ArrayList<>() : new ArrayList<>(locations);
    }

    public List<String> getSchemas() {
        return schemas;
    }

    public void setSchemas(List<String> schemas) {
        this.schemas = schemas == null ? new ArrayList<>() : new ArrayList<>(schemas);
    }

    public String getDefaultSchema() {
        return defaultSchema;
    }

    public void setDefaultSchema(String defaultSchema) {
        this.defaultSchema = defaultSchema;
    }

    public boolean isCreateSchemas() {
        return createSchemas;
    }

    public void setCreateSchemas(boolean createSchemas) {
        this.createSchemas = createSchemas;
    }

    public String getHistoryTable() {
        return historyTable;
    }

    public void setHistoryTable(String historyTable) {
        this.historyTable = historyTable;
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

    public Long getAdvisoryLockKey() {
        return advisoryLockKey;
    }

    public void setAdvisoryLockKey(Long advisoryLockKey) {
        this.advisoryLockKey = advisoryLockKey;
    }

    public boolean isOutOfOrder() {
        return outOfOrder;
    }

    public void setOutOfOrder(boolean outOfOrder) {
        this.outOfOrder = outOfOrder;
    }

    public boolean isValidateOnMigrate() {
        return validateOnMigrate;
    }

    public void setValidateOnMigrate(boolean validateOnMigrate) {
        this.validateOnMigrate = validateOnMigrate;
    }

    public boolean isAdoptFlywayHistory() {
        return adoptFlywayHistory;
    }

    public void setAdoptFlywayHistory(boolean adoptFlywayHistory) {
        this.adoptFlywayHistory = adoptFlywayHistory;
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

    public String getBaselineDescription() {
        return baselineDescription;
    }

    public void setBaselineDescription(String baselineDescription) {
        this.baselineDescription = baselineDescription;
    }
}
