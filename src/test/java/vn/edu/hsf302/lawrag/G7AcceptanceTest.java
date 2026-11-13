package vn.edu.hsf302.lawrag;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hsf302.lawrag.chat.service.ChatConsentService;
import vn.edu.hsf302.lawrag.chat.dto.ChatOutcome;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.service.ChatSessionService;
import vn.edu.hsf302.lawrag.chat.service.LocalChatOrchestrator;
import vn.edu.hsf302.lawrag.chat.service.MessageStateService;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.service.MessageSubmissionService;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.dto.SubmittedQuestion;
import vn.edu.hsf302.lawrag.gemini.service.DeterministicGroundingClient;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.gemini.service.GroundingException;
import vn.edu.hsf302.lawrag.ingestion.service.CorpusImportService;
import vn.edu.hsf302.lawrag.ingestion.service.DocumentFetcher;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;
import vn.edu.hsf302.lawrag.quota.service.QuotaExceededException;
import vn.edu.hsf302.lawrag.quota.service.QuotaRecoveryService;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationException;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationService;
import vn.edu.hsf302.lawrag.security.web.AnonymousIdentityFilter;
import vn.edu.hsf302.lawrag.websearch.dto.ClaimedJob;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;
import vn.edu.hsf302.lawrag.websearch.service.WebSearchJobService;
import vn.edu.hsf302.lawrag.websearch.service.WebSearchProvider;
import vn.edu.hsf302.lawrag.websearch.service.WebSearchWorker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G7 evidence against real PostgreSQL + pgvector: last-slot contention,
 * transactional finalization, expired-reservation recovery with web-job leases,
 * no duplicate ledger rows, locked rate limits, and budget/provider degradation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "app.websearch.enabled=true",
        "app.websearch.provider=test-double",
        "app.websearch.scheduler=false",
        "app.websearch.retry-backoff=0s",
        "app.ingestion.enabled=true",
        "app.ratelimit.enabled=true",
        "app.ratelimit.question-min-interval=5s",
        "app.ratelimit.questions-per-ip-per-hour=20",
        "app.ratelimit.confirm-retry-per-subject-per-minute=10",
        "app.ratelimit.polls-per-session-per-minute=60",
        "app.budget.crawler-enabled=true",
        "app.budget.gemini-enabled=true",
        "app.budget.max-gemini-calls-per-day=200",
        "app.budget.max-embedding-calls-per-day=2000",
        "app.budget.max-web-jobs-per-day=20",
        "app.budget.max-tokens-per-context=12000",
        "app.budget.max-urls-per-job=5",
        "app.budget.max-concurrent-web-jobs=2"
})
@Import(G7AcceptanceTest.TestAdapters.class)
class G7AcceptanceTest extends PostgresIntegrationTest {

    private static final String HELMET_QUESTION =
            "Người điều khiển xe máy có phải đội mũ bảo hiểm không?";
    private static final String PENALTY_QUESTION =
            "Theo Luật 97/2026/QH15, người điều khiển xe máy vượt đèn đỏ bị phạt bao nhiêu tiền?";
    private static final String VALID_URL = "https://vanban.chinhphu.vn/chi-tiet/luat-97-2026";
    private static final String VALID_DOC_NUMBER = "97/2026/QH15";
    private static final String ARTICLE_TEXT =
            "Người điều khiển xe máy vượt đèn đỏ bị phạt tiền từ 4.000.000 đồng đến 6.000.000 đồng.";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CorpusImportService corpusImportService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private MessageSubmissionService messageSubmissionService;

    @Autowired
    private MessageStateService messageStateService;

    @Autowired
    private LocalChatOrchestrator orchestrator;

    @Autowired
    private ChatConsentService consentService;

    @Autowired
    private QuotaReservationService quotaReservationService;

    @Autowired
    private QuotaRecoveryService quotaRecoveryService;

    @Autowired
    private WebSearchWorker worker;

    @Autowired
    private WebSearchJobService jobService;

    @Autowired
    private CostBudgetService budget;

    @Autowired
    private ConfigurableGroundingClient groundingClient;

    @Autowired
    private ConfigurableWebSearchProvider webSearchProvider;

    @Autowired
    private ConfigurableDocumentFetcher documentFetcher;

    @BeforeAll
    void seedCorpusAndResetSharedBudgets() {
        corpusImportService.importCorpus();
        // Earlier acceptance classes share this database; budget windows are
        // day-wide, so start G7 with a clean job/cost window.
        jdbcTemplate.update("delete from web_search_jobs");
        jdbcTemplate.update("delete from cost_usage_daily where usage_date = ?",
                quotaReservationService.currentPeriod());
    }

    @BeforeEach
    void resetAdapters() {
        groundingClient.reset();
        webSearchProvider.reset();
        documentFetcher.reset();
    }

    // -----------------------------------------------------------------------
    // Last-slot contention
    // -----------------------------------------------------------------------

    @Test
    void tenConcurrentRequestsForTheLastSlotProduceOneWinnerAndNineQuotaRejections() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        seedBucket(owner, 4, 0);

        List<Callable<SubmittedQuestion>> calls = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String key = "g7-race-" + index;
            calls.add(() -> messageSubmissionService.submitQuestion(
                    session.getId(), owner, key, HELMET_QUESTION));
        }
        List<Attempt<SubmittedQuestion>> results = runConcurrently(calls);

        assertThat(results.stream().filter(Attempt::succeeded)).hasSize(1);
        List<Throwable> losers = results.stream()
                .filter(result -> !result.succeeded())
                .map(Attempt::error)
                .toList();
        assertThat(losers).hasSize(9);
        assertThat(losers).allMatch(QuotaExceededException.class::isInstance);

        BucketState bucket = bucket(owner);
        assertThat(bucket.limitCount()).isEqualTo(5);
        assertThat(bucket.usedCount()).isEqualTo(4);
        assertThat(bucket.reservedCount()).isEqualTo(1);
        assertThat(bucket.usedCount()).isGreaterThanOrEqualTo(0);
        assertThat(bucket.reservedCount()).isGreaterThanOrEqualTo(0);
        assertThat(bucket.usedCount() + bucket.reservedCount()).isLessThanOrEqualTo(bucket.limitCount());
    }

    @Test
    void exhaustedQuotaMapsTo429WithResetGuidance() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        seedBucket(owner, 5, 0);

        mockMvc.perform(submitRequest(session.getId(), owner.ownerId(), "g7-http-exhausted",
                        HELMET_QUESTION, "10.7.0.1")
                        .header("HX-Request", "true"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("hết 5 lượt hôm nay")));
    }

    // -----------------------------------------------------------------------
    // Atomic finalization
    // -----------------------------------------------------------------------

    @Test
    void finalizationFailureRollsBackCompletedAndQuotaCharge() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                session.getId(), owner, "g7-rollback", HELMET_QUESTION).aiMessage().getId();

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            messageStateService.complete(messageId, owner, "Câu trả lời có căn cứ.");
            throw new IllegalStateException("simulated failure after answer write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(messageStatus(messageId)).isEqualTo("PROCESSING_LOCAL");
        BucketState bucket = bucket(owner);
        assertThat(bucket.usedCount()).isZero();
        assertThat(bucket.reservedCount()).isEqualTo(1);
        assertThat(ledgerCount(messageId)).isZero();
    }

    // -----------------------------------------------------------------------
    // Expired reservation recovery
    // -----------------------------------------------------------------------

    @Test
    void recoveryExpiresHoldAndFailsStuckLocalMessageWhenNoJobExists() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                session.getId(), owner, "g7-recovery-local", HELMET_QUESTION).aiMessage().getId();
        jdbcTemplate.update("""
                update quota_reservations set expires_at = now() - interval '1 minute'
                where message_id = ? and status = 'ACTIVE'
                """, messageId);

        assertThat(quotaRecoveryService.recoverExpiredReservations()).isGreaterThanOrEqualTo(1);

        assertThat(reservationStatus(messageId)).isEqualTo("EXPIRED");
        assertThat(messageStatus(messageId)).isEqualTo("EXPIRED");
        BucketState bucket = bucket(owner);
        assertThat(bucket.reservedCount()).isZero();
        assertThat(bucket.usedCount()).isZero();
        assertThat(ledgerCount(messageId)).isZero();
    }

    @Test
    void recoveryExtendsHoldWhileAWebJobStillHoldsAValidLease() {
        JobFixture job = confirmedJob("g7-recovery-live", PENALTY_QUESTION, "mức phạt xe máy");
        ClaimedJob claim =
                jobService.claimJob(job.jobId(), "g7-live-worker").orElseThrow();
        jdbcTemplate.update("""
                update quota_reservations set expires_at = now() - interval '1 minute'
                where message_id = ? and status = 'ACTIVE'
                """, job.messageId());

        quotaRecoveryService.recoverExpiredReservations();

        assertThat(reservationStatus(job.messageId())).isEqualTo("ACTIVE");
        assertThat(count("""
                select count(*) from quota_reservations
                where message_id = ? and status = 'ACTIVE'
                  and expires_at > now() + interval '10 minutes'
                """, job.messageId())).isEqualTo(1);
        assertThat(bucket(job.owner()).reservedCount()).isEqualTo(1);
        assertThat(messageStatus(job.messageId())).isEqualTo("WEB_QUEUED");

        jobService.finalizeFailure(job.jobId(), claim.leaseToken(), WebSearchErrorCode.INTERNAL_ERROR);
        assertThat(messageStatus(job.messageId())).isEqualTo("WEB_FAILED");
        assertThat(bucket(job.owner()).reservedCount()).isZero();
    }

    @Test
    void recoveryExpiresHoldWhenTheJobLeaseAndAttemptsCannotContinue() {
        JobFixture job = confirmedJob("g7-recovery-dead", PENALTY_QUESTION, "mức phạt xe máy");
        jobService.claimJob(job.jobId(), "g7-dead-worker").orElseThrow();
        jdbcTemplate.update("""
                update web_search_jobs
                set lease_expires_at = now() - interval '1 minute', attempt_count = 2
                where id = ?
                """, job.jobId());
        jdbcTemplate.update("""
                update quota_reservations set expires_at = now() - interval '1 minute'
                where message_id = ? and status = 'ACTIVE'
                """, job.messageId());

        quotaRecoveryService.recoverExpiredReservations();

        assertThat(reservationStatus(job.messageId())).isEqualTo("EXPIRED");
        assertThat(messageStatus(job.messageId())).isEqualTo("WEB_FAILED");
        assertThat(bucket(job.owner()).reservedCount()).isZero();
        assertThat(bucket(job.owner()).usedCount()).isZero();
        assertThat(ledgerCount(job.messageId())).isZero();
    }

    @Test
    void staleWorkerCannotCommitAfterItsReservationWasExpired() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                session.getId(), owner, "g7-stale-expired", HELMET_QUESTION).aiMessage().getId();
        expireReservationButKeepMessageProcessing(messageId);

        assertThatThrownBy(() -> messageStateService.complete(
                messageId, owner, "Câu trả lời không được công bố."))
                .isInstanceOf(QuotaReservationException.class);

        assertThat(messageStatus(messageId)).isEqualTo("PROCESSING_LOCAL");
        assertThat(bucket(owner).usedCount()).isZero();
        assertThat(ledgerCount(messageId)).isZero();
    }

    @Test
    void staleWorkerCannotCommitAnActiveButTimeExpiredReservation() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                session.getId(), owner, "g7-stale-timeout", HELMET_QUESTION).aiMessage().getId();
        jdbcTemplate.update("""
                update quota_reservations set expires_at = now() - interval '1 second'
                where message_id = ? and status = 'ACTIVE'
                """, messageId);

        assertThatThrownBy(() -> messageStateService.complete(
                messageId, owner, "Câu trả lời không được công bố."))
                .isInstanceOf(QuotaReservationException.class);

        assertThat(messageStatus(messageId)).isEqualTo("PROCESSING_LOCAL");
        assertThat(bucket(owner).usedCount()).isZero();
        assertThat(ledgerCount(messageId)).isZero();
    }

    // -----------------------------------------------------------------------
    // Retry / reload / polling idempotency
    // -----------------------------------------------------------------------

    @Test
    void retryReloadAndPollingDoNotAddLedgerRowsOrChargeExtraQuota() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);

        ChatOutcome first = orchestrator.ask(
                session.getId(), owner, "g7-idempotent", HELMET_QUESTION);
        assertThat(first.status()).isEqualTo(MessageStatus.COMPLETED);
        UUID answerId = first.messageId();
        assertThat(bucket(owner).usedCount()).isEqualTo(1);
        assertThat(ledgerCount(answerId)).isEqualTo(1);

        ChatOutcome replay = orchestrator.ask(
                session.getId(), owner, "g7-idempotent", HELMET_QUESTION);
        assertThat(replay.messageId()).isEqualTo(answerId);
        assertThat(replay.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(bucket(owner).usedCount()).isEqualTo(1);
        assertThat(ledgerCount(answerId)).isEqualTo(1);

        for (int index = 0; index < 3; index++) {
            mockMvc.perform(get("/api/chat/messages/{id}/fragment", answerId)
                            .cookie(cookie(owner.ownerId()))
                            .header("HX-Request", "true"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/chat").param("sessionId", session.getId().toString())
                        .cookie(cookie(owner.ownerId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(HELMET_QUESTION)));

        UUID awaitingId = messageSubmissionService.submitQuestion(
                session.getId(), owner, "g7-retry-web", PENALTY_QUESTION).aiMessage().getId();
        messageStateService.markAwaitingWebConfirm(awaitingId, owner, "mức phạt xe máy");
        messageStateService.confirmWebSearch(awaitingId, owner);
        UUID jobId = jdbcTemplate.queryForObject(
                "select id from web_search_jobs where message_id = ? order by created_at desc limit 1",
                UUID.class, awaitingId);
        ClaimedJob claim =
                jobService.claimJob(jobId, "g7-retry-worker").orElseThrow();
        jobService.finalizeFailure(jobId, claim.leaseToken(), WebSearchErrorCode.PROVIDER_TIMEOUT);
        assertThat(messageStatus(awaitingId)).isEqualTo("WEB_FAILED");
        assertThat(bucket(owner).reservedCount()).isZero();

        consentService.retryWebSearch(awaitingId, owner);
        assertThat(messageStatus(awaitingId)).isEqualTo("WEB_QUEUED");
        assertThat(bucket(owner).reservedCount()).isEqualTo(1);
        consentService.retryWebSearch(awaitingId, owner);
        assertThat(bucket(owner).reservedCount()).isEqualTo(1);
        assertThat(jobCount(awaitingId)).isEqualTo(2);
        assertThat(bucket(owner).usedCount()).isEqualTo(1);
        assertThat(ledgerCount(awaitingId)).isZero();
    }

    // -----------------------------------------------------------------------
    // Rate limits
    // -----------------------------------------------------------------------

    @Test
    void rapidBackToBackQuestionsAreBlockedWith429AndRetryAfter() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        String ip = "10.7.1.1";

        mockMvc.perform(submitRequest(session.getId(), owner.ownerId(), "g7-rapid-1",
                        HELMET_QUESTION, ip))
                .andExpect(status().isOk());

        mockMvc.perform(submitRequest(session.getId(), owner.ownerId(), "g7-rapid-2",
                        HELMET_QUESTION, ip)
                        .header("HX-Request", "true"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.notNullValue()))
                .andExpect(content().string(containsString("gửi câu hỏi quá nhanh")));

        assertThat(count("select count(*) from chat_messages where session_id = ?", session.getId()))
                .isEqualTo(2);
    }

    @Test
    void ipHourlyFloodIsBlockedWith429() throws Exception {
        String ip = "10.7.2.1";
        UUID sessionId = UUID.randomUUID();
        for (int index = 0; index < 20; index++) {
            mockMvc.perform(post("/api/chat/sessions/{id}/messages", sessionId)
                            .cookie(cookie(UUID.randomUUID().toString()))
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            })
                            .param("message", ""))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/chat/sessions/{id}/messages", sessionId)
                        .cookie(cookie(UUID.randomUUID().toString()))
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .param("message", "")
                        .header("HX-Request", "true"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.notNullValue()))
                .andExpect(content().string(containsString("quá nhiều câu hỏi")));
    }

    @Test
    void confirmActionsAreRateLimitedPerSubject() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                session.getId(), owner, "g7-confirm-limit", PENALTY_QUESTION).aiMessage().getId();
        messageStateService.markAwaitingWebConfirm(messageId, owner, "mức phạt xe máy");
        String hash = questionHash(messageId);

        for (int index = 0; index < 10; index++) {
            mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", messageId)
                            .cookie(cookie(owner.ownerId()))
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("10.7.4.1");
                                return request;
                            })
                            .param("questionHash", hash)
                            .header("HX-Request", "true"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", messageId)
                        .cookie(cookie(owner.ownerId()))
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("10.7.4.1");
                            return request;
                        })
                        .param("questionHash", hash)
                        .header("HX-Request", "true"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("thao tác quá nhanh")));

        assertThat(jobCount(messageId)).isEqualTo(1);
        assertThat(bucket(owner).reservedCount()).isEqualTo(1);
    }

    @Test
    void excessivePollingIsBlockedWith429AndStopsRunawayFragment() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                session.getId(), owner, "g7-poll", PENALTY_QUESTION).aiMessage().getId();
        messageStateService.markAwaitingWebConfirm(messageId, owner, "mức phạt xe máy");

        for (int index = 0; index < 60; index++) {
            mockMvc.perform(get("/api/chat/messages/{id}/fragment", messageId)
                            .cookie(cookie(owner.ownerId()))
                            .header("HX-Request", "true"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/chat/messages/{id}/fragment", messageId)
                        .cookie(cookie(owner.ownerId()))
                        .header("HX-Request", "true"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.notNullValue()))
                .andExpect(content().string(containsString("cập nhật quá nhanh")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("hx-trigger=\"every 2s\""))));
    }

    // -----------------------------------------------------------------------
    // Budget, kill switch and provider degradation
    // -----------------------------------------------------------------------

    @Test
    void crawlerBudgetExhaustionRefusesConfirmationWithoutQuotaChargeOrHistoryLoss() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                session.getId(), owner, "g7-web-budget", PENALTY_QUESTION).aiMessage().getId();
        messageStateService.markAwaitingWebConfirm(messageId, owner, "mức phạt xe máy");

        SessionOwner dummyOwner = anonymousOwner();
        ChatSession dummySession = chatSessionService.createSession(dummyOwner);
        UUID dummyMessageId = messageSubmissionService.submitQuestion(
                dummySession.getId(), dummyOwner, "g7-budget-dummy", HELMET_QUESTION).aiMessage().getId();
        List<UUID> seededJobs = seedWebJobsToDailyLimit(dummyMessageId);
        try {
            mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search", messageId)
                            .cookie(cookie(owner.ownerId()))
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("10.7.3.1");
                                return request;
                            })
                            .param("questionHash", questionHash(messageId))
                            .header("HX-Request", "true"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(content().string(containsString("giới hạn ngân sách")));

            assertThat(messageStatus(messageId)).isEqualTo("AWAITING_WEB_CONFIRM");
            assertThat(jobCount(messageId)).isZero();
            BucketState bucket = bucket(owner);
            assertThat(bucket.usedCount()).isZero();
            assertThat(bucket.reservedCount()).isZero();

            mockMvc.perform(get("/chat").param("sessionId", session.getId().toString())
                            .cookie(cookie(owner.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(PENALTY_QUESTION)));
        } finally {
            seededJobs.forEach(jobId -> jdbcTemplate.update(
                    "delete from web_search_jobs where id = ?", jobId));
        }
    }

    @Test
    void geminiBudgetExhaustionFailsClosedReleasesHoldAndKeepsHistory() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        seedGroundingUsageAtCap();

        try {
            ChatOutcome outcome = orchestrator.ask(
                    session.getId(), owner, "g7-gemini-budget", HELMET_QUESTION);

            assertThat(outcome.status()).isEqualTo(MessageStatus.FAILED);
            assertThat(messageStatus(outcome.messageId())).isEqualTo("FAILED");
            BucketState bucket = bucket(owner);
            assertThat(bucket.usedCount()).isZero();
            assertThat(bucket.reservedCount()).isZero();
            assertThat(ledgerCount(outcome.messageId())).isZero();
            assertThat(count("select count(*) from chat_messages where session_id = ?", session.getId()))
                    .isEqualTo(2);

            mockMvc.perform(get("/chat").param("sessionId", session.getId().toString())
                            .cookie(cookie(owner.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(HELMET_QUESTION)))
                    .andExpect(content().string(containsString("Không thể hoàn tất câu trả lời")));
        } finally {
            jdbcTemplate.update("delete from cost_usage_daily where usage_date = ?",
                    quotaReservationService.currentPeriod());
        }
    }

    @Test
    void gradingProviderOutageFailsClosedWithoutAnswerOrQuotaCharge() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        groundingClient.failWith(new GroundingException("grading provider unavailable"));

        try {
            ChatOutcome outcome = orchestrator.ask(
                    session.getId(), owner, "g7-provider-down", HELMET_QUESTION);

            assertThat(outcome.status()).isEqualTo(MessageStatus.FAILED);
            assertThat(messageStatus(outcome.messageId())).isEqualTo("FAILED");
            BucketState bucket = bucket(owner);
            assertThat(bucket.usedCount()).isZero();
            assertThat(bucket.reservedCount()).isZero();
            assertThat(ledgerCount(outcome.messageId())).isZero();
            assertThat(count("select count(*) from chat_messages where session_id = ?", session.getId()))
                    .isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "select content from chat_messages where id = ?", String.class, outcome.messageId()))
                    .isEmpty();
        } finally {
            groundingClient.reset();
        }
    }

    @Test
    void completedWebJobRecordsWebCostMetrics() {
        JobFixture job = confirmedJob("g7-web-cost", PENALTY_QUESTION, "mức phạt xe máy");
        webSearchProvider.respondWith(List.of(
                new WebSearchProvider.SearchResult(URI.create(VALID_URL), "candidate", 1)));
        documentFetcher.respond(VALID_URL, "text/html; charset=UTF-8", htmlBytes());

        assertThat(worker.runJob(job.jobId())).isTrue();

        assertThat(messageStatus(job.messageId())).isEqualTo("COMPLETED");
        Map<String, Object> usage = jdbcTemplate.queryForMap("""
                select web_search_calls, fetched_urls, downloaded_bytes, grounding_calls
                from cost_usage_daily
                where usage_date = ? and category = 'WEB_COMPLETED'
                """, quotaReservationService.currentPeriod());
        assertThat(((Number) usage.get("web_search_calls")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) usage.get("fetched_urls")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) usage.get("downloaded_bytes")).longValue()).isGreaterThan(0);
        assertThat(((Number) usage.get("grounding_calls")).longValue()).isGreaterThanOrEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private SessionOwner anonymousOwner() {
        return SessionOwner.anonymous(UUID.randomUUID().toString());
    }

    private void seedBucket(SessionOwner owner, int used, int reserved) {
        jdbcTemplate.update("""
                insert into quota_buckets
                    (subject_type, anon_id, quota_period, limit_count, used_count, reserved_count)
                values ('ANON', ?, ?, 5, ?, ?)
                """, owner.ownerId(), quotaReservationService.currentPeriod(), used, reserved);
    }

    private MockHttpServletRequestBuilder submitRequest(UUID sessionId, String anonId,
                                                        String idempotencyKey, String message,
                                                        String remoteAddress) {
        return post("/api/chat/sessions/{id}/messages", sessionId)
                .cookie(cookie(anonId))
                .with(csrf())
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .param("message", message)
                .param("idempotencyKey", idempotencyKey);
    }

    private JobFixture confirmedJob(String key, String question, String suggestedQuery) {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        UUID messageId = messageSubmissionService.submitQuestion(
                        session.getId(), owner, key, question)
                .aiMessage().getId();
        messageStateService.markAwaitingWebConfirm(messageId, owner, suggestedQuery);
        messageStateService.confirmWebSearch(messageId, owner);
        UUID jobId = jdbcTemplate.queryForObject("""
                select id from web_search_jobs
                where message_id = ?
                order by created_at desc
                limit 1
                """, UUID.class, messageId);
        return new JobFixture(session.getId(), messageId, jobId, owner);
    }

    private List<UUID> seedWebJobsToDailyLimit(UUID messageId) {
        long existing = count("select count(*) from web_search_jobs where created_at >= ?",
                java.sql.Timestamp.from(quotaReservationService.currentPeriod()
                        .atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));
        List<UUID> seeded = new ArrayList<>();
        for (long index = existing; index < 20; index++) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into web_search_jobs
                        (id, message_id, job_status, stage, deadline)
                    values (?, ?, 'SUCCEEDED', 'FINALIZE', now() + interval '1 hour')
                    """, id, messageId);
            seeded.add(id);
        }
        return seeded;
    }

    private void seedGroundingUsageAtCap() {
        jdbcTemplate.update("delete from cost_usage_daily where usage_date = ?",
                quotaReservationService.currentPeriod());
        jdbcTemplate.update("""
                insert into cost_usage_daily
                    (usage_date, category, grounding_calls, grounding_input_tokens, grounding_output_tokens)
                values (?, 'LOCAL_FAILED', 200, 1, 1)
                """, quotaReservationService.currentPeriod());
    }

    private void expireReservationButKeepMessageProcessing(UUID messageId) {
        UUID bucketId = jdbcTemplate.queryForObject("""
                select bucket_id from quota_reservations
                where message_id = ? and status = 'ACTIVE'
                order by created_at desc limit 1
                """, UUID.class, messageId);
        jdbcTemplate.update("""
                update quota_reservations
                set status = 'EXPIRED', released_at = now()
                where message_id = ? and status = 'ACTIVE'
                """, messageId);
        jdbcTemplate.update("""
                update quota_buckets
                set reserved_count = reserved_count - 1
                where id = ? and reserved_count > 0
                """, bucketId);
    }

    private String questionHash(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "select question_hash from chat_messages where id = ?", String.class, messageId);
    }

    private String messageStatus(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "select message_status from chat_messages where id = ?", String.class, messageId);
    }

    private String reservationStatus(UUID messageId) {
        return jdbcTemplate.queryForObject("""
                select status from quota_reservations
                where message_id = ?
                order by created_at desc limit 1
                """, String.class, messageId);
    }

    private int ledgerCount(UUID messageId) {
        return (int) count("select count(*) from quota_ledger where answer_message_id = ?", messageId);
    }

    private int jobCount(UUID messageId) {
        return (int) count("select count(*) from web_search_jobs where message_id = ?", messageId);
    }

    private BucketState bucket(SessionOwner owner) {
        List<BucketState> rows = jdbcTemplate.query("""
                select used_count, reserved_count, limit_count
                from quota_buckets
                where anon_id = ? and quota_period = ?
                """,
                (resultSet, rowNumber) -> new BucketState(
                        resultSet.getInt("used_count"),
                        resultSet.getInt("reserved_count"),
                        resultSet.getInt("limit_count")),
                owner.ownerId(), quotaReservationService.currentPeriod());
        return rows.isEmpty() ? new BucketState(0, 0, 5) : rows.get(0);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private static Cookie cookie(String anonId) {
        return new Cookie(AnonymousIdentityFilter.COOKIE_NAME, anonId);
    }

    private static byte[] htmlBytes() {
        return """
                <html><body>
                <p>QUỐC HỘI</p>
                <p>Số: %s</p>
                <p>Hà Nội, ngày 01/01/2026</p>
                <p>LUẬT GIAO THÔNG ĐƯỜNG BỘ</p>
                <p>Có hiệu lực thi hành từ ngày 01/01/2026.</p>
                <p>Điều 5. Mức phạt đối với người điều khiển xe máy vượt đèn đỏ</p>
                <p>1. %s</p>
                </body></html>
                """.formatted(VALID_DOC_NUMBER, ARTICLE_TEXT).getBytes(StandardCharsets.UTF_8);
    }

    private <T> List<Attempt<T>> runConcurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Attempt<T>>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent start timed out");
                    }
                    try {
                        return new Attempt<>(call.call(), null);
                    } catch (Throwable error) {
                        return new Attempt<T>(null, error);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt<T>> results = new ArrayList<>();
            for (Future<Attempt<T>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private record Attempt<T>(T value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }

    private record JobFixture(UUID sessionId, UUID messageId, UUID jobId, SessionOwner owner) {
    }

    private record BucketState(int usedCount, int reservedCount, int limitCount) {
    }

    @TestConfiguration
    static class TestAdapters {

        @Bean
        @Primary
        ConfigurableGroundingClient testGroundingClient() {
            return new ConfigurableGroundingClient();
        }

        @Bean
        @Primary
        ConfigurableWebSearchProvider testWebSearchProvider() {
            return new ConfigurableWebSearchProvider();
        }

        @Bean
        @Primary
        ConfigurableDocumentFetcher testDocumentFetcher() {
            return new ConfigurableDocumentFetcher();
        }
    }

    static class ConfigurableGroundingClient implements GroundingClient {

        private final GroundingClient delegate = new DeterministicGroundingClient();
        private volatile RuntimeException failure;

        @Override
        public Decision evaluate(Request request) {
            RuntimeException current = failure;
            if (current != null) {
                throw current;
            }
            return delegate.evaluate(request);
        }

        void failWith(RuntimeException next) {
            this.failure = next;
        }

        void reset() {
            this.failure = null;
        }
    }

    static class ConfigurableWebSearchProvider implements WebSearchProvider {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile List<SearchResult> results = List.of();

        @Override
        public List<SearchResult> search(String query, int maxResults) {
            calls.incrementAndGet();
            return results.stream().limit(maxResults).toList();
        }

        void respondWith(List<SearchResult> next) {
            this.results = List.copyOf(next);
        }

        void reset() {
            calls.set(0);
            results = List.of();
        }
    }

    static class ConfigurableDocumentFetcher implements DocumentFetcher {

        private final Map<String, FetchedDocument> responses = new ConcurrentHashMap<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public FetchedDocument fetch(URI url) {
            String key = url.toString();
            calls.add(key);
            FetchedDocument response = responses.get(key);
            if (response == null) {
                throw new AssertionError("unexpected fetch: " + key);
            }
            return response;
        }

        void respond(String url, String contentType, byte[] body) {
            responses.put(url, new FetchedDocument(URI.create(url), contentType, body, Instant.now()));
        }

        void reset() {
            responses.clear();
            calls.clear();
        }
    }
}
