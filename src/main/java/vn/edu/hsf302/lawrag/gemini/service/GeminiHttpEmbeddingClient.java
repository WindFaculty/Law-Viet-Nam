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
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

/**
 * Real Gemini embedding adapter (gemini-embedding-001, 768 dimensions).
 * Enabled only when {@code app.gemini.enabled=true}; tests use the deterministic
 * client and never call the network.
 */
@Component
@ConditionalOnProperty(prefix = "app.gemini", name = "enabled", havingValue = "true")
public class GeminiHttpEmbeddingClient implements EmbeddingClient {

    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiHttpEmbeddingClient(GeminiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "models/" + properties.embeddingModelId());
        ObjectNode content = requestBody.putObject("content");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", text);
        requestBody.put("taskType", taskType.name());
        requestBody.put("outputDimensionality", properties.embeddingDimensions());

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + properties.embeddingModelId() + ":embedContent"))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("x-goog-api-key", properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot build Gemini embedding request", exception);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini embedding request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Gemini embedding request failed", exception);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gemini embedding failed with HTTP " + response.statusCode());
        }

        try {
            JsonNode values = objectMapper.readTree(response.body())
                    .path("embedding")
                    .path("values");
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
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse Gemini embedding response", exception);
        }
    }
}
