package vn.edu.hsf302.lawrag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.hsf302.lawrag.chat.dto.ChatOutcome;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.service.ChatSessionService;
import vn.edu.hsf302.lawrag.chat.service.LocalChatOrchestrator;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.ingestion.service.CorpusImportService;
import vn.edu.hsf302.lawrag.ingestion.service.DocumentFetcher;
import vn.edu.hsf302.lawrag.legaldata.repository.ArticleEmbeddingRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.EmbeddingStatus;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationService;
import vn.edu.hsf302.lawrag.retrieval.service.AnswerRenderer;
import vn.edu.hsf302.lawrag.retrieval.service.CitationValidationException;
import vn.edu.hsf302.lawrag.retrieval.service.CitationValidator;
import vn.edu.hsf302.lawrag.retrieval.service.HybridRetrievalService;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.service.QueryAnalyzer;
import vn.edu.hsf302.lawrag.retrieval.dto.RenderedAnswer;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalCandidate;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalResult;
import vn.edu.hsf302.lawrag.retrieval.dto.ValidatedCitation;
import vn.edu.hsf302.lawrag.websearch.service.WebSearchProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate G4 evidence against real PostgreSQL + pgvector: sufficient local
 * evidence completes with verbatim citations and one quota slot; missing,
 * wrong-scope, wrong-time and missing-fact questions never publish a false
 * answer and never call the web adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(G4AcceptanceTest.RecordingAdapters.class)
class G4AcceptanceTest extends PostgresIntegrationTest {

    private static final String HELMET_QUESTION =
            "Người điều khiển xe máy có phải đội mũ bảo hiểm không?";
    private static final String PENALTY_QUESTION =
            "Theo Nghị định 238/2026/NĐ-CP, người điều khiển xe máy vượt đèn đỏ bị phạt bao nhiêu tiền?";
    private static final String WRONG_SCOPE_QUESTION =
            "Người lái xe ô tô có phải đội mũ bảo hiểm không?";
    private static final String CHILD_SEAT_2025 =
            "Theo Điều 10 khoản 3 Luật 36/2024/QH15, ngày 01/06/2025 trẻ em dưới 10 tuổi "
                    + "ngồi cùng hàng ghế với người lái xe ô tô có vi phạm không?";
    private static final String CHILD_SEAT_2026 =
            "Theo Điều 10 khoản 3 Luật 36/2024/QH15, ngày 01/02/2026 trẻ em dưới 10 tuổi "
                    + "ngồi cùng hàng ghế với người lái xe ô tô có vi phạm không?";
    private static final String HELMET_2024 =
            "Ngày 01/06/2024, người ngồi trên xe máy có phải đội mũ bảo hiểm không?";
    private static final String HELMET_2025 =
            "Ngày 01/06/2025, người ngồi trên xe máy có phải đội mũ bảo hiểm không?";
    private static final String MISSING_FACTS = "Tôi bị phạt bao nhiêu tiền?";
    private static final String NO_EVIDENCE = "Thủ tục ly hôn đơn phương cần những giấy tờ gì?";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CorpusImportService corpusImportService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private LocalChatOrchestrator orchestrator;

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private CitationValidator citationValidator;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ArticleEmbeddingRepository embeddings;

    @Autowired
    private QuotaReservationService quotaReservationService;

    @Autowired
    private RecordingWebSearchProvider webSearchProvider;

    @Autowired
    private RecordingDocumentFetcher documentFetcher;

    @BeforeAll
    void seedCorpusOnce() {
        corpusImportService.importCorpus();
    }

    @BeforeEach
    void resetAdapters() {
        webSearchProvider.reset();
        documentFetcher.reset();
    }

    @Test
    void sufficientLocalEvidenceCompletesWithVerbatimCitationAndOneQuotaSlot() {
        SessionOwner owner = anonymousOwner();
        ChatOutcome outcome = ask(owner, HELMET_QUESTION, "g4-answer");

        assertThat(outcome.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(outcome.decision()).isEqualTo(GroundingClient.Outcome.ANSWER);
        assertThat(outcome.reasonCode()).isEqualTo(GroundingClient.ReasonCode.SUFFICIENT);

        Map<String, Object> message = messageRow(outcome.messageId());
        assertThat(message.get("message_status")).isEqualTo("COMPLETED");
        assertThat(message.get("outcome")).isEqualTo("ANSWERED");

        List<Map<String, Object>> citations = citations(outcome.messageId());
        assertThat(citations).hasSize(1);
        Map<String, Object> citation = citations.get(0);
        assertThat(citation.get("document_key")).isEqualTo("luat-36-2024-qh15");
        assertThat(citation.get("article_code")).isEqualTo("dieu-33-khoan-2");
        assertThat(citation.get("verbatim_text")).isEqualTo(citation.get("raw_text"));
        assertThat((String) citation.get("snapshot_content")).contains((String) citation.get("raw_text"));
        assertThat((String) message.get("content")).contains((String) citation.get("raw_text"));

        BucketState bucket = bucket(owner);
        assertThat(bucket.usedCount()).isEqualTo(1);
        assertThat(bucket.reservedCount()).isZero();
        assertThat(ledgerCount(outcome.messageId())).isEqualTo(1);

        Map<String, Object> trace = trace(outcome.messageId());
        assertThat(trace.get("decision")).isEqualTo("ANSWER");
        assertThat(trace.get("reason_code")).isEqualTo("SUFFICIENT");
        assertThat((String) trace.get("model_id")).isNotBlank();
        assertThat(trace.get("prompt_version")).isEqualTo("context-grader-v1");
        assertThat((String) trace.get("corpus_version")).isEqualTo("road-traffic-seed-2026-11-12");
        assertThat((String) trace.get("candidate_scores")).contains("dieu-33-khoan-2");
        assertThat(webSearchProvider.calls()).isZero();
        assertThat(documentFetcher.calls()).isZero();
    }

    @Test
    void nonDiacriticQueryUsesNormalizedLexicalMatching() {
        SessionOwner owner = anonymousOwner();
        ChatOutcome outcome = ask(owner,
                "Nguoi dieu khien xe may co phai doi mu bao hiem khong?", "g4-no-diacritics");

        assertThat(outcome.status()).isEqualTo(MessageStatus.COMPLETED);
        List<Map<String, Object>> citations = citations(outcome.messageId());
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).get("article_code")).isEqualTo("dieu-33-khoan-2");
        assertThat(bucket(owner).usedCount()).isEqualTo(1);
    }

    @Test
    void missingPenaltyDocumentGoesToAwaitingWebConfirmWithoutWebCallsOrQuotaUse() {
        SessionOwner owner = anonymousOwner();
        ChatOutcome outcome = ask(owner, PENALTY_QUESTION, "g4-penalty");

        assertThat(outcome.status()).isEqualTo(MessageStatus.AWAITING_WEB_CONFIRM);
        assertThat(outcome.decision()).isEqualTo(GroundingClient.Outcome.NEED_WEB_SEARCH);
        assertThat(outcome.reasonCode()).isEqualTo(GroundingClient.ReasonCode.NO_MATCH);

        Map<String, Object> message = messageRow(outcome.messageId());
        assertThat(message.get("message_status")).isEqualTo("AWAITING_WEB_CONFIRM");
        assertThat((String) message.get("suggested_query")).isNotBlank();
        assertThat(citations(outcome.messageId())).isEmpty();
        assertThat(jobCount(outcome.messageId())).isZero();
        assertThat(reservationStatus(outcome.messageId())).isEqualTo("RELEASED");

        BucketState bucket = bucket(owner);
        assertThat(bucket.usedCount()).isZero();
        assertThat(bucket.reservedCount()).isZero();

        Map<String, Object> trace = trace(outcome.messageId());
        assertThat(trace.get("decision")).isEqualTo("NEED_WEB_SEARCH");
        assertThat(trace.get("reason_code")).isEqualTo("NO_MATCH");
        assertThat(webSearchProvider.calls()).isZero();
        assertThat(documentFetcher.calls()).isZero();
    }

    @Test
    void decree168OverviewQuestionAnswersWithArticle1() {
        SessionOwner owner = anonymousOwner();
        ChatOutcome outcome = ask(owner, "nghị định 168 là gì", "g4-nd168-overview");

        assertThat(outcome.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(outcome.decision()).isEqualTo(GroundingClient.Outcome.ANSWER);
        List<Map<String, Object>> citations = citations(outcome.messageId());
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).get("doc_number")).isEqualTo("168/2024/NĐ-CP");
        assertThat(citations.get(0).get("article_code")).isEqualTo("dieu-1");
        assertThat((String) citations.get(0).get("verbatim_text")).contains("Phạm vi điều chỉnh");
    }

    @Test
    void redLightPenaltyQuestionAnswersFromDecree168() {
        SessionOwner owner = anonymousOwner();
        ChatOutcome outcome = ask(owner,
                "Người điều khiển xe máy vượt đèn đỏ bị phạt bao nhiêu tiền theo quy định hiện hành?",
                "g4-nd168-redlight");

        assertThat(outcome.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(outcome.decision()).isEqualTo(GroundingClient.Outcome.ANSWER);
        List<Map<String, Object>> citations = citations(outcome.messageId());
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).get("doc_number")).isEqualTo("168/2024/NĐ-CP");
        assertThat(citations.get(0).get("article_code")).isEqualTo("dieu-7-khoan-7");
        assertThat((String) citations.get(0).get("verbatim_text"))
                .contains("4.000.000 đồng đến 6.000.000 đồng")
                .contains("đèn tín hiệu giao thông");
    }

    @Test
    void highSimilarityWrongSubjectIsRejectedAsWrongScope() {
        UUID articleId = articleId("luat-36-2024-qh15", "dieu-33-khoan-2");
        QueryAnalysis analysis = queryAnalyzer.analyze(WRONG_SCOPE_QUESTION);
        EmbeddingClient.Embedding embedding = embeddingClient.embed(
                analysis.normalizedQuestion(), EmbeddingClient.EmbeddingTaskType.RETRIEVAL_QUERY);

        UUID injectedId = null;
        try {
            injectedId = embeddings.insert(articleId, 99, "gemini-embedding-001",
                    embeddingClient.spaceId(), 768, "c".repeat(64),
                    embedding.values(), EmbeddingStatus.READY);

            SessionOwner owner = anonymousOwner();
            ChatOutcome outcome = ask(owner, WRONG_SCOPE_QUESTION, "g4-wrong-scope");

            assertThat(outcome.status()).isEqualTo(MessageStatus.AWAITING_WEB_CONFIRM);
            assertThat(outcome.status()).isNotEqualTo(MessageStatus.COMPLETED);
            assertThat(outcome.reasonCode()).isEqualTo(GroundingClient.ReasonCode.WRONG_SCOPE);
            assertThat(citations(outcome.messageId())).isEmpty();

            Map<String, Object> trace = trace(outcome.messageId());
            assertThat(trace.get("reason_code")).isEqualTo("WRONG_SCOPE");
            assertThat(((Number) trace.get("max_cosine")).doubleValue()).isGreaterThanOrEqualTo(0.999);
            assertThat(webSearchProvider.calls()).isZero();
            assertThat(documentFetcher.calls()).isZero();
        } finally {
            if (injectedId != null) {
                jdbcTemplate.update("delete from article_embeddings where id = ?", injectedId);
            }
        }
    }

    @Test
    void deferredClauseIsNotCitedBeforeItsOwnEffectiveDate() {
        SessionOwner oldDateOwner = anonymousOwner();
        ChatOutcome beforeEffective = ask(oldDateOwner, CHILD_SEAT_2025, "g4-k3-2025");

        assertThat(beforeEffective.status()).isEqualTo(MessageStatus.AWAITING_WEB_CONFIRM);
        assertThat(beforeEffective.reasonCode()).isEqualTo(GroundingClient.ReasonCode.TEMPORAL_UNCERTAINTY);
        assertThat(messageRow(beforeEffective.messageId()).get("applicable_date")).isNotNull();
        assertThat(applicableDate(beforeEffective.messageId()))
                .isEqualTo(java.time.LocalDate.of(2025, 6, 1));
        assertThat(citations(beforeEffective.messageId())).isEmpty();
        assertThat(trace(beforeEffective.messageId()).get("reason_code")).isEqualTo("TEMPORAL_UNCERTAINTY");
        assertThat(bucket(oldDateOwner).usedCount()).isZero();
        assertThat(webSearchProvider.calls()).isZero();

        SessionOwner effectiveOwner = anonymousOwner();
        ChatOutcome afterEffective = ask(effectiveOwner, CHILD_SEAT_2026, "g4-k3-2026");

        assertThat(afterEffective.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(applicableDate(afterEffective.messageId()))
                .isEqualTo(java.time.LocalDate.of(2026, 2, 1));
        List<Map<String, Object>> citations = citations(afterEffective.messageId());
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).get("article_code")).isEqualTo("dieu-10-khoan-3");
        assertThat(citations.get(0).get("verbatim_text")).isEqualTo(citations.get(0).get("raw_text"));
        assertThat(bucket(effectiveOwner).usedCount()).isEqualTo(1);
        assertThat(documentFetcher.calls()).isZero();
    }

    @Test
    void historicalAndCurrentQuestionsSelectDifferentValidVersions() {
        SessionOwner owner2024 = anonymousOwner();
        ChatOutcome historical = ask(owner2024, HELMET_2024, "g4-historical");

        assertThat(historical.status()).isEqualTo(MessageStatus.COMPLETED);
        List<Map<String, Object>> historicalCitations = citations(historical.messageId());
        assertThat(historicalCitations).hasSize(1);
        assertThat(historicalCitations.get(0).get("document_key")).isEqualTo("luat-23-2008-qh12");
        assertThat(historicalCitations.get(0).get("article_code")).isEqualTo("dieu-30-khoan-2");
        assertThat(bucket(owner2024).usedCount()).isEqualTo(1);

        SessionOwner owner2025 = anonymousOwner();
        ChatOutcome current = ask(owner2025, HELMET_2025, "g4-current");

        assertThat(current.status()).isEqualTo(MessageStatus.COMPLETED);
        List<Map<String, Object>> currentCitations = citations(current.messageId());
        assertThat(currentCitations).hasSize(1);
        assertThat(currentCitations.get(0).get("document_key")).isEqualTo("luat-36-2024-qh15");
        assertThat(currentCitations.get(0).get("article_code")).isEqualTo("dieu-33-khoan-2");
        assertThat(bucket(owner2025).usedCount()).isEqualTo(1);
        assertThat(webSearchProvider.calls()).isZero();
        assertThat(documentFetcher.calls()).isZero();
    }

    @Test
    void missingDecisiveFactsGoToClarificationWithoutQuotaUse() {
        SessionOwner penaltyOwner = anonymousOwner();
        ChatOutcome penalty = ask(penaltyOwner, MISSING_FACTS, "g4-missing-penalty");

        assertThat(penalty.status()).isEqualTo(MessageStatus.NEEDS_CLARIFICATION);
        assertThat(penalty.decision()).isEqualTo(GroundingClient.Outcome.NEED_CLARIFICATION);
        assertThat(penalty.reasonCode()).isEqualTo(GroundingClient.ReasonCode.MISSING_FACTS);
        Map<String, Object> penaltyMessage = messageRow(penalty.messageId());
        assertThat((String) penaltyMessage.get("content")).contains("loại phương tiện", "hành vi");
        assertThat(reservationStatus(penalty.messageId())).isEqualTo("RELEASED");
        assertThat(bucket(penaltyOwner).usedCount()).isZero();
        assertThat(bucket(penaltyOwner).reservedCount()).isZero();
        assertThat(trace(penalty.messageId()).get("decision")).isEqualTo("NEED_CLARIFICATION");

        SessionOwner passengerOwner = anonymousOwner();
        ChatOutcome passenger = ask(passengerOwner,
                "Tôi có được chở thêm người trên xe không?", "g4-missing-passenger");

        assertThat(passenger.status()).isEqualTo(MessageStatus.NEEDS_CLARIFICATION);
        assertThat((String) messageRow(passenger.messageId()).get("content")).contains("loại xe");
        assertThat(bucket(passengerOwner).usedCount()).isZero();
        assertThat(webSearchProvider.calls()).isZero();
        assertThat(documentFetcher.calls()).isZero();
    }

    @Test
    void outOfDomainQuestionIsRefusedWithoutCitationsWebCallsOrQuota() {
        SessionOwner owner = anonymousOwner();
        ChatOutcome outcome = ask(owner, NO_EVIDENCE, "g4-no-evidence");

        assertThat(outcome.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(messageRow(outcome.messageId()).get("outcome")).isEqualTo("OUT_OF_SCOPE");

        Map<String, Object> trace = trace(outcome.messageId());
        assertThat(trace.get("model_id")).isEqualTo("layer-1-gate");
        assertThat(trace.get("gate_passed")).isEqualTo(Boolean.FALSE);
        assertThat(trace.get("decision")).isEqualTo("OUT_OF_SCOPE");
        assertThat(trace.get("reason_code")).isEqualTo("OUT_OF_SCOPE");
        assertThat(citations(outcome.messageId())).isEmpty();
        assertThat(jobCount(outcome.messageId())).isZero();
        assertThat(bucket(owner).usedCount()).isZero();
        assertThat(bucket(owner).reservedCount()).isZero();
        assertThat(webSearchProvider.calls()).isZero();
        assertThat(documentFetcher.calls()).isZero();
    }

    @Test
    void citationValidatorBlocksHallucinatedEmptyAndTemporallyInvalidIds() {
        QueryAnalysis analysis = queryAnalyzer.analyze(HELMET_QUESTION);
        RetrievalResult retrieval = hybridRetrievalService.retrieve(analysis);

        GroundingClient.Decision hallucinated = new GroundingClient.Decision(
                GroundingClient.Outcome.ANSWER,
                List.of(UUID.randomUUID().toString()),
                GroundingClient.ReasonCode.SUFFICIENT, null, null,
                "test-grader", "test-prompt-v1");
        assertThatThrownBy(() -> citationValidator.validate(
                hallucinated, analysis, retrieval.contextCandidates()))
                .isInstanceOf(CitationValidationException.class);

        GroundingClient.Decision empty = new GroundingClient.Decision(
                GroundingClient.Outcome.ANSWER,
                List.of(),
                GroundingClient.ReasonCode.SUFFICIENT, null, null,
                "test-grader", "test-prompt-v1");
        assertThatThrownBy(() -> citationValidator.validate(
                empty, analysis, retrieval.contextCandidates()))
                .isInstanceOf(CitationValidationException.class);

        QueryAnalysis valid2026 = queryAnalyzer.analyze(CHILD_SEAT_2026);
        RetrievalResult retrieval2026 = hybridRetrievalService.retrieve(valid2026);
        RetrievalCandidate clause = retrieval2026.contextCandidates().stream()
                .filter(candidate -> candidate.articleCode().equals("dieu-10-khoan-3"))
                .findFirst()
                .orElseThrow();
        GroundingClient.Decision selectingClause = new GroundingClient.Decision(
                GroundingClient.Outcome.ANSWER,
                List.of(clause.articleId().toString()),
                GroundingClient.ReasonCode.SUFFICIENT, null, null,
                "test-grader", "test-prompt-v1");
        QueryAnalysis invalidDate = queryAnalyzer.analyze(CHILD_SEAT_2025);
        assertThatThrownBy(() -> citationValidator.validate(
                selectingClause, invalidDate, retrieval2026.contextCandidates()))
                .isInstanceOf(CitationValidationException.class);

        List<ValidatedCitation> validated = citationValidator.validate(
                selectingClause, valid2026, retrieval2026.contextCandidates());
        assertThat(validated).singleElement().satisfies(citation -> {
            assertThat(citation.rawText()).isEqualTo(clause.rawText());
            assertThat(citation.snapshotContent()).contains(citation.rawText());
            assertThat(LegalText.matchesVerbatim(citation.snapshotContent(), citation.rawText())).isTrue();
        });

        RenderedAnswer rendered = new AnswerRenderer().render(validated);
        assertThat(rendered.cards()).singleElement().satisfies(card -> {
            assertThat(card.href()).startsWith("/legal/documents/");
            assertThat(card.href()).contains("/articles/" + clause.articleId());
            assertThat(card.verbatimText()).isEqualTo(clause.rawText());
        });
        assertThat(rendered.content()).contains(clause.rawText());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ChatOutcome ask(SessionOwner owner, String question, String key) {
        ChatSession session = chatSessionService.createSession(owner);
        return orchestrator.ask(session.getId(), owner, key, question);
    }

    private SessionOwner anonymousOwner() {
        return SessionOwner.anonymous(UUID.randomUUID().toString());
    }

    private UUID articleId(String documentKey, String articleCode) {
        return jdbcTemplate.queryForObject("""
                select a.id
                from legal_articles a
                join legal_documents d on d.id = a.document_id
                where d.document_key = ? and a.article_code = ?
                """, UUID.class, documentKey, articleCode);
    }

    private Map<String, Object> messageRow(UUID messageId) {
        return jdbcTemplate.queryForMap(
                "select id, content, message_status, outcome, suggested_query, applicable_date "
                        + "from chat_messages where id = ?",
                messageId);
    }

    private java.time.LocalDate applicableDate(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "select applicable_date from chat_messages where id = ?",
                java.time.LocalDate.class, messageId);
    }

    private List<Map<String, Object>> citations(UUID messageId) {
        return jdbcTemplate.queryForList("""
                select mc.verbatim_text, mc.locator, a.article_code, a.raw_text,
                       d.document_key, d.doc_number, s.content as snapshot_content
                from message_citations mc
                join legal_articles a on a.id = mc.article_id
                join legal_documents d on d.id = mc.document_version_id
                join source_snapshots s on s.id = mc.source_snapshot_id
                where mc.message_id = ?
                order by mc.citation_order
                """, messageId);
    }

    private Map<String, Object> trace(UUID messageId) {
        return jdbcTemplate.queryForMap("""
                select model_id, prompt_version, corpus_version, gate_passed, max_cosine,
                       decision, reason_code, candidate_scores
                from retrieval_traces
                where message_id = ?
                order by created_at desc
                limit 1
                """, messageId);
    }

    private BucketState bucket(SessionOwner owner) {
        return jdbcTemplate.queryForObject("""
                select used_count, reserved_count
                from quota_buckets
                where anon_id = ? and quota_period = ?
                """,
                (resultSet, rowNumber) -> new BucketState(
                        resultSet.getInt("used_count"),
                        resultSet.getInt("reserved_count")),
                owner.ownerId(), quotaReservationService.currentPeriod());
    }

    private String reservationStatus(UUID messageId) {
        return jdbcTemplate.queryForObject("""
                select status from quota_reservations
                where message_id = ?
                order by created_at desc
                limit 1
                """, String.class, messageId);
    }

    private int ledgerCount(UUID answerMessageId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from quota_ledger where answer_message_id = ?",
                Integer.class, answerMessageId);
        return count == null ? 0 : count;
    }

    private int jobCount(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from web_search_jobs where message_id = ?",
                Integer.class, messageId);
        return count == null ? 0 : count;
    }

    private record BucketState(int usedCount, int reservedCount) {
    }

    @TestConfiguration
    static class RecordingAdapters {

        @Bean
        RecordingWebSearchProvider recordingWebSearchProvider() {
            return new RecordingWebSearchProvider();
        }

        @Bean
        RecordingDocumentFetcher recordingDocumentFetcher() {
            return new RecordingDocumentFetcher();
        }
    }

    static class RecordingWebSearchProvider implements WebSearchProvider {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<SearchResult> search(String query, int maxResults) {
            calls.incrementAndGet();
            return List.of();
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }

    static class RecordingDocumentFetcher implements DocumentFetcher {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public FetchedDocument fetch(java.net.URI url) {
            calls.incrementAndGet();
            throw new AssertionError("DocumentFetcher must not be called before consent");
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
