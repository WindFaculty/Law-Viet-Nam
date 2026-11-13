package vn.edu.hsf302.lawrag.retrieval.dto;

import java.util.List;

/**
 * Output of hybrid retrieval. {@code gatePassed} is the Layer 1 decision:
 * cosine above threshold, an exact number/locator match, a strong lexical
 * match, or a known-but-not-yet-applicable clause that must be explained.
 */
public record RetrievalResult(
        QueryAnalysis analysis,
        List<RetrievalCandidate> contextCandidates,
        List<RetrievalCandidate> lexicalCandidates,
        List<RetrievalCandidate> vectorCandidates,
        List<TemporalIssue> temporalIssues,
        boolean gatePassed,
        boolean exactMatch,
        boolean strongLexical,
        double maxCosine) {

    public RetrievalResult {
        contextCandidates = contextCandidates == null ? List.of() : List.copyOf(contextCandidates);
        lexicalCandidates = lexicalCandidates == null ? List.of() : List.copyOf(lexicalCandidates);
        vectorCandidates = vectorCandidates == null ? List.of() : List.copyOf(vectorCandidates);
        temporalIssues = temporalIssues == null ? List.of() : List.copyOf(temporalIssues);
    }
}
