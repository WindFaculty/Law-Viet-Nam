package vn.edu.hsf302.lawrag.websearch.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.repository.ChatMessageRepository;
import vn.edu.hsf302.lawrag.chat.service.MessageStateService;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.gemini.service.GroundingException;
import vn.edu.hsf302.lawrag.ingestion.service.DocumentFetcher;
import vn.edu.hsf302.lawrag.ingestion.service.DocumentFetchException;
import vn.edu.hsf302.lawrag.ingestion.service.SourceAllowlist;
import vn.edu.hsf302.lawrag.ingestion.service.WebIngestionPipelineService;
import vn.edu.hsf302.lawrag.observability.service.BudgetExceededException;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;
import vn.edu.hsf302.lawrag.observability.dto.Usage;
import vn.edu.hsf302.lawrag.observability.dto.UsageCategory;
import vn.edu.hsf302.lawrag.retrieval.service.AnswerRenderer;
import vn.edu.hsf302.lawrag.retrieval.service.CitationValidationException;
import vn.edu.hsf302.lawrag.retrieval.service.CitationValidator;
import vn.edu.hsf302.lawrag.retrieval.service.ContextGrader;
import vn.edu.hsf302.lawrag.retrieval.service.HybridRetrievalService;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.service.QueryAnalyzer;
import vn.edu.hsf302.lawrag.retrieval.dto.RenderedAnswer;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalResult;
import vn.edu.hsf302.lawrag.retrieval.dto.ValidatedCitation;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import vn.edu.hsf302.lawrag.websearch.dto.ClaimedJob;
import vn.edu.hsf302.lawrag.websearch.repository.WebSearchCandidateRepository;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStage;
import vn.edu.hsf302.lawrag.websearch.config.WebSearchProperties;

/**
 * Executes one claimed job at a time:
 * CLAIM -> SEARCH -> FETCH -> SNAPSHOT -> PARSE -> VALIDATE -> EMBED ->
 * PUBLISH -> RETRIEVE_AGAIN -> FINALIZE.
 *
 * <p>Network/API work never holds a database transaction. A daemon heartbeat
 * extends the lease; every stage and finalization is fenced by the lease token.
 * A crash at any point leaves either idempotent upserts or a reclaimable lease.
 */
@Service
@ConditionalOnProperty(prefix = "app.websearch", name = "enabled", havingValue = "true")
public class WebSearchWorker {

    private static final Logger log = LoggerFactory.getLogger(WebSearchWorker.class);

    private final WebSearchJobService jobs;
    private final WebSearchProperties properties;
    private final WebSearchProvider provider;
    private final WebIngestionPipelineService pipeline;
    private final DocumentFetcher fetcher;
    private final QuerySanitizer querySanitizer;
    private final WebSearchCandidateRepository candidates;
    private final SourceAllowlist allowlist;
    private final MessageStateService messages;
    private final ChatMessageRepository messageRepository;
    private final QueryAnalyzer queryAnalyzer;
    private final HybridRetrievalService retrieval;
    private final ContextGrader grader;
    private final CitationValidator citationValidator;
    private final AnswerRenderer answerRenderer;
    private final CostBudgetService budget;
    private final TransactionTemplate transactions;
    private final ScheduledExecutorService heartbeatExecutor;

    public WebSearchWorker(WebSearchJobService jobs,
                           WebSearchProperties properties,
                           WebSearchProvider provider,
                           WebIngestionPipelineService pipeline,
                           DocumentFetcher fetcher,
                           QuerySanitizer querySanitizer,
                           WebSearchCandidateRepository candidates,
                           SourceAllowlist allowlist,
                           MessageStateService messages,
                           ChatMessageRepository messageRepository,
                           QueryAnalyzer queryAnalyzer,
                           HybridRetrievalService retrieval,
                           ContextGrader grader,
                           CitationValidator citationValidator,
                           AnswerRenderer answerRenderer,
                           CostBudgetService budget,
                           PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.properties = properties;
        this.provider = provider;
        this.pipeline = pipeline;
        this.fetcher = fetcher;
        this.querySanitizer = querySanitizer;
        this.candidates = candidates;
        this.allowlist = allowlist;
        this.messages = messages;
        this.messageRepository = messageRepository;
        this.queryAnalyzer = queryAnalyzer;
        this.retrieval = retrieval;
        this.grader = grader;
        this.citationValidator = citationValidator;
        this.answerRenderer = answerRenderer;
        this.budget = budget;
        this.transactions = new TransactionTemplate(transactionManager);
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "websearch-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean runOnce() {
        jobs.recoverExpiredJobs();
        Optional<ClaimedJob> claim = jobs.claimNext(properties.workerId());
        claim.ifPresent(this::processClaimed);
        return claim.isPresent();
    }

    /** Target one known job (manual retry and deterministic acceptance tests). */
    public boolean runJob(UUID jobId) {
        jobs.recoverExpiredJobs();
        Optional<ClaimedJob> claim = jobs.claimJob(jobId, properties.workerId());
        claim.ifPresent(this::processClaimed);
        return claim.isPresent();
    }

    public void processClaimed(ClaimedJob claim) {
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        long heartbeatMillis = Math.max(1, properties.heartbeatInterval().toMillis());
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                jobs.heartbeat(claim.jobId(), claim.leaseToken());
            } catch (LeaseLostException exception) {
                leaseLost.set(true);
            } catch (RuntimeException exception) {
                log.warn("Heartbeat failed for job {}: {}", claim.jobId(), exception.getMessage());
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);

        WebUsage usage = new WebUsage();
        try {
            process(claim, leaseLost, usage);
        } catch (LeaseLostException exception) {
            log.warn("Worker stopped: lease lost for job {} (attempt {})",
                    claim.jobId(), claim.attemptCount());
        } catch (WebSearchJobDeadlineException exception) {
            log.warn("Worker stopped: deadline exceeded for job {}", claim.jobId());
            safelyFinalizeFailure(claim, WebSearchErrorCode.JOB_DEADLINE_EXCEEDED);
        } catch (RuntimeException exception) {
            log.error("Worker failed for job {} at attempt {}", claim.jobId(), claim.attemptCount(), exception);
            safelyHandleTransient(claim, WebSearchErrorCode.INTERNAL_ERROR);
        } finally {
            heartbeat.cancel(false);
            try {
                recordWebUsage(claim.messageId(), usage);
            } catch (RuntimeException exception) {
                // Cost metrics must never change the job's persisted outcome.
                log.warn("Cannot record web usage for job {}: {}",
                        claim.jobId(), exception.getMessage());
            }
        }
    }

    private void process(ClaimedJob claim, AtomicBoolean leaseLost, WebUsage usage) {
        WorkerMessageContext context = loadContext(claim.messageId());
        if (context == null) {
            jobs.finalizeFailure(claim.jobId(), claim.leaseToken(), WebSearchErrorCode.INTERNAL_ERROR);
            return;
        }
        if (context.confirmedAt() == null) {
            jobs.finalizeFailure(claim.jobId(), claim.leaseToken(), WebSearchErrorCode.CONSENT_MISSING);
            return;
        }
        if (!budget.crawlerEnabled()) {
            jobs.finalizeFailure(claim.jobId(), claim.leaseToken(), WebSearchErrorCode.BUDGET_EXCEEDED);
            return;
        }
        if (context.status() == MessageStatus.COMPLETED) {
            jobs.finishWithoutSideEffects(claim.jobId(), claim.leaseToken());
            return;
        }
        if (context.status() != MessageStatus.WEB_QUEUED
                && context.status() != MessageStatus.WEB_SEARCHING) {
            jobs.finishWithoutSideEffects(claim.jobId(), claim.leaseToken());
            return;
        }
        if (context.status() == MessageStatus.WEB_QUEUED) {
            messages.transition(context.messageId(), context.owner(), MessageStatus.WEB_SEARCHING);
        }
        if (Instant.now().isAfter(claim.deadline())) {
            throw new WebSearchJobDeadlineException("job deadline elapsed before search");
        }

        String query = querySanitizer.sanitize(
                context.suggestedQuery() != null ? context.suggestedQuery() : context.question());
        jobs.advanceStage(claim.jobId(), claim.leaseToken(), WebSearchJobStage.SEARCH);
        List<WebSearchProvider.SearchResult> results;
        usage.searchCalls++;
        try {
            results = provider.search(query, properties.maxResults()).stream()
                    .limit(properties.maxResults())
                    .toList();
        } catch (WebSearchProviderException exception) {
            handleProviderFailure(claim, exception.getErrorCode());
            return;
        }

        CandidateSelection selection = recordCandidates(claim.jobId(), query, results);
        if (selection.accepted().isEmpty()) {
            WebSearchErrorCode code = results.isEmpty()
                    ? WebSearchErrorCode.NO_EVIDENCE
                    : WebSearchErrorCode.ALLOWLIST_REJECTED;
            jobs.finalizeNoEvidence(claim.jobId(), claim.leaseToken(), code);
            return;
        }

        int readyDocuments = 0;
        WebSearchErrorCode lastError = null;
        WebSearchErrorCode transientError = null;
        for (URI url : selection.accepted()) {
            if (leaseLost.get()) {
                throw new LeaseLostException("heartbeat lost the lease for job " + claim.jobId());
            }
            if (Instant.now().isAfter(claim.deadline())) {
                throw new WebSearchJobDeadlineException("job deadline elapsed during fetch");
            }
            jobs.advanceStage(claim.jobId(), claim.leaseToken(), WebSearchJobStage.FETCH);
            DocumentFetcher.FetchedDocument fetched;
            try {
                fetched = fetcher.fetch(url);
                usage.fetchedUrls++;
                usage.downloadedBytes += fetched.body().length;
            } catch (DocumentFetchException exception) {
                candidates.recordResult(claim.jobId(), url.toString(), exception.getErrorCode().name());
                lastError = exception.getErrorCode();
                if (exception.getErrorCode().isTransient()) {
                    transientError = exception.getErrorCode();
                }
                continue;
            }

            WebIngestionPipelineService.IngestedWebDocument ingested;
            try {
                ingested = pipeline.ingest(claim.jobId(), claim.leaseToken(), url, fetched);
            } catch (DocumentFetchException exception) {
                candidates.recordResult(claim.jobId(), url.toString(), exception.getErrorCode().name());
                lastError = exception.getErrorCode();
                if (exception.getErrorCode().isTransient()) {
                    transientError = exception.getErrorCode();
                }
                continue;
            }
            candidates.recordResult(claim.jobId(), url.toString(),
                    ingested.ready() ? "READY" : ingested.errorCode().name());
            if (ingested.ready()) {
                readyDocuments++;
            } else {
                lastError = ingested.errorCode();
                if (ingested.errorCode().isTransient()) {
                    transientError = ingested.errorCode();
                }
            }
        }

        if (readyDocuments == 0) {
            if (transientError != null) {
                handleTransient(claim, transientError);
            } else {
                jobs.finalizeNoEvidence(claim.jobId(), claim.leaseToken(),
                        lastError == null ? WebSearchErrorCode.NO_EVIDENCE : lastError);
            }
            return;
        }

        jobs.advanceStage(claim.jobId(), claim.leaseToken(), WebSearchJobStage.RETRIEVE_AGAIN);
        reEvaluateAndFinalize(claim, context, usage);
    }

    private void reEvaluateAndFinalize(ClaimedJob claim,
                                       WorkerMessageContext context,
                                       WebUsage usage) {
        if (Instant.now().isAfter(claim.deadline())) {
            throw new WebSearchJobDeadlineException("job deadline elapsed before re-evaluation");
        }
        if (context.question() == null || context.question().isBlank()) {
            jobs.finalizeNoEvidence(claim.jobId(), claim.leaseToken(), WebSearchErrorCode.NO_EVIDENCE);
            return;
        }
        QueryAnalysis analysis = queryAnalyzer.analyze(context.question(), context.applicableDate());
        usage.embeddingCalls++;
        usage.embeddingChars += analysis.normalizedQuestion().length();
        RetrievalResult result = retrieval.retrieve(analysis);
        if (!result.gatePassed()) {
            jobs.finalizeNoEvidence(claim.jobId(), claim.leaseToken(), WebSearchErrorCode.NO_EVIDENCE);
            return;
        }

        GroundingClient.Decision decision;
        usage.groundingCalls++;
        usage.groundingInputTokens += result.contextCandidates().stream()
                .mapToLong(candidate -> candidate.rawText().length()).sum() / 4 + 1;
        try {
            decision = grader.grade(analysis, result);
            usage.groundingOutputTokens += Math.max(1, outputChars(decision) / 4);
        } catch (BudgetExceededException exception) {
            jobs.finalizeFailure(claim.jobId(), claim.leaseToken(), WebSearchErrorCode.BUDGET_EXCEEDED);
            return;
        } catch (GroundingException exception) {
            handleTransient(claim, WebSearchErrorCode.PROVIDER_5XX);
            return;
        }
        if (decision.outcome() != GroundingClient.Outcome.ANSWER) {
            jobs.finalizeNoEvidence(claim.jobId(), claim.leaseToken(), WebSearchErrorCode.NO_EVIDENCE);
            return;
        }

        try {
            List<ValidatedCitation> validated =
                    citationValidator.validate(decision, analysis, result.contextCandidates());
            RenderedAnswer rendered = answerRenderer.render(validated);
            usage.groundingOutputTokens += Math.max(1, rendered.content().length() / 4);
            jobs.finalizeSuccess(claim.jobId(), claim.leaseToken(), rendered.content(), rendered);
        } catch (CitationValidationException exception) {
            log.warn("Web re-evaluation rejected citations for job {}: {}",
                    claim.jobId(), exception.getMessage());
            jobs.finalizeNoEvidence(claim.jobId(), claim.leaseToken(), WebSearchErrorCode.NO_EVIDENCE);
        }
    }

    private CandidateSelection recordCandidates(UUID jobId,
                                                String query,
                                                List<WebSearchProvider.SearchResult> results) {
        String providerName = provider.getClass().getSimpleName();
        List<URI> accepted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (WebSearchProvider.SearchResult result : results) {
            URI url = result.url();
            String rejection = allowlist.rejectionReason(url, true);
            String canonical = null;
            if (rejection == null) {
                try {
                    canonical = allowlist.canonicalize(url).toString();
                } catch (RuntimeException exception) {
                    rejection = "MALFORMED_URL";
                }
            }
            if (rejection == null && !seen.add(canonical)) {
                rejection = "DUPLICATE_URL";
            }
            if (rejection == null
                    && accepted.size() >= Math.min(properties.maxUrlsPerJob(), budget.maxUrlsPerJob())) {
                rejection = "URL_LIMIT";
            }
            if (rejection == null) {
                accepted.add(URI.create(canonical));
                candidates.record(jobId, query, providerName, URI.create(canonical),
                        result.rank(), true, null);
            } else {
                candidates.record(jobId, query, providerName, url, result.rank(), false, rejection);
            }
        }
        return new CandidateSelection(List.copyOf(accepted));
    }

    private void handleProviderFailure(ClaimedJob claim,
                                       WebSearchErrorCode errorCode) {
        if (errorCode.isTransient()) {
            handleTransient(claim, errorCode);
        } else {
            jobs.finalizeFailure(claim.jobId(), claim.leaseToken(), errorCode);
        }
    }

    private void handleTransient(ClaimedJob claim, WebSearchErrorCode errorCode) {
        boolean retried = jobs.scheduleRetry(claim.jobId(), claim.leaseToken(), errorCode);
        log.warn("Job {} transient error {}; retry scheduled={}",
                claim.jobId(), errorCode, retried);
    }

    private void safelyHandleTransient(ClaimedJob claim, WebSearchErrorCode code) {
        try {
            handleTransient(claim, code);
        } catch (LeaseLostException exception) {
            log.warn("Cannot schedule retry for job {}: lease already lost", claim.jobId());
        }
    }

    private void safelyFinalizeFailure(ClaimedJob claim, WebSearchErrorCode code) {
        try {
            jobs.finalizeFailure(claim.jobId(), claim.leaseToken(), code);
        } catch (LeaseLostException exception) {
            log.warn("Cannot finalize job {}: lease already lost", claim.jobId());
        }
    }

    private static int outputChars(GroundingClient.Decision decision) {
        if (decision.suggestedSearchQuery() != null) {
            return decision.suggestedSearchQuery().length();
        }
        return decision.clarificationQuestion() == null ? 0 : decision.clarificationQuestion().length();
    }

    private void recordWebUsage(UUID messageId, WebUsage usage) {
        if (usage.toUsage().isEmpty()) {
            return;
        }
        UsageCategory category = transactions.execute(status -> messageRepository.findById(messageId)
                .map(message -> categoryFor(message.getMessageStatus()))
                .orElse(null));
        if (category != null) {
            budget.record(category, usage.toUsage());
        }
    }

    private static UsageCategory categoryFor(MessageStatus status) {
        return switch (status) {
            case COMPLETED -> UsageCategory.WEB_COMPLETED;
            case NO_EVIDENCE -> UsageCategory.WEB_NO_EVIDENCE;
            case WEB_FAILED, FAILED -> UsageCategory.WEB_FAILED;
            case CANCELLED -> UsageCategory.WEB_CANCELLED;
            default -> null;
        };
    }

    private WorkerMessageContext loadContext(UUID messageId) {
        return transactions.execute(status -> {
            ChatMessage message = messageRepository.findById(messageId).orElse(null);
            if (message == null) {
                return null;
            }
            return new WorkerMessageContext(
                    message.getId(),
                    message.getMessageStatus(),
                    message.getConfirmedAt(),
                    message.getSuggestedQuery(),
                    message.getApplicableDate(),
                    message.getInReplyTo() == null ? null : message.getInReplyTo().getContent(),
                    message.getSession().owner());
        });
    }

    @PreDestroy
    void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    private record WorkerMessageContext(
            UUID messageId,
            MessageStatus status,
            Instant confirmedAt,
            String suggestedQuery,
            java.time.LocalDate applicableDate,
            String question,
            SessionOwner owner) {
    }

    private record CandidateSelection(List<URI> accepted) {
    }

    private static final class WebUsage {

        private int embeddingCalls;
        private long embeddingChars;
        private int groundingCalls;
        private long groundingInputTokens;
        private long groundingOutputTokens;
        private int searchCalls;
        private int fetchedUrls;
        private long downloadedBytes;

        private Usage toUsage() {
            return new Usage(embeddingCalls, embeddingChars, 0, groundingCalls,
                    groundingInputTokens, groundingOutputTokens, searchCalls, fetchedUrls, downloadedBytes);
        }
    }
}
