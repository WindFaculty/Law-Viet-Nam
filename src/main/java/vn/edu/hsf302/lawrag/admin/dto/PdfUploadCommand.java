package vn.edu.hsf302.lawrag.admin.dto;

/** Metadata supplied by the admin when uploading a PDF document for RAG. */
public record PdfUploadCommand(
        String title,
        String docNumber,
        String issuingBody,
        String issueDate,
        String effectiveFrom,
        String effectiveTo,
        String effectiveStatus,
        String sourceUrl,
        boolean runEmbedding) {
}

