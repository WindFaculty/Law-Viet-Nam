package vn.edu.hsf302.lawrag.retrieval.service;

import org.junit.jupiter.api.Test;
import vn.edu.hsf302.lawrag.retrieval.dto.RenderedAnswer;
import vn.edu.hsf302.lawrag.retrieval.dto.ValidatedCitation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerRendererTest {

    private final AnswerRenderer renderer = new AnswerRenderer();

    @Test
    void rendersLlmAnswerBeforeVerbatimCitationWhenProvided() {
        String rawText = "Người điều khiển xe mô tô hai bánh phải đội mũ bảo hiểm.";
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

        RenderedAnswer rendered = renderer.render(
                List.of(citation),
                "Có, người đi xe máy bắt buộc phải đội mũ bảo hiểm theo khoản 2 Điều 33 Luật 36/2024/QH15.");

        assertThat(rendered.content())
                .startsWith("Có, người đi xe máy bắt buộc phải đội mũ bảo hiểm")
                .contains("TRÍCH NGUYÊN VĂN — Khoản 2 Điều 33")
                .contains(rawText);
        assertThat(rendered.citations()).hasSize(1);
        assertThat(rendered.cards()).hasSize(1);
    }

    @Test
    void fallsBackToDefaultLeadWhenLlmAnswerIsMissing() {
        String rawText = "Người điều khiển xe mô tô hai bánh phải đội mũ bảo hiểm.";
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

        RenderedAnswer rendered = renderer.render(List.of(citation));

        assertThat(rendered.content())
                .startsWith("Dẫn: Kho nội bộ có quy định trực tiếp về vấn đề bạn hỏi.")
                .contains(rawText);
    }
}

