package no.beint.pgway;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Numeric schema migration version (e.g. {@code 1}, {@code 25}, {@code 1.2}, {@code 2026.11.21.1}).
 *
 * <p>Underscore separators in filenames (e.g. {@code V1_2__...}) are normalized to dots ({@code 1.2}).
 * Ordering and equality are numeric across segments so {@code 2 < 10} and {@code 1 == 01 == 1.0}.
 */
public final class MigrationVersion implements Comparable<MigrationVersion> {
    private final String value;
    private final List<BigInteger> segments;
    private final List<BigInteger> canonicalSegments;

    private MigrationVersion(String value, List<BigInteger> segments) {
        this.value = value;
        this.segments = List.copyOf(segments);
        int end = segments.size();
        while (end > 1 && segments.get(end - 1).signum() == 0) {
            end--;
        }
        this.canonicalSegments = List.copyOf(segments.subList(0, end));
    }

    public static MigrationVersion parse(String raw) {
        Objects.requireNonNull(raw, "version must not be null");
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("Migration version must not be empty");
        }
        String normalized = raw.replace('_', '.');
        String[] parts = normalized.split("\\.", -1);
        List<BigInteger> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Invalid migration version '" + raw + "': empty version segment");
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException(
                        "Invalid migration version '" + raw + "': segments must contain ASCII digits only"
                    );
                }
            }
            segments.add(new BigInteger(part));
        }
        return new MigrationVersion(normalized, segments);
    }

    /** Returns the normalized dot-separated version string (e.g. {@code "1"}, {@code "1.2"}). */
    public String value() {
        return value;
    }

    public List<BigInteger> segments() {
        return segments;
    }

    @Override
    public int compareTo(MigrationVersion other) {
        int max = Math.max(this.canonicalSegments.size(), other.canonicalSegments.size());
        for (int i = 0; i < max; i++) {
            BigInteger a = i < this.canonicalSegments.size() ? this.canonicalSegments.get(i) : BigInteger.ZERO;
            BigInteger b = i < other.canonicalSegments.size() ? other.canonicalSegments.get(i) : BigInteger.ZERO;
            int cmp = a.compareTo(b);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MigrationVersion other && this.canonicalSegments.equals(other.canonicalSegments);
    }

    @Override
    public int hashCode() {
        return canonicalSegments.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
