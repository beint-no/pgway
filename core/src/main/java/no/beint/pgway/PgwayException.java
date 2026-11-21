package no.beint.pgway;

/** Base exception for all Pgway errors. */
public abstract sealed class PgwayException extends RuntimeException
        permits PgwayUnsupportedDatabaseException,
                PgwayLockException,
                PgwayValidationException,
                PgwaySqlParseException,
                PgwayMigrationException {

    private static final long serialVersionUID = 1L;

    protected PgwayException(String message) {
        super(message);
    }

    protected PgwayException(String message, Throwable cause) {
        super(message, cause);
    }
}
