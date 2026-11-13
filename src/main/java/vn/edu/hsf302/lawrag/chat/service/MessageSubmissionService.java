package vn.edu.hsf302.lawrag.chat.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationService;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.repository.ChatMessageRepository;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.entity.Sender;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.dto.SubmittedQuestion;

/**
 * Submits a question and its AI placeholder exactly once per
 * (session, idempotency key). A replay returns the persisted USER/AI pair and
 * performs no second quota reservation.
 *
 * <p>The method owns the transaction boundary and keeps it local: no external
 * API or network call happens while the transaction is open.
 */
@Service
public class MessageSubmissionService {

    private static final int MAX_QUESTION_LENGTH = 4_000;

    private final TransactionTemplate transactions;
    private final ChatSessionService sessions;
    private final ChatMessageRepository messages;
    private final QuotaReservationService quota;
    private final Clock clock;

    public MessageSubmissionService(PlatformTransactionManager transactionManager,
                                    ChatSessionService sessions,
                                    ChatMessageRepository messages,
                                    QuotaReservationService quota,
                                    Clock clock) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.sessions = sessions;
        this.messages = messages;
        this.quota = quota;
        this.clock = clock;
    }

    public SubmittedQuestion submitQuestion(UUID sessionId, SessionOwner owner,
                                            String idempotencyKey, String question) {
        String normalizedQuestion = normalizeQuestion(question);
        String key = normalizeKey(idempotencyKey);
        LocalDate applicableDate = LocalDate.now(clock);
        String questionHash = QuestionHash.of(normalizedQuestion);

        try {
            return transactions.execute(status -> createOrReplay(
                    sessionId, owner, key, normalizedQuestion, applicableDate, questionHash));
        } catch (DataIntegrityViolationException duplicate) {
            // Two identical requests raced past the initial lookup; the unique
            // (session_id, idempotency_key) row wins and this request replays it.
            Optional<SubmittedQuestion> existing = transactions.execute(
                    status -> findExisting(sessionId, owner, key));
            if (existing.isPresent()) {
                return existing.get();
            }
            throw duplicate;
        }
    }

    private SubmittedQuestion createOrReplay(UUID sessionId, SessionOwner owner, String key,
                                             String question, LocalDate applicableDate,
                                             String questionHash) {
        ChatSession session = sessions.requireOwnedSession(sessionId, owner);

        Optional<SubmittedQuestion> existing = findExisting(session, key);
        if (existing.isPresent()) {
            return existing.get();
        }

        ChatMessage userMessage = messages.save(
                ChatMessage.userQuestion(session, key, question, applicableDate, questionHash));
        ChatMessage aiMessage = messages.saveAndFlush(
                ChatMessage.aiProcessing(session, userMessage, applicableDate, questionHash));

        // The reservation is part of the same short transaction: if quota is
        // exhausted, both message rows are rolled back.
        quota.reserve(owner, aiMessage.getId(), key);
        session.touch(clock.instant());
        return new SubmittedQuestion(userMessage, aiMessage, false);
    }

    private Optional<SubmittedQuestion> findExisting(UUID sessionId, SessionOwner owner, String key) {
        return findExisting(sessions.requireOwnedSession(sessionId, owner), key);
    }

    private Optional<SubmittedQuestion> findExisting(ChatSession session, String key) {
        return messages.findBySessionIdAndIdempotencyKey(session.getId(), key)
                .map(userMessage -> {
                    ChatMessage aiMessage = messages
                            .findFirstByInReplyToIdAndSender(userMessage.getId(), Sender.AI)
                            .orElseThrow(() -> new IllegalStateException(
                                    "USER message " + userMessage.getId() + " has no AI reply"));
                    return new SubmittedQuestion(userMessage, aiMessage, true);
                });
    }

    private static String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        String normalized = question.strip();
        if (normalized.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question is too long");
        }
        return normalized;
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String key = idempotencyKey.strip();
        if (key.length() > 128) {
            throw new IllegalArgumentException("idempotency key is too long");
        }
        return key;
    }
}
