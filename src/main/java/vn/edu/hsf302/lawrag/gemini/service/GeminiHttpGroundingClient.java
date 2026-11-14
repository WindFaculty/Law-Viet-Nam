package vn.edu.hsf302.lawrag.gemini.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
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

    private static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int MAX_OUTPUT_TOKENS = 2_048;

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String endpoint;

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiHttpGroundingClient(GeminiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, DEFAULT_ENDPOINT);
    }

    GeminiHttpGroundingClient(GeminiProperties properties, ObjectMapper objectMapper, String endpoint) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
    }

    @Override
    public Decision evaluate(Request request) {
        HttpRequest httpRequest = buildRequest(request, false);
        HttpResponse<String> response = sendWithOneRetry(httpRequest);
        return parse(response.body(), request);
    }

    @Override
    public Decision evaluateStream(Request request, Consumer<String> onAnswerDelta) {
        HttpRequest httpRequest = buildRequest(request, true);
        return sendStreamWithOneRetry(httpRequest, request, onAnswerDelta);
    }

    private HttpRequest buildRequest(Request request, boolean streaming) {
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

        String suffix = streaming ? ":streamGenerateContent?alt=sse" : ":generateContent";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + properties.modelId() + suffix))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("x-goog-api-key", properties.apiKey());
            if (streaming) {
                builder.header("Accept", "text/event-stream");
            }
            return builder
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

    private Decision sendStreamWithOneRetry(HttpRequest httpRequest,
                                            Request request,
                                            Consumer<String> onAnswerDelta) {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean emittedAnyDelta = false;
            try {
                HttpResponse<Stream<String>> response = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofLines());
                try (Stream<String> lines = response.body()) {
                    if (response.statusCode() != 200) {
                        boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                        if (retryable && attempt == 0) {
                            continue;
                        }
                        throw new GroundingException("Gemini grading failed with HTTP " + response.statusCode());
                    }

                    StringBuilder accumulatedText = new StringBuilder();
                    StringBuilder eventPayload = new StringBuilder();
                    StringBuilder nonSseBuffer = new StringBuilder();
                    AnswerFieldStreamExtractor extractor = new AnswerFieldStreamExtractor();
                    boolean sawSseData = false;

                    Iterator<String> iterator = lines.iterator();
                    while (iterator.hasNext()) {
                        String line = iterator.next();
                        if (line.startsWith(":")) {
                            continue;
                        }
                        if (line.startsWith("data:")) {
                            sawSseData = true;
                            String dataPart = line.substring(5).stripLeading();
                            if (!eventPayload.isEmpty()) {
                                eventPayload.append('\n');
                            }
                            eventPayload.append(dataPart);
                            continue;
                        }
                        if (line.isBlank()) {
                            if (!eventPayload.isEmpty()) {
                                boolean emitted = processSseChunk(
                                        eventPayload.toString(), accumulatedText, extractor, onAnswerDelta);
                                emittedAnyDelta = emittedAnyDelta || emitted;
                                eventPayload.setLength(0);
                            }
                            continue;
                        }
                        if (!sawSseData && !line.startsWith("event:")
                                && !line.startsWith("id:") && !line.startsWith("retry:")) {
                            nonSseBuffer.append(line).append('\n');
                        }
                    }

                    if (!eventPayload.isEmpty()) {
                        boolean emitted = processSseChunk(
                                eventPayload.toString(), accumulatedText, extractor, onAnswerDelta);
                        emittedAnyDelta = emittedAnyDelta || emitted;
                    }

                    if (!sawSseData && !nonSseBuffer.isEmpty()) {
                        Decision decision = parse(nonSseBuffer.toString(), request);
                        if (decision.answer() != null && !decision.answer().isBlank() && onAnswerDelta != null) {
                            onAnswerDelta.accept(decision.answer());
                        }
                        return decision;
                    }
                    if (accumulatedText.isEmpty() || accumulatedText.toString().isBlank()) {
                        throw new GroundingException("Gemini grading response has no JSON text");
                    }
                    return parseDecisionText(accumulatedText.toString(), request);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GroundingException("Gemini grading request interrupted", exception);
            } catch (IOException | UncheckedIOException exception) {
                if (attempt == 0 && !emittedAnyDelta) {
                    continue;
                }
                throw new GroundingException("Gemini grading request failed", exception);
            }
        }
        throw new GroundingException("Gemini grading request failed");
    }

    private boolean processSseChunk(String payload,
                                    StringBuilder accumulatedText,
                                    AnswerFieldStreamExtractor extractor,
                                    Consumer<String> onAnswerDelta) {
        String trimmed = payload.strip();
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            if (!parts.isArray()) {
                return false;
            }
            boolean appended = false;
            for (JsonNode part : parts) {
                JsonNode textNode = part.path("text");
                if (textNode.isTextual()) {
                    accumulatedText.append(textNode.asText());
                    appended = true;
                }
            }
            if (!appended) {
                return false;
            }
            String delta = extractor.extractAvailable(accumulatedText);
            if (!delta.isEmpty() && onAnswerDelta != null) {
                onAnswerDelta.accept(delta);
                return true;
            }
            return false;
        } catch (IOException exception) {
            throw new GroundingException("cannot parse Gemini SSE chunk", exception);
        }
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
            return parseDecisionText(text.asText(), request);
        } catch (GroundingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new GroundingException("cannot parse Gemini grading response", exception);
        } catch (RuntimeException exception) {
            throw new GroundingException("invalid Gemini grading response", exception);
        }
    }

    private Decision parseDecisionText(String rawModelText, Request request) {
        try {
            String jsonText = stripCodeFence(rawModelText);
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
                    optionalText(decision, "answer"),
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
        properties.putObject("answer").put("type", "STRING");

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

    static final class AnswerFieldStreamExtractor {

        private enum State {
            SCANNING,
            EXPECT_COLON,
            EXPECT_VALUE,
            IN_ANSWER_VALUE,
            DONE
        }

        private State state = State.SCANNING;
        private int cursor = 0;
        private int depth = 0;
        private boolean inString = false;
        private boolean escaped = false;
        private boolean capturingKey = false;
        private final StringBuilder keyBuffer = new StringBuilder();

        String extractAvailable(CharSequence json) {
            StringBuilder delta = new StringBuilder();
            while (cursor < json.length() && state != State.DONE) {
                switch (state) {
                    case SCANNING -> scanNext(json);
                    case EXPECT_COLON -> expectColon(json);
                    case EXPECT_VALUE -> expectValue(json);
                    case IN_ANSWER_VALUE -> {
                        if (!decodeAnswerChar(json, delta)) {
                            return delta.toString();
                        }
                    }
                    case DONE -> {
                        return delta.toString();
                    }
                }
            }
            return delta.toString();
        }

        private void scanNext(CharSequence json) {
            char c = json.charAt(cursor);
            if (inString) {
                if (escaped) {
                    if (capturingKey) {
                        keyBuffer.append(c);
                    }
                    escaped = false;
                    cursor++;
                    return;
                }
                if (c == '\\') {
                    escaped = true;
                    cursor++;
                    return;
                }
                if (c == '"') {
                    inString = false;
                    cursor++;
                    if (capturingKey) {
                        capturingKey = false;
                        if ("answer".equals(keyBuffer.toString())) {
                            state = State.EXPECT_COLON;
                        }
                        keyBuffer.setLength(0);
                    }
                    return;
                }
                if (capturingKey) {
                    keyBuffer.append(c);
                }
                cursor++;
                return;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == '"') {
                inString = true;
                if (depth == 1) {
                    capturingKey = true;
                    keyBuffer.setLength(0);
                }
            }
            cursor++;
        }

        private void expectColon(CharSequence json) {
            char c = json.charAt(cursor);
            if (Character.isWhitespace(c)) {
                cursor++;
                return;
            }
            if (c == ':') {
                cursor++;
                state = State.EXPECT_VALUE;
                return;
            }
            state = State.SCANNING;
        }

        private void expectValue(CharSequence json) {
            char c = json.charAt(cursor);
            if (Character.isWhitespace(c)) {
                cursor++;
                return;
            }
            if (c == '"') {
                cursor++;
                state = State.IN_ANSWER_VALUE;
                return;
            }
            state = State.DONE;
        }

        private boolean decodeAnswerChar(CharSequence json, StringBuilder delta) {
            char c = json.charAt(cursor);
            if (c == '\\') {
                if (cursor + 1 >= json.length()) {
                    return false;
                }
                char esc = json.charAt(cursor + 1);
                if (esc == 'u') {
                    if (cursor + 6 > json.length()) {
                        return false;
                    }
                    String hex = json.subSequence(cursor + 2, cursor + 6).toString();
                    try {
                        delta.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException ignored) {
                        delta.append("\\u").append(hex);
                    }
                    cursor += 6;
                    return true;
                }
                delta.append(switch (esc) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> esc;
                });
                cursor += 2;
                return true;
            }
            if (c == '"') {
                cursor++;
                state = State.DONE;
                return true;
            }
            delta.append(c);
            cursor++;
            return true;
        }
    }
}
