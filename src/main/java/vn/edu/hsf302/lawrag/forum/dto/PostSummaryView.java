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
        boolean authorAdmin,
        boolean pinned,
        String statusCode,
        String statusLabel,
        String requestStatusCode,
        String requestStatusLabel,
        boolean hasOfficialReply,
        long commentCount,
        String createdAtDisplay) {
}
