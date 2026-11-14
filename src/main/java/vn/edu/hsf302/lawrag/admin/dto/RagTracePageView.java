package vn.edu.hsf302.lawrag.admin.dto;

import java.util.List;

/** Paginated list of RAG traces. */
public record RagTracePageView(
        List<RagTraceLogView> rows,
        int page,
        int totalPages,
        long totalItems,
        int pageSize) {

    public RagTracePageView {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }
}

