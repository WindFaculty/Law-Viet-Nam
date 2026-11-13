package vn.edu.hsf302.lawrag.quota.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hsf302.lawrag.websearch.config.WebSearchProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.quota.entity.QuotaReservationStatus;

/**
 * Reclaims expired quota holds without stealing them from a live web job.
 *
 * <p>An expired hold whose message still has a job that can continue (queued,
 * leased, or a crashed lease with attempts left) is extended. Otherwise the
 * hold becomes EXPIRED, the bucket is decremented once (never below zero) and a
 * stuck message becomes FAILED/WEB_FAILED. The message row is updated with a
 * version bump, so a stale worker's optimistic UPDATE loses instead of
 * publishing COMPLETED.
 */
@Service
public class QuotaRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(QuotaRecoveryService.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final WebSearchProperties webSearchProperties;

    public QuotaRecoveryService(JdbcTemplate jdbcTemplate,
                                PlatformTransactionManager transactionManager,
                                WebSearchProperties webSearchProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.webSearchProperties = webSearchProperties;
    }

    @Scheduled(fixedDelayString = "${app.quota.recovery-interval-ms:30000}",
            initialDelayString = "${app.quota.recovery-interval-ms:30000}")
    public void scheduledRecovery() {
        try {
            int changed = recoverExpiredReservations();
            if (changed > 0) {
                log.info("Quota recovery settled {} expired reservation(s)", changed);
            }
        } catch (RuntimeException exception) {
            log.error("Quota recovery task failed", exception);
        }
    }

    /** @return number of reservations extended or expired by this pass */
    public int recoverExpiredReservations() {
        List<UUID> ids = jdbcTemplate.queryForList("""
                select id from quota_reservations
                where status = 'ACTIVE' and expires_at < now()
                order by expires_at
                limit ?
                """, UUID.class, BATCH_SIZE);
        int changed = 0;
        for (UUID id : ids) {
            try {
                Boolean settled = transactions.execute(status -> recoverOne(id));
                if (Boolean.TRUE.equals(settled)) {
                    changed++;
                }
            } catch (RuntimeException exception) {
                // One inconsistent row must not stop the rest of the batch.
                log.warn("Cannot recover quota reservation {}: {}", id, exception.getMessage());
            }
        }
        return changed;
    }

    private boolean recoverOne(UUID reservationId) {
        UUID messageId = jdbcTemplate.queryForList(
                "select message_id from quota_reservations where id = ?",
                UUID.class, reservationId).stream().findFirst().orElse(null);
        if (messageId == null) {
            return false;
        }

        // Lock the message first, then the reservation and bucket, matching
        // MessageStateService's order (message -> reservation -> bucket).
        MessageRow message = jdbcTemplate.query("""
                        select id, message_status
                        from chat_messages
                        where id = ?
                        for update
                        """,
                (resultSet, rowNumber) -> new MessageRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("message_status")),
                messageId).stream().findFirst().orElse(null);

        ReservationRow reservation = jdbcTemplate.query("""
                        select id, bucket_id, message_id, status, expires_at
                        from quota_reservations
                        where id = ?
                        for update
                        """,
                (resultSet, rowNumber) -> new ReservationRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("bucket_id", UUID.class),
                        resultSet.getObject("message_id", UUID.class),
                        QuotaReservationStatus.valueOf(resultSet.getString("status")),
                        toInstant(resultSet.getTimestamp("expires_at"))),
                reservationId).stream().findFirst().orElse(null);
        if (reservation == null
                || reservation.status() != QuotaReservationStatus.ACTIVE
                || reservation.expiresAt().isAfter(Instant.now())) {
            return false;
        }

        if (hasContinuableJob(reservation.messageId())) {
            jdbcTemplate.update("""
                    update quota_reservations
                    set expires_at = now() + make_interval(secs => ?), updated_at = now()
                    where id = ? and status = 'ACTIVE'
                    """, (int) QuotaReservationService.RESERVATION_TTL.toSeconds(), reservationId);
            log.debug("Extended reservation {} because message {} still has a live web job",
                    reservationId, reservation.messageId());
            return true;
        }

        int expired = jdbcTemplate.update("""
                update quota_reservations
                set status = 'EXPIRED', released_at = now(), updated_at = now()
                where id = ? and status = 'ACTIVE'
                """, reservationId);
        if (expired == 0) {
            return false;
        }
        int decremented = jdbcTemplate.update("""
                update quota_buckets
                set reserved_count = reserved_count - 1, updated_at = now()
                where id = ? and reserved_count > 0
                """, reservation.bucketId());
        if (decremented == 0) {
            log.warn("Reservation {} expired but its bucket {} reserved_count was already 0",
                    reservationId, reservation.bucketId());
        }
        if (message != null) {
            settleStuckMessage(message);
        }
        log.info("Expired quota reservation {} for message {} (bucket {})",
                reservationId, reservation.messageId(), reservation.bucketId());
        return true;
    }

    private boolean hasContinuableJob(UUID messageId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from web_search_jobs j
                    where j.message_id = ?
                      and j.job_status in ('QUEUED', 'CLAIMED', 'RUNNING', 'RETRY_WAIT')
                      and j.deadline > now()
                      and (
                            j.job_status in ('QUEUED', 'RETRY_WAIT')
                            or (j.job_status in ('CLAIMED', 'RUNNING')
                                and (j.lease_expires_at > now() or j.attempt_count < ?))
                      )
                )
                """, Boolean.class, messageId, webSearchProperties.maxAttempts());
        return Boolean.TRUE.equals(exists);
    }

    private void settleStuckMessage(MessageRow message) {
        int updated = jdbcTemplate.update("""
                update chat_messages
                set message_status = case
                        when message_status = 'PROCESSING_LOCAL' then 'EXPIRED'
                        else 'WEB_FAILED'
                    end,
                    outcome = case
                        when message_status = 'PROCESSING_LOCAL' then 'EXPIRED'
                        else 'WEB_FAILED'
                    end,
                    updated_at = now(),
                    version = version + 1
                where id = ?
                  and message_status in ('PROCESSING_LOCAL', 'WEB_QUEUED', 'WEB_SEARCHING')
                """, message.id());
        if (updated > 0) {
            log.info("Recovery marked stuck message {} in status {} as failed",
                    message.id(), message.status());
        }
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ReservationRow(UUID id, UUID bucketId, UUID messageId,
                                  QuotaReservationStatus status, Instant expiresAt) {
    }

    private record MessageRow(UUID id, String status) {
    }
}
