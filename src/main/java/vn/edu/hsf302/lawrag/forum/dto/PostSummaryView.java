package vn.edu.hsf302.lawrag.forum.dto;

import java.util.UUID;

public record PostSummaryView(
        UUID id,
        String title,
        String excerpt,
        String categoryCode,
        String categoryLabel,
        String authorDisplayName,
        String authorUsername,
        boolean pinned,
        long commentCount,
        String createdAtDisplay) {
}
