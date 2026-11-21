package no.beint.pgway.spring;

import java.util.Collections;
import java.util.Set;
import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;
import org.springframework.boot.sql.init.dependency.DatabaseInitializerDetector;

/**
 * Registers {@link PgwayMigrationInitializer} as a Spring Boot database initializer so JPA, JDBC,
 * and other database-dependent beans automatically initialize after Pgway migrations complete.
 */
public final class PgwayDatabaseInitializerDetector extends AbstractBeansOfTypeDatabaseInitializerDetector {
    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Collections.singleton(PgwayMigrationInitializer.class);
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
