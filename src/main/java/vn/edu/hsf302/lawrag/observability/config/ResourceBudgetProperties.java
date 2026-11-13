package vn.edu.hsf302.lawrag.observability.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Daily resource caps and kill switches from decisions.md §6.3. These are
 * resource budgets, not a promise about provider billing (OD-02 is still open).
 */
@Validated
@ConfigurationProperties(prefix = "app.budget")
public record ResourceBudgetProperties(
        boolean crawlerEnabled,
        boolean geminiEnabled,
        @Min(0) int maxGeminiCallsPerDay,
        @Min(0) int maxEmbeddingCallsPerDay,
        @Min(0) int maxWebJobsPerDay,
        @Min(1) int maxTokensPerContext,
        @Min(1) int maxUrlsPerJob,
        @Min(1) int maxConcurrentWebJobs) {
}
