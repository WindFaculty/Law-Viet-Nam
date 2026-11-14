package vn.edu.hsf302.lawrag.admin.dto;

import java.util.List;

/** Paginated view of admin audit logs. */
public record AdminAuditPageView(
        List<AuditRow> rows,
        int page,
        int totalPages,
        long totalItems,
        int pageSize) {

    public AdminAuditPageView {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }
}

