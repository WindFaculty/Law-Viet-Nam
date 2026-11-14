package vn.edu.hsf302.lawrag.admin.dto;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Daily API/token usage row from {@code cost_usage_daily}. */
public record CostUsageLogView(
        LocalDate usageDate,
        String category,
        int embeddingCalls,
        long embeddingInputChars,
        int embeddingCacheHits,
        int groundingCalls,
        long groundingInputTokens,
        long groundingOutputTokens,
        int webSearchCalls,
        int fetchedUrls,
        long downloadedBytes) {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public String usageDateDisplay() {
        return usageDate == null ? "" : DISPLAY_DATE.format(usageDate);
    }

    public long totalTokens() {
        return groundingInputTokens + groundingOutputTokens;
    }

    public String downloadedSizeDisplay() {
        return CostUsageSummaryView.formatBytes(downloadedBytes);
    }

    public String categoryLabel() {
        if (category == null) {
            return "";
        }
        return switch (category) {
            case "LOCAL_ANSWERED" -> "Trả lời nội bộ";
            case "LOCAL_AWAITING_CONFIRM" -> "Chờ xác nhận Web";
            case "LOCAL_REFUSED" -> "Từ chối / Ngoài phạm vi";
            case "WEB_ANSWERED" -> "Trả lời nguồn Web";
            case "WEB_FAILED" -> "Lỗi tìm Web";
            case "FORUM_AI_DRAFT" -> "Nháp AI diễn đàn";
            default -> category;
        };
    }
}

