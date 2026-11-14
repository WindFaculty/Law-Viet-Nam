package vn.edu.hsf302.lawrag.forum.dto;

import java.util.List;

public record ForumPostPageView(
        List<PostSummaryView> posts,
        int page,
        int totalPages,
        long totalPosts,
        int pageSize
) {
    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }
}

