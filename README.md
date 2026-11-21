# Pgway

Convention-first PostgreSQL 19+ schema migrations for JDK 27.

Pgway is a focused, first-principles replacement for Flyway with one clear way to do things: no multi-database abstractions, no legacy compatibility shims, no baseline machinery, no repeatable scripts, zero runtime dependencies in `core`, and a Gradle plugin that verifies every migration against a real disposable PostgreSQL 19+ instance at build time.

Requires **JDK 27** and **PostgreSQL 19 or higher**.

## Modules

- **`no.beint.pgway:core`** — Zero-dependency PostgreSQL 19+ migration engine. Supply pgjdbc and your `DataSource` / pool.
- **`no.beint.pgway:spring`** — Convention-first Spring Boot 4 auto-configuration (`PgwayAutoConfiguration`), immutable `PgwayProperties` record, `DatabaseInitializerDetector` (runs before JPA/Hibernate and JDBC beans), and `SchemaManagementProvider`.
- **`no.beint.pgway`** (`gradle-plugin`) — Gradle plugin that validates and replays `src/main/resources/db/migration` against PostgreSQL 19+ during `processResources` and `check`, failing the build immediately if anything is wrong.

## Conventions (one way to do things)

- **Migrations location:** always `src/main/resources/db/migration` (`classpath*:db/migration/**/*.sql`).
- **Naming:** strictly positive integer versions without leading zeros: `V1__schema.sql`, `V2__add_index.sql`, ... (`^V([1-9][0-9]*)__(.+)\.sql$`). Non-`.sql` files (such as `AGENTS.md`) are ignored.
- **History table (`pgway_history`):** compact, column-Tetris aligned table in the target schema (default `public`; non-`public` schemas are created automatically) with `version integer PRIMARY KEY` and 32-byte raw SHA-256 `checksum bytea`.
- **Fast lock-free startup:** steady-state up-to-date startups complete via a lock-free read-only check without lexing already-applied SQL scripts or taking an advisory lock; pending migrations serialize under a bounded session advisory lock.
- **Atomic transactional DDL:** transactional migrations commit their `pgway_history` row in the **same transaction** as the DDL. Failed transactional migrations roll back cleanly without leaving dirty history rows.
- **Inline `-- pgway:` directives:** configure non-transactional execution (`CREATE INDEX CONCURRENTLY`) and per-migration timeouts directly at the top of the `.sql` file (no `.sql.conf` sidecars):
  ```sql
  -- pgway: transaction=false
  -- pgway: lock_timeout=10s
  -- pgway: statement_timeout=5min
  CREATE INDEX CONCURRENTLY users_email_idx ON users (email);
  ```
  Unknown, duplicate, or misplaced directives fail the build.
- **JDK 27 `ScopedValue` context:** `Pgway.currentMigration()` exposes an immutable `MigrationContext` (`schema`, `version`, `script`, `checksum`, `startedAt`) to `MigrationListener`s with zero `ThreadLocal` leaks.
- **Fail-closed on non-empty schemas:** if `pgway_history` does not exist and the target schema already contains user tables/objects, `migrate()` and `validate()` refuse to proceed instead of replaying `V1..Vn` against an existing database (extension-owned objects are ignored).
- **Compaction instead of baseline:** when cutting over an existing database or squashing `V1..Vn` into a compacted `V1`, replace `pgway_history` with the single `V1` row (`pgway.compactHistory()` or a single SQL `INSERT`).

## Usage

### Build-time verification (`no.beint.pgway` Gradle plugin)

```kotlin
plugins {
    id("no.beint.pgway") version "3.0.2"
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

Add `implementation("no.beint.pgway:spring:3.0.2")` alongside `org.postgresql:postgresql`. **Zero configuration** is needed for the default `public` schema. Available properties under `pgway.*`:

| Property | Default | Purpose |
|---|---|---|
| `pgway.enabled` | `true` | Disable in tests without a database (also backs off automatically when no `DataSource` bean exists) |
| `pgway.schema` | `public` | Target PostgreSQL schema |
| `pgway.lock-timeout` | `30s` | Timeout acquiring Pgway's session advisory lock |
| `pgway.migration-lock-timeout` | unset | Default `lock_timeout` per migration |
| `pgway.migration-statement-timeout` | unset | Default `statement_timeout` per migration |

### Programmatic (`no.beint.pgway:core`)

```java
Pgway pgway = Pgway.configure()
    .dataSource(dataSource)
    .directory(migrationsDir)
    .create();

MigrationResult result = pgway.migrate();
```

## Building and testing

Requires JDK 27 and PostgreSQL 19+ server binaries (`postgres`, `initdb`, `pg_ctl`).

```shell
./gradlew clean check releaseBundle
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
