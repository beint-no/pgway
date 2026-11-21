package no.beint.pgway;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;

/** Internal PostgreSQL 19+ identifier quoting, duration formatting, and lock-key helpers. */
final class PgIdentifiers {
    private static final int PG_MAX_IDENTIFIER_BYTES = 63;

    private PgIdentifiers() {}

    static String validateIdentifier(String identifier, String label) {
        Objects.requireNonNull(identifier, label + " must not be null");
        if (identifier.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must not contain NUL bytes");
        }
        byte[] utf8 = identifier.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > PG_MAX_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException(
                label + " exceeds PostgreSQL's 63-byte identifier limit: '" + identifier + "'"
            );
        }
        return identifier;
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    static String qualifiedTable(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    static long deriveAdvisoryLockKey(String schema, String table) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(("pgway:lock:" + schema + "." + table).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 missing from JDK", e);
        }
    }

    static String formatPgDuration(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Duration must not be negative: " + duration);
        }
        long millis = duration.toMillis();
        return millis == 0L ? "0" : millis + "ms";
    }
}
