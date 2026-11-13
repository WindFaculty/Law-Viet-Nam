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
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

/**
 * Real Gemini structured-output grader. The request explicitly contains only
 * generateContent fields (no tools), so Google Search/URL grounding cannot be
 * activated in this local pipeline. A schema/enum failure raises
 * {@link GroundingException} and can never become a COMPLETED answer.
 */
@Component
@ConditionalOnProperty(prefix = "app.gemini", name = "enabled", havingValue = "true")
public class GeminiHttpGroundingClient implements GroundingClient {

    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int MAX_OUTPUT_TOKENS = 2_048;

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiHttpGroundingClient(GeminiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
    }

    @Override
    public Decision evaluate(Request request) {
        HttpRequest httpRequest = buildRequest(request);
        HttpResponse<String> response = sendWithOneRetry(httpRequest);
        return parse(response.body(), request);
    }

    private HttpRequest buildRequest(Request request) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", request.prompt());

        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("temperature", 0);
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        generationConfig.set("responseSchema", decisionSchema());

        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + properties.modelId() + ":generateContent"))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("x-goog-api-key", properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException exception) {
            throw new GroundingException("cannot build Gemini grading request", exception);
        }
    }

    private HttpResponse<String> sendWithOneRetry(HttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    return response;
                }
                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                if (retryable && attempt == 0) {
                    continue;
                }
                throw new GroundingException("Gemini grading failed with HTTP " + response.statusCode());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GroundingException("Gemini grading request interrupted", exception);
            } catch (IOException exception) {
                if (attempt == 0) {
                    continue;
                }
                throw new GroundingException("Gemini grading request failed", exception);
            }
        }
        throw new GroundingException("Gemini grading request failed");
    }

    private Decision parse(String body, Request request) {
        try {
            JsonNode text = objectMapper.readTree(body)
                    .path("candidates").path(0)
                    .path("content").path("parts").path(0)
                    .path("text");
            if (!text.isTextual() || text.asText().isBlank()) {
                throw new GroundingException("Gemini grading response has no JSON text");
            }
            String jsonText = stripCodeFence(text.asText());
            JsonNode decision = objectMapper.readTree(jsonText);

            String outcomeValue = requiredText(decision, "decision");
            String reasonValue = requiredText(decision, "reasonCode");
            JsonNode selected = decision.path("selectedArticleIds");
            if (!selected.isArray()) {
                throw new GroundingException("selectedArticleIds must be an array");
            }
            List<String> selectedIds = new ArrayList<>(selected.size());
            for (JsonNode id : selected) {
                if (!id.isTextual()) {
                    throw new GroundingException("selectedArticleIds contains a non-string value");
                }
                selectedIds.add(id.asText());
            }
            return new Decision(
                    Outcome.fromModelValue(outcomeValue),
                    selectedIds,
                    ReasonCode.fromModelValue(reasonValue),
                    optionalText(decision, "suggestedSearchQuery"),
                    optionalText(decision, "clarificationQuestion"),
                    properties.modelId(),
                    request.promptVersion());
        } catch (GroundingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new GroundingException("cannot parse Gemini grading response", exception);
        } catch (RuntimeException exception) {
            throw new GroundingException("invalid Gemini grading response", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new GroundingException(field + " must be a non-blank string");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String stripCodeFence(String value) {
        String text = value.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline >= 0) {
            text = text.substring(firstNewline + 1);
        }
        int closing = text.lastIndexOf("```");
        if (closing >= 0) {
            text = text.substring(0, closing);
        }
        return text.strip();
    }

    private ObjectNode decisionSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "OBJECT");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode decision = properties.putObject("decision");
        decision.put("type", "STRING");
        addEnum(decision.putArray("enum"), "ANSWER", "NEED_WEB_SEARCH", "NEED_CLARIFICATION");

        ObjectNode selected = properties.putObject("selectedArticleIds");
        selected.put("type", "ARRAY");
        selected.putObject("items").put("type", "STRING");

        ObjectNode reason = properties.putObject("reasonCode");
        reason.put("type", "STRING");
        addEnum(reason.putArray("enum"),
                "SUFFICIENT", "NO_MATCH", "WRONG_SCOPE", "TEMPORAL_UNCERTAINTY", "MISSING_FACTS");

        properties.putObject("suggestedSearchQuery").put("type", "STRING");
        properties.putObject("clarificationQuestion").put("type", "STRING");

        ArrayNode required = schema.putArray("required");
        required.add("decision");
        required.add("selectedArticleIds");
        required.add("reasonCode");
        return schema;
    }

    private static void addEnum(ArrayNode target, String... values) {
        for (String value : values) {
            target.add(value);
        }
    }
}
