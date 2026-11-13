package vn.edu.hsf302.lawrag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.hsf302.lawrag.chat.exception.ChatAccessDeniedException;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.service.ChatSessionService;
import vn.edu.hsf302.lawrag.chat.exception.ConsentExpiredException;
import vn.edu.hsf302.lawrag.chat.exception.InvalidMessageTransitionException;
import vn.edu.hsf302.lawrag.chat.service.MessageStateService;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.service.MessageSubmissionService;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.dto.SubmittedQuestion;
import vn.edu.hsf302.lawrag.legaldata.repository.ArticleEmbeddingRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.EmbeddingStatus;
import vn.edu.hsf302.lawrag.quota.service.QuotaExceededException;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Gate G2 evidence against real PostgreSQL + pgvector:
 * blank-DB migration, schema constraints/indexes, cross-owner blocking,
 * idempotent question submission, single-winner transitions and atomic quota.
 */
class G2AcceptanceTest extends PostgresIntegrationTest {

    private static final String QUESTION = "Người điều khiển xe máy có phải đội mũ bảo hiểm không?";
    private static final String SEARCH_QUERY = "mũ bảo hiểm xe máy quy định pháp luật";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private MessageSubmissionService messageSubmissionService;

    @Autowired
    private MessageStateService messageStateService;

    @Autowired
    private QuotaReservationService quotaReservationService;

    @Autowired
    private ArticleEmbeddingRepository articleEmbeddingRepository;

    @Test
    void migrationV2RunsFromBlankDatabaseAndCreatesCoreSchema() {
        Integer v2Applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success = true",
                Integer.class);
        assertThat(v2Applied).isEqualTo(1);

        Integer failedMigrations = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        assertThat(failedMigrations).isZero();

        Integer coreTables = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_name in (
                    'chat_sessions', 'chat_messages', 'message_citations',
                    'legal_documents', 'legal_articles', 'legal_document_relations',
                    'article_embeddings', 'source_snapshots', 'web_search_jobs',
                    'quota_buckets', 'quota_reservations', 'quota_ledger'
                  )
                """, Integer.class);
        assertThat(coreTables).isEqualTo(12);

        String embeddingType = jdbcTemplate.queryForObject("""
                select format_type(atttypid, atttypmod)
                from pg_attribute
                where attrelid = 'public.article_embeddings'::regclass
                  and attname = 'embedding'
                  and not attisdropped
                """, String.class);
        assertThat(embeddingType).isEqualTo("vector(768)");

        String hnswIndex = jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = 'idx_article_embeddings_embedding_hnsw'",
                String.class);
        assertThat(hnswIndex.toLowerCase(Locale.ROOT))
                .contains("using hnsw")
                .contains("vector_cosine_ops");

        String ftsIndex = jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = 'idx_legal_articles_search_vector'",
                String.class);
        assertThat(ftsIndex.toLowerCase(Locale.ROOT)).contains("using gin");

        String activeJobIndex = jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = 'uq_web_search_jobs_active_per_message'",
                String.class);
        assertThat(activeJobIndex.toUpperCase(Locale.ROOT)).contains("WHERE");

        Integer criticalConstraints = jdbcTemplate.queryForObject("""
                select count(*)
                from pg_constraint
                where conname in (
                    'uq_chat_messages_idempotency',
                    'uq_quota_ledger_answer_message',
                    'ck_quota_buckets_capacity',
                    'ck_quota_buckets_used',
                    'ck_quota_buckets_reserved',
                    'ck_chat_messages_status'
                )
                """, Integer.class);
        assertThat(criticalConstraints).isEqualTo(6);
    }

    @Test
    void vectorAndFullTextIndexesAreUsable() {
        UUID snapshotId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        String sha = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");

        jdbcTemplate.update("""
                insert into source_snapshots
                    (id, source_url, content, content_sha256, fetched_at, content_type, parser_version, byte_size)
                values (?, 'https://example.test/legal', 'raw payload', ?, now(), 'text/html', 'test-parser-v1', 11)
                """, snapshotId, sha);
        jdbcTemplate.update("""
                insert into legal_documents (id, document_key, title, source_url, snapshot_id)
                values (?, 'test-document', 'Luật thử nghiệm', 'https://example.test/legal', ?)
                """, documentId, snapshotId);
        jdbcTemplate.update("""
                insert into legal_articles
                    (id, document_id, hierarchy_level, article_code, article_number,
                     title, raw_text, search_text, locator)
                values (?, ?, 'DIEU', 'dieu-10', '10',
                        'Mũ bảo hiểm', 'Người đi xe máy phải đội mũ bảo hiểm.', 'mũ bảo hiểm xe máy', 'Điều 10')
                """, articleId, documentId);

        float[] vector = new float[768];
        vector[0] = 1.0f;
        UUID embeddingId = articleEmbeddingRepository.insert(
                articleId, 0, "gemini-embedding-001", "gemini-embedding-001@768:v1",
                768, "b".repeat(64), vector, EmbeddingStatus.READY);

        assertThat(embeddingId).isNotNull();
        assertThat(articleEmbeddingRepository.findByArticleId(articleId))
                .singleElement()
                .satisfies(embedding -> assertThat(embedding.embedding()).hasSize(768));

        Double cosineDistance = jdbcTemplate.queryForObject(
                "select embedding <=> ?::vector from article_embeddings where id = ?",
                Double.class, vectorLiteral(vector), embeddingId);
        assertThat(cosineDistance).isCloseTo(0.0, within(1e-6));

        Boolean ftsMatch = jdbcTemplate.queryForObject("""
                select search_vector @@ plainto_tsquery('simple', ?)
                from legal_articles where id = ?
                """, Boolean.class, "mũ bảo hiểm", articleId);
        assertThat(ftsMatch).isTrue();
    }

    @Test
    void crossOwnerAccessAndMutationAreBlocked() {
        SessionOwner owner = anonymousOwner();
        SessionOwner intruder = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        SubmittedQuestion pair = submit(owner, session, "cross-owner-key");

        assertThatThrownBy(() -> messageStateService.getOwnedMessage(pair.aiMessage().getId(), intruder))
                .isInstanceOf(ChatAccessDeniedException.class);
        assertThatThrownBy(() -> messageStateService.markAwaitingWebConfirm(
                pair.aiMessage().getId(), intruder, SEARCH_QUERY))
                .isInstanceOf(ChatAccessDeniedException.class);
        assertThatThrownBy(() -> messageSubmissionService.submitQuestion(
                session.getId(), intruder, "intruder-key", "Câu hỏi khác"))
                .isInstanceOf(ChatAccessDeniedException.class);

        assertThat(messageStatus(pair.aiMessage().getId())).isEqualTo("PROCESSING_LOCAL");
        assertThat(messageCount(session.getId())).isEqualTo(2);
        assertThat(bucket(owner).reservedCount()).isEqualTo(1);
    }

    @Test
    void duplicateSubmissionReturnsSamePairWithoutDuplicateRowsOrQuota() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);

        SubmittedQuestion first = submit(owner, session, "dup-key-1");
        assertThat(first.replay()).isFalse();

        SubmittedQuestion replay = submit(owner, session, "dup-key-1");
        assertThat(replay.replay()).isTrue();
        assertThat(replay.userMessage().getId()).isEqualTo(first.userMessage().getId());
        assertThat(replay.aiMessage().getId()).isEqualTo(first.aiMessage().getId());

        assertThat(messageCount(session.getId())).isEqualTo(2);
        assertThat(reservationCount(first.aiMessage().getId())).isEqualTo(1);
        BucketState bucket = bucket(owner);
        assertThat(bucket.reservedCount()).isEqualTo(1);
        assertThat(bucket.usedCount()).isZero();
    }

    @Test
    void concurrentDuplicateSubmissionsConvergeOnOnePairAndOneReservation() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        List<Callable<SubmittedQuestion>> calls = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            calls.add(() -> submit(owner, session, "race-key"));
        }

        List<Attempt<SubmittedQuestion>> results = runConcurrently(calls);

        assertThat(results).allMatch(Attempt::succeeded);
        List<UUID> userMessages = results.stream()
                .map(result -> result.value().userMessage().getId())
                .distinct()
                .toList();
        List<UUID> aiMessages = results.stream()
                .map(result -> result.value().aiMessage().getId())
                .distinct()
                .toList();
        assertThat(userMessages).hasSize(1);
        assertThat(aiMessages).hasSize(1);

        assertThat(messageCount(session.getId())).isEqualTo(2);
        assertThat(reservationCount(aiMessages.get(0))).isEqualTo(1);
        BucketState bucket = bucket(owner);
        assertThat(bucket.reservedCount()).isEqualTo(1);
        assertThat(bucket.usedCount()).isZero();
    }

    @Test
    void concurrentConfirmAndCancelHaveExactlyOneWinner() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        SubmittedQuestion pair = submit(owner, session, "confirm-cancel-key");
        UUID messageId = pair.aiMessage().getId();
        messageStateService.markAwaitingWebConfirm(messageId, owner, SEARCH_QUERY);
        assertThat(bucket(owner).reservedCount()).isZero();

        List<Callable<String>> calls = List.of(
                () -> {
                    messageStateService.confirmWebSearch(messageId, owner);
                    return "CONFIRM";
                },
                () -> {
                    messageStateService.cancelWebSearch(messageId, owner);
                    return "CANCEL";
                });

        List<Attempt<String>> results = runConcurrently(calls);
        List<String> successes = results.stream()
                .filter(Attempt::succeeded)
                .map(Attempt::value)
                .toList();
        List<Throwable> failures = results.stream()
                .filter(result -> !result.succeeded())
                .map(Attempt::error)
                .toList();

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOfAny(
                vn.edu.hsf302.lawrag.chat.exception.MessageTransitionConflictException.class,
                InvalidMessageTransitionException.class);

        String winner = successes.get(0);
        String finalStatus = messageStatus(messageId);
        BucketState bucket = bucket(owner);
        Integer jobCount = jdbcTemplate.queryForObject(
                "select count(*) from web_search_jobs where message_id = ?", Integer.class, messageId);
        Integer activeJobCount = jdbcTemplate.queryForObject("""
                select count(*) from web_search_jobs
                where message_id = ? and job_status in ('QUEUED', 'CLAIMED', 'RUNNING', 'RETRY_WAIT')
                """, Integer.class, messageId);

        if ("CONFIRM".equals(winner)) {
            assertThat(finalStatus).isEqualTo("WEB_QUEUED");
            assertThat(jobCount).isEqualTo(1);
            assertThat(activeJobCount).isEqualTo(1);
            assertThat(bucket.reservedCount()).isEqualTo(1);
            assertThat(bucket.usedCount()).isZero();
            assertThat(activeReservationCount(messageId)).isEqualTo(1);
        } else {
            assertThat(finalStatus).isEqualTo("CANCELLED");
            assertThat(jobCount).isZero();
            assertThat(bucket.reservedCount()).isZero();
            assertThat(bucket.usedCount()).isZero();
            assertThat(activeReservationCount(messageId)).isZero();
        }
    }

    @Test
    void expiredConsentCannotCreateJobOrConsumeReservation() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        SubmittedQuestion pair = submit(owner, session, "expired-key");
        UUID messageId = pair.aiMessage().getId();
        messageStateService.markAwaitingWebConfirm(messageId, owner, SEARCH_QUERY);

        jdbcTemplate.update(
                "update chat_messages set confirm_expires_at = now() - interval '1 minute' where id = ?",
                messageId);

        assertThatThrownBy(() -> messageStateService.confirmWebSearch(messageId, owner))
                .isInstanceOf(ConsentExpiredException.class);

        assertThat(messageStatus(messageId)).isEqualTo("AWAITING_WEB_CONFIRM");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from web_search_jobs where message_id = ?", Integer.class, messageId)).isZero();
        assertThat(activeReservationCount(messageId)).isZero();
        assertThat(bucket(owner).reservedCount()).isZero();
    }

    @Test
    void concurrentReservationsForLastSlotNeverExceedOrNegateQuota() throws Exception {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);

        List<SubmittedQuestion> existing = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            existing.add(submit(owner, session, "slot-" + index));
        }
        assertThat(bucket(owner).reservedCount()).isEqualTo(4);

        List<Callable<SubmittedQuestion>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int attempt = index;
            calls.add(() -> submit(owner, session, "last-slot-" + attempt));
        }
        List<Attempt<SubmittedQuestion>> results = runConcurrently(calls);

        List<SubmittedQuestion> winners = results.stream()
                .filter(Attempt::succeeded)
                .map(Attempt::value)
                .toList();
        List<Throwable> losers = results.stream()
                .filter(result -> !result.succeeded())
                .map(Attempt::error)
                .toList();

        assertThat(winners).hasSize(1);
        assertThat(losers).hasSize(7);
        assertThat(losers).allMatch(QuotaExceededException.class::isInstance);

        BucketState bucket = bucket(owner);
        assertThat(bucket.limitCount()).isEqualTo(5);
        assertThat(bucket.reservedCount()).isEqualTo(5);
        assertThat(bucket.usedCount()).isZero();
        assertThat(bucket.reservedCount()).isGreaterThanOrEqualTo(0);
        assertThat(bucket.usedCount()).isGreaterThanOrEqualTo(0);
        assertThat(bucket.usedCount() + bucket.reservedCount()).isLessThanOrEqualTo(bucket.limitCount());
        assertThat(messageCount(session.getId())).isEqualTo(10);
        assertThat(activeReservationCountForOwner(owner)).isEqualTo(5);

        SubmittedQuestion released = existing.get(0);
        messageStateService.markAwaitingWebConfirm(released.aiMessage().getId(), owner, SEARCH_QUERY);
        BucketState afterRelease = bucket(owner);
        assertThat(afterRelease.reservedCount()).isEqualTo(4);
        assertThat(afterRelease.usedCount()).isZero();
        assertThat(reservationStatus(released.aiMessage().getId())).isEqualTo("RELEASED");

        SubmittedQuestion completed = existing.get(1);
        messageStateService.complete(completed.aiMessage().getId(), owner, "Câu trả lời có căn cứ.");
        BucketState afterCommit = bucket(owner);
        assertThat(afterCommit.reservedCount()).isEqualTo(3);
        assertThat(afterCommit.usedCount()).isEqualTo(1);
        assertThat(ledgerCount(completed.aiMessage().getId())).isEqualTo(1);

        // Replaying the same answer and calling commit again must never charge twice.
        messageStateService.complete(completed.aiMessage().getId(), owner, "Câu trả lời có căn cứ.");
        assertThat(quotaReservationService.commit(completed.aiMessage().getId())).isFalse();

        BucketState afterReplay = bucket(owner);
        assertThat(afterReplay.reservedCount()).isEqualTo(3);
        assertThat(afterReplay.usedCount()).isEqualTo(1);
        assertThat(ledgerCount(completed.aiMessage().getId())).isEqualTo(1);
    }

    @Test
    void databaseChecksRejectNegativeAndOverLimitQuota() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        submit(owner, session, "constraint-key");
        UUID bucketId = jdbcTemplate.queryForObject(
                "select id from quota_buckets where anon_id = ? and quota_period = ?",
                UUID.class, owner.ownerId(), quotaReservationService.currentPeriod());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update quota_buckets set reserved_count = -1 where id = ?", bucketId))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update quota_buckets set used_count = 4, reserved_count = 2 where id = ?", bucketId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void invalidStateMachineMovesAreRejected() {
        SessionOwner owner = anonymousOwner();
        ChatSession session = chatSessionService.createSession(owner);
        SubmittedQuestion pair = submit(owner, session, "invalid-transition-key");
        UUID messageId = pair.aiMessage().getId();

        assertThatThrownBy(() -> messageStateService.cancelWebSearch(messageId, owner))
                .isInstanceOf(InvalidMessageTransitionException.class);
        assertThatThrownBy(() -> messageStateService.transition(messageId, owner, MessageStatus.WEB_SEARCHING))
                .isInstanceOf(InvalidMessageTransitionException.class);

        messageStateService.markAwaitingWebConfirm(messageId, owner, SEARCH_QUERY);
        assertThatThrownBy(() -> messageStateService.transition(messageId, owner, MessageStatus.COMPLETED))
                .isInstanceOf(InvalidMessageTransitionException.class);

        assertThat(messageStatus(messageId)).isEqualTo("AWAITING_WEB_CONFIRM");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from web_search_jobs where message_id = ?", Integer.class, messageId)).isZero();
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private SessionOwner anonymousOwner() {
        return SessionOwner.anonymous(UUID.randomUUID().toString());
    }

    private SubmittedQuestion submit(SessionOwner owner, ChatSession session, String key) {
        return messageSubmissionService.submitQuestion(session.getId(), owner, key, QUESTION);
    }

    private String messageStatus(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "select message_status from chat_messages where id = ?", String.class, messageId);
    }

    private int messageCount(UUID sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from chat_messages where session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    private int reservationCount(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from quota_reservations where message_id = ?", Integer.class, messageId);
        return count == null ? 0 : count;
    }

    private int activeReservationCount(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from quota_reservations where message_id = ? and status = 'ACTIVE'",
                Integer.class, messageId);
        return count == null ? 0 : count;
    }

    private int activeReservationCountForOwner(SessionOwner owner) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from quota_reservations r
                join quota_buckets b on b.id = r.bucket_id
                where b.anon_id = ? and b.quota_period = ? and r.status = 'ACTIVE'
                """, Integer.class, owner.ownerId(), quotaReservationService.currentPeriod());
        return count == null ? 0 : count;
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

    private BucketState bucket(SessionOwner owner) {
        return jdbcTemplate.queryForObject("""
                select limit_count, used_count, reserved_count
                from quota_buckets
                where anon_id = ? and quota_period = ?
                """,
                (resultSet, rowNumber) -> new BucketState(
                        resultSet.getInt("limit_count"),
                        resultSet.getInt("used_count"),
                        resultSet.getInt("reserved_count")),
                owner.ownerId(), quotaReservationService.currentPeriod());
    }

    private <T> List<Attempt<T>> runConcurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent test start timed out");
                    }
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Attempt<T>> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(Attempt.success(future.get(30, TimeUnit.SECONDS)));
                } catch (ExecutionException exception) {
                    results.add(Attempt.failure(exception.getCause()));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private record BucketState(int limitCount, int usedCount, int reservedCount) {
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    private record Attempt<T>(T value, Throwable error) {

        static <T> Attempt<T> success(T value) {
            return new Attempt<>(value, null);
        }

        static <T> Attempt<T> failure(Throwable error) {
            return new Attempt<>(null, error);
        }

        boolean succeeded() {
            return error == null;
        }
    }
}
