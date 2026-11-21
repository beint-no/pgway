package no.beint.pgway;

import java.util.Objects;

/**
 * Cryptographic history-compaction directive ({@code -- pgway: replaces=V<n>:<chain-sha256>})
 * placed at the top of a compacted {@code V1} migration.
 *
 * <p>When an existing database's {@code pgway_history} matches {@code V1..V<throughVersion>}
 * with the exact {@code chainChecksum}, {@link Pgway#migrate()} atomically compacts
 * {@code pgway_history} to the single {@code V1} row without re-executing {@code V1}.
 */
public record ReplacesHistory(int throughVersion, String chainChecksum) {
    public ReplacesHistory {
        if (throughVersion <= 0) {
            throw new IllegalArgumentException("throughVersion must be positive: " + throughVersion);
        }
        Objects.requireNonNull(chainChecksum, "chainChecksum must not be null");
        if (chainChecksum.length() != 64 || !isLowerHex(chainChecksum)) {
            throw new IllegalArgumentException("chainChecksum must be 64 lowercase hex characters: " + chainChecksum);
        }
    }

    private static boolean isLowerHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
