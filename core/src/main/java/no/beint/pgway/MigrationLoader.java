package no.beint.pgway;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads, strictly decodes, validates filenames, computes SHA-256 checksums, parses directives/SQL,
 * and sorts all migrations from configured {@link MigrationSource}s.
 */
final class MigrationLoader {
    private static final Pattern VERSIONED_PATTERN = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__(.+)\\.sql$");
    private static final Pattern REPEATABLE_PATTERN = Pattern.compile("^R__(.+)\\.sql$");

    private MigrationLoader() {}

    record LoadedMigrations(
            List<MigrationScript> versioned,
            List<MigrationScript> repeatable,
            List<ValidationIssue> issues) {}

    static LoadedMigrations load(List<MigrationSource> sources) {
        List<RawMigrationResource> rawResources = new ArrayList<>();
        for (MigrationSource source : sources) {
            try {
                rawResources.addAll(source.load());
            } catch (IOException e) {
                throw new PgwayMigrationException("Failed to load migrations from source: " + e.getMessage(), e);
            }
        }

        List<MigrationScript> versioned = new ArrayList<>();
        List<MigrationScript> repeatable = new ArrayList<>();
        List<ValidationIssue> issues = new ArrayList<>();

        Map<MigrationVersion, String> seenVersions = new HashMap<>();
        Map<String, String> seenRepeatables = new HashMap<>();

        for (RawMigrationResource resource : rawResources) {
            String fullName = resource.name();
            String fileName = baseName(fullName);
            if (!fileName.endsWith(".sql")) {
                continue;
            }

            Matcher versionedMatcher = VERSIONED_PATTERN.matcher(fileName);
            Matcher repeatableMatcher = REPEATABLE_PATTERN.matcher(fileName);

            MigrationKind kind;
            MigrationVersion version;
            String rawDescription;
            if (versionedMatcher.matches()) {
                kind = MigrationKind.VERSIONED;
                try {
                    version = MigrationVersion.parse(versionedMatcher.group(1));
                } catch (IllegalArgumentException e) {
                    issues.add(new ValidationIssue(
                        ValidationErrorCode.INVALID_FILENAME,
                        fullName,
                        null,
                        "Invalid version in migration filename '" + fullName + "': " + e.getMessage()
                    ));
                    continue;
                }
                rawDescription = versionedMatcher.group(2);
            } else if (repeatableMatcher.matches()) {
                kind = MigrationKind.REPEATABLE;
                version = null;
                rawDescription = repeatableMatcher.group(1);
            } else {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_FILENAME,
                    fullName,
                    null,
                    "Invalid migration filename '" + fullName
                        + "': expected V<version>__<description>.sql or R__<description>.sql"
                ));
                continue;
            }

            String description = rawDescription.replace('_', ' ').trim();
            if (description.isEmpty()) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_FILENAME,
                    fullName,
                    version,
                    "Migration filename '" + fullName + "' has an empty description"
                ));
                continue;
            }

            String decoded;
            try {
                decoded = decodeStrictUtf8(resource.bytes());
            } catch (CharacterCodingException e) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_UTF8,
                    fullName,
                    version,
                    "Migration '" + fullName + "' is not valid UTF-8: " + e.getMessage()
                ));
                continue;
            }

            String normalizedSql = SqlScriptParser.normalizeSql(decoded);
            String checksum = sha256Hex(normalizedSql);

            SqlScriptParser.ParsedScript parsed;
            try {
                parsed = SqlScriptParser.parse(fullName, normalizedSql);
            } catch (PgwaySqlParseException e) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_SQL_SCRIPT,
                    fullName,
                    version,
                    e.getMessage()
                ));
                continue;
            }

            if (kind == MigrationKind.VERSIONED) {
                String previous = seenVersions.putIfAbsent(version, fullName);
                if (previous != null) {
                    issues.add(new ValidationIssue(
                        ValidationErrorCode.DUPLICATE_VERSION,
                        fullName,
                        version,
                        "Duplicate migration version " + version + ": '" + previous + "' and '" + fullName + "'"
                    ));
                    continue;
                }
                versioned.add(new MigrationScript(
                    kind,
                    version,
                    description,
                    fullName,
                    resource.location(),
                    normalizedSql,
                    checksum,
                    parsed.directives(),
                    parsed.statements()
                ));
            } else {
                String previous = seenRepeatables.putIfAbsent(fullName, resource.location());
                if (previous != null) {
                    issues.add(new ValidationIssue(
                        ValidationErrorCode.DUPLICATE_REPEATABLE,
                        fullName,
                        null,
                        "Duplicate repeatable migration '" + fullName + "' found in " + previous + " and " + resource.location()
                    ));
                    continue;
                }
                repeatable.add(new MigrationScript(
                    kind,
                    null,
                    description,
                    fullName,
                    resource.location(),
                    normalizedSql,
                    checksum,
                    parsed.directives(),
                    parsed.statements()
                ));
            }
        }

        versioned.sort(Comparator.comparing(MigrationScript::version));
        repeatable.sort(Comparator.comparing(MigrationScript::description).thenComparing(MigrationScript::scriptName));

        return new LoadedMigrations(List.copyOf(versioned), List.copyOf(repeatable), List.copyOf(issues));
    }

    static String sha256Hex(String normalizedSql) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalizedSql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 missing from JDK", e);
        }
    }

    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
