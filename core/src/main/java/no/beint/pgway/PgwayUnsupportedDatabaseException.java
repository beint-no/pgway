package no.beint.pgway;

/** Thrown when connected to a database older than PostgreSQL 19 (or a non-PostgreSQL database). */
public final class PgwayUnsupportedDatabaseException extends PgwayException {
    private static final long serialVersionUID = 1L;
    private final int serverVersionNum;
    private final String serverVersion;

    public PgwayUnsupportedDatabaseException(int serverVersionNum, String serverVersion, String message) {
        super(message);
        this.serverVersionNum = serverVersionNum;
        this.serverVersion = serverVersion;
    }

    public PgwayUnsupportedDatabaseException(String message, Throwable cause) {
        super(message, cause);
        this.serverVersionNum = -1;
        this.serverVersion = "";
    }

    public int serverVersionNum() {
        return serverVersionNum;
    }

    public String serverVersion() {
        return serverVersion;
    }
}
