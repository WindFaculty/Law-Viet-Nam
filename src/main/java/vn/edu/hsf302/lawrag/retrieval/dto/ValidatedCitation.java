package vn.edu.hsf302.lawrag.retrieval.dto;

import java.time.LocalDate;
import java.util.UUID;

public record ValidatedCitation(
        UUID articleId,
        UUID documentVersionId,
        UUID snapshotId,
        String documentTitle,
        String documentNumber,
        String articleCode,
        String locator,
        String rawText,
        String snapshotContent,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
