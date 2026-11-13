package vn.edu.hsf302.lawrag.chat.dto;

import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStage;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;

/**
 * Immutable data required by the state-driven chat fragment. Everything lazy
 * is materialized inside the service transaction; Thymeleaf never touches JPA.
 */
public record ChatMessageView(
        UUID id,
        boolean user,
        MessageStatus status,
        String content,
        String suggestedQuery,
        String questionHash,
        Instant confirmExpiresAt,
        WebSearchJobStatus jobStatus,
        WebSearchJobStage jobStage,
        String jobErrorCode,
        boolean polling,
        boolean canConfirm,
        boolean canCancel,
        boolean canRetry,
        boolean webSearchAllowed,
        boolean hasQuota,
        List<Citation> citations) {

    public ChatMessageView {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public record Citation(
            int order,
            String locator,
            String documentTitle,
            String documentNumber,
            String verbatimText,
            String href) {
    }
}
