package no.beint.pgway.gradle;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Disposable, loopback-only PostgreSQL 19+ cluster for build-time migration verification. */
final class DisposablePostgresCluster implements AutoCloseable {
    private final Path directory;
    private final Path binDir;
    private final String password;
    private int port;
    private boolean started;
    private boolean closed;

    DisposablePostgresCluster(String configuredBin) throws IOException, InterruptedException {
        this.binDir = discoverPostgresBin(configuredBin);
        this.directory = Files.createTempDirectory("pgway-verify-pg-");
        this.password = UUID.randomUUID().toString();
        try {
            String version = run(Duration.ofSeconds(10), "postgres", "--version");
            if (!version.matches("(?s).*PostgreSQL\\) (19|[2-9][0-9])\\..*")) {
                throw new IOException("Pgway requires PostgreSQL 19+ binaries for build verification, found: " + version.strip());
            }
            Path pwFile = directory.resolve("pw");
            Files.writeString(pwFile, password);
            try {
                run(
                    Duration.ofSeconds(60),
                    "initdb",
                    "-D",
                    dataDir().toString(),
                    "--username=pgway",
                    "--auth=scram-sha-256",
                    "--pwfile=" + pwFile,
                    "--encoding=UTF8",
                    "--locale=C"
                );
            } finally {
                Files.deleteIfExists(pwFile);
            }
            startPostmaster();
        } catch (IOException | InterruptedException | RuntimeException e) {
            try {
                close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    String url() {
        return "jdbc:postgresql://127.0.0.1:" + port + "/postgres";
    }

    String user() {
        return "pgway";
    }

    String password() {
        return password;
    }

    private Path dataDir() {
        return directory.resolve("data");
    }

    private void startPostmaster() throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            try {
                run(
                    Duration.ofSeconds(60),
                    "pg_ctl",
                    "-D",
                    dataDir().toString(),
                    "-l",
                    directory.resolve("postgres.log").toString(),
                    "-o",
                    "-h 127.0.0.1 -p " + port
                        + " -c unix_socket_directories=''"
                        + " -c max_connections=20"
                        + " -c fsync=off"
                        + " -c full_page_writes=off"
                        + " -c synchronous_commit=off",
                    "-w",
                    "start"
                );
                started = true;
                return;
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw lastFailure;
    }

    private String run(Duration timeout, String binary, String... args) throws IOException, InterruptedException {
        String executable = binDir != null ? binDir.resolve(binary).toString() : binary;
        String[] cmd = new String[args.length + 1];
        cmd[0] = executable;
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes());
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out running " + executable + ": " + output);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Command failed (" + process.exitValue() + "): " + executable + "\n" + output);
        }
        return output;
    }

    private static Path discoverPostgresBin(String configuredBin) throws IOException {
        if (configuredBin != null && !configuredBin.isBlank()) {
            Path p = Path.of(configuredBin);
            if (Files.isExecutable(p.resolve("postgres"))) {
                return p;
            }
            throw new IOException("Configured pgway.postgresBin does not contain postgres executable: " + p);
        }
        String env = System.getenv("PGWAY_POSTGRES_BIN");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            if (Files.isExecutable(p.resolve("postgres"))) {
                return p;
            }
            throw new IOException("PGWAY_POSTGRES_BIN does not contain postgres executable: " + p);
        }
        List<Path> candidates = List.of(
            Path.of("/opt/homebrew/opt/postgresql@19/bin"),
            Path.of("/usr/local/opt/postgresql@19/bin"),
            Path.of("/usr/lib/postgresql/19/bin"),
            Path.of("/usr/pgsql-19/bin"),
            Path.of("/Applications/Postgres.app/Contents/Versions/19/bin")
        );
        for (Path candidate : candidates) {
            if (Files.isExecutable(candidate.resolve("postgres"))) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (started || Files.exists(dataDir().resolve("postmaster.pid"))) {
            try {
                run(Duration.ofSeconds(30), "pg_ctl", "-D", dataDir().toString(), "-m", "immediate", "-w", "stop");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted stopping disposable PostgreSQL cluster", e);
            }
            started = false;
        }
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        closed = true;
    }
}
