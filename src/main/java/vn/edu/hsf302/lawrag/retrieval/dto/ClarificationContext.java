package vn.edu.hsf302.lawrag.retrieval.dto;

import java.util.List;

/**
 * Resolved conversation context across contiguous NEEDS_CLARIFICATION turns.
 */
public record ClarificationContext(
        String currentQuestion,
        String effectiveQuestion,
        List<ClarificationTurn> history) {

    public ClarificationContext {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public boolean hasHistory() {
        return !history.isEmpty();
    }

    public static ClarificationContext singleTurn(String question) {
        return new ClarificationContext(question, question, List.of());
    }
}

