package vn.edu.hsf302.lawrag.gemini.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiHttpEmbeddingClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void retriesHttp429AndCallsBatchEmbedContents() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                int currentAttempt = attempts.incrementAndGet();
                capturedPath.set(exchange.getRequestURI().getPath());
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (currentAttempt == 1) {
                    byte[] rateLimited = "{\"error\":{\"code\":429}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, rateLimited.length);
                    exchange.getResponseBody().write(rateLimited);
                    exchange.close();
                    return;
                }

                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode embeddings = response.putArray("embeddings");
                embeddings.add(embeddingNode(0.1));
                embeddings.add(embeddingNode(0.2));
                byte[] bytes = objectMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/";
            GeminiProperties properties = new GeminiProperties(
                    true,
                    "test-api-key",
                    "gemini-3.5-flash-lite",
                    "gemini-embedding-001",
                    "gemini-embedding-001@768:v1",
                    768,
                    Duration.ofSeconds(10));
            GeminiHttpEmbeddingClient client = new GeminiHttpEmbeddingClient(properties, objectMapper, endpoint, 5L);

            List<EmbeddingClient.Embedding> results = client.embedBatch(
                    List.of("Đoạn văn thứ nhất", "Đoạn văn thứ hai"),
                    EmbeddingClient.EmbeddingTaskType.RETRIEVAL_DOCUMENT);

            assertThat(attempts.get()).isEqualTo(2);
            assertThat(capturedPath.get()).isEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents");
            JsonNode requests = objectMapper.readTree(capturedBody.get()).path("requests");
            assertThat(requests.size()).isEqualTo(2);
            assertThat(requests.get(0).path("model").asText()).isEqualTo("models/gemini-embedding-001");
            assertThat(requests.get(0).path("outputDimensionality").asInt()).isEqualTo(768);
            assertThat(results).hasSize(2);
            assertThat(results.get(0).values()[0]).isCloseTo(0.1f, org.assertj.core.data.Offset.offset(1e-5f));
            assertThat(results.get(1).values()[0]).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(1e-5f));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsFastWhenDailyQuotaExhausted() throws IOException {
        AtomicInteger attempts = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                attempts.incrementAndGet();
                byte[] dailyLimit = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric EmbedContentRequestsPerDay\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, dailyLimit.length);
                exchange.getResponseBody().write(dailyLimit);
                exchange.close();
            });
            server.start();

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/";
            GeminiProperties properties = new GeminiProperties(
                    true,
                    "test-api-key",
                    "gemini-3.5-flash-lite",
                    "gemini-embedding-001",
                    "gemini-embedding-001@768:v1",
                    768,
                    Duration.ofSeconds(10));
            GeminiHttpEmbeddingClient client = new GeminiHttpEmbeddingClient(properties, objectMapper, endpoint, 5L);

            assertThatThrownBy(() -> client.embed("Người đi xe máy", EmbeddingClient.EmbeddingTaskType.RETRIEVAL_QUERY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 429");
            assertThat(attempts.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private ObjectNode embeddingNode(double fillValue) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode values = node.putArray("values");
        for (int index = 0; index < 768; index++) {
            values.add(fillValue);
        }
        return node;
    }
}

