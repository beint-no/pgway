# Contributing to Pgway

Pgway targets the current JDK (JDK 27) and PostgreSQL 19 exclusively, without backwards-compatibility shims for older versions or other database engines.

## Requirements

- JDK 27
- PostgreSQL 19 server binaries (`postgres`, `initdb`, `pg_ctl`)

## Local verification

```shell
./gradlew clean check releaseBundle
```

All compiler warnings are treated as errors (`-Xlint:all,-processing -Werror`). Pull requests must pass Linux and macOS CI against PostgreSQL 19.
