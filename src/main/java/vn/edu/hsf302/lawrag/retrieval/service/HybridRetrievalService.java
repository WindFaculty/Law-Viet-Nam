package vn.edu.hsf302.lawrag.retrieval.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalCandidate;
import vn.edu.hsf302.lawrag.retrieval.config.RetrievalProperties;
import vn.edu.hsf302.lawrag.retrieval.repository.RetrievalRepository;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalResult;
import vn.edu.hsf302.lawrag.retrieval.dto.TemporalIssue;

/**
 * Hybrid lexical + vector retrieval with Reciprocal Rank Fusion.
 *
 * <p>Cosine similarity is the only score compared with the 0.75 gate; FTS
 * scores and RRF scores are rank-only evidence. If neither branch produces a
 * qualifying candidate the caller must skip Gemini and go straight to the web
 * consent state.
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final RetrievalRepository repository;
    private final EmbeddingClient embeddingClient;
    private final RetrievalProperties properties;
    private final CostBudgetService budget;

    public HybridRetrievalService(RetrievalRepository repository,
                                  EmbeddingClient embeddingClient,
                                  RetrievalProperties properties,
                                  CostBudgetService budget) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.budget = budget;
    }

    public RetrievalResult retrieve(QueryAnalysis analysis) {
        String embeddingSpace = embeddingClient.spaceId();

        List<RetrievalCandidate> lexical = rankLexical(
                repository.lexicalCandidates(analysis, properties.topK(), embeddingSpace), analysis);
        List<RetrievalCandidate> vector = retrieveVector(analysis, embeddingSpace);
        List<TemporalIssue> temporalIssues = repository.temporalIssues(analysis);

        List<RetrievalCandidate> context = selectContext(
                fuse(lexical, vector, properties.rrfK()),
                lexical,
                vector,
                analysis,
                properties.contextLimit(),
                Math.min(properties.contextTokenBudget(), budget.maxTokensPerContext()));

        double maxCosine = vector.stream()
                .mapToDouble(RetrievalCandidate::cosineSimilarity)
                .max()
                .orElse(0.0);
        boolean exactMatch = lexical.stream().anyMatch(RetrievalCandidate::exactMatch);
        boolean strongLexical = lexical.stream().anyMatch(RetrievalCandidate::strongLexical);
        boolean gatePassed = maxCosine >= properties.cosineThreshold()
                || exactMatch
                || strongLexical
                || !temporalIssues.isEmpty();

        return new RetrievalResult(analysis, context, lexical, vector, temporalIssues,
                gatePassed, exactMatch, strongLexical, maxCosine);
    }

    private List<RetrievalCandidate> retrieveVector(QueryAnalysis analysis, String embeddingSpace) {
        try {
            budget.requireEmbeddingCall();
            EmbeddingClient.Embedding embedding = embeddingClient.embed(
                    analysis.normalizedQuestion(), EmbeddingClient.EmbeddingTaskType.RETRIEVAL_QUERY);
            if (!embeddingSpace.equals(embedding.spaceId())) {
                throw new IllegalStateException("query embedding came from a different vector space");
            }
            List<RetrievalCandidate> vector = repository.vectorCandidates(
                    embedding.values(), analysis, properties.topK(), embeddingSpace);
            List<RetrievalCandidate> ranked = new ArrayList<>(vector.size());
            for (int index = 0; index < vector.size(); index++) {
                ranked.add(vector.get(index).withVector(index + 1,
                        vector.get(index).cosineSimilarity()));
            }
            return List.copyOf(ranked);
        } catch (RuntimeException exception) {
            // A provider outage must not disable exact/lexical retrieval; the
            // local branch can still answer or ask for consent.
            log.warn("Query embedding failed; continuing with lexical branch only: {}",
                    exception.getMessage());
            return List.of();
        }
    }

    private List<RetrievalCandidate> rankLexical(List<RetrievalCandidate> lexical, QueryAnalysis analysis) {
        List<String> queryTokens = VietnameseText.keywords(analysis.normalizedQuestion(), true);
        List<String> canonicalTokens = (analysis.subjects().isEmpty() && analysis.topics().isEmpty())
                ? List.of()
                : VietnameseText.keywords(
                        analysis.searchQuery().replace("quy định pháp luật giao thông đường bộ", " "), true);
        List<RetrievalCandidate> ranked = new ArrayList<>(lexical.size());
        for (int index = 0; index < lexical.size(); index++) {
            RetrievalCandidate candidate = lexical.get(index);
            boolean strong = isStrongLexical(candidate, queryTokens)
                    || isStrongLexical(candidate, canonicalTokens);
            ranked.add(candidate.withLexical(index + 1, candidate.lexicalScore(),
                    candidate.exactMatch(), strong));
        }
        return List.copyOf(ranked);
    }

    private boolean isStrongLexical(RetrievalCandidate candidate, List<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return false;
        }
        String haystack = " " + VietnameseText.normalizeForMatching(
                candidate.scope() + " " + candidate.title() + " " + candidate.searchText()) + " ";
        int matched = 0;
        for (String token : queryTokens) {
            if (haystack.contains(" " + token + " ")) {
                matched++;
            }
        }
        double coverage = (double) matched / queryTokens.size();
        return matched >= 1 && coverage >= properties.strongLexicalMinCoverage();
    }

    private List<RetrievalCandidate> fuse(List<RetrievalCandidate> lexical,
                                          List<RetrievalCandidate> vector,
                                          int rrfK) {
        Map<UUID, RetrievalCandidate> merged = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : lexical) {
            merged.put(candidate.articleId(), candidate);
        }
        for (RetrievalCandidate candidate : vector) {
            merged.merge(candidate.articleId(), candidate, (left, right) ->
                    left.withVector(right.vectorRank(), right.cosineSimilarity()));
        }

        List<RetrievalCandidate> fused = new ArrayList<>(merged.size());
        for (RetrievalCandidate candidate : merged.values()) {
            double score = 0;
            if (candidate.lexicalRank() > 0) {
                score += 1.0 / (rrfK + candidate.lexicalRank());
            }
            if (candidate.vectorRank() > 0) {
                score += 1.0 / (rrfK + candidate.vectorRank());
            }
            fused.add(candidate.withFused(score));
        }
        fused.sort(Comparator
                .comparingDouble(RetrievalCandidate::rrfScore).reversed()
                .thenComparing(RetrievalCandidate::exactMatch, Comparator.reverseOrder())
                .thenComparing(RetrievalCandidate::lexicalScore, Comparator.reverseOrder())
                .thenComparing(RetrievalCandidate::cosineSimilarity, Comparator.reverseOrder())
                .thenComparing(RetrievalCandidate::articleCode));
        return List.copyOf(fused);
    }

    private static List<RetrievalCandidate> selectContext(List<RetrievalCandidate> fused,
                                                          List<RetrievalCandidate> lexical,
                                                          List<RetrievalCandidate> vector,
                                                          QueryAnalysis analysis,
                                                          int limit,
                                                          int tokenBudget) {
        LinkedHashMap<UUID, RetrievalCandidate> ordered = new LinkedHashMap<>();
        // An explicitly requested article must reach the grader even if its
        // vector rank is weak; otherwise a user could ask for a clause and the
        // context would only contain the parent article.
        for (RetrievalCandidate candidate : fused) {
            if (analysis.articleCodes().contains(candidate.articleCode())) {
                ordered.put(candidate.articleId(), candidate);
            }
        }
        if (analysis.articleCodes().isEmpty()
                && !analysis.documentNumbers().isEmpty()
                && analysis.topics().isEmpty()) {
            List<String> requestedDocKeys = analysis.documentNumbers().stream()
                    .map(VietnameseText::docNumberKey)
                    .toList();
            for (RetrievalCandidate candidate : fused) {
                if (("dieu-1".equals(candidate.articleCode())
                        || "dieu-1-khoan-1".equals(candidate.articleCode()))
                        && requestedDocKeys.contains(
                                VietnameseText.docNumberKey(candidate.documentNumber()))) {
                    ordered.putIfAbsent(candidate.articleId(), candidate);
                }
            }
        }
        boolean preferPenalty = analysis.topics().contains("muc_phat")
                || analysis.documentNumbers().stream()
                        .map(VietnameseText::docNumberKey)
                        .anyMatch("168/2024/ND-CP"::equals);

        // The best vector candidate may be a near-synonym with the wrong
        // subject; the grader must see it in order to reject it as WRONG_SCOPE.
        if (!vector.isEmpty()) {
            ordered.putIfAbsent(vector.get(0).articleId(), vector.get(0));
        }
        // Strong lexical evidence is what makes the Layer 1 gate pass; keep the
        // best of it in front of vector-only neighbours, prioritizing conduct laws
        // on non-penalty queries and concise KHOAN/DIEM units before large DIEU blocks.
        addPrioritized(ordered, lexical.stream().filter(RetrievalCandidate::strongLexical).toList(), preferPenalty);
        addPrioritized(ordered, fused, preferPenalty);

        List<RetrievalCandidate> context = new ArrayList<>();
        int usedTokens = 0;
        for (RetrievalCandidate candidate : ordered.values()) {
            if (context.size() >= limit) {
                break;
            }
            int candidateTokens = Math.max(1, candidate.rawText().length() / 4 + 1);
            if (usedTokens + candidateTokens > tokenBudget) {
                continue;
            }
            context.add(candidate);
            usedTokens += candidateTokens;
        }
        return List.copyOf(context);
    }

    private static void addPrioritized(LinkedHashMap<UUID, RetrievalCandidate> ordered,
                                       List<RetrievalCandidate> candidates,
                                       boolean preferPenalty) {
        candidates.stream()
                .sorted(Comparator
                        .<RetrievalCandidate>comparingInt(c ->
                                (!preferPenalty && "168/2024/ND-CP".equals(
                                        VietnameseText.docNumberKey(c.documentNumber()))) ? 1 : 0)
                        .thenComparingInt(c -> "DIEU".equals(c.hierarchyLevel().name()) ? 1 : 0))
                .forEach(candidate -> ordered.putIfAbsent(candidate.articleId(), candidate));
    }
}
