package no.beint.pgway.testsupport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.Driver;

/**
 * Disposable, loopback-only PostgreSQL 19 cluster for integration tests.
 * Never connects to any developer or system PostgreSQL instance.
 */
public final class Pg19TestCluster implements AutoCloseable {
    private static final Driver DRIVER = new Driver();
    private static final AtomicInteger DB_SEQUENCE = new AtomicInteger(1);
    private static volatile Pg19TestCluster shared;

    private final Path directory;
    private final Path binDir;
    private final String password;
    private int port;
    private boolean started;
    private boolean closed;
    private final Thread shutdownHook;

    public static synchronized Pg19TestCluster shared() {
        if (shared == null || shared.closed) {
            try {
                shared = new Pg19TestCluster();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to start disposable PostgreSQL 19 test cluster", e);
            }
        }
        return shared;
    }

    public Pg19TestCluster() throws IOException, InterruptedException {
        this.binDir = discoverPostgres19Bin();
        this.directory = Files.createTempDirectory("pgway-pg19-");
        this.password = UUID.randomUUID().toString();
        this.shutdownHook = new Thread(() -> {
            try {
                close();
            } catch (IOException e) {
                System.err.println("Pgway test cluster cleanup failed for " + directory + ": " + e.getMessage());
            }
        }, "pgway-pg19-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            String version = run(Duration.ofSeconds(10), "postgres", "--version");
            if (!version.matches("(?s).*PostgreSQL\\) 19\\..*")) {
                throw new IOException("Expected PostgreSQL 19 binaries, found: " + version.strip());
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

    public String user() {
        return "pgway";
    }

    public String password() {
        return password;
    }

    public int port() {
        return port;
    }

    public String url(String database) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }

    public String maintenanceUrl() {
        return url("postgres");
    }

    public String createDatabase(String prefix) throws SQLException {
        String safe = prefix.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (safe.isEmpty() || !Character.isLetter(safe.charAt(0))) {
            safe = "db_" + safe;
        }
        String name = safe + "_" + DB_SEQUENCE.getAndIncrement();
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        try (Connection c = connect("postgres"); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE \"" + name + "\"");
        }
        return name;
    }

    public Connection connect(String database) throws SQLException {
        return connect(url(database), user(), password());
    }

    public static Connection connect(String url, String user, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        Connection connection = DRIVER.connect(url, props);
        if (connection == null) {
            throw new SQLException("Unable to connect to PostgreSQL URL: " + url);
        }
        return connection;
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
                        + " -c max_connections=100"
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

    private static Path discoverPostgres19Bin() throws IOException {
        String configured = System.getProperty("pgway.postgresBin", System.getenv("PGWAY_POSTGRES_BIN"));
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isExecutable(p.resolve("postgres"))) {
                return p;
            }
            throw new IOException("Configured postgresBin does not contain postgres executable: " + p);
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
        // Fall back to PATH
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
                throw new IOException("Interrupted stopping PostgreSQL 19 test cluster", e);
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
        if (Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutting down
            }
        }
    }
}
