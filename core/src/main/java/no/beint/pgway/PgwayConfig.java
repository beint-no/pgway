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

/** Immutable configuration for {@link Pgway}. */
public final class PgwayConfig {
    public static final String DEFAULT_SCHEMA = "public";
    public static final String DEFAULT_HISTORY_TABLE = "pgway_schema_history";
    public static final String DEFAULT_LOCATION = "classpath:db/migration";
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);
    public static final String DEFAULT_BASELINE_DESCRIPTION = "<< Pgway Baseline >>";

    private final DataSource dataSource;
    private final List<MigrationSource> sources;
    private final List<String> schemas;
    private final String defaultSchema;
    private final boolean createSchemas;
    private final String historyTable;
    private final Duration lockTimeout;
    private final Duration migrationLockTimeout;
    private final Duration migrationStatementTimeout;
    private final Long advisoryLockKey;
    private final boolean outOfOrder;
    private final boolean validateOnMigrate;
    private final boolean baselineOnMigrate;
    private final MigrationVersion baselineVersion;
    private final String baselineDescription;
    private final MigrationListener listener;

    private PgwayConfig(Builder builder) {
        this.dataSource = Objects.requireNonNull(builder.dataSource, "dataSource must be configured");
        this.sources = builder.sources.isEmpty()
            ? List.of(parseLocation(DEFAULT_LOCATION, builder.classLoader))
            : List.copyOf(builder.sources);

        List<String> validatedSchemas = new ArrayList<>();
        for (String schema : builder.schemas) {
            validatedSchemas.add(PgIdentifiers.validateIdentifier(schema, "schema"));
        }
        if (builder.defaultSchema != null) {
            this.defaultSchema = PgIdentifiers.validateIdentifier(builder.defaultSchema, "defaultSchema");
            if (validatedSchemas.isEmpty()) {
                validatedSchemas.add(this.defaultSchema);
            } else if (!validatedSchemas.contains(this.defaultSchema)) {
                validatedSchemas.addFirst(this.defaultSchema);
            }
        } else if (!validatedSchemas.isEmpty()) {
            this.defaultSchema = validatedSchemas.getFirst();
        } else {
            this.defaultSchema = DEFAULT_SCHEMA;
        }
        this.schemas = List.copyOf(validatedSchemas);

        this.createSchemas = builder.createSchemas;
        this.historyTable = PgIdentifiers.validateIdentifier(builder.historyTable, "historyTable");
        this.lockTimeout = validateNonNegative(builder.lockTimeout, "lockTimeout");
        this.migrationLockTimeout = builder.migrationLockTimeout == null
            ? null
            : validateNonNegative(builder.migrationLockTimeout, "migrationLockTimeout");
        this.migrationStatementTimeout = builder.migrationStatementTimeout == null
            ? null
            : validateNonNegative(builder.migrationStatementTimeout, "migrationStatementTimeout");
        this.advisoryLockKey = builder.advisoryLockKey;
        this.outOfOrder = builder.outOfOrder;
        this.validateOnMigrate = builder.validateOnMigrate;
        this.baselineOnMigrate = builder.baselineOnMigrate;
        this.baselineVersion = Objects.requireNonNull(builder.baselineVersion, "baselineVersion must not be null");
        this.baselineDescription = Objects.requireNonNull(builder.baselineDescription, "baselineDescription must not be null");
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

    public List<String> schemas() {
        return schemas;
    }

    public String defaultSchema() {
        return defaultSchema;
    }

    public boolean createSchemas() {
        return createSchemas;
    }

    public String historyTable() {
        return historyTable;
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

    public long resolvedAdvisoryLockKey() {
        return advisoryLockKey != null
            ? advisoryLockKey
            : PgIdentifiers.deriveAdvisoryLockKey(defaultSchema, historyTable);
    }

    public boolean outOfOrder() {
        return outOfOrder;
    }

    public boolean validateOnMigrate() {
        return validateOnMigrate;
    }

    public boolean baselineOnMigrate() {
        return baselineOnMigrate;
    }

    public MigrationVersion baselineVersion() {
        return baselineVersion;
    }

    public String baselineDescription() {
        return baselineDescription;
    }

    public MigrationListener listener() {
        return listener;
    }

    static MigrationSource parseLocation(String location, ClassLoader classLoader) {
        Objects.requireNonNull(location, "location must not be null");
        String trimmed = location.trim();
        if (trimmed.startsWith("filesystem:")) {
            return new FilesystemMigrationSource(Path.of(trimmed.substring("filesystem:".length())));
        }
        if (trimmed.startsWith("classpath:")) {
            String path = trimmed.substring("classpath:".length());
            return classLoader != null
                ? new ClasspathMigrationSource(path, classLoader)
                : new ClasspathMigrationSource(path);
        }
        return classLoader != null
            ? new ClasspathMigrationSource(trimmed, classLoader)
            : new ClasspathMigrationSource(trimmed);
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
        private ClassLoader classLoader;
        private final List<String> schemas = new ArrayList<>();
        private String defaultSchema;
        private boolean createSchemas = true;
        private String historyTable = DEFAULT_HISTORY_TABLE;
        private Duration lockTimeout = DEFAULT_LOCK_TIMEOUT;
        private Duration migrationLockTimeout;
        private Duration migrationStatementTimeout;
        private Long advisoryLockKey;
        private boolean outOfOrder = false;
        private boolean validateOnMigrate = true;
        private boolean baselineOnMigrate = false;
        private MigrationVersion baselineVersion = MigrationVersion.parse("1");
        private String baselineDescription = DEFAULT_BASELINE_DESCRIPTION;
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

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public Builder locations(String... locations) {
            this.sources.clear();
            for (String location : locations) {
                this.sources.add(parseLocation(location, classLoader));
            }
            return this;
        }

        public Builder locations(List<String> locations) {
            this.sources.clear();
            for (String location : locations) {
                this.sources.add(parseLocation(location, classLoader));
            }
            return this;
        }

        public Builder paths(Path... directories) {
            this.sources.clear();
            for (Path dir : directories) {
                this.sources.add(new FilesystemMigrationSource(dir));
            }
            return this;
        }

        public Builder addSource(MigrationSource source) {
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

        public Builder schemas(String... schemas) {
            this.schemas.clear();
            for (String schema : schemas) {
                this.schemas.add(schema);
            }
            return this;
        }

        public Builder schemas(List<String> schemas) {
            this.schemas.clear();
            this.schemas.addAll(schemas);
            return this;
        }

        public Builder defaultSchema(String defaultSchema) {
            this.defaultSchema = defaultSchema;
            return this;
        }

        public Builder createSchemas(boolean createSchemas) {
            this.createSchemas = createSchemas;
            return this;
        }

        public Builder historyTable(String historyTable) {
            this.historyTable = historyTable;
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

        public Builder advisoryLockKey(long advisoryLockKey) {
            this.advisoryLockKey = advisoryLockKey;
            return this;
        }

        public Builder outOfOrder(boolean outOfOrder) {
            this.outOfOrder = outOfOrder;
            return this;
        }

        public Builder validateOnMigrate(boolean validateOnMigrate) {
            this.validateOnMigrate = validateOnMigrate;
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

        public Builder baselineDescription(String baselineDescription) {
            this.baselineDescription = baselineDescription;
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
