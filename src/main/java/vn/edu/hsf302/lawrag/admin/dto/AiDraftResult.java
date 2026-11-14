package vn.edu.hsf302.lawrag.admin.dto;

/** Result returned to the admin forum moderation UI when requesting a RAG draft reply. */
public record AiDraftResult(
        boolean success,
        String draft,
        String message) {
}

