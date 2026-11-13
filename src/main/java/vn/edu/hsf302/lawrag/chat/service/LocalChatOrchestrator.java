package vn.edu.hsf302.lawrag.chat.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.gemini.service.GroundingException;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.repository.SourceSnapshotRepository;
import vn.edu.hsf302.lawrag.observability.service.BudgetExceededException;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;
import vn.edu.hsf302.lawrag.observability.dto.Usage;
import vn.edu.hsf302.lawrag.observability.dto.UsageCategory;
import vn.edu.hsf302.lawrag.retrieval.service.AnswerRenderer;
import vn.edu.hsf302.lawrag.retrieval.dto.CitationDraft;
import vn.edu.hsf302.lawrag.retrieval.service.CitationValidationException;
import vn.edu.hsf302.lawrag.retrieval.service.CitationValidator;
import vn.edu.hsf302.lawrag.retrieval.service.ContextGrader;
import vn.edu.hsf302.lawrag.retrieval.service.CorpusVersionProvider;
import vn.edu.hsf302.lawrag.retrieval.service.HybridRetrievalService;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.service.QueryAnalyzer;
import vn.edu.hsf302.lawrag.retrieval.dto.RenderedAnswer;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalResult;
import vn.edu.hsf302.lawrag.retrieval.repository.RetrievalTraceRepository;
import vn.edu.hsf302.lawrag.retrieval.dto.TemporalIssue;
import vn.edu.hsf302.lawrag.retrieval.dto.ValidatedCitation;

import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.dto.ChatOutcome;
import vn.edu.hsf302.lawrag.chat.entity.MessageCitation;
import vn.edu.hsf302.lawrag.chat.repository.MessageCitationRepository;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.dto.SubmittedQuestion;

/**
 * Local RAG pipeline:
 * MessageSubmissionService -> QueryAnalyzer -> HybridRetrievalService ->
 * ContextGrader -> CitationValidator -> AnswerRenderer -> MessageStateService.
 *
 * <p>Embedding/Gemini calls happen before the short database transaction that
 * stores the answer, citations, quota settlement and retrieval trace.
 * AWAITING_WEB_CONFIRM and NEEDS_CLARIFICATION never call WebSearchProvider or
 * DocumentFetcher and always release the quota reservation.
 */
@Service
public class LocalChatOrchestrator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LocalChatOrchestrator.class);
    private static final String GATE_MODEL = "layer-1-gate";
    private static final String GATE_PROMPT_VERSION = "layer-1-gate-v1";
    private static final String QUERY_ANALYZER_MODEL = "query-analyzer";
    private static final String QUERY_ANALYZER_PROMPT_VERSION = "query-analyzer-v1";
    private static final int MAX_TEXT_LENGTH = 1_000;
    private static final String OUT_OF_SCOPE_REPLY =
            "Xin lỗi, câu hỏi này nằm ngoài phạm vi hỗ trợ của hệ thống "
                    + "(pháp luật giao thông đường bộ). Tôi không tự mở rộng kho dữ liệu "
                    + "hoặc đưa ra căn cứ cho lĩnh vực khác.";

    private final TransactionTemplate transactions;
    private final MessageSubmissionService submissions;
    private final MessageStateService messages;
    private final QueryAnalyzer queryAnalyzer;
    private final HybridRetrievalService retrieval;
    private final ContextGrader grader;
    private final CitationValidator citationValidator;
    private final AnswerRenderer answerRenderer;
    private final MessageCitationRepository citations;
    private final LegalDocumentRepository documents;
    private final LegalArticleRepository articles;
    private final SourceSnapshotRepository snapshots;
    private final RetrievalTraceRepository traces;
    private final CorpusVersionProvider corpusVersion;
    private final EmbeddingClient embeddingClient;
    private final CostBudgetService budget;

    public LocalChatOrchestrator(PlatformTransactionManager transactionManager,
                                 MessageSubmissionService submissions,
                                 MessageStateService messages,
                                 QueryAnalyzer queryAnalyzer,
                                 HybridRetrievalService retrieval,
                                 ContextGrader grader,
                                 CitationValidator citationValidator,
                                 AnswerRenderer answerRenderer,
                                 MessageCitationRepository citations,
                                 LegalDocumentRepository documents,
                                 LegalArticleRepository articles,
                                 SourceSnapshotRepository snapshots,
                                 RetrievalTraceRepository traces,
                                 CorpusVersionProvider corpusVersion,
                                 EmbeddingClient embeddingClient,
                                 CostBudgetService budget) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.submissions = submissions;
        this.messages = messages;
        this.queryAnalyzer = queryAnalyzer;
        this.retrieval = retrieval;
        this.grader = grader;
        this.citationValidator = citationValidator;
        this.answerRenderer = answerRenderer;
        this.citations = citations;
        this.documents = documents;
        this.articles = articles;
        this.snapshots = snapshots;
        this.traces = traces;
        this.corpusVersion = corpusVersion;
        this.embeddingClient = embeddingClient;
        this.budget = budget;
    }

    public ChatOutcome ask(UUID sessionId, SessionOwner owner, String idempotencyKey, String question) {
        SubmittedQuestion submitted =
                submissions.submitQuestion(sessionId, owner, idempotencyKey, question);
        if (submitted.replay()) {
            return persisted(submitted.aiMessage());
        }
        return process(submitted.aiMessage(), submitted.userMessage().getContent(), owner);
    }

    private ChatOutcome process(ChatMessage aiMessage, String question, SessionOwner owner) {
        QueryAnalysis analysis = queryAnalyzer.analyze(question, aiMessage.getApplicableDate());
        if (!analysis.applicableDate().equals(aiMessage.getApplicableDate())) {
            transactions.executeWithoutResult(status ->
                    messages.updateApplicableDate(aiMessage.getId(), owner, analysis.applicableDate()));
        }

        if (analysis.outOfScope()) {
            transactions.executeWithoutResult(status -> {
                ChatMessage saved = messages.markOutOfScope(
                        aiMessage.getId(), owner, OUT_OF_SCOPE_REPLY);
                recordTrace(saved, analysis, null, null, "OUT_OF_SCOPE", "OUT_OF_SCOPE");
            });
            return new ChatOutcome(aiMessage.getId(), MessageStatus.COMPLETED, null, null, List.of());
        }

        if (analysis.requiresClarification()) {
            GroundingClient.Decision decision = new GroundingClient.Decision(
                    GroundingClient.Outcome.NEED_CLARIFICATION,
                    List.of(),
                    GroundingClient.ReasonCode.MISSING_FACTS,
                    null,
                    analysis.clarificationQuestion(),
                    QUERY_ANALYZER_MODEL,
                    QUERY_ANALYZER_PROMPT_VERSION);
            transactions.executeWithoutResult(status -> {
                ChatMessage saved = messages.requestClarification(
                        aiMessage.getId(), owner, decision.clarificationQuestion());
                recordTrace(saved, analysis, null, decision, decision.outcome().name(),
                        decision.reasonCode().name());
            });
            return outcome(aiMessage.getId(), MessageStatus.NEEDS_CLARIFICATION, decision, List.of());
        }

        RetrievalResult retrievalResult = retrieval.retrieve(analysis);
        GroundingClient.Decision decision;
        if (!retrievalResult.gatePassed()) {
            decision = new GroundingClient.Decision(
                    GroundingClient.Outcome.NEED_WEB_SEARCH,
                    List.of(),
                    GroundingClient.ReasonCode.NO_MATCH,
                    analysis.searchQuery(),
                    null,
                    GATE_MODEL,
                    GATE_PROMPT_VERSION);
        } else {
            try {
                decision = grader.grade(analysis, retrievalResult);
            } catch (GroundingException | BudgetExceededException exception) {
                // No unvalidated answer: persist FAILED, release the hold and keep history.
                return fail(aiMessage.getId(), owner, analysis, retrievalResult,
                        GroundingClient.Outcome.NEED_WEB_SEARCH,
                        GroundingClient.ReasonCode.NO_MATCH, exception.getMessage());
            }
        }

        switch (decision.outcome()) {
            case ANSWER -> {
                try {
                    List<ValidatedCitation> validated = citationValidator.validate(
                            decision, analysis, retrievalResult.contextCandidates());
                    RenderedAnswer rendered = answerRenderer.render(validated);
                    transactions.executeWithoutResult(status -> {
                        ChatMessage saved = messages.complete(
                                aiMessage.getId(), owner, rendered.content());
                        persistCitations(saved, rendered.citations());
                        recordTrace(saved, analysis, retrievalResult, decision,
                                decision.outcome().name(), decision.reasonCode().name());
                    });
                    recordLocalUsage(UsageCategory.LOCAL_ANSWERED, analysis, retrievalResult,
                            rendered.content().length());
                    return outcome(aiMessage.getId(), MessageStatus.COMPLETED, decision,
                            validated.stream().map(ValidatedCitation::articleId).toList());
                } catch (CitationValidationException exception) {
                    return fail(aiMessage.getId(), owner, analysis, retrievalResult,
                            decision.outcome(), decision.reasonCode(), exception.getMessage());
                }
            }
            case NEED_WEB_SEARCH -> {
                String suggestedQuery = suggestedQuery(decision, analysis);
                transactions.executeWithoutResult(status -> {
                    ChatMessage saved = messages.markAwaitingWebConfirm(
                            aiMessage.getId(), owner, suggestedQuery);
                    recordTrace(saved, analysis, retrievalResult, decision,
                            decision.outcome().name(), decision.reasonCode().name());
                });
                recordLocalUsage(UsageCategory.LOCAL_AWAITING_CONFIRM, analysis, retrievalResult,
                        suggestedQuery.length());
                return outcome(aiMessage.getId(), MessageStatus.AWAITING_WEB_CONFIRM, decision, List.of());
            }
            case NEED_CLARIFICATION -> {
                String clarification = clarificationQuestion(decision, analysis);
                transactions.executeWithoutResult(status -> {
                    ChatMessage saved = messages.requestClarification(
                            aiMessage.getId(), owner, clarification);
                    recordTrace(saved, analysis, retrievalResult, decision,
                            decision.outcome().name(), decision.reasonCode().name());
                });
                recordLocalUsage(UsageCategory.CLARIFICATION, analysis, retrievalResult,
                        clarification.length());
                return outcome(aiMessage.getId(), MessageStatus.NEEDS_CLARIFICATION, decision, List.of());
            }
            default -> throw new IllegalStateException("unsupported grading outcome");
        }
    }

    private ChatOutcome fail(UUID messageId, SessionOwner owner, QueryAnalysis analysis,
                         RetrievalResult retrievalResult, GroundingClient.Outcome outcome,
                         GroundingClient.ReasonCode reasonCode, String detail) {
        log.warn("Local RAG answer rejected for message {}: {}", messageId, detail);
        transactions.executeWithoutResult(status -> {
            ChatMessage saved = messages.transition(messageId, owner, MessageStatus.FAILED);
            recordTrace(saved, analysis, retrievalResult, null, "FAILED", "VALIDATION_FAILED");
        });
        recordLocalUsage(UsageCategory.LOCAL_FAILED, analysis, retrievalResult,
                detail == null ? 0 : detail.length());
        return new ChatOutcome(messageId, MessageStatus.FAILED, outcome, reasonCode, List.of());
    }

    /**
     * Tokens are estimated from character counts because provider usage is not
     * available without billing access (OD-02). The metric still shows relative
     * daily pressure without inventing a currency amount.
     */
    private void recordLocalUsage(UsageCategory category, QueryAnalysis analysis,
                                  RetrievalResult retrievalResult, int outputChars) {
        Usage usage = Usage.embedding(analysis == null ? 0 : analysis.normalizedQuestion().length());
        if (retrievalResult != null && retrievalResult.gatePassed()) {
            long inputChars = retrievalResult.contextCandidates().stream()
                    .mapToLong(candidate -> candidate.rawText().length())
                    .sum();
            usage = usage.plus(Usage.grounding(inputChars / 4 + 1, Math.max(1, outputChars / 4)));
        }
        try {
            budget.record(category, usage);
        } catch (RuntimeException exception) {
            // Metrics are not allowed to turn a persisted answer into an error.
            log.warn("Cannot record cost usage for {}: {}", category, exception.getMessage());
        }
    }

    private void persistCitations(ChatMessage message, List<CitationDraft> drafts) {
        for (CitationDraft draft : drafts) {
            citations.save(new MessageCitation(
                    message,
                    documents.getReferenceById(draft.documentVersionId()),
                    articles.getReferenceById(draft.articleId()),
                    snapshots.getReferenceById(draft.snapshotId()),
                    draft.citationOrder(),
                    draft.excerptStart(),
                    draft.excerptEnd(),
                    draft.locator(),
                    draft.verbatimText()));
        }
    }

    private void recordTrace(ChatMessage message, QueryAnalysis analysis,
                             RetrievalResult retrievalResult, GroundingClient.Decision decision,
                             String decisionCode, String reasonCode) {
        List<vn.edu.hsf302.lawrag.retrieval.dto.RetrievalCandidate> candidates =
                retrievalResult == null ? List.of() : retrievalResult.contextCandidates();
        List<TemporalIssue> temporalIssues =
                retrievalResult == null ? List.of() : retrievalResult.temporalIssues();
        String modelId = decision == null ? GATE_MODEL : decision.modelId();
        String promptVersion = decision == null ? GATE_PROMPT_VERSION : decision.promptVersion();
        traces.record(
                message.getId(),
                analysis.originalQuestion(),
                analysis.applicableDate(),
                modelId,
                promptVersion,
                corpusVersion.corpusVersion(),
                embeddingClient.spaceId(),
                retrievalResult != null && retrievalResult.gatePassed(),
                retrievalResult == null ? 0.0 : retrievalResult.maxCosine(),
                decisionCode,
                reasonCode,
                candidates,
                decision == null ? List.of() : decision.selectedArticleIds(),
                temporalIssues);
    }

    private static String suggestedQuery(GroundingClient.Decision decision, QueryAnalysis analysis) {
        String candidate = decision.suggestedSearchQuery();
        if (candidate == null || candidate.isBlank()) {
            candidate = analysis.searchQuery();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = "quy định pháp luật giao thông đường bộ";
        }
        candidate = candidate.strip();
        return candidate.length() <= MAX_TEXT_LENGTH ? candidate : candidate.substring(0, MAX_TEXT_LENGTH);
    }

    private static String clarificationQuestion(GroundingClient.Decision decision, QueryAnalysis analysis) {
        String candidate = decision.clarificationQuestion();
        if (candidate == null || candidate.isBlank()) {
            candidate = analysis.clarificationQuestion();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = "Bạn cho biết thêm dữ kiện cụ thể để xác định quy định áp dụng không?";
        }
        candidate = candidate.strip();
        return candidate.length() <= MAX_TEXT_LENGTH ? candidate : candidate.substring(0, MAX_TEXT_LENGTH);
    }

    private static ChatOutcome persisted(ChatMessage message) {
        return new ChatOutcome(message.getId(), message.getMessageStatus(), null, null, List.of());
    }

    private static ChatOutcome outcome(UUID messageId, MessageStatus status,
                                   GroundingClient.Decision decision, List<UUID> selectedArticleIds) {
        return new ChatOutcome(messageId, status, decision.outcome(), decision.reasonCode(), selectedArticleIds);
    }
}
