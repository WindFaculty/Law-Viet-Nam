package vn.edu.hsf302.lawrag.websearch.dto;

import java.time.Instant;
import java.util.UUID;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStage;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStatus;

public record JobRow(
        UUID id,
        UUID messageId,
        WebSearchJobStatus status,
        WebSearchJobStage stage,
        int attemptCount,
        Instant deadline,
        UUID leaseToken) {
}
