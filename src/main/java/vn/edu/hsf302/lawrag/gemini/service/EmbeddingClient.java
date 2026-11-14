package vn.edu.hsf302.lawrag.gemini.service;

import java.util.List;

/**
 * Adapter boundary for the embedding provider. Controllers and services depend on
 * this interface so tests and future providers can replace the Gemini implementation.
 */
public interface EmbeddingClient {

    /**
     * Locked vector space for this client. Vectors from different spaces must
     * never be compared.
     */
    String spaceId();

    /**
     * @param text non-blank text already normalized by the caller
     * @param taskType distinguishes stored documents from search queries
     */
    Embedding embed(String text, EmbeddingTaskType taskType);

    /**
     * Embeds a batch of non-blank texts in order. Implementations backed by remote APIs
     * may override this to call a batch endpoint.
     */
    default List<Embedding> embedBatch(List<String> texts, EmbeddingTaskType taskType) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream()
                .map(text -> embed(text, taskType))
                .toList();
    }

    enum EmbeddingTaskType {
        RETRIEVAL_DOCUMENT,
        RETRIEVAL_QUERY
    }

    record Embedding(String spaceId, int dimensions, float[] values) {

        public Embedding {
            if (spaceId == null || spaceId.isBlank()) {
                throw new IllegalArgumentException("embedding spaceId must not be blank");
            }
            if (values == null || values.length != dimensions) {
                throw new IllegalArgumentException("embedding values must match the declared dimensions");
            }
        }
    }
}
