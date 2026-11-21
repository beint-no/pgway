package no.beint.pgway.spring;

import java.util.Objects;
import no.beint.pgway.AppliedMigration;
import no.beint.pgway.MigrationResult;
import no.beint.pgway.Pgway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.Ordered;

/**
 * {@link InitializingBean} that runs {@link Pgway#migrate()} during Spring Boot startup
 * before dependent database beans (such as JPA {@code EntityManagerFactory} or {@code JdbcTemplate}) initialize.
 */
public final class PgwayMigrationInitializer implements InitializingBean, Ordered {
    private static final Logger log = LoggerFactory.getLogger(PgwayMigrationInitializer.class);

    private final Pgway pgway;

    public PgwayMigrationInitializer(Pgway pgway) {
        this.pgway = Objects.requireNonNull(pgway, "pgway must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        MigrationResult result = pgway.migrate();
        String schema = pgway.config().schema();
        if (result.baselined()) {
            log.info("Pgway baselined schema '{}' at version {}", schema, result.currentVersion());
        }
        if (result.appliedCount() == 0) {
            log.info(
                "Pgway schema '{}' is up to date (version {}, {} ms)",
                schema,
                result.currentVersion() != null ? result.currentVersion() : "none",
                result.totalDuration().toMillis()
            );
        } else {
            for (AppliedMigration applied : result.applied()) {
                log.info(
                    "Pgway applied {} ({}, {} ms)",
                    applied.script(),
                    applied.version() != null ? "V" + applied.version() : "R",
                    applied.executionMs()
                );
            }
            log.info(
                "Pgway applied {} migration(s) to schema '{}' (now at version {}, {} ms total)",
                result.appliedCount(),
                schema,
                result.currentVersion() != null ? result.currentVersion() : "none",
                result.totalDuration().toMillis()
            );
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
