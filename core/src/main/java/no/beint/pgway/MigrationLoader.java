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
 * computes SHA-256 checksums, lazily parses directives/SQL, and sorts migrations.
 */
final class MigrationLoader {
    private static final Pattern VERSIONED_PATTERN = Pattern.compile("^V([1-9][0-9]*)__(.+)\\.sql$");

    private MigrationLoader() {}

    record DiscoveredMigration(
            int version,
            String description,
            String scriptName,
            String location,
            String normalizedSql,
            String checksum) {

        MigrationScript parse() {
            SqlScriptParser.ParsedScript parsed = SqlScriptParser.parse(scriptName, normalizedSql);
            if (parsed.directives().replaces() != null && version != 1) {
                throw new PgwaySqlParseException(
                    scriptName,
                    1,
                    "Pgway directive 'replaces' is only allowed on V1 migrations (found on V" + version + ")"
                );
            }
            return new MigrationScript(
                version,
                description,
                scriptName,
                location,
                normalizedSql,
                checksum,
                parsed.directives(),
                parsed.statements()
            );
        }
    }

    record LoadedHeaders(List<DiscoveredMigration> scripts, List<ValidationIssue> issues) {}

    record LoadedScripts(List<MigrationScript> scripts, List<ValidationIssue> issues) {}

    /** Loads filenames, validates UTF-8 and duplicate versions, and computes checksums without lexing SQL. */
    static LoadedHeaders loadHeaders(MigrationSource source) {
        List<RawMigrationResource> rawResources;
        try {
            rawResources = source.load();
        } catch (IOException e) {
            throw new PgwayMigrationException("Failed to load migrations from source: " + e.getMessage(), e);
        }

        List<DiscoveredMigration> scripts = new ArrayList<>(rawResources.size());
        List<ValidationIssue> issues = new ArrayList<>();
        Map<Integer, String> seenVersions = new HashMap<>(rawResources.size() * 2);

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

            scripts.add(new DiscoveredMigration(
                version,
                description,
                fullName,
                resource.location(),
                normalizedSql,
                checksum
            ));
        }

        scripts.sort(Comparator.comparingInt(DiscoveredMigration::version));
        return new LoadedHeaders(List.copyOf(scripts), List.copyOf(issues));
    }

    /** Loads and eagerly parses directives and SQL statements for every migration script. */
    static LoadedScripts loadAndParseAll(MigrationSource source) {
        LoadedHeaders headers = loadHeaders(source);
        List<MigrationScript> scripts = new ArrayList<>(headers.scripts().size());
        List<ValidationIssue> issues = new ArrayList<>(headers.issues());
        for (DiscoveredMigration discovered : headers.scripts()) {
            try {
                scripts.add(discovered.parse());
            } catch (PgwaySqlParseException e) {
                issues.add(new ValidationIssue(
                    ValidationErrorCode.INVALID_SQL_SCRIPT,
                    discovered.scriptName(),
                    discovered.version(),
                    e.getMessage()
                ));
            }
        }
        return new LoadedScripts(List.copyOf(scripts), List.copyOf(issues));
    }

    static String descriptionFromScriptName(String scriptName) {
        String fileName = baseName(scriptName);
        Matcher matcher = VERSIONED_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            return matcher.group(2).replace('_', ' ').trim();
        }
        return fileName;
    }

    static String historyChainChecksum(List<AppliedMigration> history) {
        StringBuilder sb = new StringBuilder(history.size() * 72);
        List<AppliedMigration> sorted = new ArrayList<>(history);
        sorted.sort(Comparator.comparingInt(AppliedMigration::version));
        for (AppliedMigration row : sorted) {
            sb.append('V').append(row.version()).append(':').append(row.checksum()).append('\n');
        }
        return sha256Hex(sb.toString());
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
