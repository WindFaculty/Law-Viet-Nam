package vn.edu.hsf302.lawrag.gemini.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.gemini")
public record GeminiProperties(
        boolean enabled,
        String apiKey,
        @NotBlank String modelId,
        @NotBlank String embeddingModelId,
        @NotBlank String embeddingSpaceId,
        @Min(1) int embeddingDimensions,
        @NotNull Duration timeout) {

    public static final int SUPPORTED_EMBEDDING_DIMENSIONS = 768;

    @AssertTrue(message = "app.gemini.embedding-dimensions must be 768; the database schema uses vector(768)")
    public boolean isEmbeddingDimensionsSupported() {
        return embeddingDimensions == SUPPORTED_EMBEDDING_DIMENSIONS;
    }

    @AssertTrue(message = "app.gemini.api-key (GOOGLE_API_KEY) is required when app.gemini.enabled=true")
    public boolean isApiKeyPresentWhenEnabled() {
        return !enabled || (apiKey != null && !apiKey.isBlank());
    }

    /** Never let an accidental log statement print the API key. */
    @Override
    public String toString() {
        return "GeminiProperties[enabled=" + enabled
                + ", modelId=" + modelId
                + ", embeddingModelId=" + embeddingModelId
                + ", embeddingSpaceId=" + embeddingSpaceId
                + ", embeddingDimensions=" + embeddingDimensions
                + ", timeout=" + timeout
                + ", apiKey=***]";
    }
}
