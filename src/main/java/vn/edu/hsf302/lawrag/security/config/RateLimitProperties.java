package vn.edu.hsf302.lawrag.security.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Locked anti-spam limits from decisions.md §2.2. */
@Validated
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(
        boolean enabled,
        @NotNull Duration questionMinInterval,
        @Min(1) int questionsPerIpPerHour,
        @Min(1) int confirmRetryPerSubjectPerMinute,
        @Min(1) int pollsPerSessionPerMinute) {
}
