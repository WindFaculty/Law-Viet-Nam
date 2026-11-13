package vn.edu.hsf302.lawrag.ingestion.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Source-policy limits from docs/source-policy.md §3.3 and §5.7. */
@Validated
@ConfigurationProperties(prefix = "app.ingestion")
public record IngestionProperties(
        boolean enabled,
        @Min(1) long maxBytes,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration totalTimeout,
        @Min(1) int maxRedirects,
        @NotBlank String parserVersion,
        @NotNull List<String> autoPublishHosts,
        @NotBlank String userAgent) {

    public IngestionProperties {
        autoPublishHosts = autoPublishHosts == null ? List.of() : List.copyOf(autoPublishHosts);
    }
}
