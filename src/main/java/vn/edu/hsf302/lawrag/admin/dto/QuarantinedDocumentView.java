package vn.edu.hsf302.lawrag.admin.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record QuarantinedDocumentView(
        UUID id,
        String title,
        String docNumber,
        String issuingBody,
        LocalDate issueDate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String effectiveStatus,
        String sourceUrl,
        String verificationNotes,
        Instant createdAt,
        String contentSha256,
        int articleCount) {
}
