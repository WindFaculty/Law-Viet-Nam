package vn.edu.hsf302.lawrag.admin.dto;

import java.util.Locale;

/** Aggregated totals across filtered {@link CostUsageLogView} rows. */
public record CostUsageSummaryView(
        long totalEmbeddingCalls,
        long totalEmbeddingInputChars,
        long totalEmbeddingCacheHits,
        long totalGroundingCalls,
        long totalInputTokens,
        long totalOutputTokens,
        long totalWebSearchCalls,
        long totalFetchedUrls,
        long totalDownloadedBytes) {

    public long totalTokens() {
        return totalInputTokens + totalOutputTokens;
    }

    public String downloadedSizeDisplay() {
        return formatBytes(totalDownloadedBytes);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }
}

