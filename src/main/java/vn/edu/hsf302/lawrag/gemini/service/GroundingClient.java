package vn.edu.hsf302.lawrag.gemini.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Adapter boundary for the Gemini context-grading call. The backend validates
 * every returned article ID, its document status and its effective dates before
 * publishing an answer. Google Search/URL grounding tools are never enabled
 * for this local call.
 */
public interface GroundingClient {

    Decision evaluate(Request request);

    default Decision evaluateStream(Request request, Consumer<String> onAnswerDelta) {
        Decision decision = evaluate(request);
        if (decision.answer() != null && !decision.answer().isBlank() && onAnswerDelta != null) {
            onAnswerDelta.accept(decision.answer());
        }
        return decision;
    }

    record ContextArticle(
            int ordinal,
            String articleId,
            String documentVersionId,
            String documentNumber,
            String documentTitle,
            String articleCode,
            String hierarchyLevel,
            String locator,
            String scope,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String rawText) {

        public ContextArticle {
            if (ordinal < 1 || articleId == null || articleId.isBlank()) {
                throw new IllegalArgumentException("context article needs an ordinal and id");
            }
            rawText = rawText == null ? "" : rawText;
        }
    }

    record TemporalNote(
            String articleId,
            String articleCode,
            String locator,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String note) {
    }

    record Request(
            String question,
            List<ContextArticle> contextArticles,
            LocalDate applicableDate,
            List<String> subjects,
            List<String> topics,
            List<String> requestedArticleCodes,
            List<String> documentNumbers,
            List<TemporalNote> temporalNotes,
            String prompt,
            String promptVersion) {

        public Request {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            contextArticles = contextArticles == null ? List.of() : List.copyOf(contextArticles);
            subjects = subjects == null ? List.of() : List.copyOf(subjects);
            topics = topics == null ? List.of() : List.copyOf(topics);
            requestedArticleCodes = requestedArticleCodes == null ? List.of() : List.copyOf(requestedArticleCodes);
            documentNumbers = documentNumbers == null ? List.of() : List.copyOf(documentNumbers);
            temporalNotes = temporalNotes == null ? List.of() : List.copyOf(temporalNotes);
            if (applicableDate == null) {
                throw new IllegalArgumentException("applicableDate must not be null");
            }
            if (promptVersion == null || promptVersion.isBlank()) {
                throw new IllegalArgumentException("promptVersion must not be blank");
            }
        }
    }

    record Decision(
            Outcome outcome,
            List<String> selectedArticleIds,
            ReasonCode reasonCode,
            String suggestedSearchQuery,
            String clarificationQuestion,
            String answer,
            String modelId,
            String promptVersion) {

        public Decision {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
            if (reasonCode == null) {
                throw new IllegalArgumentException("reasonCode must not be null");
            }
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId must not be blank");
            }
            if (promptVersion == null || promptVersion.isBlank()) {
                throw new IllegalArgumentException("promptVersion must not be blank");
            }
            answer = (answer == null || answer.isBlank()) ? null : answer.strip();
            selectedArticleIds = selectedArticleIds == null ? List.of() : List.copyOf(selectedArticleIds);
        }

        public Decision(Outcome outcome,
                        List<String> selectedArticleIds,
                        ReasonCode reasonCode,
                        String suggestedSearchQuery,
                        String clarificationQuestion,
                        String modelId,
                        String promptVersion) {
            this(outcome, selectedArticleIds, reasonCode, suggestedSearchQuery,
                    clarificationQuestion, null, modelId, promptVersion);
        }
    }

    enum Outcome {
        ANSWER,
        NEED_WEB_SEARCH,
        NEED_CLARIFICATION;

        public static Outcome fromModelValue(String value) {
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new GroundingException("unknown decision value: " + value, exception);
            }
        }
    }

    enum ReasonCode {
        SUFFICIENT,
        NO_MATCH,
        WRONG_SCOPE,
        TEMPORAL_UNCERTAINTY,
        MISSING_FACTS;

        public static ReasonCode fromModelValue(String value) {
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new GroundingException("unknown reasonCode value: " + value, exception);
            }
        }
    }
}
