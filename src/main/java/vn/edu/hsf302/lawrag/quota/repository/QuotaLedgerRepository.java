package vn.edu.hsf302.lawrag.quota.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.quota.entity.QuotaLedger;

public interface QuotaLedgerRepository extends JpaRepository<QuotaLedger, UUID> {

    boolean existsByAnswerMessageId(UUID answerMessageId);

    Optional<QuotaLedger> findByAnswerMessageId(UUID answerMessageId);
}
