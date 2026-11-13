package vn.edu.hsf302.lawrag.websearch.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Durable worker limits from docs/decisions.md §6.2. Values are configuration,
 * not guarantees: Phase 8 recalibrates them from measured runs.
 */
@Validated
@ConfigurationProperties(prefix = "app.websearch")
public record WebSearchProperties(
        boolean enabled,
        boolean allowAnonymous,
        @NotBlank String provider,
        boolean scheduler,
        @Min(1) @Max(5) int maxUrlsPerJob,
        @Min(1) @Max(2) int maxConcurrentJobs,
        @Min(1) @Max(10) int maxResults,
        @Min(1) @Max(2) int maxAttempts,
        @NotNull Duration jobDeadline,
        @NotNull Duration leaseDuration,
        @NotNull Duration heartbeatInterval,
        @NotNull Duration retryBackoff,
        @NotBlank String workerId,
        @NotBlank String userAgent) {
}
