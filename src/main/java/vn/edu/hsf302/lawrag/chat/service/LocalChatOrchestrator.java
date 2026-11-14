package vn.edu.hsf302.lawrag.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
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
import vn.edu.hsf302.lawrag.retrieval.dto.ClarificationContext;
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

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.dto.ChatOutcome;
import vn.edu.hsf302.lawrag.chat.entity.MessageCitation;
import vn.edu.hsf302.lawrag.chat.repository.MessageCitationRepository;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.dto.SubmittedQuestion;
import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;
import vn.edu.hsf302.lawrag.llm.service.LlmPersonaService;

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
    private static final long STREAM_TIMEOUT_MILLIS = 120_000L;
    private static final long STREAM_RETAIN_SECONDS = 30L;
    private static final int FALLBACK_CHUNK_SIZE = 24;
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
    private final LlmPersonaService personas;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<UUID, StreamSession> activeStreams = new ConcurrentHashMap<>();
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService streamCleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-stream-cleanup");
        thread.setDaemon(true);
        return thread;
    });

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
                                 CostBudgetService budget,
                                 LlmPersonaService personas,
                                 ObjectMapper objectMapper) {
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
        this.personas = personas;
        this.objectMapper = objectMapper;
    }

    public ChatOutcome ask(UUID sessionId, SessionOwner owner, String idempotencyKey, String question) {
        return ask(sessionId, owner, idempotencyKey, question, null);
    }

    public ChatOutcome ask(UUID sessionId, SessionOwner owner, String idempotencyKey,
                           String question, String personaCode) {
        SubmittedQuestion submitted =
                submissions.submitQuestion(sessionId, owner, idempotencyKey, question, personaCode);
        if (submitted.replay()) {
            return persisted(submitted.aiMessage());
        }
        return processInternal(
                submitted.aiMessage().getId(),
                submitted.aiMessage().getApplicableDate(),
                submitted.aiMessage().getPersonaCode(),
                submitted.userMessage().getContent(),
                owner,
                StreamSink.NOOP);
    }

    public ChatOutcome submitForStream(UUID sessionId, SessionOwner owner, String idempotencyKey,
                                       String question, String personaCode) {
        SubmittedQuestion submitted =
                submissions.submitQuestion(sessionId, owner, idempotencyKey, question, personaCode);
        ChatMessage aiMessage = submitted.aiMessage();
        if (!submitted.replay()
                || (aiMessage.getMessageStatus() == MessageStatus.PROCESSING_LOCAL
                        && !activeStreams.containsKey(aiMessage.getId()))) {
            StreamWork work = new StreamWork(
                    aiMessage.getId(),
                    aiMessage.getApplicableDate(),
                    aiMessage.getPersonaCode(),
                    submitted.userMessage().getContent(),
                    owner);
            startStreamSession(work);
        }
        return persisted(aiMessage);
    }

    public SseEmitter connectStream(UUID messageId, SessionOwner owner) {
        MessageViewSnapshot snapshot = transactions.execute(status -> {
            ChatMessage message = messages.getOwnedMessage(messageId, owner);
            String question = message.getInReplyTo() == null ? null : message.getInReplyTo().getContent();
            return new MessageViewSnapshot(
                    message.getId(),
                    message.getMessageStatus(),
                    message.getApplicableDate(),
                    message.getPersonaCode(),
                    question);
        });
        if (snapshot == null) {
            throw new IllegalStateException("Message snapshot could not be loaded");
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        StreamSession existing = activeStreams.get(messageId);
        if (existing != null) {
            existing.attach(emitter);
            return emitter;
        }
        if (snapshot.status() != MessageStatus.PROCESSING_LOCAL) {
            sendImmediateComplete(emitter, snapshot.status());
            return emitter;
        }
        StreamSession session = startStreamSession(new StreamWork(
                snapshot.messageId(),
                snapshot.applicableDate(),
                snapshot.personaCode(),
                snapshot.question(),
                owner));
        session.attach(emitter);
        return emitter;
    }

    @PreDestroy
    void shutdownStreamExecutors() {
        streamExecutor.shutdownNow();
        streamCleanupExecutor.shutdownNow();
    }

    private StreamSession startStreamSession(StreamWork work) {
        AtomicBoolean created = new AtomicBoolean(false);
        StreamSession session = activeStreams.computeIfAbsent(work.messageId(), id -> {
            created.set(true);
            return new StreamSession(id);
        });
        if (created.get()) {
            streamExecutor.submit(() -> runStreamWork(work, session));
        }
        return session;
    }

    private void runStreamWork(StreamWork work, StreamSession session) {
        MessageStatus terminal = MessageStatus.FAILED;
        try {
            MessageStatus currentStatus = transactions.execute(status ->
                    messages.getOwnedMessage(work.messageId(), work.owner()).getMessageStatus());
            if (currentStatus != null && currentStatus != MessageStatus.PROCESSING_LOCAL) {
                terminal = currentStatus;
                return;
            }
            if (work.question() == null || work.question().isBlank()) {
                transactions.executeWithoutResult(status ->
                        messages.transition(work.messageId(), work.owner(), MessageStatus.FAILED));
                terminal = MessageStatus.FAILED;
                return;
            }
            ChatOutcome result = processInternal(
                    work.messageId(),
                    work.applicableDate(),
                    work.personaCode(),
                    work.question(),
                    work.owner(),
                    new ActiveStreamSink(session));
            terminal = result.status();
        } catch (RuntimeException exception) {
            log.error("Streaming orchestration failed for message {}", work.messageId(), exception);
            try {
                MessageStatus currentStatus = transactions.execute(status ->
                        messages.getOwnedMessage(work.messageId(), work.owner()).getMessageStatus());
                if (currentStatus == MessageStatus.PROCESSING_LOCAL) {
                    transactions.executeWithoutResult(status ->
                            messages.transition(work.messageId(), work.owner(), MessageStatus.FAILED));
                    terminal = MessageStatus.FAILED;
                } else if (currentStatus != null) {
                    terminal = currentStatus;
                }
            } catch (RuntimeException cleanupException) {
                log.warn("Failed to transition message {} after stream error: {}",
                        work.messageId(), cleanupException.getMessage());
            }
        } finally {
            session.finish(terminal);
            streamCleanupExecutor.schedule(
                    () -> activeStreams.remove(work.messageId(), session),
                    STREAM_RETAIN_SECONDS,
                    TimeUnit.SECONDS);
        }
    }

    private ChatOutcome processInternal(UUID messageId,
                                        LocalDate initialApplicableDate,
                                        String personaCode,
                                        String question,
                                        SessionOwner owner,
                                        StreamSink sink) {
        sink.emitStage("ANALYZING", "Đang phân tích câu hỏi và thời điểm áp dụng…");
        ClarificationContext clarificationContext = messages.resolveClarificationContext(messageId);
        String effectiveQuestion = clarificationContext.effectiveQuestion() != null
                && !clarificationContext.effectiveQuestion().isBlank()
                ? clarificationContext.effectiveQuestion()
                : question;
        QueryAnalysis analysis = queryAnalyzer.analyze(effectiveQuestion, initialApplicableDate);
        if (!analysis.applicableDate().equals(initialApplicableDate)) {
            transactions.executeWithoutResult(status ->
                    messages.updateApplicableDate(messageId, owner, analysis.applicableDate()));
        }

        if (analysis.outOfScope()) {
            sink.emitTextChunks(OUT_OF_SCOPE_REPLY);
            transactions.executeWithoutResult(status -> {
                ChatMessage saved = messages.markOutOfScope(
                        messageId, owner, OUT_OF_SCOPE_REPLY);
                recordTrace(saved, analysis, null, null, "OUT_OF_SCOPE", "OUT_OF_SCOPE");
            });
            sink.complete(MessageStatus.COMPLETED);
            return new ChatOutcome(messageId, MessageStatus.COMPLETED, null, null, List.of());
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
            sink.emitTextChunks(decision.clarificationQuestion());
            transactions.executeWithoutResult(status -> {
                ChatMessage saved = messages.requestClarification(
                        messageId, owner, decision.clarificationQuestion());
                recordTrace(saved, analysis, null, decision, decision.outcome().name(),
                        decision.reasonCode().name());
            });
            sink.complete(MessageStatus.NEEDS_CLARIFICATION);
            return outcome(messageId, MessageStatus.NEEDS_CLARIFICATION, decision, List.of());
        }

        LlmPersona persona = personas.resolveOrDefault(personaCode);
        sink.emitStage("RETRIEVING", "Đang tìm và đối chiếu điều khoản trong kho nội bộ…");
        RetrievalResult retrievalResult = retrieval.retrieve(analysis);
        boolean hasRagContext = !retrievalResult.contextCandidates().isEmpty()
                || !retrievalResult.temporalIssues().isEmpty();
        AtomicBoolean emittedFromModel = new AtomicBoolean(false);
        GroundingClient.Decision decision;
        if (!hasRagContext) {
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
                if (sink.isStreaming()) {
                    sink.emitStage("GENERATING", "Đang tổng hợp câu trả lời từ căn cứ pháp lý…");
                    decision = grader.gradeStream(
                            analysis, retrievalResult, persona, clarificationContext.history(), delta -> {
                        if (delta != null && !delta.isEmpty()) {
                            emittedFromModel.set(true);
                            sink.emitDelta(delta);
                        }
                    });
                } else {
                    decision = grader.grade(
                            analysis, retrievalResult, persona, clarificationContext.history());
                }
            } catch (GroundingException | BudgetExceededException exception) {
                // No unvalidated answer: persist FAILED, release the hold and keep history.
                ChatOutcome failed = fail(messageId, owner, analysis, retrievalResult,
                        GroundingClient.Outcome.NEED_WEB_SEARCH,
                        GroundingClient.ReasonCode.NO_MATCH, exception.getMessage());
                sink.complete(MessageStatus.FAILED);
                return failed;
            }
        }

        switch (decision.outcome()) {
            case ANSWER -> {
                try {
                    List<ValidatedCitation> validated = citationValidator.validate(
                            decision, analysis, retrievalResult.contextCandidates());
                    RenderedAnswer rendered = answerRenderer.render(validated, decision.answer(), persona);
                    if (!emittedFromModel.get()) {
                        sink.emitTextChunks(rendered.content());
                    }
                    transactions.executeWithoutResult(status -> {
                        ChatMessage saved = messages.complete(
                                messageId, owner, rendered.content());
                        persistCitations(saved, rendered.citations());
                        recordTrace(saved, analysis, retrievalResult, decision,
                                decision.outcome().name(), decision.reasonCode().name());
                    });
                    recordLocalUsage(UsageCategory.LOCAL_ANSWERED, analysis, retrievalResult,
                            hasRagContext, rendered.content().length());
                    sink.complete(MessageStatus.COMPLETED);
                    return outcome(messageId, MessageStatus.COMPLETED, decision,
                            validated.stream().map(ValidatedCitation::articleId).toList());
                } catch (CitationValidationException exception) {
                    ChatOutcome failed = fail(messageId, owner, analysis, retrievalResult,
                            decision.outcome(), decision.reasonCode(), exception.getMessage());
                    sink.complete(MessageStatus.FAILED);
                    return failed;
                }
            }
            case NEED_WEB_SEARCH -> {
                String suggestedQuery = suggestedQuery(decision, analysis);
                transactions.executeWithoutResult(status -> {
                    ChatMessage saved = messages.markAwaitingWebConfirm(
                            messageId, owner, suggestedQuery);
                    recordTrace(saved, analysis, retrievalResult, decision,
                            decision.outcome().name(), decision.reasonCode().name());
                });
                recordLocalUsage(UsageCategory.LOCAL_AWAITING_CONFIRM, analysis, retrievalResult,
                        hasRagContext, suggestedQuery.length());
                sink.complete(MessageStatus.AWAITING_WEB_CONFIRM);
                return outcome(messageId, MessageStatus.AWAITING_WEB_CONFIRM, decision, List.of());
            }
            case NEED_CLARIFICATION -> {
                String clarification = clarificationQuestion(decision, analysis);
                if (!emittedFromModel.get()) {
                    sink.emitTextChunks(clarification);
                }
                transactions.executeWithoutResult(status -> {
                    ChatMessage saved = messages.requestClarification(
                            messageId, owner, clarification);
                    recordTrace(saved, analysis, retrievalResult, decision,
                            decision.outcome().name(), decision.reasonCode().name());
                });
                recordLocalUsage(UsageCategory.CLARIFICATION, analysis, retrievalResult,
                        hasRagContext, clarification.length());
                sink.complete(MessageStatus.NEEDS_CLARIFICATION);
                return outcome(messageId, MessageStatus.NEEDS_CLARIFICATION, decision, List.of());
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
        boolean hasRagContext = retrievalResult != null
                && (!retrievalResult.contextCandidates().isEmpty() || !retrievalResult.temporalIssues().isEmpty());
        recordLocalUsage(UsageCategory.LOCAL_FAILED, analysis, retrievalResult,
                hasRagContext, detail == null ? 0 : detail.length());
        return new ChatOutcome(messageId, MessageStatus.FAILED, outcome, reasonCode, List.of());
    }

    /**
     * Tokens are estimated from character counts because provider usage is not
     * available without billing access (OD-02). The metric still shows relative
     * daily pressure without inventing a currency amount.
     */
    private void recordLocalUsage(UsageCategory category, QueryAnalysis analysis,
                                  RetrievalResult retrievalResult, boolean graded, int outputChars) {
        Usage usage = Usage.embedding(analysis == null ? 0 : analysis.normalizedQuestion().length());
        if (retrievalResult != null && graded) {
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
                temporalIssues,
                message.getPersonaCode());
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

    private void sendImmediateComplete(SseEmitter emitter, MessageStatus status) {
        try {
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(toJson(Map.of("status", status.name()))));
            emitter.complete();
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize stream event", exception);
        }
    }

    private record StreamWork(
            UUID messageId,
            LocalDate applicableDate,
            String personaCode,
            String question,
            SessionOwner owner) {
    }

    private record MessageViewSnapshot(
            UUID messageId,
            MessageStatus status,
            LocalDate applicableDate,
            String personaCode,
            String question) {
    }

    private record StreamEvent(String name, String jsonData) {
    }

    private interface StreamSink {

        StreamSink NOOP = new StreamSink() {
            @Override
            public boolean isStreaming() {
                return false;
            }

            @Override
            public void emitStage(String stage, String message) {
            }

            @Override
            public void emitDelta(String delta) {
            }

            @Override
            public void emitTextChunks(String fullText) {
            }

            @Override
            public void complete(MessageStatus status) {
            }
        };

        boolean isStreaming();

        void emitStage(String stage, String message);

        void emitDelta(String delta);

        void emitTextChunks(String fullText);

        void complete(MessageStatus status);
    }

    private final class ActiveStreamSink implements StreamSink {

        private final StreamSession session;

        private ActiveStreamSink(StreamSession session) {
            this.session = session;
        }

        @Override
        public boolean isStreaming() {
            return true;
        }

        @Override
        public void emitStage(String stage, String message) {
            session.emit("stage", toJson(Map.of("stage", stage, "message", message)));
        }

        @Override
        public void emitDelta(String delta) {
            if (delta != null && !delta.isEmpty()) {
                session.emit("delta", toJson(Map.of("text", delta)));
            }
        }

        @Override
        public void emitTextChunks(String fullText) {
            if (fullText == null || fullText.isEmpty()) {
                return;
            }
            int length = fullText.length();
            int start = 0;
            while (start < length) {
                int end = Math.min(length, start + FALLBACK_CHUNK_SIZE);
                if (end < length) {
                    int space = fullText.lastIndexOf(' ', end);
                    if (space > start) {
                        end = space + 1;
                    }
                }
                emitDelta(fullText.substring(start, end));
                start = end;
            }
        }

        @Override
        public void complete(MessageStatus status) {
            session.finish(status);
        }
    }

    private final class StreamSession {

        private final UUID messageId;
        private final Object lock = new Object();
        private final List<StreamEvent> history = new ArrayList<>();
        private final List<SseEmitter> emitters = new ArrayList<>();
        private boolean finished;

        private StreamSession(UUID messageId) {
            this.messageId = messageId;
        }

        private void attach(SseEmitter emitter) {
            emitter.onCompletion(() -> removeEmitter(emitter));
            emitter.onTimeout(() -> removeEmitter(emitter));
            emitter.onError(ignored -> removeEmitter(emitter));

            synchronized (lock) {
                for (StreamEvent event : history) {
                    if (!trySend(emitter, event)) {
                        return;
                    }
                }
                if (finished) {
                    emitter.complete();
                } else {
                    emitters.add(emitter);
                }
            }
        }

        private void emit(String name, String jsonData) {
            synchronized (lock) {
                if (finished) {
                    return;
                }
                StreamEvent event = new StreamEvent(name, jsonData);
                history.add(event);
                Iterator<SseEmitter> iterator = emitters.iterator();
                while (iterator.hasNext()) {
                    SseEmitter emitter = iterator.next();
                    if (!trySend(emitter, event)) {
                        iterator.remove();
                    }
                }
            }
        }

        private void finish(MessageStatus status) {
            synchronized (lock) {
                if (finished) {
                    return;
                }
                finished = true;
                StreamEvent completeEvent = new StreamEvent(
                        "complete", toJson(Map.of("status", status.name())));
                history.add(completeEvent);
                for (SseEmitter emitter : emitters) {
                    if (trySend(emitter, completeEvent)) {
                        emitter.complete();
                    }
                }
                emitters.clear();
            }
        }

        private void removeEmitter(SseEmitter emitter) {
            synchronized (lock) {
                emitters.remove(emitter);
            }
        }

        private boolean trySend(SseEmitter emitter, StreamEvent event) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.name())
                        .data(event.jsonData()));
                return true;
            } catch (IOException | IllegalStateException exception) {
                log.debug("SSE client disconnected for message {}: {}", messageId, exception.getMessage());
                return false;
            }
        }
    }
}
