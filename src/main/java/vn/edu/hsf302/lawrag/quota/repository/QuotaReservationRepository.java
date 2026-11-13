package vn.edu.hsf302.lawrag.quota.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.quota.entity.QuotaReservation;
import vn.edu.hsf302.lawrag.quota.entity.QuotaReservationStatus;

public interface QuotaReservationRepository extends JpaRepository<QuotaReservation, UUID> {

    Optional<QuotaReservation> findByMessageIdAndIdempotencyKey(UUID messageId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<QuotaReservation> findFirstByMessageIdAndStatusOrderByCreatedAtDesc(
            UUID messageId, QuotaReservationStatus status);

    boolean existsByMessageIdAndStatus(UUID messageId, QuotaReservationStatus status);

    Optional<QuotaReservation> findFirstByMessageIdOrderByCreatedAtDesc(UUID messageId);
}
