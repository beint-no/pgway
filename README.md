# Pgway

Convention-first PostgreSQL 19+ schema migrations for JDK 27.

Pgway is a focused replacement for Flyway with one clear way to do things: no multi-database abstractions, no legacy compatibility shims, no redundant configuration knobs, zero runtime dependencies in `core`, and a Gradle plugin that verifies every migration against a real disposable PostgreSQL 19+ instance at build time.

Requires **JDK 27** and **PostgreSQL 19 or higher**.

## Modules

- **`no.beint.pgway:core`** — Zero-dependency PostgreSQL 19+ migration engine. Supply pgjdbc and your `DataSource` / pool.
- **`no.beint.pgway:spring`** — Convention-first Spring Boot 4 auto-configuration (`PgwayAutoConfiguration`), `DatabaseInitializerDetector` (runs before JPA/Hibernate and JDBC beans), and `SchemaManagementProvider`.
- **`no.beint.pgway`** (`gradle-plugin`) — Gradle plugin that validates and replays `src/main/resources/db/migration` against PostgreSQL 19+ during `processResources` and `check`, failing the build immediately if anything is wrong.

## Conventions (one way to do things)

- **Migrations location:** always `src/main/resources/db/migration` (`classpath*:db/migration/**/*.sql`).
- **History table:** always `pgway_schema_history` in the target schema (default `public`; non-`public` schemas are created automatically).
- **Ordering & validation:** always strictly ordered and validated before execution.
- **Atomic transactional DDL:** transactional migrations commit their `pgway_schema_history` row in the **same transaction** as the DDL. Failed transactional migrations roll back cleanly without leaving dirty history rows.
- **Inline `-- pgway:` directives:** configure non-transactional execution (`CREATE INDEX CONCURRENTLY`) and per-migration timeouts directly at the top of the `.sql` file (no `.sql.conf` sidecars):
  ```sql
  -- pgway: transaction=false
  -- pgway: lock_timeout=10s
  -- pgway: statement_timeout=5min
  CREATE INDEX CONCURRENTLY users_email_idx ON users (email);
  ```
  Unknown, duplicate, or misplaced directives fail the build.
- **Session-level advisory lock & hygiene:** serializes concurrent startups via `pg_advisory_lock` with bounded `lock_timeout`, and restores `search_path`, `lock_timeout`, and `statement_timeout` after every migration.
- **SHA-256 checksums & strict UTF-8:** malformed UTF-8 and invalid `.sql` filenames fail fast; non-`.sql` files (such as `AGENTS.md`) are ignored. No `clean()` footgun.

## Usage

### Build-time verification (`no.beint.pgway` Gradle plugin)

```kotlin
plugins {
    id("no.beint.pgway") version "2.0.0"
}
```

That's all for `public` schema apps. For a custom schema:

```kotlin
pgway {
    schema.set("utin")
}
```

`verifyPgwayMigrations` runs automatically on `processResources` and `check` (incremental and `UP-TO-DATE` in 0 ms when migrations are unchanged).

### Spring Boot 4 (`no.beint.pgway:spring`)

Add `implementation("no.beint.pgway:spring:2.0.0")` alongside `org.postgresql:postgresql`. **Zero configuration** is needed for the default `public` schema. Available properties under `pgway.*`:

| Property | Default | Purpose |
|---|---|---|
| `pgway.enabled` | `true` | Disable in tests without a database (also backs off automatically when no `DataSource` bean exists) |
| `pgway.schema` | `public` | Target PostgreSQL schema |
| `pgway.lock-timeout` | `30s` | Timeout acquiring Pgway's session advisory lock |
| `pgway.migration-lock-timeout` | unset | Default `lock_timeout` per migration |
| `pgway.migration-statement-timeout` | unset | Default `statement_timeout` per migration |
| `pgway.baseline-on-migrate` | `false` | Baseline a pre-existing non-empty schema without `pgway_schema_history` (no-op on empty databases) |
| `pgway.baseline-version` | `1` | Version recorded when `baseline-on-migrate` triggers |

### Programmatic (`no.beint.pgway:core`)

```java
Pgway pgway = Pgway.configure()
    .dataSource(dataSource)
    .directory(migrationsDir)
    .create();

MigrationResult result = pgway.migrate();
```

## Migration naming

- Versioned: `V<version>__<description>.sql` (e.g. `V1__schema.sql`, `V2__add_index.sql`). Versions compare numerically across segments (`V2 < V10`); duplicate numeric equivalents (`V1`, `V01`, `V1.0`) fail fast.
- Repeatable: `R__<description>.sql`. Runs after versioned migrations whenever its SHA-256 checksum changes.

## Building and testing

Requires JDK 27 and PostgreSQL 19+ server binaries (`postgres`, `initdb`, `pg_ctl`).

```shell
./gradlew clean check releaseBundle
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
