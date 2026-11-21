package no.beint.pgway;

/** Thrown when executing a migration or history operation against PostgreSQL fails. */
public final class PgwayMigrationException extends PgwayException {
    private static final long serialVersionUID = 1L;
    private final String script;
    private final transient MigrationVersion version;
    private final int statementIndex;
    private final int line;
    private final String sqlState;

    public PgwayMigrationException(String message, Throwable cause) {
        super(message, cause);
        this.script = null;
        this.version = null;
        this.statementIndex = 0;
        this.line = 0;
        this.sqlState = null;
    }

    public PgwayMigrationException(
            String script,
            MigrationVersion version,
            int statementIndex,
            int line,
            String sqlState,
            String message,
            Throwable cause) {
        super(message, cause);
        this.script = script;
        this.version = version;
        this.statementIndex = statementIndex;
        this.line = line;
        this.sqlState = sqlState;
    }

    public String script() {
        return script;
    }

    public MigrationVersion version() {
        return version;
    }

    public int statementIndex() {
        return statementIndex;
    }

    public int line() {
        return line;
    }

    public String sqlState() {
        return sqlState;
    }
}
