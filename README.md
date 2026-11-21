# Pgway

Modern PostgreSQL 19+ schema migrations for JDK 27.

Pgway is a focused replacement for Flyway built specifically for PostgreSQL 19+ and modern JVM applications. No multi-database abstractions, no legacy compatibility layers, no commercial feature gates, and no runtime dependencies in `core`.

Requires **JDK 27** and **PostgreSQL 19 or higher**. Connections to PostgreSQL versions older than 19 are rejected immediately.

## Modules

- **`no.beint.pgway:core`** — Zero-dependency PostgreSQL 19+ migration engine. Supply pgjdbc and your `DataSource` / pool.
- **`no.beint.pgway:spring`** — Spring Boot 4 auto-configuration (`PgwayAutoConfiguration`), `PgwayProperties` (`pgway.*`), `DatabaseInitializerDetector` (runs before JPA/Hibernate and JDBC beans), `SchemaManagementProvider`, and Spring `ResourcePatternResolver` support for multi-module and executable nested JARs.

## Design choices

- **PostgreSQL 19+ only**: Enforces `server_version_num >= 190000`. Targets modern PostgreSQL features natively (`GENERATED ... VIRTUAL`, `BEGIN ATOMIC ... END` SQL functions, nested `/* /* */ */` block comments, `$tag$` dollar quotes, `CREATE INDEX CONCURRENTLY`).
- **Atomic transactional DDL**: Transactional migrations record their `pgway_schema_history` row inside the **same transaction** as the DDL. If a transactional migration fails, PostgreSQL rolls back everything cleanly without leaving a dirty failed row that requires manual `repair()`.
- **Explicit non-transactional directives**: Use inline SQL comments at the top of a migration file instead of sidecar `.conf` files:
  ```sql
  -- pgway: transaction=false
  -- pgway: lock_timeout=10s
  -- pgway: statement_timeout=5min
  CREATE INDEX CONCURRENTLY users_email_idx ON users (email);
  ```
  Non-transactional failures record a `success = false` row and block subsequent migrations until inspected and cleared with `repair()`. Unknown, duplicate, or misplaced `-- pgway:` directives fail fast.
- **Session-level advisory locking & session hygiene**: Serializes concurrent rolling-deploy startups via `pg_advisory_lock` keyed by `SHA-256("pgway:lock:<schema>.<table>")` with bounded `lock_timeout`. Works cleanly with single-connection pools, `auto-commit=false` HikariCP pools, `autosave=always`, and non-transactional migrations. Restores `search_path`, `lock_timeout`, and `statement_timeout` after each migration so scripts cannot leak session state into subsequent migrations or the connection pool.
- **SHA-256 checksums & strict UTF-8**: History rows use `timestamptz`, `text`, and normalized LF SHA-256 checksums (64 hex chars). Malformed UTF-8 and invalid `.sql` filenames fail fast; non-`.sql` files (such as `AGENTS.md`) are ignored.
- **No `clean()` footgun**: Destructive schema wiping is intentionally absent.

## Usage

### Programmatic (`no.beint.pgway:core`)

```java
Pgway pgway = Pgway.configure()
    .dataSource(dataSource)
    .locations("classpath:db/migration")
    .migrationLockTimeout(Duration.ofSeconds(10))
    .create();

MigrationResult result = pgway.migrate();
```

Multiple locations (`filesystem:` and `classpath:`), `paths(Path...)`, custom `MigrationSource` implementations, `validate()`, `validateOrThrow()`, `info()`, `repair()`, `repair(true)` (checksum realignment), and `baseline(...)` are exposed on `Pgway`.

### Spring Boot 4 (`no.beint.pgway:spring`)

Add `no.beint.pgway:spring:1.0.0` (which pulls `no.beint.pgway:core`) alongside `org.postgresql:postgresql`. Auto-configuration runs `PgwayMigrationInitializer` before JPA `EntityManagerFactory`, `JdbcTemplate`, and other database consumers initialize.

```yaml
pgway:
  locations:
    - classpath*:db/migration
  schemas:
    - public
  lock-timeout: 30s
  migration-lock-timeout: 10s
```

Disable in slice tests with `pgway.enabled=false`. Customize programmatically with `PgwayConfigurationCustomizer`, `PgwayMigrationStrategy`, or `MigrationListener` beans.

### Adopting Pgway on an existing database

For a database that already has tables applied through version `N`, enable baseline-on-migrate for the cutover deploy:

```yaml
pgway:
  baseline-on-migrate: true
  baseline-version: 25
```

On an empty database (such as local dev or CI), `baseline-on-migrate` does nothing and migrations run from `V1` normally. On a non-empty schema without `pgway_schema_history`, Pgway atomically creates the history table with a `BASELINE` row at `baseline-version` and applies only versions higher than `baseline-version`.

## Migration naming

- Versioned: `V<version>__<description>.sql` (e.g. `V1__init.sql`, `V25__sync_toggles.sql`, `V1.2__patch.sql`, `V1_2__patch.sql`). Versions compare numerically across segments (`V2 < V10`); duplicate numeric equivalents (`V1` and `V01` or `V1.0`) are rejected.
- Repeatable: `R__<description>.sql`. Runs after all versioned migrations whenever its SHA-256 checksum changes.

## Directives

Supported leading comment directives (must appear before the first SQL statement):

| Directive | Values | Default |
|---|---|---|
| `-- pgway: transaction=<bool>` | `true`, `false` | `true` |
| `-- pgway: lock_timeout=<dur>` | `0`, `<n>ms`, `<n>s`, `<n>m`/`<n>min`, `<n>h` | config default (unset) |
| `-- pgway: statement_timeout=<dur>` | `0`, `<n>ms`, `<n>s`, `<n>m`/`<n>min`, `<n>h` | config default (unset) |

## Building and testing

Requires JDK 27 and PostgreSQL 19+ server binaries (`postgres`, `initdb`, `pg_ctl`) on `PATH` or in standard Homebrew/PGDG locations (override with `-Dpgway.postgresBin=...`). Tests spin up a disposable, loopback-only PostgreSQL cluster and never touch existing local databases.

```shell
./gradlew clean check releaseBundle
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
