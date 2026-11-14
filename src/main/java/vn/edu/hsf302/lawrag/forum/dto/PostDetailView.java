package vn.edu.hsf302.lawrag.forum.dto;

import java.util.List;
import java.util.UUID;

public record PostDetailView(
        UUID id,
        String title,
        String content,
        String categoryCode,
        String categoryLabel,
        String authorDisplayName,
        String authorUsername,
        boolean authorAdmin,
        boolean pinned,
        String statusCode,
        String statusLabel,
        String removedReason,
        String requestStatusCode,
        String requestStatusLabel,
        String createdAtDisplay,
        String editedAtDisplay,
        boolean canEdit,
        boolean canDelete,
        boolean canPin,
        int totalCommentCount,
        List<CommentView> comments) {

    public PostDetailView {
        comments = comments == null ? List.of() : List.copyOf(comments);
    }
}
