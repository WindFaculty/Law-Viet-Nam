package vn.edu.hsf302.lawrag.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.entity.Sender;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    Optional<ChatMessage> findBySessionIdAndIdempotencyKey(UUID sessionId, String idempotencyKey);

    Optional<ChatMessage> findFirstByInReplyToIdAndSender(UUID inReplyToId, Sender sender);

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Conversation order for rendering. The USER question and its AI answer can
     * share the same transaction timestamp, so sender is the tie-breaker.
     */
    @Query("""
            select m from ChatMessage m
            where m.session.id = :sessionId
            order by m.createdAt asc, m.sender desc
            """)
    List<ChatMessage> findConversation(@Param("sessionId") UUID sessionId);

    long countBySessionId(UUID sessionId);

    @Query("""
            select m.session.id, count(m.id) from ChatMessage m
            where m.session.id in :sessionIds
            group by m.session.id
            """)
    List<Object[]> countBySessionIds(@Param("sessionIds") List<UUID> sessionIds);

    @Query("""
            select m from ChatMessage m
            where m.session.id in :sessionIds
              and m.sender = vn.edu.hsf302.lawrag.chat.entity.Sender.USER
            order by m.createdAt asc
            """)
    List<ChatMessage> findUserMessagesForSessions(@Param("sessionIds") List<UUID> sessionIds);
}
