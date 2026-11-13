package vn.edu.hsf302.lawrag.retrieval.dto;

public record CitationCard(
        int citationOrder,
        String documentTitle,
        String documentNumber,
        String locator,
        String verbatimText,
        String href) {
}
