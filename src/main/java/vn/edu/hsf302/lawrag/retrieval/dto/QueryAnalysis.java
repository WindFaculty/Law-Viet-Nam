package vn.edu.hsf302.lawrag.retrieval.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Structured signals produced before retrieval. A non-null clarification
 * question means the orchestrator must stop with NEEDS_CLARIFICATION and must
 * not spend a Gemini/embedding round on a guess.
 */
public record QueryAnalysis(
        String originalQuestion,
        String normalizedQuestion,
        String searchQuery,
        List<String> documentNumbers,
        List<String> articleCodes,
        String articleNumber,
        String clauseNumber,
        String pointLetter,
        List<String> subjects,
        List<String> topics,
        LocalDate applicableDate,
        boolean explicitDate,
        boolean ambiguousDate,
        boolean outOfScope,
        String clarificationQuestion) {

    public QueryAnalysis {
        documentNumbers = documentNumbers == null ? List.of() : List.copyOf(documentNumbers);
        articleCodes = articleCodes == null ? List.of() : List.copyOf(articleCodes);
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        topics = topics == null ? List.of() : List.copyOf(topics);
        if (applicableDate == null) {
            throw new IllegalArgumentException("applicableDate is required");
        }
    }

    public boolean requiresClarification() {
        return clarificationQuestion != null && !clarificationQuestion.isBlank();
    }

    public boolean hasExplicitLocator() {
        return !articleCodes.isEmpty() || !documentNumbers.isEmpty();
    }

    public String locatorDisplay() {
        if (articleNumber == null) {
            return "";
        }
        StringBuilder display = new StringBuilder("Điều ").append(articleNumber);
        if (clauseNumber != null) {
            display.append(" khoản ").append(clauseNumber);
        }
        if (pointLetter != null) {
            display.append(" điểm ").append(pointLetter);
        }
        return display.toString();
    }
}
