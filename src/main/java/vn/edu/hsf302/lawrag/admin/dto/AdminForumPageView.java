package vn.edu.hsf302.lawrag.admin.dto;

import java.util.List;

/** Paginated forum moderation listing plus request/status summary counts. */
public record AdminForumPageView(
        List<AdminForumPostView> posts,
        int page,
        int totalPages,
        long totalItems,
        int pageSize,
        long publishedCount,
        long openCount,
        long answeredCount,
        long resolvedCount,
        long removedCount) {

    public AdminForumPageView {
        posts = posts == null ? List.of() : List.copyOf(posts);
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }
}

