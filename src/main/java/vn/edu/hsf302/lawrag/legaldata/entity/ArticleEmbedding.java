package vn.edu.hsf302.lawrag.legaldata.entity;

import java.util.UUID;

/**
 * A searchable vector row. Vectors are handled through Spring JDBC, not JPA,
 * so the pgvector column stays explicit (see docs/decisions.md §3).
 */
public record ArticleEmbedding(
        UUID id,
        UUID articleId,
        int chunkIndex,
        String modelId,
        String embeddingSpaceId,
        int dimensions,
        String textHash,
        float[] embedding,
        EmbeddingStatus status
) {
}
