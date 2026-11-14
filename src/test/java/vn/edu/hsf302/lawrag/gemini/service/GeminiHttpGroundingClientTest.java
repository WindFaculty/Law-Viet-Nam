package vn.edu.hsf302.lawrag.gemini.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiHttpGroundingClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsSchemaWithAnswerToConfiguredModelAndParsesRagAnswer() throws IOException {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String articleId = UUID.randomUUID().toString();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                capturedPath.set(exchange.getRequestURI().getPath());
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String modelJson = """
                        {
                          "decision": "ANSWER",
                          "selectedArticleIds": ["%s"],
                          "reasonCode": "SUFFICIENT",
                          "answer": "Người điều khiển xe mô tô, xe gắn máy bắt buộc phải đội mũ bảo hiểm theo khoản 2 Điều 33 Luật 36/2024/QH15."
                        }
                        """.formatted(articleId);
                String apiResponse = objectMapper.writeValueAsString(
                        objectMapper.createObjectNode().set("candidates",
                                objectMapper.createArrayNode().add(
                                        objectMapper.createObjectNode().set("content",
                                                objectMapper.createObjectNode().set("parts",
                                                        objectMapper.createArrayNode().add(
                                                                objectMapper.createObjectNode().put("text", modelJson)))))));
                byte[] bytes = apiResponse.getBytes(StandardCharsets.UTF_8);
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
            GeminiHttpGroundingClient client = new GeminiHttpGroundingClient(properties, objectMapper, endpoint);

            GroundingClient.Request request = new GroundingClient.Request(
                    "Người đi xe máy có phải đội mũ bảo hiểm không?",
                    List.of(new GroundingClient.ContextArticle(
                            1, articleId, UUID.randomUUID().toString(),
                            "36/2024/QH15", "Luật Trật tự, an toàn giao thông đường bộ",
                            "dieu-33-khoan-2", "KHOAN", "Khoản 2 Điều 33", "Mũ bảo hiểm",
                            LocalDate.of(2025, 1, 1), null,
                            "Người điều khiển, người được chở trên xe mô tô hai bánh phải đội mũ bảo hiểm.")),
                    LocalDate.of(2026, 11, 14),
                    List.of("xe_mo_to"),
                    List.of("mu_bao_hiem"),
                    List.of(),
                    List.of(),
                    List.of(),
                    "Prompt đọc RAG",
                    "context-grader-v1");

            GroundingClient.Decision decision = client.evaluate(request);

            assertThat(capturedPath.get()).isEqualTo("/v1beta/models/gemini-3.5-flash-lite:generateContent");
            JsonNode sentJson = objectMapper.readTree(capturedBody.get());
            JsonNode schemaProps = sentJson.path("generationConfig").path("responseSchema").path("properties");
            assertThat(schemaProps.has("answer")).isTrue();
            assertThat(sentJson.has("tools")).isFalse();

            assertThat(decision.outcome()).isEqualTo(GroundingClient.Outcome.ANSWER);
            assertThat(decision.reasonCode()).isEqualTo(GroundingClient.ReasonCode.SUFFICIENT);
            assertThat(decision.selectedArticleIds()).containsExactly(articleId);
            assertThat(decision.answer()).contains("khoản 2 Điều 33 Luật 36/2024/QH15");
            assertThat(decision.modelId()).isEqualTo("gemini-3.5-flash-lite");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsAnswerChunksViaSseAndParsesFinalDecision() throws IOException {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        String articleId = UUID.randomUUID().toString();

        List<String> jsonParts = List.of(
                "{\"decision\":\"ANSWER\",\"selectedArticleIds\":[\"" + articleId + "\"],\"reasonCode\":\"SUFFICIENT\",\"answer\":\"Người đi ",
                "xe máy phải đội mũ bảo hiểm.\\",
                "nCăn cứ: \\\"Khoản 2 Điều 33\\\" Luật 36/2024/QH15.\"}"
        );

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                capturedPath.set(exchange.getRequestURI().getPath());
                capturedQuery.set(exchange.getRequestURI().getQuery());
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String textPart : jsonParts) {
                        String chunkJson = objectMapper.writeValueAsString(
                                objectMapper.createObjectNode().set("candidates",
                                        objectMapper.createArrayNode().add(
                                                objectMapper.createObjectNode().set("content",
                                                        objectMapper.createObjectNode().set("parts",
                                                                objectMapper.createArrayNode().add(
                                                                        objectMapper.createObjectNode().put("text", textPart)))))));
                        String sseFrame = "data: " + chunkJson + "\n\n";
                        out.write(sseFrame.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
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
            GeminiHttpGroundingClient client = new GeminiHttpGroundingClient(properties, objectMapper, endpoint);

            GroundingClient.Request request = new GroundingClient.Request(
                    "Người đi xe máy có phải đội mũ bảo hiểm không?",
                    List.of(new GroundingClient.ContextArticle(
                            1, articleId, UUID.randomUUID().toString(),
                            "36/2024/QH15", "Luật Trật tự, an toàn giao thông đường bộ",
                            "dieu-33-khoan-2", "KHOAN", "Khoản 2 Điều 33", "Mũ bảo hiểm",
                            LocalDate.of(2025, 1, 1), null,
                            "Người điều khiển, người được chở trên xe mô tô hai bánh phải đội mũ bảo hiểm.")),
                    LocalDate.of(2026, 11, 14),
                    List.of("xe_mo_to"),
                    List.of("mu_bao_hiem"),
                    List.of(),
                    List.of(),
                    List.of(),
                    "Prompt đọc RAG",
                    "context-grader-v1");

            List<String> deltas = new ArrayList<>();
            GroundingClient.Decision decision = client.evaluateStream(request, deltas::add);

            assertThat(capturedPath.get()).isEqualTo("/v1beta/models/gemini-3.5-flash-lite:streamGenerateContent");
            assertThat(capturedQuery.get()).isEqualTo("alt=sse");
            assertThat(deltas).hasSizeGreaterThanOrEqualTo(2);
            assertThat(String.join("", deltas))
                    .isEqualTo("Người đi xe máy phải đội mũ bảo hiểm.\nCăn cứ: \"Khoản 2 Điều 33\" Luật 36/2024/QH15.");
            assertThat(decision.outcome()).isEqualTo(GroundingClient.Outcome.ANSWER);
            assertThat(decision.reasonCode()).isEqualTo(GroundingClient.ReasonCode.SUFFICIENT);
            assertThat(decision.selectedArticleIds()).containsExactly(articleId);
            assertThat(decision.answer())
                    .isEqualTo("Người đi xe máy phải đội mũ bảo hiểm.\nCăn cứ: \"Khoản 2 Điều 33\" Luật 36/2024/QH15.");
        } finally {
            server.stop(0);
        }
    }
}

