package vn.edu.hsf302.lawrag.gemini.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.edu.hsf302.lawrag.retrieval.service.VietnameseText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline/test grader. It is corpus-aware through the same deterministic
 * Vietnamese subject/topic extraction as the pre-check, so G4 can exercise
 * wrong-scope, temporal and missing-evidence behaviour without a network call.
 */
@Component
@ConditionalOnProperty(prefix = "app.gemini", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DeterministicGroundingClient implements GroundingClient {

    public static final String MODEL_ID = "deterministic-corpus-grader-v1";

    @Override
    public Decision evaluate(Request originalRequest) {
        String suggestedQuery = searchQuery(originalRequest);

        if (!originalRequest.temporalNotes().isEmpty()) {
            return decision(originalRequest, Outcome.NEED_WEB_SEARCH, List.of(),
                    ReasonCode.TEMPORAL_UNCERTAINTY, suggestedQuery, null);
        }
        if (originalRequest.contextArticles().isEmpty()) {
            return decision(originalRequest, Outcome.NEED_WEB_SEARCH, List.of(),
                    ReasonCode.NO_MATCH, suggestedQuery, null);
        }

        List<ContextArticle> docScoped = originalRequest.documentNumbers().isEmpty()
                ? originalRequest.contextArticles()
                : matchingDocumentArticles(originalRequest);
        if (docScoped.isEmpty()) {
            return decision(originalRequest, Outcome.NEED_WEB_SEARCH, List.of(),
                    ReasonCode.NO_MATCH, suggestedQuery, null);
        }

        List<ContextArticle> requested = docScoped.stream()
                .filter(article -> originalRequest.requestedArticleCodes().contains(article.articleCode()))
                .toList();
        if (!requested.isEmpty()) {
            return answer(originalRequest, List.of(best(requested)));
        }

        if (originalRequest.topics().isEmpty()) {
            if (!originalRequest.documentNumbers().isEmpty()) {
                return answer(originalRequest, List.of(selectDocumentOverview(docScoped)));
            }
            return decision(originalRequest, Outcome.NEED_WEB_SEARCH, List.of(),
                    ReasonCode.NO_MATCH, suggestedQuery, null);
        }

        boolean preferPenalty = originalRequest.topics().contains("muc_phat")
                || originalRequest.documentNumbers().stream()
                        .map(VietnameseText::docNumberKey)
                        .anyMatch("168/2024/ND-CP"::equals);
        List<ContextArticle> scopedArticles = preferPenalty
                ? docScoped
                : docScoped.stream()
                        .filter(article -> !"168/2024/ND-CP".equals(
                                VietnameseText.docNumberKey(article.documentNumber())))
                        .toList();
        if (scopedArticles.isEmpty()) {
            return decision(originalRequest, Outcome.NEED_WEB_SEARCH, List.of(),
                    ReasonCode.NO_MATCH, suggestedQuery, null);
        }
        Request request = new Request(
                originalRequest.question(),
                scopedArticles,
                originalRequest.applicableDate(),
                originalRequest.subjects(),
                originalRequest.topics(),
                originalRequest.requestedArticleCodes(),
                originalRequest.documentNumbers(),
                originalRequest.temporalNotes(),
                originalRequest.prompt(),
                originalRequest.promptVersion());

        List<ContextArticle> topicMatches = new ArrayList<>();
        List<ContextArticle> clauseSubjectTopicMatches = new ArrayList<>();
        List<ContextArticle> parentSubjectTopicMatches = new ArrayList<>();
        for (ContextArticle article : request.contextArticles()) {
            List<String> articleTopics = VietnameseText.topics(article.rawText());
            if (!articleTopics.containsAll(request.topics())) {
                continue;
            }
            topicMatches.add(article);
            if (!matchesSubject(article, request.subjects())) {
                continue;
            }
            if ("DIEU".equals(article.hierarchyLevel())) {
                parentSubjectTopicMatches.add(article);
            } else {
                clauseSubjectTopicMatches.add(article);
            }
        }

        // A parent article aggregates all of its clauses, so it must not hide a
        // wrong-subject clause. Direct matching therefore uses clause-level
        // units first; a parent is only a fallback for multi-clause questions.
        if (!clauseSubjectTopicMatches.isEmpty()) {
            List<ContextArticle> selected = selectCovering(clauseSubjectTopicMatches, request.topics());
            if (!selected.isEmpty()) {
                return answer(request, selected);
            }
        }
        if (request.topics().size() > 1 && !parentSubjectTopicMatches.isEmpty()) {
            List<ContextArticle> selected = selectCovering(parentSubjectTopicMatches, request.topics());
            if (!selected.isEmpty()) {
                return answer(request, selected);
            }
        }
        if (!topicMatches.isEmpty()) {
            return decision(request, Outcome.NEED_WEB_SEARCH, List.of(),
                    ReasonCode.WRONG_SCOPE, suggestedQuery, null);
        }
        return decision(request, Outcome.NEED_WEB_SEARCH, List.of(),
                ReasonCode.NO_MATCH, suggestedQuery, null);
    }

    private static boolean matchesSubject(ContextArticle article, List<String> querySubjects) {
        if (querySubjects.isEmpty()) {
            return true;
        }
        List<String> articleSubjects = VietnameseText.subjects(article.rawText());
        if (articleSubjects.isEmpty() && article.scope() != null && !article.scope().isBlank()) {
            articleSubjects = VietnameseText.subjects(article.scope());
        }
        return querySubjects.stream().anyMatch(articleSubjects::contains);
    }

    /**
     * A question may require more than one clause (for example passenger limit
     * plus helmet rule). Prefer one article that covers every topic; otherwise
     * combine the first subject-matching article for each uncovered topic.
     */
    private static List<ContextArticle> selectCovering(List<ContextArticle> matches, List<String> topics) {
        List<ContextArticle> complete = matches.stream()
                .filter(article -> VietnameseText.topics(article.rawText()).containsAll(topics))
                .toList();
        if (!complete.isEmpty()) {
            return List.of(best(complete));
        }

        LinkedHashSet<ContextArticle> selected = new LinkedHashSet<>();
        Set<String> covered = new LinkedHashSet<>();
        for (String topic : topics) {
            if (covered.contains(topic)) {
                continue;
            }
            ContextArticle match = null;
            for (ContextArticle article : matches) {
                List<String> articleTopics = VietnameseText.topics(article.rawText());
                if (articleTopics.contains(topic)) {
                    match = article;
                    covered.addAll(articleTopics);
                    break;
                }
            }
            if (match == null) {
                return List.of();
            }
            selected.add(match);
        }
        return covered.containsAll(topics) ? List.copyOf(selected) : List.of();
    }

    private static ContextArticle best(List<ContextArticle> matches) {
        ContextArticle best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ContextArticle article : matches) {
            int score = specificity(article);
            if (score > bestScore) {
                best = article;
                bestScore = score;
            }
        }
        return best;
    }

    private static List<ContextArticle> matchingDocumentArticles(Request request) {
        List<String> keys = request.documentNumbers().stream()
                .map(VietnameseText::docNumberKey)
                .toList();
        return request.contextArticles().stream()
                .filter(article -> keys.contains(VietnameseText.docNumberKey(article.documentNumber())))
                .toList();
    }

    private static ContextArticle selectDocumentOverview(List<ContextArticle> docMatches) {
        for (String preferredCode : List.of("dieu-1", "dieu-1-khoan-1")) {
            for (ContextArticle article : docMatches) {
                if (preferredCode.equals(article.articleCode())) {
                    return article;
                }
            }
        }
        return best(docMatches);
    }

    private static int specificity(ContextArticle article) {
        return switch (article.hierarchyLevel()) {
            case "KHOAN" -> 30;
            case "DIEM" -> 20;
            default -> 10;
        };
    }

    private static Decision answer(Request request, List<ContextArticle> articles) {
        return decision(request, Outcome.ANSWER,
                articles.stream().map(ContextArticle::articleId).toList(),
                ReasonCode.SUFFICIENT, null, null);
    }

    private static Decision decision(Request request, Outcome outcome, List<String> selectedIds,
                                     ReasonCode reasonCode, String suggestedQuery,
                                     String clarificationQuestion) {
        return new Decision(outcome, selectedIds, reasonCode, suggestedQuery,
                clarificationQuestion, MODEL_ID, request.promptVersion());
    }

    private static String searchQuery(Request request) {
        return VietnameseText.searchQuery(request.question(), request.subjects(), request.topics(),
                request.documentNumbers(), request.requestedArticleCodes());
    }
}
