package vn.edu.hsf302.lawrag.websearch.dto;

import java.time.Instant;
import java.util.UUID;

public record ClaimedJob(
        UUID jobId,
        UUID messageId,
        UUID leaseToken,
        int attemptCount,
        Instant deadline) {
}
