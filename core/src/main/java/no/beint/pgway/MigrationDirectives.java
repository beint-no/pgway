package no.beint.pgway;

import java.time.Duration;

/**
 * Per-script execution directives parsed from leading {@code -- pgway: key=value} header comments.
 *
 * @param transactional whether the script runs inside a single PostgreSQL transaction (default {@code true})
 * @param lockTimeout optional per-migration {@code lock_timeout} override
 * @param statementTimeout optional per-migration {@code statement_timeout} override
 * @param replaces optional compaction proof ({@code replaces=V<n>:<chain-sha256>}), valid only on {@code V1}
 */
public record MigrationDirectives(
        boolean transactional,
        Duration lockTimeout,
        Duration statementTimeout,
        ReplacesHistory replaces) {

    public static final MigrationDirectives DEFAULT = new MigrationDirectives(true, null, null, null);
}
