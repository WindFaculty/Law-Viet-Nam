package vn.edu.hsf302.lawrag.gemini.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

class DeterministicEmbeddingClientTest {

    private final DeterministicEmbeddingClient client = new DeterministicEmbeddingClient(
            new GeminiProperties(false, "", "gemini-3.5-flash-lite", "gemini-embedding-001",
                    "gemini-embedding-001@768:v1", 768, Duration.ofSeconds(30)));

    @Test
    void producesStableSevenHundredSixtyEightDimensionalVectors() {
        EmbeddingClient.Embedding first = client.embed("Người đi xe máy phải đội mũ bảo hiểm",
                EmbeddingClient.EmbeddingTaskType.RETRIEVAL_DOCUMENT);
        EmbeddingClient.Embedding second = client.embed("Người đi xe máy phải đội mũ bảo hiểm",
                EmbeddingClient.EmbeddingTaskType.RETRIEVAL_DOCUMENT);
        EmbeddingClient.Embedding other = client.embed("Thủ tục ly hôn",
                EmbeddingClient.EmbeddingTaskType.RETRIEVAL_DOCUMENT);

        assertThat(client.spaceId()).isEqualTo("gemini-embedding-001@768:v1");
        assertThat(first.dimensions()).isEqualTo(768);
        assertThat(first.values()).hasSize(768);
        assertThat(first.values()).containsExactly(second.values());
        assertThat(first.values()).isNotEqualTo(other.values());
    }
}
