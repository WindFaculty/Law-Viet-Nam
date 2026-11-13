package vn.edu.hsf302.lawrag.retrieval.dto;

import vn.edu.hsf302.lawrag.legaldata.entity.LegalHierarchyLevel;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One retrievable article, already filtered to READY document/article/embedding
 * and to the applicable date. Scores from the lexical and vector branches are
 * kept separately; only RRF compares their ranks.
 */
public record RetrievalCandidate(
        UUID articleId,
        UUID documentVersionId,
        UUID snapshotId,
        String documentKey,
        String documentNumber,
        String documentTitle,
        String articleCode,
        String articleNumber,
        String title,
        LegalHierarchyLevel hierarchyLevel,
        String rawText,
        String searchText,
        String locator,
        String scope,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int lexicalRank,
        double lexicalScore,
        boolean exactMatch,
        boolean strongLexical,
        int vectorRank,
        double cosineSimilarity,
        double rrfScore) {

    public RetrievalCandidate {
        rawText = rawText == null ? "" : rawText;
        searchText = searchText == null ? "" : searchText;
    }

    public RetrievalCandidate withLexical(int rank, double score, boolean exact, boolean strong) {
        return new RetrievalCandidate(articleId, documentVersionId, snapshotId, documentKey,
                documentNumber, documentTitle, articleCode, articleNumber, title, hierarchyLevel,
                rawText, searchText, locator, scope, effectiveFrom, effectiveTo,
                rank, score, exact, strong, vectorRank, cosineSimilarity, rrfScore);
    }

    public RetrievalCandidate withVector(int rank, double cosine) {
        return new RetrievalCandidate(articleId, documentVersionId, snapshotId, documentKey,
                documentNumber, documentTitle, articleCode, articleNumber, title, hierarchyLevel,
                rawText, searchText, locator, scope, effectiveFrom, effectiveTo,
                lexicalRank, lexicalScore, exactMatch, strongLexical, rank, cosine, rrfScore);
    }

    public RetrievalCandidate withFused(double score) {
        return new RetrievalCandidate(articleId, documentVersionId, snapshotId, documentKey,
                documentNumber, documentTitle, articleCode, articleNumber, title, hierarchyLevel,
                rawText, searchText, locator, scope, effectiveFrom, effectiveTo,
                lexicalRank, lexicalScore, exactMatch, strongLexical, vectorRank, cosineSimilarity, score);
    }
}
