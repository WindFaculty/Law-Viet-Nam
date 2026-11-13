package vn.edu.hsf302.lawrag.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminJobView(
        UUID id,
        UUID messageId,
        UUID sessionId,
        String status,
        String stage,
        int attemptCount,
        String leaseOwner,
        Instant leaseExpiresAt,
        String errorCode,
        Instant createdAt,
        Instant deadline,
        String suggestedQuery,
        List<AdminCandidateView> candidates) {
}
