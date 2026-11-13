package vn.edu.hsf302.lawrag.admin.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditRow(
        String actor,
        String action,
        String entityType,
        UUID entityId,
        String details,
        Instant createdAt) {
}
