package vn.edu.hsf302.lawrag.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.chat.exception.ChatAccessDeniedException;
import vn.edu.hsf302.lawrag.chat.repository.ChatMessageRepository;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.repository.ChatSessionRepository;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.entity.SessionOwnerType;

/** Creates and authorizes chat sessions. The owner is checked on every lookup. */
@Service
public class ChatSessionService {

    public static final Duration SESSION_TTL = Duration.ofDays(90);

    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final Clock clock;

    public ChatSessionService(
            ChatSessionRepository sessions,
            ChatMessageRepository messages,
            Clock clock) {
        this.sessions = sessions;
        this.messages = messages;
        this.clock = clock;
    }

    @Transactional
    public ChatSession createSession(SessionOwner owner) {
        Instant now = clock.instant();
        return sessions.save(new ChatSession(owner, now, now.plus(SESSION_TTL)));
    }

    /**
     * Reuses the latest active session when it has no messages yet, avoiding
     * duplicate empty sessions on repeated "new conversation" clicks.
     */
    @Transactional
    public ChatSession createOrReuseEmptySession(SessionOwner owner) {
        Optional<ChatSession> latest = findActiveSession(owner);
        if (latest.isPresent() && messages.countBySessionId(latest.get().getId()) == 0) {
            ChatSession existing = latest.get();
            existing.touch(clock.instant());
            return sessions.save(existing);
        }
        return createSession(owner);
    }

    /** Most recently used non-expired session for this owner, if any. */
    @Transactional(readOnly = true)
    public Optional<ChatSession> findActiveSession(SessionOwner owner) {
        Instant now = clock.instant();
        return owner.type() == SessionOwnerType.ANON
                ? sessions.findFirstByAnonIdAndExpiresAtAfterOrderByLastActivityAtDesc(owner.ownerId(), now)
                : sessions.findFirstByOwnerUserIdAndExpiresAtAfterOrderByLastActivityAtDesc(owner.ownerId(), now);
    }

    @Transactional(readOnly = true)
    public List<ChatSession> listActiveSessions(SessionOwner owner) {
        Instant now = clock.instant();
        return owner.type() == SessionOwnerType.ANON
                ? sessions.findTop20ByAnonIdAndExpiresAtAfterOrderByLastActivityAtDesc(owner.ownerId(), now)
                : sessions.findTop20ByOwnerUserIdAndExpiresAtAfterOrderByLastActivityAtDesc(owner.ownerId(), now);
    }

    @Transactional
    public void closeSession(UUID sessionId, SessionOwner owner) {
        ChatSession session = requireOwnedSession(sessionId, owner);
        session.expire(clock.instant());
        sessions.save(session);
    }

    /**
     * Resolve a session by ID and verify the caller owns it. Missing, expired
     * and foreign sessions all raise the same access-denied error.
     */
    @Transactional(readOnly = true)
    public ChatSession requireOwnedSession(UUID sessionId, SessionOwner owner) {
        ChatSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new ChatAccessDeniedException("Session is not accessible"));
        if (!session.isOwnedBy(owner) || session.isExpired(clock.instant())) {
            throw new ChatAccessDeniedException("Session is not accessible");
        }
        return session;
    }
}
