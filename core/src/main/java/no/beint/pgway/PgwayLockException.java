package no.beint.pgway;

import java.time.Duration;

/** Thrown when Pgway cannot acquire its PostgreSQL session advisory lock within the configured timeout. */
public final class PgwayLockException extends PgwayException {
    private static final long serialVersionUID = 1L;
    private final long lockKey;
    private final Duration timeout;

    public PgwayLockException(long lockKey, Duration timeout, String message, Throwable cause) {
        super(message, cause);
        this.lockKey = lockKey;
        this.timeout = timeout;
    }

    public long lockKey() {
        return lockKey;
    }

    public Duration timeout() {
        return timeout;
    }
}
