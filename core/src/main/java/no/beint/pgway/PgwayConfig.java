package no.beint.pgway;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Immutable, convention-first configuration for {@link Pgway}.
 *
 * <p>Conventions:
 * <ul>
 *   <li>Versioned migrations {@code V1__*.sql}, {@code V2__*.sql}, ... live in {@code db/migration}</li>
 *   <li>Target schema is {@code public} (non-{@code public} schemas are created automatically)</li>
 *   <li>History table is always {@code pgway_history} in the target schema</li>
 *   <li>Migrations are always validated and strictly ordered before execution</li>
 * </ul>
 */
public final class PgwayConfig {
    public static final String DEFAULT_SCHEMA = "public";
    public static final String HISTORY_TABLE = "pgway_history";
    public static final String DEFAULT_CLASSPATH_LOCATION = "db/migration";
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final MigrationSource source;
    private final String schema;
    private final Duration lockTimeout;
    private final Duration migrationLockTimeout;
    private final Duration migrationStatementTimeout;
    private final MigrationListener listener;

    private PgwayConfig(Builder builder) {
        this.dataSource = Objects.requireNonNull(builder.dataSource, "dataSource must be configured");
        this.source = builder.source != null
            ? builder.source
            : new ClasspathMigrationSource(DEFAULT_CLASSPATH_LOCATION);
        this.schema = PgIdentifiers.validateIdentifier(builder.schema, "schema");
        this.lockTimeout = validateNonNegative(builder.lockTimeout, "lockTimeout");
        this.migrationLockTimeout = builder.migrationLockTimeout == null
            ? null
            : validateNonNegative(builder.migrationLockTimeout, "migrationLockTimeout");
        this.migrationStatementTimeout = builder.migrationStatementTimeout == null
            ? null
            : validateNonNegative(builder.migrationStatementTimeout, "migrationStatementTimeout");
        this.listener = Objects.requireNonNull(builder.listener, "listener must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public MigrationSource source() {
        return source;
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
        private MigrationSource source;
        private String schema = DEFAULT_SCHEMA;
        private Duration lockTimeout = DEFAULT_LOCK_TIMEOUT;
        private Duration migrationLockTimeout;
        private Duration migrationStatementTimeout;
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
            this.source = new FilesystemMigrationSource(directory);
            return this;
        }

        public Builder source(MigrationSource source) {
            this.source = Objects.requireNonNull(source, "source must not be null");
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
