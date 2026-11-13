package vn.edu.hsf302.lawrag.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.edu.hsf302.lawrag.websearch.repository.WebSearchJobRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.dto.ChatMessageView;
import vn.edu.hsf302.lawrag.chat.exception.ConsentExpiredException;
import vn.edu.hsf302.lawrag.chat.exception.InvalidMessageTransitionException;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.exception.MessageTransitionConflictException;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.entity.SessionOwnerType;
import vn.edu.hsf302.lawrag.chat.exception.WebSearchNotAllowedException;
import vn.edu.hsf302.lawrag.chat.exception.WebSearchRetryNotAllowedException;

/**
 * Consent policy above {@link MessageStateService}: replays, expiry reflection,
 * feature flags and retry eligibility. Side effects stay in the short atomic
 * transitions of the state service.
 */
@Service
public class ChatConsentService {

    private final MessageStateService messages;
    private final ChatViewService views;
    private final WebSearchJobRepository jobs;
    private final Clock clock;
    private final boolean allowAnonymousWebSearch;

    public ChatConsentService(MessageStateService messages,
                              ChatViewService views,
                              WebSearchJobRepository jobs,
                              Clock clock,
                              @Value("${app.websearch.allow-anonymous:true}") boolean allowAnonymousWebSearch) {
        this.messages = messages;
        this.views = views;
        this.jobs = jobs;
        this.clock = clock;
        this.allowAnonymousWebSearch = allowAnonymousWebSearch;
    }

    public ChatMessageView confirmWebSearch(UUID messageId, SessionOwner owner, String questionHash) {
        ChatMessage message = messages.getOwnedMessage(messageId, owner);
        MessageStatus status = message.getMessageStatus();
        if (isReplayable(status)) {
            return views.view(messageId, owner);
        }
        if (status != MessageStatus.AWAITING_WEB_CONFIRM) {
            throw new InvalidMessageTransitionException(messageId, status, MessageStatus.WEB_QUEUED);
        }
        requireWebSearchAllowed(owner);
        if (message.getQuestionHash() == null
                || !message.getQuestionHash().equals(questionHash)) {
            throw new InvalidMessageTransitionException(
                    messageId, status, MessageStatus.WEB_QUEUED);
        }
        if (!consentActive(message)) {
            expireQuietly(messageId, owner);
            return views.view(messageId, owner);
        }

        try {
            messages.confirmWebSearch(messageId, owner);
        } catch (ConsentExpiredException expired) {
            expireQuietly(messageId, owner);
        } catch (MessageTransitionConflictException conflict) {
            ChatMessage current = messages.getOwnedMessage(messageId, owner);
            if (!isReplayable(current.getMessageStatus())) {
                throw conflict;
            }
        }
        return views.view(messageId, owner);
    }

    public ChatMessageView cancelWebSearch(UUID messageId, SessionOwner owner) {
        ChatMessage message = messages.getOwnedMessage(messageId, owner);
        MessageStatus status = message.getMessageStatus();
        if (status == MessageStatus.CANCELLED) {
            return views.view(messageId, owner);
        }
        if (status != MessageStatus.AWAITING_WEB_CONFIRM) {
            throw new InvalidMessageTransitionException(messageId, status, MessageStatus.CANCELLED);
        }
        try {
            messages.cancelWebSearch(messageId, owner);
        } catch (MessageTransitionConflictException conflict) {
            ChatMessage current = messages.getOwnedMessage(messageId, owner);
            if (current.getMessageStatus() != MessageStatus.CANCELLED) {
                throw conflict;
            }
        }
        return views.view(messageId, owner);
    }

    /**
     * Polling read. The only permitted mutation is reflecting an elapsed consent
     * window as EXPIRED; quota and jobs are never changed here.
     */
    public ChatMessageView fragment(UUID messageId, SessionOwner owner) {
        ChatMessage message = messages.getOwnedMessage(messageId, owner);
        if (message.getMessageStatus() == MessageStatus.AWAITING_WEB_CONFIRM
                && !consentActive(message)) {
            expireQuietly(messageId, owner);
        }
        return views.view(messageId, owner);
    }

    public ChatMessageView retryWebSearch(UUID messageId, SessionOwner owner) {
        ChatMessage message = messages.getOwnedMessage(messageId, owner);
        MessageStatus status = message.getMessageStatus();
        if (isReplayable(status)) {
            return views.view(messageId, owner);
        }
        if (status != MessageStatus.WEB_FAILED) {
            throw new InvalidMessageTransitionException(messageId, status, MessageStatus.WEB_QUEUED);
        }
        requireWebSearchAllowed(owner);
        if (!consentActive(message)) {
            throw new WebSearchRetryNotAllowedException(
                    "Hạn xác nhận 15 phút đã hết. Vui lòng gửi câu hỏi mới để tìm nguồn bổ sung.");
        }
        if (jobs.countByMessageId(messageId) >= MessageStateService.MAX_WEB_JOBS_PER_MESSAGE) {
            throw new WebSearchRetryNotAllowedException(
                    "Đã đạt giới hạn 2 lần thử cho câu hỏi này. Vui lòng gửi câu hỏi mới.");
        }

        try {
            messages.transition(messageId, owner, MessageStatus.WEB_QUEUED);
        } catch (ConsentExpiredException expired) {
            throw new WebSearchRetryNotAllowedException(
                    "Hạn xác nhận 15 phút đã hết. Vui lòng gửi câu hỏi mới để tìm nguồn bổ sung.");
        } catch (MessageTransitionConflictException conflict) {
            ChatMessage current = messages.getOwnedMessage(messageId, owner);
            if (!isReplayable(current.getMessageStatus())) {
                throw conflict;
            }
        }
        return views.view(messageId, owner);
    }

    private void expireQuietly(UUID messageId, SessionOwner owner) {
        try {
            messages.expireWebSearch(messageId, owner);
        } catch (MessageTransitionConflictException | InvalidMessageTransitionException ignored) {
            // A concurrent confirm/cancel already decided the state.
        }
    }

    private void requireWebSearchAllowed(SessionOwner owner) {
        if (owner.type() == SessionOwnerType.ANON && !allowAnonymousWebSearch) {
            throw new WebSearchNotAllowedException();
        }
    }

    private boolean consentActive(ChatMessage message) {
        Instant expiresAt = message.getConfirmExpiresAt();
        return expiresAt != null && expiresAt.isAfter(clock.instant());
    }

    private static boolean isReplayable(MessageStatus status) {
        return status == MessageStatus.WEB_QUEUED
                || status == MessageStatus.WEB_SEARCHING
                || status == MessageStatus.COMPLETED;
    }
}
