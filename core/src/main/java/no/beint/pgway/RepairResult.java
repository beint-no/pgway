package no.beint.pgway;

import java.util.List;
import java.util.Objects;

/** Result of {@link Pgway#repair(boolean)}. */
public record RepairResult(
        int failedRowsRemoved,
        int checksumsRealigned,
        List<String> details) {

    public RepairResult {
        details = List.copyOf(Objects.requireNonNull(details, "details must not be null"));
    }
}
