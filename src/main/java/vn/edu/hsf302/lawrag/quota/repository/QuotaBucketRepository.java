package vn.edu.hsf302.lawrag.quota.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.quota.entity.QuotaBucket;

public interface QuotaBucketRepository extends JpaRepository<QuotaBucket, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from QuotaBucket b where b.anonId = :anonId and b.quotaPeriod = :period")
    Optional<QuotaBucket> findForUpdateByAnonIdAndQuotaPeriod(
            @Param("anonId") String anonId, @Param("period") LocalDate period);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from QuotaBucket b where b.ownerUserId = :ownerUserId and b.quotaPeriod = :period")
    Optional<QuotaBucket> findForUpdateByOwnerUserIdAndQuotaPeriod(
            @Param("ownerUserId") String ownerUserId, @Param("period") LocalDate period);

    Optional<QuotaBucket> findByAnonIdAndQuotaPeriod(String anonId, LocalDate period);

    Optional<QuotaBucket> findByOwnerUserIdAndQuotaPeriod(String ownerUserId, LocalDate period);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from QuotaBucket b where b.id = :id")
    Optional<QuotaBucket> findForUpdateById(@Param("id") UUID id);
}
