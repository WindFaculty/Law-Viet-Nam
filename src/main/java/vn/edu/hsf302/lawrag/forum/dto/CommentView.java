package vn.edu.hsf302.lawrag.forum.dto;

import java.util.UUID;

public record CommentView(
        UUID id,
        String content,
        String authorDisplayName,
        String authorUsername,
        String createdAtDisplay,
        boolean canDelete) {
}
