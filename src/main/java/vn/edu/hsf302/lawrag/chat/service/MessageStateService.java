package vn.edu.hsf302.lawrag.chat.service;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationService;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJob;
import vn.edu.hsf302.lawrag.websearch.repository.WebSearchJobRepository;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.exception.ChatAccessDeniedException;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.repository.ChatMessageRepository;
import vn.edu.hsf302.lawrag.chat.exception.ConsentExpiredException;
import vn.edu.hsf302.lawrag.chat.exception.InvalidMessageTransitionException;
import vn.edu.hsf302.lawrag.chat.entity.MessageOutcome;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.exception.MessageTransitionConflictException;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.exception.WebSearchJobLimitExceededException;

/**
 * Owns every AI-message state change.
 *
 * <p>Each operation first resolves the message by ID and compares the session
 * owner with the caller. Transitions are validated against
 * {@link MessageStatus#canTransitionTo(MessageStatus)}, then persisted with the
 * optimistic {@code version} column before quota/job side effects. Two
 * conflicting requests therefore cannot both win: the loser throws
 * {@link MessageTransitionConflictException} and its whole transaction
 * (including reservation and job rows) rolls back.
 */
@Service
public class MessageStateService {

    public static final Duration CONSENT_TTL = Duration.ofMinutes(15);
    public static final Duration JOB_DEADLINE = Duration.ofSeconds(120);
    public static final int DAILY_WEB_JOB_LIMIT = 3;
    public static final int MAX_WEB_JOBS_PER_MESSAGE = 2;
    private static final int MAX_SUGGESTED_QUERY_LENGTH = 1_000;
    private static final EnumSet<WebSearchJobStatus> ACTIVE_JOB_STATUSES = EnumSet.of(
            WebSearchJobStatus.QUEUED,
            WebSearchJobStatus.CLAIMED,
            WebSearchJobStatus.RUNNING,
            WebSearchJobStatus.RETRY_WAIT);

    private final ChatMessageRepository messages;
    private final WebSearchJobRepository jobs;
    private final QuotaReservationService quota;
    private final CostBudgetService budget;
    private final Clock clock;

    public MessageStateService(ChatMessageRepository messages,
                               WebSearchJobRepository jobs,
                               QuotaReservationService quota,
                               CostBudgetService budget,
                               Clock clock) {
        this.messages = messages;
        this.jobs = jobs;
        this.quota = quota;
        this.budget = budget;
        this.clock = clock;
    }

    /** Read one message if, and only if, the caller owns its session. */
    @Transactional(readOnly = true)
    public ChatMessage getOwnedMessage(UUID messageId, SessionOwner actor) {
        return loadOwned(messageId, actor);
    }

    /**
     * Local retrieval found insufficient evidence and asks for consent.
     * The processing reservation is released; consent lasts 15 minutes.
     */
    @Transactional
    public ChatMessage markAwaitingWebConfirm(UUID messageId, SessionOwner actor, String suggestedQuery) {
        ChatMessage message = loadOwned(messageId, actor);
        requireStatus(message, MessageStatus.PROCESSING_LOCAL, MessageStatus.AWAITING_WEB_CONFIRM);
        String query = normalizeSuggestedQuery(suggestedQuery);
        message.setSuggestedQuery(query);
        if (message.getQuestionHash() == null) {
            message.setQuestionHash(QuestionHash.of(message.getInReplyTo().getContent()));
        }
        applyTransition(message, MessageStatus.AWAITING_WEB_CONFIRM);
        return message;
    }

    /**
     * Missing decisive facts. The clarification question becomes the AI
     * message content and the processing reservation is released.
     */
    @Transactional
    public ChatMessage requestClarification(UUID messageId, SessionOwner actor, String clarificationQuestion) {
        ChatMessage message = loadOwned(messageId, actor);
        if (message.getMessageStatus() == MessageStatus.NEEDS_CLARIFICATION) {
            return message;
        }
        requireStatus(message, MessageStatus.PROCESSING_LOCAL, MessageStatus.NEEDS_CLARIFICATION);
        message.setContent(normalizeClarification(clarificationQuestion));
        applyTransition(message, MessageStatus.NEEDS_CLARIFICATION);
        return message;
    }

    /**
     * Persist the date resolved by {@link vn.edu.hsf302.lawrag.retrieval.service.QueryAnalyzer}.
     * The USER question and its AI answer share the same applicability anchor.
     */
    @Transactional
    public ChatMessage updateApplicableDate(UUID messageId, SessionOwner actor, LocalDate applicableDate) {
        if (applicableDate == null) {
            throw new IllegalArgumentException("applicable date is required");
        }
        ChatMessage message = loadOwned(messageId, actor);
        boolean changed = !applicableDate.equals(message.getApplicableDate());
        message.setApplicableDate(applicableDate);
        ChatMessage question = message.getInReplyTo();
        if (question != null && !applicableDate.equals(question.getApplicableDate())) {
            question.setApplicableDate(applicableDate);
            changed = true;
        }
        if (changed) {
            persist(message);
        }
        return message;
    }

    /**
     * User agrees to a web search. Repeating the request after the message is
     * already queued/searching is a read-only replay of the existing job.
     */
    @Transactional
    public ChatMessage confirmWebSearch(UUID messageId, SessionOwner actor) {
        ChatMessage message = loadOwned(messageId, actor);
        if (message.getMessageStatus() == MessageStatus.WEB_QUEUED
                || message.getMessageStatus() == MessageStatus.WEB_SEARCHING) {
            return message;
        }
        requireStatus(message, MessageStatus.AWAITING_WEB_CONFIRM, MessageStatus.WEB_QUEUED);
        applyTransition(message, MessageStatus.WEB_QUEUED);
        return message;
    }

    /** User rejects the web search. No quota is consumed. */
    @Transactional
    public ChatMessage cancelWebSearch(UUID messageId, SessionOwner actor) {
        ChatMessage message = loadOwned(messageId, actor);
        if (message.getMessageStatus() == MessageStatus.CANCELLED) {
            return message;
        }
        requireStatus(message, MessageStatus.AWAITING_WEB_CONFIRM, MessageStatus.CANCELLED);
        applyTransition(message, MessageStatus.CANCELLED);
        return message;
    }

    /** Consent deadline elapsed. No job is created and no quota is consumed. */
    @Transactional
    public ChatMessage expireWebSearch(UUID messageId, SessionOwner actor) {
        ChatMessage message = loadOwned(messageId, actor);
        if (message.getMessageStatus() == MessageStatus.EXPIRED) {
            return message;
        }
        requireStatus(message, MessageStatus.AWAITING_WEB_CONFIRM, MessageStatus.EXPIRED);
        applyTransition(message, MessageStatus.EXPIRED);
        return message;
    }

    /**
     * Out-of-domain refusal: the turn ends as COMPLETED with outcome
     * OUT_OF_SCOPE and the hold is released, never charged. No citation and no
     * web job exists for this path.
     */
    @Transactional
    public ChatMessage markOutOfScope(UUID messageId, SessionOwner actor, String content) {
        ChatMessage message = loadOwned(messageId, actor);
        if (message.getMessageStatus() == MessageStatus.COMPLETED) {
            return message;
        }
        requireStatus(message, MessageStatus.PROCESSING_LOCAL, MessageStatus.COMPLETED);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("an out-of-scope reply must have content");
        }
        message.setContent(content.strip());
        message.setMessageStatus(MessageStatus.COMPLETED);
        message.setOutcome(MessageOutcome.OUT_OF_SCOPE);
        persist(message);
        quota.release(messageId, MessageOutcome.OUT_OF_SCOPE.name());
        return message;
    }

    /**
     * Persist a valid answer and settle the reservation exactly once. Calling
     * this again on an already COMPLETED message is a no-op replay.
     */
    @Transactional
    public ChatMessage complete(UUID messageId, SessionOwner actor, String answerContent) {
        ChatMessage message = loadOwned(messageId, actor);
        if (message.getMessageStatus() == MessageStatus.COMPLETED) {
            return message;
        }
        if (answerContent == null || answerContent.isBlank()) {
            throw new IllegalArgumentException("a completed answer must have content");
        }
        if (message.getMessageStatus() != MessageStatus.PROCESSING_LOCAL
                && message.getMessageStatus() != MessageStatus.WEB_SEARCHING) {
            throw new InvalidMessageTransitionException(
                    message.getId(), message.getMessageStatus(), MessageStatus.COMPLETED);
        }
        message.setContent(answerContent.strip());
        applyTransition(message, MessageStatus.COMPLETED);
        return message;
    }

    /**
     * Worker/operations transition entry point. The target must be a legal
     * edge in the state machine; the same ownership and optimistic rules apply.
     */
    @Transactional
    public ChatMessage transition(UUID messageId, SessionOwner actor, MessageStatus target) {
        ChatMessage message = loadOwned(messageId, actor);
        applyTransition(message, target);
        return message;
    }

    private void applyTransition(ChatMessage message, MessageStatus target) {
        MessageStatus current = message.getMessageStatus();
        if (!message.isAi()) {
            throw new InvalidMessageTransitionException(message.getId(), current, target);
        }
        if (!current.canTransitionTo(target)) {
            throw new InvalidMessageTransitionException(message.getId(), current, target);
        }

        Instant now = clock.instant();
        if (target == MessageStatus.WEB_QUEUED) {
            if (message.getConfirmExpiresAt() == null || now.isAfter(message.getConfirmExpiresAt())) {
                throw new ConsentExpiredException(message.getId());
            }
            verifyQuestionHash(message, current, target);
            if (message.getConfirmedAt() == null) {
                message.setConfirmedAt(now);
            }
        }
        if (target == MessageStatus.AWAITING_WEB_CONFIRM) {
            message.setConfirmExpiresAt(now.plus(CONSENT_TTL));
        }

        message.setMessageStatus(target);
        message.setOutcome(outcomeFor(target));

        // Flush the versioned row first: this is the single-winner gate and it
        // fixes lock order (message row, then quota bucket) for all transitions.
        persist(message);

        switch (target) {
            case AWAITING_WEB_CONFIRM,
                 NEEDS_CLARIFICATION,
                 CANCELLED,
                 EXPIRED,
                 NO_EVIDENCE,
                 WEB_FAILED,
                 FAILED -> quota.release(message.getId(), target.name());
            case WEB_QUEUED -> {
                // Budget refusal rolls the whole transaction back: the message
                // stays AWAITING_WEB_CONFIRM, no reservation is charged and no job exists.
                budget.requireWebSearchJob();
                enforceDailyWebJobLimit(message);
                quota.reserve(message.getSession().owner(), message.getId(), nextReservationKey(message));
                createJob(message, now);
            }
            case COMPLETED -> quota.commit(message.getId());
            default -> {
                // WEB_SEARCHING and other in-flight states have no quota side effect.
            }
        }
    }

    private void persist(ChatMessage message) {
        try {
            messages.saveAndFlush(message);
        } catch (OptimisticLockingFailureException | OptimisticLockException conflict) {
            throw new MessageTransitionConflictException(message.getId());
        }
    }

    private void createJob(ChatMessage message, Instant now) {
        if (jobs.existsByMessageIdAndJobStatusIn(message.getId(), ACTIVE_JOB_STATUSES)) {
            throw new MessageTransitionConflictException(message.getId());
        }
        jobs.save(WebSearchJob.queued(message, now.plus(JOB_DEADLINE)));
    }

    /**
     * Daily cap per owner (including failed jobs, per decisions.md). Checked in
     * the same transaction as the transition, reservation and job insert.
     */
    private void enforceDailyWebJobLimit(ChatMessage message) {
        if (message.getSession().getAnonId() == null
                && message.getSession().getOwnerUserId() == null) {
            throw new IllegalStateException("Chat session has no owner");
        }
        Instant startOfDay = LocalDate.now(clock)
                .atStartOfDay(clock.getZone())
                .toInstant();
        long jobsToday = message.getSession().getAnonId() != null
                ? jobs.countByMessageSessionAnonIdAndCreatedAtGreaterThanEqual(
                        message.getSession().getAnonId(), startOfDay)
                : jobs.countByMessageSessionOwnerUserIdAndCreatedAtGreaterThanEqual(
                        message.getSession().getOwnerUserId(), startOfDay);
        if (jobsToday >= DAILY_WEB_JOB_LIMIT) {
            throw new WebSearchJobLimitExceededException(DAILY_WEB_JOB_LIMIT);
        }
    }

    private String nextReservationKey(ChatMessage message) {
        return "web-" + (jobs.countByMessageId(message.getId()) + 1);
    }

    private void verifyQuestionHash(ChatMessage message, MessageStatus from, MessageStatus to) {
        if (message.getQuestionHash() == null
                || message.getInReplyTo() == null
                || !message.getQuestionHash().equals(QuestionHash.of(message.getInReplyTo().getContent()))) {
            throw new InvalidMessageTransitionException(message.getId(), from, to);
        }
    }

    private ChatMessage loadOwned(UUID messageId, SessionOwner actor) {
        ChatMessage message = messages.findById(messageId)
                .orElseThrow(() -> new ChatAccessDeniedException("Message is not accessible"));
        if (actor == null || !message.getSession().isOwnedBy(actor)) {
            throw new ChatAccessDeniedException("Message is not accessible");
        }
        return message;
    }

    private static void requireStatus(ChatMessage message, MessageStatus expected, MessageStatus target) {
        if (message.getMessageStatus() != expected) {
            throw new InvalidMessageTransitionException(message.getId(), message.getMessageStatus(), target);
        }
    }

    private static String normalizeSuggestedQuery(String suggestedQuery) {
        if (suggestedQuery == null || suggestedQuery.isBlank()) {
            throw new IllegalArgumentException("a suggested search query is required");
        }
        String query = suggestedQuery.strip();
        if (query.length() > MAX_SUGGESTED_QUERY_LENGTH) {
            throw new IllegalArgumentException("suggested search query is too long");
        }
        return query;
    }

    private static String normalizeClarification(String clarificationQuestion) {
        if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
            throw new IllegalArgumentException("a clarification question is required");
        }
        String question = clarificationQuestion.strip();
        if (question.length() > MAX_SUGGESTED_QUERY_LENGTH) {
            throw new IllegalArgumentException("clarification question is too long");
        }
        return question;
    }

    private static MessageOutcome outcomeFor(MessageStatus target) {
        return switch (target) {
            case COMPLETED -> MessageOutcome.ANSWERED;
            case AWAITING_WEB_CONFIRM -> MessageOutcome.AWAITING_WEB_CONFIRM;
            case NEEDS_CLARIFICATION -> MessageOutcome.NEEDS_CLARIFICATION;
            case CANCELLED -> MessageOutcome.CANCELLED;
            case EXPIRED -> MessageOutcome.EXPIRED;
            case NO_EVIDENCE -> MessageOutcome.NO_EVIDENCE;
            case WEB_FAILED -> MessageOutcome.WEB_FAILED;
            case FAILED -> MessageOutcome.FAILED;
            case PROCESSING_LOCAL, WEB_QUEUED, WEB_SEARCHING -> null;
        };
    }
}
