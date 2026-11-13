package vn.edu.hsf302.lawrag.websearch.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJob;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStatus;

public interface WebSearchJobRepository extends JpaRepository<WebSearchJob, UUID> {

    boolean existsByMessageIdAndJobStatusIn(UUID messageId, Collection<WebSearchJobStatus> statuses);

    List<WebSearchJob> findByMessageIdOrderByCreatedAtAsc(UUID messageId);

    long countByMessageId(UUID messageId);

    long countByMessageSessionAnonIdAndCreatedAtGreaterThanEqual(String anonId, Instant since);

    long countByMessageSessionOwnerUserIdAndCreatedAtGreaterThanEqual(String ownerUserId, Instant since);
}
