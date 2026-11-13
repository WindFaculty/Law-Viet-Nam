package vn.edu.hsf302.lawrag.websearch.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.repository.ChatMessageRepository;
import vn.edu.hsf302.lawrag.chat.entity.MessageCitation;
import vn.edu.hsf302.lawrag.chat.repository.MessageCitationRepository;
import vn.edu.hsf302.lawrag.chat.service.MessageStateService;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.repository.SourceSnapshotRepository;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;
import vn.edu.hsf302.lawrag.retrieval.dto.CitationDraft;
import vn.edu.hsf302.lawrag.retrieval.dto.RenderedAnswer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.websearch.dto.ClaimedJob;
import vn.edu.hsf302.lawrag.websearch.dto.JobRow;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStage;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStatus;
import vn.edu.hsf302.lawrag.websearch.config.WebSearchProperties;

/**
 * Durable job ownership. Claiming is a single conditional UPDATE under a
 * PostgreSQL advisory lock, so two workers cannot own the same job or exceed
 * the global concurrency cap. Every stage/finalization update carries the lease
 * token: a stale worker gets zero rows and must stop without writing.
 */
@Service
public class WebSearchJobService {

    private static final long CLAIM_LOCK = 734_219_001L;

    private static final String CLAIMABLE = """
            j.attempt_count < ?
              and j.deadline > now()
              and (
                    j.job_status = 'QUEUED'
                    or (j.job_status = 'RETRY_WAIT'
                        and (j.next_attempt_at is null or j.next_attempt_at <= now()))
                    or (j.job_status in ('CLAIMED', 'RUNNING') and j.lease_expires_at < now())
              )
              and (
                    select count(*) from web_search_jobs active
                    where active.job_status in ('CLAIMED', 'RUNNING')
                      and active.lease_expires_at > now()
              ) < ?
            """;

    private static final RowMapper<ClaimedJob> CLAIMED_MAPPER = (resultSet, rowNumber) -> new ClaimedJob(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("message_id", UUID.class),
            resultSet.getObject("lease_token", UUID.class),
            resultSet.getInt("attempt_count"),
            resultSet.getTimestamp("deadline").toInstant());

    private static final RowMapper<JobRow> JOB_MAPPER = (resultSet, rowNumber) -> new JobRow(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("message_id", UUID.class),
            WebSearchJobStatus.valueOf(resultSet.getString("job_status")),
            WebSearchJobStage.valueOf(resultSet.getString("stage")),
            resultSet.getInt("attempt_count"),
            resultSet.getTimestamp("deadline").toInstant(),
            resultSet.getObject("lease_token", UUID.class));

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final MessageStateService messages;
    private final ChatMessageRepository messageRepository;
    private final MessageCitationRepository citations;
    private final LegalDocumentRepository documents;
    private final LegalArticleRepository articles;
    private final SourceSnapshotRepository snapshots;
    private final WebSearchProperties properties;
    private final CostBudgetService budget;

    public WebSearchJobService(JdbcTemplate jdbcTemplate,
                               PlatformTransactionManager transactionManager,
                               MessageStateService messages,
                               ChatMessageRepository messageRepository,
                               MessageCitationRepository citations,
                               LegalDocumentRepository documents,
                               LegalArticleRepository articles,
                               SourceSnapshotRepository snapshots,
                               WebSearchProperties properties,
                               CostBudgetService budget) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.messages = messages;
        this.messageRepository = messageRepository;
        this.citations = citations;
        this.documents = documents;
        this.articles = articles;
        this.snapshots = snapshots;
        this.properties = properties;
        this.budget = budget;
    }

    @Transactional
    public Optional<ClaimedJob> claimNext(String workerId) {
        acquireClaimLock();
        List<UUID> ids = jdbcTemplate.queryForList("""
                select j.id
                from web_search_jobs j
                where %s
                order by j.created_at, j.id
                for update skip locked
                limit 1
                """.formatted(CLAIMABLE),
                UUID.class, properties.maxAttempts(), concurrentJobLimit());
        return ids.isEmpty() ? Optional.empty() : Optional.of(claim(ids.get(0), workerId));
    }

    @Transactional
    public Optional<ClaimedJob> claimJob(UUID jobId, String workerId) {
        acquireClaimLock();
        List<UUID> ids = jdbcTemplate.queryForList("""
                select j.id
                from web_search_jobs j
                where j.id = ? and %s
                for update skip locked
                limit 1
                """.formatted(CLAIMABLE),
                UUID.class, jobId, properties.maxAttempts(), concurrentJobLimit());
        return ids.isEmpty() ? Optional.empty() : Optional.of(claim(ids.get(0), workerId));
    }

    private int concurrentJobLimit() {
        return Math.min(properties.maxConcurrentJobs(), budget.maxConcurrentWebJobs());
    }

    private ClaimedJob claim(UUID jobId, String workerId) {
        UUID token = UUID.randomUUID();
        String owner = workerId.length() <= 100 ? workerId : workerId.substring(0, 100);
        List<ClaimedJob> claimed = jdbcTemplate.query("""
                update web_search_jobs
                set job_status = 'CLAIMED',
                    stage = 'CLAIM',
                    attempt_count = attempt_count + 1,
                    lease_token = ?,
                    lease_owner = ?,
                    lease_expires_at = now() + make_interval(secs => ?),
                    heartbeat_at = now(),
                    error_code = null,
                    next_attempt_at = null,
                    updated_at = now()
                where id = ?
                returning id, message_id, lease_token, attempt_count, deadline
                """, CLAIMED_MAPPER, token, owner, (int) properties.leaseDuration().toSeconds(), jobId);
        if (claimed.isEmpty()) {
            throw new LeaseLostException("job disappeared while claiming: " + jobId);
        }
        return claimed.get(0);
    }

    @Transactional
    public void heartbeat(UUID jobId, UUID leaseToken) {
        int updated = jdbcTemplate.update("""
                update web_search_jobs
                set heartbeat_at = now(),
                    lease_expires_at = now() + make_interval(secs => ?),
                    updated_at = now()
                where id = ? and lease_token = ?
                  and job_status in ('CLAIMED', 'RUNNING')
                """, (int) properties.leaseDuration().toSeconds(), jobId, leaseToken);
        if (updated == 0) {
            throw new LeaseLostException("lease lost while heartbeating: " + jobId);
        }
    }

    @Transactional
    public void advanceStage(UUID jobId, UUID leaseToken, WebSearchJobStage stage) {
        int updated = jdbcTemplate.update("""
                update web_search_jobs
                set stage = ?,
                    job_status = case when job_status = 'CLAIMED' then 'RUNNING' else job_status end,
                    heartbeat_at = now(),
                    lease_expires_at = now() + make_interval(secs => ?),
                    updated_at = now()
                where id = ? and lease_token = ?
                  and job_status in ('CLAIMED', 'RUNNING')
                  and deadline > now()
                """, stage.name(), (int) properties.leaseDuration().toSeconds(), jobId, leaseToken);
        if (updated == 0) {
            throw new LeaseLostException("lease/deadline lost before stage " + stage + ": " + jobId);
        }
    }

    @Transactional
    public void finalizeSuccess(UUID jobId, UUID leaseToken,
                                String answerContent, RenderedAnswer rendered) {
        UUID messageId = requireMessageId(jobId);
        fence(jobId, leaseToken, WebSearchJobStatus.SUCCEEDED, null);
        ChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new LeaseLostException("message disappeared: " + messageId));
        if (message.getMessageStatus() == MessageStatus.COMPLETED) {
            return;
        }
        SessionOwner owner = message.getSession().owner();
        if (message.getMessageStatus() == MessageStatus.WEB_QUEUED) {
            messages.transition(messageId, owner, MessageStatus.WEB_SEARCHING);
        } else if (message.getMessageStatus() != MessageStatus.WEB_SEARCHING) {
            // A concurrent terminal transition already decided the outcome; the
            // fenced job is still finalized without touching quota/citations.
            return;
        }
        messages.complete(messageId, owner, answerContent);
        for (CitationDraft draft : rendered.citations()) {
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

    @Transactional
    public void finalizeNoEvidence(UUID jobId, UUID leaseToken, WebSearchErrorCode errorCode) {
        UUID messageId = requireMessageId(jobId);
        fence(jobId, leaseToken, WebSearchJobStatus.SUCCEEDED, errorCode.name());
        transitionIfActive(messageId, MessageStatus.NO_EVIDENCE);
    }

    @Transactional
    public void finalizeFailure(UUID jobId, UUID leaseToken, WebSearchErrorCode errorCode) {
        UUID messageId = requireMessageId(jobId);
        fence(jobId, leaseToken, WebSearchJobStatus.FAILED, errorCode.name());
        transitionIfActive(messageId, MessageStatus.WEB_FAILED);
    }

    /** Fence a job whose message already reached a terminal state (replay). */
    @Transactional
    public void finishWithoutSideEffects(UUID jobId, UUID leaseToken) {
        fence(jobId, leaseToken, WebSearchJobStatus.SUCCEEDED, null);
    }

    /**
     * @return true when the job was re-queued with backoff, false when the
     * attempt budget is exhausted and the message was finalized as failed
     */
    @Transactional
    public boolean scheduleRetry(UUID jobId, UUID leaseToken, WebSearchErrorCode errorCode) {
        JobRow job = findJob(jobId)
                .orElseThrow(() -> new LeaseLostException("job disappeared: " + jobId));
        if (job.attemptCount() >= properties.maxAttempts()) {
            fence(jobId, leaseToken, WebSearchJobStatus.FAILED, errorCode.name());
            transitionIfActive(job.messageId(), MessageStatus.WEB_FAILED);
            return false;
        }
        int updated = jdbcTemplate.update("""
                update web_search_jobs
                set job_status = 'RETRY_WAIT',
                    error_code = ?,
                    next_attempt_at = now() + make_interval(secs => ?),
                    lease_token = null,
                    lease_owner = null,
                    lease_expires_at = null,
                    heartbeat_at = null,
                    updated_at = now()
                where id = ? and lease_token = ?
                  and job_status in ('CLAIMED', 'RUNNING')
                """, errorCode.name(), (int) properties.retryBackoff().toSeconds(), jobId, leaseToken);
        if (updated == 0) {
            throw new LeaseLostException("lease lost before retry scheduling: " + jobId);
        }
        return true;
    }

    /**
     * Fails jobs whose deadline passed or whose lease expired after the final
     * allowed attempt. Called before each claim so a restarted worker does not
     * leave a message polling forever.
     */
    public void recoverExpiredJobs() {
        List<ExpiredJob> expired = jdbcTemplate.query("""
                select id, message_id, (deadline <= now()) as deadline_expired
                from web_search_jobs
                where job_status in ('QUEUED', 'CLAIMED', 'RUNNING', 'RETRY_WAIT')
                  and (
                        deadline <= now()
                        or (job_status in ('CLAIMED', 'RUNNING')
                            and lease_expires_at < now()
                            and attempt_count >= ?)
                  )
                """,
                (resultSet, rowNumber) -> new ExpiredJob(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getBoolean("deadline_expired")),
                properties.maxAttempts());
        for (ExpiredJob job : expired) {
            transactions.executeWithoutResult(status -> recoverOne(job));
        }
    }

    public Optional<JobRow> findJob(UUID jobId) {
        return jdbcTemplate.query("""
                select id, message_id, job_status, stage, attempt_count, deadline, lease_token
                from web_search_jobs
                where id = ?
                """, JOB_MAPPER, jobId).stream().findFirst();
    }

    private void recoverOne(ExpiredJob job) {
        WebSearchErrorCode code = job.deadlineExpired()
                ? WebSearchErrorCode.JOB_DEADLINE_EXCEEDED
                : WebSearchErrorCode.LEASE_EXPIRED;
        int updated = jdbcTemplate.update("""
                update web_search_jobs
                set job_status = 'FAILED',
                    stage = 'FINALIZE',
                    error_code = ?,
                    lease_token = null,
                    lease_owner = null,
                    lease_expires_at = null,
                    heartbeat_at = null,
                    next_attempt_at = null,
                    updated_at = now()
                where id = ?
                  and job_status in ('QUEUED', 'CLAIMED', 'RUNNING', 'RETRY_WAIT')
                """, code.name(), job.id());
        if (updated > 0) {
            transitionIfActive(job.messageId(), MessageStatus.WEB_FAILED);
        }
    }

    private void fence(UUID jobId, UUID leaseToken,
                       WebSearchJobStatus target, String errorCode) {
        int updated = jdbcTemplate.update("""
                update web_search_jobs
                set job_status = ?,
                    stage = 'FINALIZE',
                    error_code = ?,
                    lease_token = null,
                    lease_owner = null,
                    lease_expires_at = null,
                    heartbeat_at = null,
                    next_attempt_at = null,
                    updated_at = now()
                where id = ? and lease_token = ?
                  and job_status in ('CLAIMED', 'RUNNING')
                """, target.name(), errorCode, jobId, leaseToken);
        if (updated == 0) {
            throw new LeaseLostException("stale lease token cannot finalize job " + jobId);
        }
    }

    private void transitionIfActive(UUID messageId, MessageStatus target) {
        ChatMessage message = messageRepository.findById(messageId).orElse(null);
        if (message == null) {
            return;
        }
        MessageStatus current = message.getMessageStatus();
        if (current != MessageStatus.WEB_QUEUED && current != MessageStatus.WEB_SEARCHING) {
            return;
        }
        SessionOwner owner = message.getSession().owner();
        if (target == MessageStatus.NO_EVIDENCE && current == MessageStatus.WEB_QUEUED) {
            messages.transition(messageId, owner, MessageStatus.WEB_SEARCHING);
        }
        messages.transition(messageId, owner, target);
    }

    private UUID requireMessageId(UUID jobId) {
        List<UUID> ids = jdbcTemplate.queryForList(
                "select message_id from web_search_jobs where id = ?", UUID.class, jobId);
        if (ids.isEmpty()) {
            throw new LeaseLostException("job not found: " + jobId);
        }
        return ids.get(0);
    }

    private void acquireClaimLock() {
        jdbcTemplate.execute("select pg_advisory_xact_lock(" + CLAIM_LOCK + ")");
    }

    private record ExpiredJob(UUID id, UUID messageId, boolean deadlineExpired) {
    }
}
