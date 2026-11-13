package vn.edu.hsf302.lawrag.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Optional<ChatSession> findFirstByAnonIdAndExpiresAtAfterOrderByLastActivityAtDesc(
            String anonId, Instant now);

    Optional<ChatSession> findFirstByOwnerUserIdAndExpiresAtAfterOrderByLastActivityAtDesc(
            String ownerUserId, Instant now);

    List<ChatSession> findTop20ByAnonIdAndExpiresAtAfterOrderByLastActivityAtDesc(
            String anonId, Instant now);

    List<ChatSession> findTop20ByOwnerUserIdAndExpiresAtAfterOrderByLastActivityAtDesc(
            String ownerUserId, Instant now);
}
