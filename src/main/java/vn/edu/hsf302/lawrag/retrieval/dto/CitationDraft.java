package vn.edu.hsf302.lawrag.retrieval.dto;

import java.util.UUID;

public record CitationDraft(
        UUID documentVersionId,
        UUID articleId,
        UUID snapshotId,
        int citationOrder,
        int excerptStart,
        int excerptEnd,
        String locator,
        String verbatimText) {
}
