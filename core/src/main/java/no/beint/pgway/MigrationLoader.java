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
 * Loads, strictly decodes, validates filenames ({@code V<positive-int>__<description>.sql}),
 * computes SHA-256 checksums, parses directives/SQL, and sorts migrations.
 */
final class MigrationLoader {
    private static final Pattern VERSIONED_PATTERN = Pattern.compile("^V([1-9][0-9]*)__(.+)\\.sql$");

    private MigrationLoader() {}

    record LoadedMigrations(List<MigrationScript> scripts, List<ValidationIssue> issues) {}

    static LoadedMigrations load(List<MigrationSource> sources) {
        List<RawMigrationResource> rawResources = new ArrayList<>();
        for (MigrationSource source : sources) {
            try {
                rawResources.addAll(source.load());
            } catch (IOException e) {
                throw new PgwayMigrationException("Failed to load migrations from source: " + e.getMessage(), e);
            }
        }

        List<MigrationScript> scripts = new ArrayList<>(rawResources.size());
        List<ValidationIssue> issues = new ArrayList<>();
        Map<Integer, String> seenVersions = new HashMap<>();

        for (RawMigrationResource resource : rawResources) {
            String fullName = resource.name();
            String fileName = baseName(fullName);
            if (!fileName.endsWith(".sql")) {
                continue;
            }

            Matcher matcher = VERSIONED_PATTERN.matcher(fileName);
            if (!matcher.matches()) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_FILENAME,
                    fullName,
                    0,
                    "Invalid migration filename '" + fullName
                        + "': expected V<version>__<description>.sql (positive integer without leading zeros, e.g. V1__init.sql)"
                ));
                continue;
            }

            int version;
            try {
                version = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException _) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_FILENAME,
                    fullName,
                    0,
                    "Migration version overflows 32-bit integer in '" + fullName + "'"
                ));
                continue;
            }

            String description = matcher.group(2).replace('_', ' ').trim();
            if (description.isEmpty()) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_FILENAME,
                    fullName,
                    version,
                    "Migration filename '" + fullName + "' has an empty description"
                ));
                continue;
            }

            byte[] rawBytes = resource.bytes();
            String decoded;
            try {
                decoded = decodeStrictUtf8(rawBytes);
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
            // Zero-copy fast path: hash raw UTF-8 bytes directly when no BOM or CR normalization occurred.
            String checksum = normalizedSql == decoded
                ? sha256Hex(rawBytes)
                : sha256Hex(normalizedSql);

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

            String previous = seenVersions.putIfAbsent(version, fullName);
            if (previous != null) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.DUPLICATE_VERSION,
                    fullName,
                    version,
                    "Duplicate migration version V" + version + ": '" + previous + "' and '" + fullName + "'"
                ));
                continue;
            }

            scripts.add(new MigrationScript(
                version,
                description,
                fullName,
                resource.location(),
                normalizedSql,
                checksum,
                parsed.directives(),
                parsed.statements()
            ));
        }

        scripts.sort(Comparator.comparingInt(MigrationScript::version));
        return new LoadedMigrations(List.copyOf(scripts), List.copyOf(issues));
    }

    static String descriptionFromScriptName(String scriptName) {
        String fileName = baseName(scriptName);
        Matcher matcher = VERSIONED_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            return matcher.group(2).replace('_', ' ').trim();
        }
        return fileName;
    }

    static String sha256Hex(String normalizedSql) {
        return sha256Hex(normalizedSql.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
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
