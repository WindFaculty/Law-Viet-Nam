package vn.edu.hsf302.lawrag.forum.dto;

import java.util.List;
import java.util.UUID;

public record CommentView(
        UUID id,
        UUID parentCommentId,
        String replyToDisplayName,
        String content,
        String authorDisplayName,
        String authorUsername,
        boolean authorAdmin,
        boolean officialAdminReply,
        boolean acceptedAnswer,
        String createdAtDisplay,
        String editedAtDisplay,
        boolean canEdit,
        boolean canDelete,
        boolean canAccept,
        boolean canReply,
        List<CommentView> replies) {

    public CommentView {
        replies = replies == null ? List.of() : List.copyOf(replies);
    }
}
