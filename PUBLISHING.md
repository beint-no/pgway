# Publishing

Pgway publishes `no.beint.pgway:core` and `no.beint.pgway:spring` to Maven Central and attaches a
reproducible Maven repository bundle (`pgway-<version>-maven.zip`) with SHA-256 checksum to each
GitHub Release.

## Release procedure

1. Set `pgwayVersion` in `gradle.properties` and merge to `main`.
2. Verify `CI=true ./gradlew clean check releaseBundle` passes on Linux and macOS against PostgreSQL 19.
3. Tag the release commit `v<version>` and push the tag.
4. Create the GitHub Release with `pgway-<version>-maven.zip` and its `.sha256` checksum.
5. Publish signed artifacts to Maven Central via the `Publish to Maven Central` workflow or
   `./gradlew publishAndReleaseToMavenCentral`.
