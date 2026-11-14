package vn.edu.hsf302.lawrag.llm;

import org.junit.jupiter.api.Test;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;
import vn.edu.hsf302.lawrag.llm.entity.ResponseStyle;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.dto.RenderedAnswer;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalResult;
import vn.edu.hsf302.lawrag.retrieval.dto.ValidatedCitation;
import vn.edu.hsf302.lawrag.retrieval.service.AnswerRenderer;
import vn.edu.hsf302.lawrag.retrieval.service.ContextGrader;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPersonaAndPromptTest {

    private final AnswerRenderer renderer = new AnswerRenderer();

    @Test
    void answerRendererProducesDistinctLeadsForPersonasAndPreservesVerbatimQuote() {
        String rawText = "Người điều khiển xe mô tô hai bánh phải đội mũ bảo hiểm có cài quai đúng quy cách.";
        ValidatedCitation citation = new ValidatedCitation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Luật Trật tự, an toàn giao thông đường bộ",
                "36/2024/QH15",
                "dieu-33-khoan-2",
                "Khoản 2 Điều 33",
                rawText,
                "Điều 33\n" + rawText,
                LocalDate.of(2025, 1, 1),
                null);

        LlmPersona professional = persona("CHUYEN_NGHIEP", "Chuyên nghiệp", ResponseStyle.PROFESSIONAL);
        LlmPersona concise = persona("NGAN_GON", "Ngắn gọn", ResponseStyle.CONCISE);
        LlmPersona detailed = persona("DAY_DU", "Đầy đủ", ResponseStyle.DETAILED);
        LlmPersona custom = persona("THAM_KHAO", "Tham khảo", ResponseStyle.CUSTOM);

        RenderedAnswer proAnswer = renderer.render(List.of(citation), null, professional);
        RenderedAnswer conciseAnswer = renderer.render(List.of(citation), null, concise);
        RenderedAnswer detailedAnswer = renderer.render(List.of(citation), null, detailed);
        RenderedAnswer customAnswer = renderer.render(List.of(citation), null, custom);
        RenderedAnswer legacyAnswer = renderer.render(List.of(citation));

        assertThat(legacyAnswer.content()).startsWith("Dẫn: Kho nội bộ có quy định trực tiếp về vấn đề bạn hỏi.");
        assertThat(proAnswer.content()).startsWith("Dẫn (Chuyên nghiệp): Căn cứ quy định hiện hành");
        assertThat(conciseAnswer.content()).startsWith("Kết luận nhanh (Ngắn gọn): Áp dụng");
        assertThat(detailedAnswer.content()).startsWith("Phân tích đầy đủ (Đầy đủ):");
        assertThat(customAnswer.content()).startsWith("Dẫn (Tham khảo): Áp dụng quy định tại");

        for (RenderedAnswer rendered : List.of(proAnswer, conciseAnswer, detailedAnswer, customAnswer)) {
            assertThat(rendered.content()).contains("TRÍCH NGUYÊN VĂN — Khoản 2 Điều 33 — Luật Trật tự, an toàn giao thông đường bộ (36/2024/QH15):");
            assertThat(rendered.content()).contains(rawText);
        }
    }

    @Test
    void contextGraderInjectsPersonaInstructionWhileKeepingPromptVersion() {
        AtomicReference<GroundingClient.Request> captured = new AtomicReference<>();
        GroundingClient client = request -> {
            captured.set(request);
            return new GroundingClient.Decision(
                    GroundingClient.Outcome.NEED_WEB_SEARCH,
                    List.of(),
                    GroundingClient.ReasonCode.NO_MATCH,
                    "mũ bảo hiểm",
                    null,
                    "gemini-test",
                    ContextGrader.PROMPT_VERSION);
        };
        CostBudgetService noopBudget = new CostBudgetService(null, null, Clock.systemUTC()) {
            @Override
            public void requireGeminiCall() {
                // Allow prompt inspection in isolated unit test.
            }
        };

        ContextGrader grader = new ContextGrader(client, noopBudget);
        QueryAnalysis analysis = new QueryAnalysis(
                "Đi xe máy có phải đội mũ bảo hiểm không?",
                "đi xe máy có phải đội mũ bảo hiểm không",
                "mũ bảo hiểm xe máy",
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of("xe máy"),
                List.of("mũ bảo hiểm"),
                LocalDate.of(2026, 1, 1),
                false,
                false,
                false,
                null);
        RetrievalResult emptyRetrieval = new RetrievalResult(
                analysis, List.of(), List.of(), List.of(), List.of(), false, false, false, 0.0);

        LlmPersona concise = persona("NGAN_GON", "Ngắn gọn", ResponseStyle.CONCISE);
        concise.setSystemInstruction("Trả lời cô đọng tối đa 2 câu.");

        grader.grade(analysis, emptyRetrieval, concise);

        GroundingClient.Request reqWithPersona = captured.get();
        assertThat(reqWithPersona.promptVersion()).isEqualTo("context-grader-v1");
        assertThat(reqWithPersona.prompt())
                .contains("Phong cách trả lời mong muốn (Ngắn gọn — CONCISE)")
                .contains("Trả lời cô đọng tối đa 2 câu.");

        grader.grade(analysis, emptyRetrieval);
        GroundingClient.Request reqWithoutPersona = captured.get();
        assertThat(reqWithoutPersona.prompt()).doesNotContain("Phong cách trả lời mong muốn");
    }

    private static LlmPersona persona(String code, String name, ResponseStyle style) {
        return new LlmPersona(
                code,
                name,
                "Mô tả " + name,
                "Hướng dẫn " + name,
                style,
                false,
                true,
                1);
    }
}

