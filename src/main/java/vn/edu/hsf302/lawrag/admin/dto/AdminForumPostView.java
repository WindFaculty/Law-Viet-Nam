package vn.edu.hsf302.lawrag.admin.dto;

import java.util.UUID;

/** Admin forum moderation row including visibility, request tracking, and reply counts. */
public record AdminForumPostView(
        UUID id,
        String title,
        String excerpt,
        String fullContent,
        String categoryCode,
        String categoryLabel,
        String authorDisplayName,
        String authorUsername,
        boolean authorAdmin,
        boolean pinned,
        String statusCode,
        String statusLabel,
        String removedReason,
        String removedBy,
        String requestStatusCode,
        String requestStatusLabel,
        long commentCount,
        long officialReplyCount,
        String createdAtDisplay) {
}

