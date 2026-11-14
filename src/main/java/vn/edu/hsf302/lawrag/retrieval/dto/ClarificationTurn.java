package vn.edu.hsf302.lawrag.retrieval.dto;

/**
 * One preceding USER question and AI clarification prompt within an active
 * clarification chain inside a chat session.
 */
public record ClarificationTurn(String userQuestion, String aiClarification) {
}

