package vn.edu.hsf302.lawrag.retrieval.dto;

import java.util.List;

public record RenderedAnswer(
        String content,
        List<CitationDraft> citations,
        List<CitationCard> cards) {
}
