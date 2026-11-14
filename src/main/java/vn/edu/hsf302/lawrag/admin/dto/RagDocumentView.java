package vn.edu.hsf302.lawrag.admin.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/** Read model describing a PDF / legal document used in the RAG corpus. */
public record RagDocumentView(
        UUID id,
        String documentKey,
        String title,
        String docNumber,
        String issuingBody,
        LocalDate issueDate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String effectiveStatus,
        String verificationStatus,
        String sourceUrl,
        String storagePath,
        String contentType,
        Long byteSize,
        String contentSha256,
        int articleCount,
        int readyArticleCount,
        int embeddingCount,
        Instant createdAt,
        Instant publishedAt) {

    public String displayFileName() {
        if (storagePath != null && !storagePath.isBlank()) {
            int slash = Math.max(storagePath.lastIndexOf('/'), storagePath.lastIndexOf('\\'));
            return slash >= 0 ? storagePath.substring(slash + 1) : storagePath;
        }
        if (documentKey != null && !documentKey.isBlank()) {
            return switch (documentKey) {
                case "luat-36-2024-qh15" -> "36-2024-qh15__36-2024-qh15.pdf";
                case "nghi-dinh-168-2024-nd-cp" -> "168-2024-nd-cp__168-nd-cp.signed.pdf";
                case "nghi-dinh-238-2026-nd-cp" -> "238-2026-nd-cp__238-ndcp.signed.pdf";
                case "luat-23-2008-qh12" -> "23-2008-qh12__83559_l23qh.doc";
                default -> documentKey.replace(':', '-') + ".pdf";
            };
        }
        return "document.pdf";
    }

    public String formattedSize() {
        if (byteSize == null || byteSize <= 0) {
            return "—";
        }
        if (byteSize < 1024) {
            return byteSize + " B";
        }
        if (byteSize < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", byteSize / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", byteSize / (1024.0 * 1024.0));
    }

    public String shortSha256() {
        if (contentSha256 == null || contentSha256.isBlank()) {
            return "—";
        }
        return contentSha256.length() <= 12 ? contentSha256 : contentSha256.substring(0, 12) + "…";
    }
}

