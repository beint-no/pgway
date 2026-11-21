package no.beint.pgway.spring;

import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import no.beint.pgway.Pgway;
import org.springframework.boot.jdbc.SchemaManagement;
import org.springframework.boot.jdbc.SchemaManagementProvider;

/** Reports {@link SchemaManagement#MANAGED} for any {@link DataSource} managed by a {@link Pgway} bean. */
public final class PgwaySchemaManagementProvider implements SchemaManagementProvider {
    private final Iterable<Pgway> pgwayInstances;

    public PgwaySchemaManagementProvider(Iterable<Pgway> pgwayInstances) {
        this.pgwayInstances = pgwayInstances;
    }

    @Override
    public SchemaManagement getSchemaManagement(DataSource dataSource) {
        return StreamSupport.stream(pgwayInstances.spliterator(), false)
            .map(p -> p.config().dataSource())
            .filter(dataSource::equals)
            .findFirst()
            .map(ignored -> SchemaManagement.MANAGED)
            .orElse(SchemaManagement.UNMANAGED);
    }
}
