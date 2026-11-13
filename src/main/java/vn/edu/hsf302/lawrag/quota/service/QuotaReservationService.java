package vn.edu.hsf302.lawrag.quota.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.chat.repository.ChatMessageRepository;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.entity.SessionOwnerType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.quota.entity.QuotaBucket;
import vn.edu.hsf302.lawrag.quota.repository.QuotaBucketRepository;
import vn.edu.hsf302.lawrag.quota.entity.QuotaLedger;
import vn.edu.hsf302.lawrag.quota.repository.QuotaLedgerRepository;
import vn.edu.hsf302.lawrag.quota.entity.QuotaReservation;
import vn.edu.hsf302.lawrag.quota.repository.QuotaReservationRepository;
import vn.edu.hsf302.lawrag.quota.entity.QuotaReservationStatus;
import vn.edu.hsf302.lawrag.quota.dto.QuotaSnapshot;
import vn.edu.hsf302.lawrag.quota.entity.QuotaSubjectType;

/**
 * Atomic quota accounting.
 *
 * <p>Every operation is a short local transaction. Bucket rows are locked with
 * SELECT ... FOR UPDATE before capacity is changed, so competing requests
 * serialize and can never push {@code reserved_count} below zero or
 * {@code used_count + reserved_count} above {@code limit_count}.
 *
 * <p>Only a persisted valid answer may commit a reservation. Every other
 * terminal/consent state releases it. The ledger row is unique per answer
 * message, making double charging impossible even if a worker retries.
 */
@Service
public class QuotaReservationService {

    private static final Logger log = LoggerFactory.getLogger(QuotaReservationService.class);
    public static final int DAILY_LIMIT = 5;
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbcTemplate;
    private final QuotaBucketRepository buckets;
    private final QuotaReservationRepository reservations;
    private final QuotaLedgerRepository ledger;
    private final ChatMessageRepository messages;
    private final Clock clock;

    public QuotaReservationService(JdbcTemplate jdbcTemplate,
                                   QuotaBucketRepository buckets,
                                   QuotaReservationRepository reservations,
                                   QuotaLedgerRepository ledger,
                                   ChatMessageRepository messages,
                                   Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.buckets = buckets;
        this.reservations = reservations;
        this.ledger = ledger;
        this.messages = messages;
        this.clock = clock;
    }

    public LocalDate currentPeriod() {
        return LocalDate.now(clock);
    }

    /** Read-only quota badge data; no row is created or locked. */
    @Transactional(readOnly = true)
    public QuotaSnapshot snapshot(SessionOwner owner) {
        LocalDate period = currentPeriod();
        Optional<QuotaBucket> bucket = owner.type() == SessionOwnerType.ANON
                ? buckets.findByAnonIdAndQuotaPeriod(owner.ownerId(), period)
                : buckets.findByOwnerUserIdAndQuotaPeriod(owner.ownerId(), period);
        return bucket
                .map(row -> new QuotaSnapshot(
                        row.getUsedCount(), row.getReservedCount(), row.getLimitCount(), period))
                .orElseGet(() -> new QuotaSnapshot(0, 0, DAILY_LIMIT, period));
    }

    /**
     * Reserve one slot for a message/processing attempt. Repeating the same
     * (message, idempotency key) returns the existing ACTIVE reservation.
     */
    @Transactional
    public QuotaReservation reserve(SessionOwner owner, UUID messageId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("reservation idempotency key must be 1..128 characters");
        }
        LocalDate period = currentPeriod();
        ensureBucket(owner, period);

        QuotaBucket bucket = lockBucket(owner, period);
        Optional<QuotaReservation> existing =
                reservations.findByMessageIdAndIdempotencyKey(messageId, idempotencyKey);
        if (existing.isPresent()) {
            QuotaReservation reservation = existing.get();
            if (reservation.getStatus() == QuotaReservationStatus.ACTIVE) {
                return reservation;
            }
            throw new QuotaReservationException(
                    "Reservation key was already settled with status " + reservation.getStatus());
        }

        if (bucket.getUsedCount() + bucket.getReservedCount() >= bucket.getLimitCount()) {
            throw new QuotaExceededException(period, bucket.getLimitCount());
        }

        QuotaReservation reservation = new QuotaReservation(
                bucket,
                messages.getReferenceById(messageId),
                idempotencyKey,
                Instant.now().plus(RESERVATION_TTL));
        bucket.setReservedCount(bucket.getReservedCount() + 1);
        return reservations.save(reservation);
    }

    /**
     * Release an ACTIVE reservation without consuming a turn.
     *
     * @return true when a reservation was settled, false when none was active
     */
    @Transactional
    public boolean release(UUID messageId, String reason) {
        Optional<QuotaReservation> active = lockActiveReservation(messageId);
        if (active.isEmpty()) {
            return false;
        }
        QuotaReservation reservation = active.get();
        QuotaBucket bucket = lockBucketById(reservation.getBucket().getId());
        if (bucket.getReservedCount() <= 0) {
            throw new QuotaReservationException("Bucket reserved_count is already zero for " + bucket.getId());
        }
        bucket.setReservedCount(bucket.getReservedCount() - 1);
        reservation.setStatus(QuotaReservationStatus.RELEASED);
        reservation.setReleasedAt(clock.instant());
        log.debug("Released quota reservation {} for message {} ({})",
                reservation.getId(), messageId, reason);
        return true;
    }

    /**
     * Convert an ACTIVE reservation into one consumed turn and append exactly
     * one ledger row. Returns false when the answer was already charged.
     */
    @Transactional
    public boolean commit(UUID answerMessageId) {
        if (ledger.existsByAnswerMessageId(answerMessageId)) {
            return false;
        }
        Optional<QuotaReservation> active = lockActiveReservation(answerMessageId);
        if (active.isEmpty()) {
            if (ledger.existsByAnswerMessageId(answerMessageId)) {
                return false;
            }
            // A recovery task may already have expired the hold. Fail loudly so
            // the caller's transaction (including COMPLETED) rolls back instead
            // of publishing a free answer.
            Optional<QuotaReservation> latest = reservations
                    .findFirstByMessageIdOrderByCreatedAtDesc(answerMessageId);
            if (latest.isPresent()) {
                throw new QuotaReservationException("Reservation " + latest.get().getId()
                        + " is " + latest.get().getStatus()
                        + "; a stale worker cannot commit quota without re-acquiring it");
            }
            throw new QuotaReservationException("No ACTIVE reservation for answer message " + answerMessageId);
        }
        QuotaReservation reservation = active.get();
        QuotaBucket bucket = lockBucketById(reservation.getBucket().getId());
        if (reservation.getStatus() != QuotaReservationStatus.ACTIVE) {
            return false;
        }
        if (!reservation.getExpiresAt().isAfter(Instant.now())) {
            throw new QuotaReservationException("Reservation " + reservation.getId()
                    + " expired at " + reservation.getExpiresAt()
                    + "; a stale worker cannot commit quota without re-acquiring it");
        }
        if (bucket.getReservedCount() <= 0) {
            throw new QuotaReservationException("Bucket reserved_count is already zero for " + bucket.getId());
        }
        if (bucket.getUsedCount() >= bucket.getLimitCount()) {
            throw new QuotaReservationException("Bucket used_count would exceed limit for " + bucket.getId());
        }

        bucket.setReservedCount(bucket.getReservedCount() - 1);
        bucket.setUsedCount(bucket.getUsedCount() + 1);
        reservation.setStatus(QuotaReservationStatus.COMMITTED);
        reservation.setCommittedAt(clock.instant());
        ledger.save(new QuotaLedger(
                bucket, reservation, reservation.getMessage(), 1, bucket.getQuotaPeriod()));
        return true;
    }

    private QuotaBucket lockBucket(SessionOwner owner, LocalDate period) {
        Optional<QuotaBucket> bucket = owner.type() == SessionOwnerType.ANON
                ? buckets.findForUpdateByAnonIdAndQuotaPeriod(owner.ownerId(), period)
                : buckets.findForUpdateByOwnerUserIdAndQuotaPeriod(owner.ownerId(), period);
        return bucket.orElseThrow(() -> new QuotaReservationException("Quota bucket is missing"));
    }

    private QuotaBucket lockBucketById(UUID id) {
        return buckets.findForUpdateById(id)
                .orElseThrow(() -> new QuotaReservationException("Quota bucket not found: " + id));
    }

    private Optional<QuotaReservation> lockActiveReservation(UUID messageId) {
        return reservations.findFirstByMessageIdAndStatusOrderByCreatedAtDesc(
                messageId, QuotaReservationStatus.ACTIVE);
    }

    /**
     * Concurrent first requests race to create the bucket. The partial unique
     * indexes make ON CONFLICT DO NOTHING safe; one row then gets locked.
     */
    private void ensureBucket(SessionOwner owner, LocalDate period) {
        boolean anonymous = owner.type() == SessionOwnerType.ANON;
        jdbcTemplate.update("""
                insert into quota_buckets
                    (subject_type, anon_id, owner_user_id, quota_period, limit_count, used_count, reserved_count)
                values (?, ?, ?, ?, ?, 0, 0)
                on conflict do nothing
                """,
                anonymous ? QuotaSubjectType.ANON.name() : QuotaSubjectType.USER.name(),
                anonymous ? owner.ownerId() : null,
                anonymous ? null : owner.ownerId(),
                period,
                DAILY_LIMIT);
    }
}
