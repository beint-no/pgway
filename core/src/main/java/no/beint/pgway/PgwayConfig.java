package no.beint.pgway;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Immutable, convention-first configuration for {@link Pgway}.
 *
 * <p>Conventions:
 * <ul>
 *   <li>Migrations live in {@code db/migration} on the classpath unless directories or sources are supplied</li>
 *   <li>Target schema is {@code public} (non-{@code public} schemas are created automatically)</li>
 *   <li>History table is always {@code pgway_schema_history} in the target schema</li>
 *   <li>Migrations are always validated and strictly ordered before execution</li>
 * </ul>
 */
public final class PgwayConfig {
    public static final String DEFAULT_SCHEMA = "public";
    public static final String HISTORY_TABLE = "pgway_schema_history";
    public static final String DEFAULT_CLASSPATH_LOCATION = "db/migration";
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);
    static final String BASELINE_DESCRIPTION = "<< Pgway Baseline >>";

    private final DataSource dataSource;
    private final List<MigrationSource> sources;
    private final String schema;
    private final Duration lockTimeout;
    private final Duration migrationLockTimeout;
    private final Duration migrationStatementTimeout;
    private final boolean baselineOnMigrate;
    private final MigrationVersion baselineVersion;
    private final MigrationListener listener;

    private PgwayConfig(Builder builder) {
        this.dataSource = Objects.requireNonNull(builder.dataSource, "dataSource must be configured");
        this.sources = builder.sources.isEmpty()
            ? List.of(new ClasspathMigrationSource(DEFAULT_CLASSPATH_LOCATION))
            : List.copyOf(builder.sources);
        this.schema = PgIdentifiers.validateIdentifier(builder.schema, "schema");
        this.lockTimeout = validateNonNegative(builder.lockTimeout, "lockTimeout");
        this.migrationLockTimeout = builder.migrationLockTimeout == null
            ? null
            : validateNonNegative(builder.migrationLockTimeout, "migrationLockTimeout");
        this.migrationStatementTimeout = builder.migrationStatementTimeout == null
            ? null
            : validateNonNegative(builder.migrationStatementTimeout, "migrationStatementTimeout");
        this.baselineOnMigrate = builder.baselineOnMigrate;
        this.baselineVersion = Objects.requireNonNull(builder.baselineVersion, "baselineVersion must not be null");
        this.listener = Objects.requireNonNull(builder.listener, "listener must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public List<MigrationSource> sources() {
        return sources;
    }

    public String schema() {
        return schema;
    }

    public Duration lockTimeout() {
        return lockTimeout;
    }

    public Duration migrationLockTimeout() {
        return migrationLockTimeout;
    }

    public Duration migrationStatementTimeout() {
        return migrationStatementTimeout;
    }

    public long advisoryLockKey() {
        return PgIdentifiers.deriveAdvisoryLockKey(schema, HISTORY_TABLE);
    }

    public boolean baselineOnMigrate() {
        return baselineOnMigrate;
    }

    public MigrationVersion baselineVersion() {
        return baselineVersion;
    }

    public MigrationListener listener() {
        return listener;
    }

    private static Duration validateNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative: " + duration);
        }
        return duration;
    }

    public static final class Builder {
        private DataSource dataSource;
        private final List<MigrationSource> sources = new ArrayList<>();
        private String schema = DEFAULT_SCHEMA;
        private Duration lockTimeout = DEFAULT_LOCK_TIMEOUT;
        private Duration migrationLockTimeout;
        private Duration migrationStatementTimeout;
        private boolean baselineOnMigrate = false;
        private MigrationVersion baselineVersion = MigrationVersion.parse("1");
        private MigrationListener listener = MigrationListener.NOOP;

        private Builder() {}

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
            return this;
        }

        public Builder dataSource(String url, String user, String password) {
            this.dataSource = new DriverManagerDataSource(url, user, password);
            return this;
        }

        public Builder directory(Path directory) {
            this.sources.clear();
            this.sources.add(new FilesystemMigrationSource(directory));
            return this;
        }

        public Builder directories(Path... directories) {
            this.sources.clear();
            for (Path dir : directories) {
                this.sources.add(new FilesystemMigrationSource(dir));
            }
            return this;
        }

        public Builder classpath(String resourcePath) {
            this.sources.clear();
            this.sources.add(new ClasspathMigrationSource(resourcePath));
            return this;
        }

        public Builder classpath(String resourcePath, ClassLoader classLoader) {
            this.sources.clear();
            this.sources.add(new ClasspathMigrationSource(resourcePath, classLoader));
            return this;
        }

        public Builder source(MigrationSource source) {
            this.sources.clear();
            this.sources.add(Objects.requireNonNull(source, "source must not be null"));
            return this;
        }

        public Builder sources(List<MigrationSource> sources) {
            this.sources.clear();
            for (MigrationSource source : sources) {
                this.sources.add(Objects.requireNonNull(source, "source must not be null"));
            }
            return this;
        }

        public Builder schema(String schema) {
            this.schema = Objects.requireNonNull(schema, "schema must not be null");
            return this;
        }

        public Builder lockTimeout(Duration lockTimeout) {
            this.lockTimeout = lockTimeout;
            return this;
        }

        public Builder migrationLockTimeout(Duration migrationLockTimeout) {
            this.migrationLockTimeout = migrationLockTimeout;
            return this;
        }

        public Builder migrationStatementTimeout(Duration migrationStatementTimeout) {
            this.migrationStatementTimeout = migrationStatementTimeout;
            return this;
        }

        public Builder baselineOnMigrate(boolean baselineOnMigrate) {
            this.baselineOnMigrate = baselineOnMigrate;
            return this;
        }

        public Builder baselineVersion(MigrationVersion baselineVersion) {
            this.baselineVersion = baselineVersion;
            return this;
        }

        public Builder baselineVersion(String baselineVersion) {
            this.baselineVersion = MigrationVersion.parse(baselineVersion);
            return this;
        }

        public Builder listener(MigrationListener listener) {
            this.listener = listener;
            return this;
        }

        public PgwayConfig build() {
            return new PgwayConfig(this);
        }

        public Pgway create() {
            return Pgway.create(build());
        }
    }

    private static final class DriverManagerDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;
        private PrintWriter logWriter;
        private int loginTimeout;

        DriverManagerDataSource(String url, String user, String password) {
            this.url = Objects.requireNonNull(url, "url must not be null");
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pwd) throws SQLException {
            return DriverManager.getConnection(url, username, pwd);
        }

        @Override
        public PrintWriter getLogWriter() {
            return logWriter;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            this.logWriter = out;
        }

        @Override
        public void setLoginTimeout(int seconds) {
            this.loginTimeout = seconds;
        }

        @Override
        public int getLoginTimeout() {
            return loginTimeout;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
