package vn.edu.hsf302.lawrag.retrieval.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        if (citations == null || citations.isEmpty()) {
            throw new CitationValidationException("an answer needs at least one validated citation");
        }

        StringBuilder content = new StringBuilder(
                "Dẫn: Kho nội bộ có quy định trực tiếp về vấn đề bạn hỏi.\n");
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
}
