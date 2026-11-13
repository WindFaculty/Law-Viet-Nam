package vn.edu.hsf302.lawrag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.hsf302.lawrag.chat.dto.ChatOutcome;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.service.ChatSessionService;
import vn.edu.hsf302.lawrag.chat.service.LocalChatOrchestrator;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.gemini.service.DeterministicGroundingClient;
import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
import vn.edu.hsf302.lawrag.ingestion.service.CorpusImportService;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationService;
import vn.edu.hsf302.lawrag.retrieval.service.ContextGrader;
import vn.edu.hsf302.lawrag.retrieval.config.RetrievalProperties;
import vn.edu.hsf302.lawrag.security.web.AnonymousIdentityFilter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 8 RAG evaluation harness against real PostgreSQL + pgvector.
 *
 * <p>The 60 dev questions are executed first and the code/corpus/retrieval
 * configuration is hashed into {@code target/eval-reports/config-lock.json}.
 * Only then are the 60 holdout questions executed. The deterministic grader
 * and embedding fixture are used, so every measured number below is a
 * fixture/deterministic run, not a live Google API run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "app.websearch.enabled=false",
        "app.ratelimit.enabled=false",
        "app.quota.recovery-interval-ms=86400000"
})
@Import(RagEvaluationHarnessTest.FixedClockConfig.class)
class RagEvaluationHarnessTest extends PostgresIntegrationTest {

    private static final String DATASET = "eval/eval-120-questions.json";
    private static final String DATASET_VERSION = "road-traffic-120-v1";
    private static final String CORPUS_VERSION = "road-traffic-seed-2026-11-12";
    private static final String STUDY_DATE = "2026-11-12";
    private static final Path REPORT_DIR = Path.of("target", "eval-reports");
    private static final Path LOCK_FILE = REPORT_DIR.resolve("config-lock.json");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CorpusImportService corpusImportService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private LocalChatOrchestrator orchestrator;

    @Autowired
    private QuotaReservationService quotaReservationService;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private RetrievalProperties retrievalProperties;

    private JsonNode dataset;
    private List<QuestionResult> devResults;
    private List<QuestionResult> holdoutResults;
    private List<Long> concurrentLatencies = List.of();
    private List<Long> confirmLatencies = List.of();
    private long localDatasetLatencyP50;
    private long localDatasetLatencyP95;
    private int accessControlLeaks;
    private int accessControlChecks;
    private Map<String, Object> accessControlJobs = Map.of();
    private JsonNode lockNode;
    private String datasetDevHash;

    @BeforeAll
    void evaluateDevAndLockConfiguration() throws Exception {
        Files.createDirectories(REPORT_DIR);
        resetDatabaseToSeedCorpus();
        dataset = objectMapper.readTree(new ClassPathResource(DATASET).getInputStream());
        assertStructure(dataset);

        datasetDevHash = hashJson(objectMapper.valueToTree(devQuestions()));
        devResults = runQuestions(devQuestions());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                REPORT_DIR.resolve("dev-results-partial.json").toFile(), resultsNode(devResults));
        localDatasetLatencyP50 = percentile(devResults.stream().map(QuestionResult::latencyMs).toList(), 50);
        localDatasetLatencyP95 = percentile(devResults.stream().map(QuestionResult::latencyMs).toList(), 95);

        lockNode = buildLock();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(LOCK_FILE.toFile(), lockNode);

        runConcurrencyProbe();
        runConfirmationLatencyProbe();
    }

    @Test
    @Order(1)
    void devSplitMeetsAnswerRecallMissingEvidenceAndVerbatimTargets() {
        Map<String, Object> metrics = metricsFor(devResults);
        assertThat(metrics.get("answerRecallNumerator")).as("C1 answers").isEqualTo(10L);
        assertThat(metrics.get("answerRecallDenominator")).isEqualTo(10L);
        assertThat(metrics.get("missingEvidenceNumerator")).as("C2/C3 safe").isEqualTo(20L);
        assertThat(metrics.get("missingEvidenceDenominator")).isEqualTo(20L);
        assertThat(metrics.get("verbatimMatches")).isEqualTo(metrics.get("verbatimQuotes"));
        assertThat(metrics.get("decisionPasses")).as("dev decision matches").isEqualTo(60L);
        assertThat(metrics.get("confirmationComplianceNumerator")).isEqualTo(60L);
        assertThat(metrics.get("quotaAnomalies")).isEqualTo(0L);
        assertThat(devResults).allMatch(QuestionResult::passed);
    }

    @Test
    @Order(2)
    void holdoutSplitMeetsTargetsAgainstLockedConfiguration() throws Exception {
        assertThat(Files.exists(LOCK_FILE)).as("dev split must be locked before holdout").isTrue();
        JsonNode storedLock = objectMapper.readTree(LOCK_FILE.toFile());
        JsonNode recomputedLock = buildLock();
        assertThat(recomputedLock.get("configFingerprint").asText())
                .as("configuration changed after the dev split was locked")
                .isEqualTo(storedLock.get("configFingerprint").asText());
        assertThat(storedLock.get("devDatasetHash").asText()).isEqualTo(datasetDevHash);

        holdoutResults = runQuestions(holdoutQuestions());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                REPORT_DIR.resolve("holdout-results-partial.json").toFile(), resultsNode(holdoutResults));
        Map<String, Object> dev = metricsFor(devResults);
        Map<String, Object> holdout = metricsFor(holdoutResults);
        Map<String, Object> all = metricsFor(allResults());

        assertThat(holdout.get("answerRecallNumerator")).as("holdout C1 answers").isEqualTo(10L);
        assertThat(holdout.get("answerRecallDenominator")).isEqualTo(10L);
        assertThat(holdout.get("missingEvidenceNumerator")).as("holdout C2/C3 safe").isEqualTo(20L);
        assertThat(holdout.get("missingEvidenceDenominator")).isEqualTo(20L);
        assertThat(holdout.get("evidenceQualityNumerator")).isEqualTo(holdout.get("evidenceQualityDenominator"));
        assertThat(holdout.get("decisionPasses")).as("holdout decision matches").isEqualTo(60L);
        assertThat(holdout.get("verbatimMatches")).isEqualTo(holdout.get("verbatimQuotes"));
        assertThat(holdout.get("quotaAnomalies")).isEqualTo(0L);
        assertThat(holdoutResults).allMatch(QuestionResult::passed);

        accessControlProbe();
        assertThat(accessControlLeaks).as("cross-session leaks").isZero();
        long negativeBuckets = count("""
                select count(*) from quota_buckets
                where used_count < 0 or reserved_count < 0
                   or used_count + reserved_count > limit_count
                """);
        long duplicateLedger = count("""
                select count(*) from (
                    select answer_message_id from quota_ledger
                    group by answer_message_id having count(*) > 1
                ) duplicates
                """);
        long duplicateActiveJobs = count("""
                select count(*) from (
                    select message_id from web_search_jobs
                    where job_status in ('QUEUED','CLAIMED','RUNNING','RETRY_WAIT')
                    group by message_id having count(*) > 1
                ) duplicates
                """);
        assertThat(negativeBuckets).as("negative/over-limit quota buckets").isZero();
        assertThat(duplicateLedger).as("duplicate ledger rows").isZero();
        assertThat(duplicateActiveJobs).as("duplicate active jobs").isZero();

        assertThat(localDatasetLatencyP95).isLessThanOrEqualTo(10_000L);
        assertThat(percentile(concurrentLatencies, 95)).isLessThanOrEqualTo(10_000L);
        assertThat(percentile(confirmLatencies, 95)).isLessThanOrEqualTo(1_000L);

        ObjectNode report = objectMapper.createObjectNode();
        report.put("runId", java.time.OffsetDateTime.now(
                ZoneId.of("Asia/Ho_Chi_Minh")).toString());
        report.put("datasetId", DATASET_VERSION);
        report.put("datasetVersion", DATASET_VERSION);
        report.put("corpusVersion", CORPUS_VERSION);
        report.put("studyDate", STUDY_DATE);
        report.put("mode", "deterministic-fixture");
        report.put("postgresServer", jdbcTemplate.queryForObject("select version()", String.class));
        report.put("pgvectorVersion", jdbcTemplate.queryForObject(
                "select extversion from pg_extension where extname = 'vector'", String.class));
        report.put("modelId", DeterministicGroundingClient.MODEL_ID);
        report.put("promptVersion", ContextGrader.PROMPT_VERSION);
        report.put("embeddingSpaceId", embeddingClient.spaceId());
        report.put("retrievalConfigVersion", "rrf-v1");
        report.set("splitLock", lockNode);
        report.set("retrievalConfig", retrievalConfigNode());
        report.put("devQuestions", devResults.size());
        report.put("holdoutQuestions", holdoutResults.size());
        report.set("devMetrics", toNode(dev));
        report.set("holdoutMetrics", toNode(holdout));
        report.set("allMetrics", toNode(all));
        report.put("localDatasetLatencyP50Ms", localDatasetLatencyP50);
        report.put("localDatasetLatencyP95Ms", localDatasetLatencyP95);
        report.put("concurrentSessions", 10);
        report.put("concurrentSamples", concurrentLatencies.size());
        report.put("concurrentLatencyP50Ms", percentile(concurrentLatencies, 50));
        report.put("concurrentLatencyP95Ms", percentile(concurrentLatencies, 95));
        report.put("confirmSamples", confirmLatencies.size());
        report.put("confirmLatencyP50Ms", percentile(confirmLatencies, 50));
        report.put("confirmLatencyP95Ms", percentile(confirmLatencies, 95));
        report.set("concurrentLatencySamplesMs", objectMapper.valueToTree(concurrentLatencies));
        report.set("confirmLatencySamplesMs", objectMapper.valueToTree(confirmLatencies));
        report.set("costUsage", costUsageNode());
        report.put("accessControlChecks", accessControlChecks);
        report.put("accessControlLeaks", accessControlLeaks);
        report.set("accessControlJobs", toNode(accessControlJobs));
        report.put("globalNegativeQuotaBuckets", negativeBuckets);
        report.put("globalDuplicateLedgerRows", duplicateLedger);
        report.put("globalDuplicateActiveJobs", duplicateActiveJobs);
        report.put("searchCallsBeforeConsent", webSearchCalls());
        report.put("fetchedUrlsBeforeConsent", fetchedUrls());
        report.set("results", resultsNode(allResults()));
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(REPORT_DIR.resolve("rag-evaluation-metrics.json").toFile(), report);

        if (System.getProperty("eval.dump") != null) {
            for (QuestionResult result : allResults()) {
                System.out.printf("EVAL %s %s/%s reason=%s citations=%s latency=%dms%n",
                        result.id(), result.actualDecision(), result.expectedDecision(),
                        result.actualReason(), result.citationCodes(), result.latencyMs());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dataset / execution
    // -----------------------------------------------------------------------

    private void assertStructure(JsonNode root) {
        List<JsonNode> questions = questions(root);
        assertThat(questions).hasSize(120);
        assertThat(questions).filteredOn(question -> "dev".equals(question.path("split").asText())).hasSize(60);
        assertThat(questions).filteredOn(question -> "holdout".equals(question.path("split").asText())).hasSize(60);
        List<String> categories = List.of("C1_DIRECT_EVIDENCE", "C2_MISSING_DOCUMENT",
                "C3_NEAR_SYNONYM_WRONG_SCOPE", "C4_TEMPORAL_VERSION",
                "C5_MISSING_FACTS", "C6_OUT_OF_SCOPE");
        for (String category : categories) {
            for (String split : List.of("dev", "holdout")) {
                assertThat(questions).filteredOn(question ->
                                category.equals(question.path("category").asText())
                                        && split.equals(question.path("split").asText()))
                        .as(category + "/" + split).hasSize(10);
            }
        }
        assertThat(questions).allMatch(question ->
                !question.path("id").asText().isBlank()
                        && !question.path("question").asText().isBlank()
                        && !question.path("asOfDate").asText().isBlank()
                        && !question.path("expectedDecision").asText().isBlank()
                        && question.path("acceptanceCriteria").isArray()
                        && question.path("forbiddenBehaviors").isArray());
    }

    private List<JsonNode> questions(JsonNode root) {
        List<JsonNode> questions = new ArrayList<>();
        root.path("questions").forEach(questions::add);
        return questions;
    }

    private List<JsonNode> devQuestions() {
        return questions(dataset).stream().filter(q -> "dev".equals(q.path("split").asText())).toList();
    }

    private List<JsonNode> holdoutQuestions() {
        return questions(dataset).stream().filter(q -> "holdout".equals(q.path("split").asText())).toList();
    }

    private List<QuestionResult> allResults() {
        List<QuestionResult> all = new ArrayList<>(devResults == null ? List.of() : devResults);
        all.addAll(holdoutResults == null ? List.of() : holdoutResults);
        return all;
    }

    private List<QuestionResult> runQuestions(List<JsonNode> questions) {
        List<QuestionResult> results = new ArrayList<>();
        for (JsonNode question : questions) {
            results.add(runQuestion(question));
        }
        return List.copyOf(results);
    }

    private QuestionResult runQuestion(JsonNode question) {
        SessionOwner owner = owner();
        ChatSession session = chatSessionService.createSession(owner);
        String key = "eval-" + question.path("id").asText();
        long start = System.nanoTime();
        ChatOutcome outcome = orchestrator.ask(
                session.getId(), owner, key, question.path("question").asText());
        long latencyMs = durationMs(start);
        return inspect(question, owner, session, key, outcome, latencyMs);
    }

    private QuestionResult inspect(JsonNode question, SessionOwner owner, ChatSession session,
                                   String key, ChatOutcome outcome, long latencyMs) {
        Map<String, Object> message = jdbcTemplate.queryForMap("""
                select message_status, outcome, applicable_date, content
                from chat_messages where id = ?
                """, outcome.messageId());
        String status = String.valueOf(message.get("message_status"));
        String outcomeCode = message.get("outcome") == null ? null : String.valueOf(message.get("outcome"));
        LocalDate applicableDate = ((java.sql.Date) message.get("applicable_date")).toLocalDate();
        List<Map<String, Object>> citations = jdbcTemplate.queryForList("""
                select a.article_code, mc.verbatim_text, a.raw_text,
                       s.content as snapshot_content, d.document_key
                from message_citations mc
                join legal_articles a on a.id = mc.article_id
                join legal_documents d on d.id = mc.document_version_id
                join source_snapshots s on s.id = mc.source_snapshot_id
                where mc.message_id = ?
                order by mc.citation_order
                """, outcome.messageId());
        List<String> actualCodes = citations.stream()
                .map(row -> String.valueOf(row.get("article_code"))).toList();
        List<String> expectedCodes = new ArrayList<>();
        question.path("expectedSourceRefs").forEach(ref ->
                expectedCodes.add(ref.path("articleCode").asText()));
        Set<String> actualSet = new LinkedHashSet<>(actualCodes);
        Set<String> expectedSet = new LinkedHashSet<>(expectedCodes);
        String actualDecision = decision(status, outcomeCode);
        String expectedDecision = question.path("expectedDecision").asText();
        String expectedReason = question.path("expectedReasonCode").asText("");
        String actualReason = traceReason(outcome.messageId());
        int jobCount = jobCount(outcome.messageId());
        Bucket bucket = bucket(owner);
        int ledger = ledgerCount(outcome.messageId());

        boolean quotesVerbatim = true;
        for (Map<String, Object> citation : citations) {
            String verbatim = String.valueOf(citation.get("verbatim_text"));
            String snapshot = String.valueOf(citation.get("snapshot_content"));
            String raw = String.valueOf(citation.get("raw_text"));
            quotesVerbatim &= verbatim.equals(raw)
                    && snapshot.contains(verbatim)
                    && LegalText.matchesVerbatim(snapshot, verbatim);
        }

        boolean decisionPass = expectedDecision.equals(actualDecision);
        boolean reasonPass = expectedReason.isBlank() || expectedReason.equals(actualReason);
        boolean datePass = LocalDate.parse(question.path("asOfDate").asText()).equals(applicableDate);
        boolean citationsPass;
        boolean quotaPass;
        if ("ANSWER_LOCAL".equals(expectedDecision)) {
            citationsPass = actualSet.equals(expectedSet) && !citations.isEmpty();
            quotaPass = ledger == 1 && bucket.usedCount() == 1 && bucket.reservedCount() == 0 && jobCount == 0;
        } else if ("OUT_OF_SCOPE".equals(expectedDecision)) {
            citationsPass = citations.isEmpty();
            quotaPass = ledger == 0 && bucket.usedCount() == 0 && bucket.reservedCount() == 0 && jobCount == 0;
        } else {
            citationsPass = citations.isEmpty();
            quotaPass = ledger == 0 && bucket.usedCount() == 0 && bucket.reservedCount() == 0 && jobCount == 0;
        }
        boolean bucketSane = bucket.usedCount() >= 0 && bucket.reservedCount() >= 0
                && bucket.usedCount() + bucket.reservedCount() <= bucket.limitCount();
        boolean passed = decisionPass && reasonPass && datePass && citationsPass && quotaPass
                && quotesVerbatim && bucketSane;

        ReplayResult replay = replay(question, owner, session, key, outcome.messageId());
        int quotaAnomalies = (bucketSane ? 0 : 1)
                + (replay.anomalies() ? 1 : 0)
                + (ledger > 1 ? 1 : 0)
                + (jobCount > 1 ? 1 : 0);
        if (quotaAnomalies > 0) {
            System.out.println("QUOTA_ANOMALY " + question.path("id").asText()
                    + " value=" + quotaAnomalies
                    + " components=" + (bucketSane ? 0 : 1) + ","
                    + (replay.anomalies() ? 1 : 0) + ","
                    + (ledger > 1 ? 1 : 0) + "," + (jobCount > 1 ? 1 : 0)
                    + " replay=" + replay.detail()
                    + " ledger=" + ledger + " jobs=" + jobCount
                    + " bucket=" + bucket.usedCount() + "/" + bucket.reservedCount()
                    + "/" + bucket.limitCount());
        }
        return new QuestionResult(
                question.path("id").asText(),
                question.path("category").asText(),
                question.path("split").asText(),
                outcome.messageId(),
                expectedDecision, expectedReason, actualDecision, actualReason,
                status, outcomeCode, applicableDate, List.copyOf(actualCodes),
                citations.size(), quotesVerbatim, jobCount,
                bucket.usedCount(), bucket.reservedCount(), bucket.limitCount(), ledger,
                latencyMs, decisionPass, reasonPass, datePass, citationsPass, quotaPass,
                passed, quotaAnomalies);
    }

    private ReplayResult replay(JsonNode question, SessionOwner owner, ChatSession session,
                                String key, UUID expectedMessageId) {
        Bucket before = bucket(owner);
        int ledgerBefore = ledgerCount(expectedMessageId);
        int messagesBefore = messageCount(session.getId());
        try {
            ChatOutcome replay = orchestrator.ask(
                    session.getId(), owner, key, question.path("question").asText());
            Bucket after = bucket(owner);
            boolean sameMessage = expectedMessageId.equals(replay.messageId());
            boolean sameMessages = messagesBefore == messageCount(session.getId());
            boolean sameQuota = before.usedCount() == after.usedCount()
                    && before.reservedCount() == after.reservedCount();
            boolean sameLedger = ledgerBefore == ledgerCount(expectedMessageId);
            return new ReplayResult(
                    !(sameMessage && sameMessages && sameQuota && sameLedger),
                    "message=" + sameMessage + ",messages=" + sameMessages
                            + ",quota=" + sameQuota + ",ledger=" + sameLedger);
        } catch (RuntimeException exception) {
            // A failed replay (for example an expired consent window) must not
            // change persisted quota or create another message.
            Bucket after = bucket(owner);
            boolean sameQuota = before.usedCount() == after.usedCount()
                    && before.reservedCount() == after.reservedCount();
            boolean sameMessages = messagesBefore == messageCount(session.getId());
            boolean sameLedger = ledgerBefore == ledgerCount(expectedMessageId);
            return new ReplayResult(!(sameQuota && sameMessages && sameLedger),
                    "exception=" + exception.getClass().getSimpleName()
                            + ",messages=" + sameMessages + ",quota=" + sameQuota
                            + ",ledger=" + sameLedger);
        }
    }

    private int messageCount(UUID sessionId) {
        return count("select count(*) from chat_messages where session_id = ?", sessionId);
    }

    private static String decision(String status, String outcome) {
        if ("COMPLETED".equals(status) && "OUT_OF_SCOPE".equals(outcome)) {
            return "OUT_OF_SCOPE";
        }
        if ("COMPLETED".equals(status) && "ANSWERED".equals(outcome)) {
            return "ANSWER_LOCAL";
        }
        return switch (status) {
            case "AWAITING_WEB_CONFIRM" -> "NEED_WEB_CONFIRM";
            case "NEEDS_CLARIFICATION" -> "NEED_CLARIFICATION";
            case "NO_EVIDENCE" -> "NO_EVIDENCE";
            case "CANCELLED" -> "CANCELLED";
            case "EXPIRED" -> "EXPIRED";
            case "WEB_FAILED", "FAILED" -> "FAILED";
            default -> status;
        };
    }

    // -----------------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------------

    private Map<String, Object> metricsFor(List<QuestionResult> results) {
        long decisionPasses = results.stream().filter(QuestionResult::passed).count();
        long answerNumerator = results.stream().filter(result ->
                "C1_DIRECT_EVIDENCE".equals(result.category())
                        && "ANSWER_LOCAL".equals(result.expectedDecision())
                        && "ANSWER_LOCAL".equals(result.actualDecision())
                        && result.citationsPass()).count();
        long answerDenominator = results.stream()
                .filter(result -> "C1_DIRECT_EVIDENCE".equals(result.category())).count();
        long missingNumerator = results.stream().filter(result ->
                ("C2_MISSING_DOCUMENT".equals(result.category())
                        || "C3_NEAR_SYNONYM_WRONG_SCOPE".equals(result.category()))
                        && "AWAITING_WEB_CONFIRM".equals(result.status())
                        && result.citationCount() == 0
                        && result.jobCount() == 0).count();
        long missingDenominator = results.stream().filter(result ->
                "C2_MISSING_DOCUMENT".equals(result.category())
                        || "C3_NEAR_SYNONYM_WRONG_SCOPE".equals(result.category())).count();
        long answeredHoldout = results.stream().filter(result ->
                "holdout".equals(result.split())
                        && "COMPLETED".equals(result.status())
                        && result.citationCount() > 0).count();
        long qualityNumerator = results.stream().filter(result ->
                "holdout".equals(result.split())
                        && "COMPLETED".equals(result.status())
                        && result.citationCount() > 0
                        && result.citationsPass()
                        && result.quotesVerbatim()
                        && result.datePass()).count();
        long quotes = results.stream().mapToLong(QuestionResult::citationCount).sum();
        long verbatim = results.stream().filter(QuestionResult::quotesVerbatim)
                .mapToLong(QuestionResult::citationCount).sum();
        long confirmationNumerator = results.stream().filter(result -> result.jobCount() == 0).count();
        long quotaAnomalies = results.stream().mapToLong(QuestionResult::quotaAnomalies).sum();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("questions", results.size());
        metrics.put("decisionPasses", decisionPasses);
        metrics.put("decisionTotal", results.size());
        metrics.put("answerRecallNumerator", answerNumerator);
        metrics.put("answerRecallDenominator", answerDenominator);
        metrics.put("missingEvidenceNumerator", missingNumerator);
        metrics.put("missingEvidenceDenominator", missingDenominator);
        metrics.put("evidenceQualityNumerator", qualityNumerator);
        metrics.put("evidenceQualityDenominator", answeredHoldout);
        metrics.put("verbatimQuotes", quotes);
        metrics.put("verbatimMatches", verbatim);
        metrics.put("confirmationComplianceNumerator", confirmationNumerator);
        metrics.put("confirmationComplianceDenominator", results.size());
        metrics.put("quotaAnomalies", quotaAnomalies);
        metrics.put("crossSessionLeaks", accessControlLeaks);
        metrics.put("webSearchCallsBeforeConsent", webSearchCalls());
        metrics.put("fetchedUrlsBeforeConsent", fetchedUrls());
        return metrics;
    }

    private Map<String, Object> categoryBreakdown(List<QuestionResult> results) {
        Map<String, Object> breakdown = new LinkedHashMap<>();
        for (String category : List.of("C1_DIRECT_EVIDENCE", "C2_MISSING_DOCUMENT",
                "C3_NEAR_SYNONYM_WRONG_SCOPE", "C4_TEMPORAL_VERSION",
                "C5_MISSING_FACTS", "C6_OUT_OF_SCOPE")) {
            long total = results.stream().filter(result -> category.equals(result.category())).count();
            long pass = results.stream().filter(result -> category.equals(result.category())
                    && result.passed()).count();
            breakdown.put(category, Map.of("total", total, "pass", pass));
        }
        return breakdown;
    }

    // -----------------------------------------------------------------------
    // Concurrency / confirmation latency
    // -----------------------------------------------------------------------

    private void runConcurrencyProbe() {
        Set<String> passingLocalIds = new LinkedHashSet<>();
        devResults.stream()
                .filter(result -> "C1_DIRECT_EVIDENCE".equals(result.category())
                        && result.passed() && "ANSWER_LOCAL".equals(result.expectedDecision()))
                .forEach(result -> passingLocalIds.add(result.id()));
        List<JsonNode> localQuestions = devQuestions().stream()
                .filter(question -> passingLocalIds.contains(question.path("id").asText()))
                .toList();
        assertThat(localQuestions).as("at least one passing C1 dev question").isNotEmpty();
        int sessions = 10;
        int rounds = 5;
        ExecutorService executor = Executors.newFixedThreadPool(sessions);
        CountDownLatch ready = new CountDownLatch(sessions);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int sessionIndex = 0; sessionIndex < sessions; sessionIndex++) {
                int index = sessionIndex;
                futures.add(executor.submit(() -> {
                    SessionOwner owner = owner();
                    ChatSession session = chatSessionService.createSession(owner);
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent start timed out");
                    }
                    for (int round = 0; round < rounds; round++) {
                        JsonNode question = localQuestions.get((index + round) % localQuestions.size());
                        long started = System.nanoTime();
                        ChatOutcome outcome = orchestrator.ask(
                                session.getId(), owner, "eval-concurrent-" + index + "-" + round,
                                question.path("question").asText());
                        latencies.add(durationMs(started));
                        if (outcome.status() != MessageStatus.COMPLETED) {
                            throw new AssertionError("concurrent local answer did not complete: "
                                    + outcome.status());
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            concurrentLatencies = List.copyOf(latencies);
        } catch (Exception exception) {
            throw new IllegalStateException("concurrency probe failed", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private void runConfirmationLatencyProbe() throws Exception {
        List<Long> latencies = new ArrayList<>();
        List<JsonNode> awaiting = devQuestions().stream()
                .filter(question -> "C2_MISSING_DOCUMENT".equals(question.path("category").asText()))
                .limit(10).toList();
        for (JsonNode question : awaiting) {
            SessionOwner owner = owner();
            ChatSession session = chatSessionService.createSession(owner);
            ChatOutcome outcome = orchestrator.ask(
                    session.getId(), owner, "eval-confirm-" + question.path("id").asText(),
                    question.path("question").asText());
            assertThat(outcome.status()).isEqualTo(MessageStatus.AWAITING_WEB_CONFIRM);
            String hash = jdbcTemplate.queryForObject(
                    "select question_hash from chat_messages where id = ?",
                    String.class, outcome.messageId());
            long started = System.nanoTime();
            int status = mockMvc.perform(post("/api/chat/messages/{id}/confirm-web-search",
                            outcome.messageId())
                            .cookie(cookie(owner.ownerId()))
                            .with(csrf())
                            .param("questionHash", hash)
                            .header("HX-Request", "true"))
                    .andReturn().getResponse().getStatus();
            latencies.add(durationMs(started));
            assertThat(status).isEqualTo(200);
            assertThat(jdbcTemplate.queryForObject(
                    "select message_status from chat_messages where id = ?",
                    String.class, outcome.messageId())).isEqualTo("WEB_QUEUED");
        }
        confirmLatencies = List.copyOf(latencies);
    }

    // -----------------------------------------------------------------------
    // Access control
    // -----------------------------------------------------------------------

    private void accessControlProbe() throws Exception {
        int checks = 0;
        int leaks = 0;
        Map<String, Integer> jobsBefore = new LinkedHashMap<>();
        List<QuestionResult> all = allResults();
        for (QuestionResult result : all) {
            String foreignAnon = UUID.randomUUID().toString();
            int status = mockMvc.perform(get("/api/chat/messages/{id}/fragment", result.messageId())
                            .cookie(cookie(foreignAnon))
                            .header("HX-Request", "true"))
                    .andReturn().getResponse().getStatus();
            checks++;
            if (status != 403) {
                leaks++;
            }
            if (result.status().equals("AWAITING_WEB_CONFIRM")) {
                String hash = jdbcTemplate.queryForObject(
                        "select question_hash from chat_messages where id = ?",
                        String.class, result.messageId());
                int before = jobCount(result.messageId());
                jobsBefore.put(result.messageId().toString(), before);
                int confirmStatus = mockMvc.perform(post(
                                "/api/chat/messages/{id}/confirm-web-search", result.messageId())
                                .cookie(cookie(foreignAnon))
                                .with(csrf())
                                .param("questionHash", hash)
                                .header("HX-Request", "true"))
                        .andReturn().getResponse().getStatus();
                int cancelStatus = mockMvc.perform(post(
                                "/api/chat/messages/{id}/cancel-web-search", result.messageId())
                                .cookie(cookie(foreignAnon))
                                .with(csrf())
                                .header("HX-Request", "true"))
                        .andReturn().getResponse().getStatus();
                checks += 2;
                if (confirmStatus != 403) {
                    leaks++;
                }
                if (cancelStatus != 403) {
                    leaks++;
                }
                if (jobCount(result.messageId()) != before) {
                    leaks++;
                }
            }
        }
        int adminStatus = mockMvc.perform(get("/admin/web-search-jobs"))
                .andReturn().getResponse().getStatus();
        checks++;
        if (adminStatus != 403) {
            leaks++;
        }
        accessControlChecks = checks;
        accessControlLeaks = leaks;
        accessControlJobs = Map.copyOf(jobsBefore);
    }

    // -----------------------------------------------------------------------
    // Lock and report helpers
    // -----------------------------------------------------------------------

    private JsonNode buildLock() {
        ObjectNode lock = objectMapper.createObjectNode();
        lock.put("datasetId", DATASET_VERSION);
        lock.put("corpusVersion", CORPUS_VERSION);
        lock.put("modelId", DeterministicGroundingClient.MODEL_ID);
        lock.put("promptVersion", ContextGrader.PROMPT_VERSION);
        lock.put("embeddingSpaceId", embeddingClient.spaceId());
        lock.put("devDatasetHash", datasetDevHash);
        lock.set("retrievalConfig", retrievalConfigNode());
        lock.put("lockedAt", STUDY_DATE);
        String fingerprint = LegalText.sha256Hex(String.join("|",
                DATASET_VERSION, CORPUS_VERSION, DeterministicGroundingClient.MODEL_ID,
                ContextGrader.PROMPT_VERSION, embeddingClient.spaceId(),
                String.valueOf(retrievalProperties.topK()),
                String.valueOf(retrievalProperties.rrfK()),
                String.valueOf(retrievalProperties.contextLimit()),
                String.valueOf(retrievalProperties.cosineThreshold()),
                String.valueOf(retrievalProperties.strongLexicalMinCoverage()),
                String.valueOf(retrievalProperties.contextTokenBudget()),
                datasetDevHash));
        lock.put("configFingerprint", fingerprint);
        return lock;
    }

    private ObjectNode retrievalConfigNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("topK", retrievalProperties.topK());
        node.put("rrfK", retrievalProperties.rrfK());
        node.put("contextLimit", retrievalProperties.contextLimit());
        node.put("cosineThreshold", retrievalProperties.cosineThreshold());
        node.put("strongLexicalMinCoverage", retrievalProperties.strongLexicalMinCoverage());
        node.put("contextTokenBudget", retrievalProperties.contextTokenBudget());
        return node;
    }

    private String hashJson(JsonNode node) {
        try {
            return LegalText.sha256Hex(objectMapper.writer().writeValueAsBytes(node));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot hash dev dataset", exception);
        }
    }

    private ObjectNode resultsNode(List<QuestionResult> results) {
        ArrayNode array = objectMapper.createArrayNode();
        for (QuestionResult result : results) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", result.id());
            node.put("category", result.category());
            node.put("split", result.split());
            node.put("expectedDecision", result.expectedDecision());
            node.put("actualDecision", result.actualDecision());
            node.put("expectedReasonCode", result.expectedReason());
            node.put("actualReasonCode", result.actualReason());
            node.put("status", result.status());
            node.put("outcome", result.outcomeCode());
            node.put("applicableDate", result.applicableDate().toString());
            node.set("citationCodes", objectMapper.valueToTree(result.citationCodes()));
            node.put("citationCount", result.citationCount());
            node.put("quotesVerbatim", result.quotesVerbatim());
            node.put("jobCount", result.jobCount());
            node.put("quotaUsed", result.quotaUsed());
            node.put("quotaReserved", result.quotaReserved());
            node.put("quotaLimit", result.quotaLimit());
            node.put("ledgerRows", result.ledgerRows());
            node.put("latencyMs", result.latencyMs());
            node.put("decisionPass", result.decisionPass());
            node.put("reasonPass", result.reasonPass());
            node.put("datePass", result.datePass());
            node.put("citationsPass", result.citationsPass());
            node.put("quotaPass", result.quotaPass());
            node.put("passed", result.passed());
            array.add(node);
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("items", array);
        return wrapper;
    }

    private ObjectNode costUsageNode() {
        ObjectNode usage = objectMapper.createObjectNode();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select category, embedding_calls, embedding_input_chars, embedding_cache_hits,
                       grounding_calls, grounding_input_tokens, grounding_output_tokens,
                       web_search_calls, fetched_urls, downloaded_bytes
                from cost_usage_daily where usage_date = ?
                order by category
                """, quotaReservationService.currentPeriod())) {
            ObjectNode values = objectMapper.createObjectNode();
            values.put("embeddingCalls", number(row, "embedding_calls"));
            values.put("embeddingInputChars", number(row, "embedding_input_chars"));
            values.put("embeddingCacheHits", number(row, "embedding_cache_hits"));
            values.put("groundingCalls", number(row, "grounding_calls"));
            values.put("groundingInputTokens", number(row, "grounding_input_tokens"));
            values.put("groundingOutputTokens", number(row, "grounding_output_tokens"));
            values.put("webSearchCalls", number(row, "web_search_calls"));
            values.put("fetchedUrls", number(row, "fetched_urls"));
            values.put("downloadedBytes", number(row, "downloaded_bytes"));
            usage.set(String.valueOf(row.get("category")), values);
        }
        return usage;
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private ObjectNode toNode(Map<String, Object> map) {
        ObjectNode node = objectMapper.createObjectNode();
        map.forEach((key, value) -> {
            if (value instanceof Long longValue) {
                node.put(key, longValue);
            } else if (value instanceof Integer integerValue) {
                node.put(key, integerValue);
            } else if (value instanceof Boolean booleanValue) {
                node.put(key, booleanValue);
            } else {
                node.put(key, String.valueOf(value));
            }
        });
        return node;
    }

    // -----------------------------------------------------------------------
    // DB helpers
    // -----------------------------------------------------------------------

    private void resetDatabaseToSeedCorpus() {
        jdbcTemplate.execute("alter table quota_ledger disable trigger trg_quota_ledger_immutable");
        try {
            jdbcTemplate.update("delete from quota_ledger");
        } finally {
            jdbcTemplate.execute("alter table quota_ledger enable trigger trg_quota_ledger_immutable");
        }
        jdbcTemplate.update("delete from quota_reservations");
        jdbcTemplate.update("delete from quota_buckets");
        jdbcTemplate.update("delete from web_search_candidates");
        jdbcTemplate.update("delete from web_search_jobs");
        jdbcTemplate.update("delete from admin_audit_log");
        jdbcTemplate.update("delete from chat_sessions");
        jdbcTemplate.update("""
                delete from legal_documents where document_key not in
                    ('luat-36-2024-qh15', 'luat-23-2008-qh12',
                     'nghi-dinh-168-2024-nd-cp', 'nghi-dinh-238-2026-nd-cp')
                """);
        jdbcTemplate.update("delete from source_snapshots where storage_path is null");
        jdbcTemplate.update("delete from cost_usage_daily");
        corpusImportService.importCorpus();
    }

    private SessionOwner owner() {
        return SessionOwner.anonymous(UUID.randomUUID().toString());
    }

    private String traceReason(UUID messageId) {
        List<String> reasons = jdbcTemplate.queryForList("""
                select reason_code from retrieval_traces
                where message_id = ? order by created_at desc limit 1
                """, String.class, messageId);
        return reasons.isEmpty() ? null : reasons.get(0);
    }

    private int jobCount(UUID messageId) {
        return count("select count(*) from web_search_jobs where message_id = ?", messageId);
    }

    private int ledgerCount(UUID messageId) {
        return count("select count(*) from quota_ledger where answer_message_id = ?", messageId);
    }

    private long webSearchCalls() {
        Long value = jdbcTemplate.queryForObject(
                "select coalesce(sum(web_search_calls), 0) from cost_usage_daily", Long.class);
        return value == null ? 0 : value;
    }

    private long fetchedUrls() {
        Long value = jdbcTemplate.queryForObject(
                "select coalesce(sum(fetched_urls), 0) from cost_usage_daily", Long.class);
        return value == null ? 0 : value;
    }

    private Bucket bucket(SessionOwner owner) {
        List<Bucket> rows = jdbcTemplate.query("""
                select used_count, reserved_count, limit_count
                from quota_buckets where anon_id = ? and quota_period = ?
                """,
                (resultSet, rowNumber) -> new Bucket(
                        resultSet.getInt("used_count"),
                        resultSet.getInt("reserved_count"),
                        resultSet.getInt("limit_count")),
                owner.ownerId(), quotaReservationService.currentPeriod());
        return rows.isEmpty() ? new Bucket(0, 0, QuotaReservationService.DAILY_LIMIT) : rows.get(0);
    }

    private int count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value.intValue();
    }

    private static Cookie cookie(String anonId) {
        return new Cookie(AnonymousIdentityFilter.COOKIE_NAME, anonId);
    }

    private static long durationMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private record ReplayResult(boolean anomalies, String detail) {
    }

    private record Bucket(int usedCount, int reservedCount, int limitCount) {
    }

    private record QuestionResult(
            String id,
            String category,
            String split,
            UUID messageId,
            String expectedDecision,
            String expectedReason,
            String actualDecision,
            String actualReason,
            String status,
            String outcomeCode,
            LocalDate applicableDate,
            List<String> citationCodes,
            int citationCount,
            boolean quotesVerbatim,
            int jobCount,
            int quotaUsed,
            int quotaReserved,
            int quotaLimit,
            int ledgerRows,
            long latencyMs,
            boolean decisionPass,
            boolean reasonPass,
            boolean datePass,
            boolean citationsPass,
            boolean quotaPass,
            boolean passed,
            int quotaAnomalies) {
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock evalClock() {
            return Clock.fixed(Instant.parse("2026-11-11T17:00:00Z"),
                    ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
