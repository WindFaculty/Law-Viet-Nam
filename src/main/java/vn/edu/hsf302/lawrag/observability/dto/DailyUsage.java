package vn.edu.hsf302.lawrag.observability.dto;

import java.time.LocalDate;

public record DailyUsage(
        LocalDate date,
        long embeddingCalls,
        long groundingCalls,
        long webJobs,
        long webSearchCalls,
        long fetchedUrls,
        long downloadedBytes) {
}
