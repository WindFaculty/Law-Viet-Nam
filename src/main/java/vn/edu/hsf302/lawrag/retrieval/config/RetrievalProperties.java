package vn.edu.hsf302.lawrag.retrieval.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Initial retrieval calibration from docs/decisions.md §6.3. The cosine
 * threshold is deliberately applied only to the vector branch; RRF and FTS
 * scores use a different scale.
 */
@Validated
@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalProperties(
        @Min(1) int topK,
        @Min(1) int rrfK,
        @Min(1) int contextLimit,
        @DecimalMin("0.0") @DecimalMax("1.0") double cosineThreshold,
        @DecimalMin("0.0") @DecimalMax("1.0") double strongLexicalMinCoverage,
        @Min(1) int contextTokenBudget) {
}
