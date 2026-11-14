package vn.edu.hsf302.lawrag.gemini.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

/**
 * Real Gemini embedding adapter (gemini-embedding-001, 768 dimensions).
 * Enabled only when {@code app.gemini.enabled=true}; tests use the deterministic
 * client and never call the network.
 */
@Component
@ConditionalOnProperty(prefix = "app.gemini", name = "enabled", havingValue = "true")
public class GeminiHttpEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiHttpEmbeddingClient.class);
    private static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int MAX_QUERY_RETRIES = 1;
    private static final int MAX_BATCH_RETRIES = 5;
    private static final long DEFAULT_RETRY_DELAY_MS = 5_000L;

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String endpoint;
    private final long initialRetryDelayMs;

    @Autowired
    public GeminiHttpEmbeddingClient(GeminiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, DEFAULT_ENDPOINT, DEFAULT_RETRY_DELAY_MS);
    }

    GeminiHttpEmbeddingClient(GeminiProperties properties,
                              ObjectMapper objectMapper,
                              String endpoint,
                              long initialRetryDelayMs) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        this.initialRetryDelayMs = Math.max(1L, initialRetryDelayMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
    }

    @Override
    public String spaceId() {
        return properties.embeddingSpaceId();
    }

    @Override
    public Embedding embed(String text, EmbeddingTaskType taskType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embedding text must not be blank");
        }
        ObjectNode requestBody = singleRequestNode(text, taskType);
        HttpResponse<String> response = sendWithRetry(
                endpoint + properties.embeddingModelId() + ":embedContent",
                requestBody,
                MAX_QUERY_RETRIES);
        try {
            JsonNode values = objectMapper.readTree(response.body())
                    .path("embedding")
                    .path("values");
            return toEmbedding(values);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse Gemini embedding response", exception);
        }
    }

    @Override
    public List<Embedding> embedBatch(List<String> texts, EmbeddingTaskType taskType) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (texts.size() == 1) {
            return List.of(embed(texts.get(0), taskType));
        }

        ObjectNode requestBody = objectMapper.createObjectNode();
        ArrayNode requests = requestBody.putArray("requests");
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("embedding text must not be blank");
            }
            requests.add(singleRequestNode(text, taskType));
        }

        HttpResponse<String> response = sendWithRetry(
                endpoint + properties.embeddingModelId() + ":batchEmbedContents",
                requestBody,
                MAX_BATCH_RETRIES);
        try {
            JsonNode embeddingsNode = objectMapper.readTree(response.body()).path("embeddings");
            if (!embeddingsNode.isArray() || embeddingsNode.size() != texts.size()) {
                throw new IllegalStateException("Gemini batch embedding count mismatch: expected "
                        + texts.size() + ", got " + embeddingsNode.size());
            }
            List<Embedding> results = new ArrayList<>(texts.size());
            for (JsonNode entry : embeddingsNode) {
                results.add(toEmbedding(entry.path("values")));
            }
            return results;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse Gemini batch embedding response", exception);
        }
    }

    private ObjectNode singleRequestNode(String text, EmbeddingTaskType taskType) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "models/" + properties.embeddingModelId());
        ObjectNode content = requestBody.putObject("content");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", text);
        requestBody.put("taskType", taskType.name());
        requestBody.put("outputDimensionality", properties.embeddingDimensions());
        return requestBody;
    }

    private HttpResponse<String> sendWithRetry(String url, ObjectNode requestBody, int maxRetries) {
        String serializedBody;
        try {
            serializedBody = objectMapper.writeValueAsString(requestBody);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot build Gemini embedding request", exception);
        }

        long delayMs = initialRetryDelayMs;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("x-goog-api-key", properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(serializedBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Gemini embedding request interrupted", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("Gemini embedding request failed", exception);
            }

            int status = response.statusCode();
            if (status == 200) {
                return response;
            }
            String body = response.body();
            if ((status == 429 || status == 503) && attempt < maxRetries && !isDailyQuotaExhausted(body)) {
                long sleepMs = parseRetryAfterMs(response, delayMs);
                log.warn("Gemini embedding HTTP {} on attempt {}/{}; retrying in {} ms",
                        status, attempt + 1, maxRetries + 1, sleepMs);
                sleepQuietly(sleepMs);
                delayMs = Math.min(delayMs * 2, 60_000L);
                continue;
            }
            log.warn("Gemini embedding HTTP {} failed permanently: {}", status, summarizeErrorBody(body));
            throw new IllegalStateException("Gemini embedding failed with HTTP " + status);
        }
        throw new IllegalStateException("Gemini embedding failed after retries");
    }

    private static boolean isDailyQuotaExhausted(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("perday") || lower.contains("per_day");
    }

    private static String summarizeErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return "(empty)";
        }
        String collapsed = body.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 240 ? collapsed : collapsed.substring(0, 240) + "...";
    }

    private static long parseRetryAfterMs(HttpResponse<String> response, long fallbackMs) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Math.max(fallbackMs, Long.parseLong(value.trim()) * 1_000L);
                    } catch (NumberFormatException ignored) {
                        return fallbackMs;
                    }
                })
                .orElse(fallbackMs);
    }

    private static void sleepQuietly(long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini embedding retry interrupted", exception);
        }
    }

    private Embedding toEmbedding(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("Gemini embedding response has no values");
        }
        float[] embedding = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            embedding[index] = (float) values.get(index).asDouble();
        }
        if (embedding.length != properties.embeddingDimensions()) {
            throw new IllegalStateException("Gemini returned " + embedding.length
                    + " dimensions, expected " + properties.embeddingDimensions());
        }
        return new Embedding(properties.embeddingSpaceId(), embedding.length, embedding);
    }
}
