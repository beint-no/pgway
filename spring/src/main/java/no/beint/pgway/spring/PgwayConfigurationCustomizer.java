package no.beint.pgway.spring;

import no.beint.pgway.PgwayConfig;

/** Callback interface for customizing {@link PgwayConfig.Builder} before {@link no.beint.pgway.Pgway} is created. */
@FunctionalInterface
public interface PgwayConfigurationCustomizer {
    void customize(PgwayConfig.Builder builder);
}
