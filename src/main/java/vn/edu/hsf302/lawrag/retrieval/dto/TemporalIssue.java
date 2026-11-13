package vn.edu.hsf302.lawrag.retrieval.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An explicitly requested article that exists and is READY but is not in force
 * on the applicable date. It is never put in the valid context; the grader is
 * told about it so it can return TEMPORAL_UNCERTAINTY instead of guessing.
 */
public record TemporalIssue(
        UUID articleId,
        String articleCode,
        String locator,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String note) {
}
