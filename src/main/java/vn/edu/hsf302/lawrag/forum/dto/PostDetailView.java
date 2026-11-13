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
        boolean pinned,
        String createdAtDisplay,
        boolean canDelete,
        boolean canPin,
        List<CommentView> comments) {
}
