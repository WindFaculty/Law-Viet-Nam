package vn.edu.hsf302.lawrag.admin.dto;

import vn.edu.hsf302.lawrag.config.TimeConfig;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public record AuditRow(
        String actor,
        String action,
        String entityType,
        UUID entityId,
        String details,
        Instant createdAt) {

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(TimeConfig.BUSINESS_ZONE);

    public String createdAtDisplay() {
        return createdAt == null ? "" : DISPLAY_TIME.format(createdAt);
    }
}
