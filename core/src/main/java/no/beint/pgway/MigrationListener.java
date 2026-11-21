package no.beint.pgway;

/** Lifecycle callback for observing migration execution. */
public interface MigrationListener {
    MigrationListener NOOP = new MigrationListener() {};

    default void beforeMigration(MigrationScript script) {}

    default void afterMigration(AppliedMigration applied) {}

    default void onMigrationFailed(MigrationScript script, Throwable error) {}
}
