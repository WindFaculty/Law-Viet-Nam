package vn.edu.hsf302.lawrag.retrieval.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;
import vn.edu.hsf302.lawrag.llm.entity.ResponseStyle;
import vn.edu.hsf302.lawrag.retrieval.dto.CitationCard;
import vn.edu.hsf302.lawrag.retrieval.dto.CitationDraft;
import vn.edu.hsf302.lawrag.retrieval.dto.RenderedAnswer;
import vn.edu.hsf302.lawrag.retrieval.dto.ValidatedCitation;

/**
 * Builds the stored answer and citation cards. Verbatim quotes always come from
 * {@code legal_articles.raw_text}; no model-generated wording is used for the
 * quote. The short introduction is a separate, clearly labelled frame.
 */
@Service
public class AnswerRenderer {

    public RenderedAnswer render(List<ValidatedCitation> citations) {
        return render(citations, null, null);
    }

    public RenderedAnswer render(List<ValidatedCitation> citations, String llmAnswer) {
        return render(citations, llmAnswer, null);
    }

    public RenderedAnswer render(List<ValidatedCitation> citations, String llmAnswer, LlmPersona persona) {
        if (citations == null || citations.isEmpty()) {
            throw new CitationValidationException("an answer needs at least one validated citation");
        }

        String intro = buildIntro(llmAnswer, citations, persona);
        StringBuilder content = new StringBuilder(intro);
        List<CitationDraft> drafts = new ArrayList<>(citations.size());
        List<CitationCard> cards = new ArrayList<>(citations.size());

        for (int index = 0; index < citations.size(); index++) {
            ValidatedCitation citation = citations.get(index);
            int start = citation.snapshotContent().indexOf(citation.rawText());
            if (start < 0) {
                throw new CitationValidationException(
                        "cannot locate verbatim excerpt for " + citation.articleId());
            }
            int end = start + citation.rawText().length();

            content.append("\nTRÍCH NGUYÊN VĂN — ")
                    .append(citation.locator())
                    .append(" — ")
                    .append(citation.documentTitle())
                    .append(" (").append(citation.documentNumber()).append("):\n\"")
                    .append(citation.rawText())
                    .append("\"\n");

            drafts.add(new CitationDraft(
                    citation.documentVersionId(),
                    citation.articleId(),
                    citation.snapshotId(),
                    index,
                    start,
                    end,
                    citation.locator(),
                    citation.rawText()));
            cards.add(new CitationCard(
                    index,
                    citation.documentTitle(),
                    citation.documentNumber(),
                    citation.locator(),
                    citation.rawText(),
                    citationLink(citation.documentVersionId(), citation.articleId())));
        }

        return new RenderedAnswer(content.toString().strip(), List.copyOf(drafts), List.copyOf(cards));
    }

    public static String citationLink(UUID documentVersionId, UUID articleId) {
        return "/legal/documents/" + documentVersionId + "/articles/" + articleId;
    }

    private static String buildIntro(String llmAnswer, List<ValidatedCitation> citations, LlmPersona persona) {
        if (llmAnswer != null && !llmAnswer.isBlank()) {
            return llmAnswer.strip() + "\n";
        }
        if (persona == null) {
            return "Dẫn: Kho nội bộ có quy định trực tiếp về vấn đề bạn hỏi.\n";
        }
        ValidatedCitation first = citations.getFirst();
        String primaryRef = first.locator() + " — " + first.documentTitle() + " (" + first.documentNumber() + ")";
        return switch (persona.getResponseStyle()) {
            case CONCISE -> "Kết luận nhanh (" + persona.getName() + "): Áp dụng " + primaryRef
                    + (citations.size() > 1 ? " cùng " + (citations.size() - 1) + " điều khoản liên quan bên dưới." : ".")
                    + "\n";
            case DETAILED -> {
                StringBuilder sb = new StringBuilder();
                sb.append("Phân tích đầy đủ (").append(persona.getName()).append("):\n")
                        .append("1. Kết luận tổng quát: Kho văn bản nội bộ có ").append(citations.size())
                        .append(" điều khoản điều chỉnh trực tiếp câu hỏi của bạn.\n")
                        .append("2. Các căn cứ pháp lý đối chiếu:\n");
                for (ValidatedCitation citation : citations) {
                    sb.append("   - ").append(citation.locator()).append(" — ")
                            .append(citation.documentTitle()).append(" (").append(citation.documentNumber()).append(")\n");
                }
                sb.append("3. Nội dung nguyên văn để kiểm chứng đối tượng, điều kiện và ngoại lệ được trích đầy đủ ngay bên dưới.\n");
                yield sb.toString();
            }
            case PROFESSIONAL -> "Dẫn (" + persona.getName() + "): Căn cứ quy định hiện hành tại " + primaryRef
                    + ", nội dung pháp lý áp dụng được trích nguyên văn như sau:\n";
            case CUSTOM -> "Dẫn (" + persona.getName() + "): Áp dụng quy định tại " + primaryRef
                    + (citations.size() > 1 ? " và " + (citations.size() - 1) + " điều khoản liên quan" : "") + ":\n";
        };
    }
}
