package vn.edu.hsf302.lawrag.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.config.TimeConfig;
import vn.edu.hsf302.lawrag.quota.service.QuotaReservationService;
import vn.edu.hsf302.lawrag.quota.dto.QuotaSnapshot;
import vn.edu.hsf302.lawrag.retrieval.service.AnswerRenderer;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJob;
import vn.edu.hsf302.lawrag.websearch.repository.WebSearchJobRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.dto.ChatConversationView;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;
import vn.edu.hsf302.lawrag.chat.dto.ChatMessagePairView;
import vn.edu.hsf302.lawrag.chat.repository.ChatMessageRepository;
import vn.edu.hsf302.lawrag.chat.dto.ChatMessageView;
import vn.edu.hsf302.lawrag.chat.entity.ChatSession;
import vn.edu.hsf302.lawrag.chat.dto.ChatSessionSummaryView;
import vn.edu.hsf302.lawrag.chat.entity.MessageCitation;
import vn.edu.hsf302.lawrag.chat.repository.MessageCitationRepository;
import vn.edu.hsf302.lawrag.chat.entity.MessageStatus;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.entity.SessionOwnerType;

/**
 * Builds detached view models for the Thymeleaf fragments. A message whose
 * consent window has elapsed is rendered as EXPIRED even before the next
 * fragment request persists that transition.
 */
@Service
public class ChatViewService {

    private static final DateTimeFormatter SESSION_TIME =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(TimeConfig.BUSINESS_ZONE);

    private final MessageStateService state;
    private final ChatSessionService sessions;
    private final ChatMessageRepository messages;
    private final MessageCitationRepository citations;
    private final WebSearchJobRepository jobs;
    private final QuotaReservationService quota;
    private final Clock clock;
    private final boolean allowAnonymousWebSearch;

    public ChatViewService(MessageStateService state,
                           ChatSessionService sessions,
                           ChatMessageRepository messages,
                           MessageCitationRepository citations,
                           WebSearchJobRepository jobs,
                           QuotaReservationService quota,
                           Clock clock,
                           @Value("${app.websearch.allow-anonymous:true}") boolean allowAnonymousWebSearch) {
        this.state = state;
        this.sessions = sessions;
        this.messages = messages;
        this.citations = citations;
        this.jobs = jobs;
        this.quota = quota;
        this.clock = clock;
        this.allowAnonymousWebSearch = allowAnonymousWebSearch;
    }

    @Transactional(readOnly = true)
    public ChatMessageView view(UUID messageId, SessionOwner owner) {
        ChatMessage message = state.getOwnedMessage(messageId, owner);
        return toView(message, quota.snapshot(owner), webSearchAllowed(owner));
    }

    @Transactional(readOnly = true)
    public ChatMessagePairView pair(UUID assistantMessageId, SessionOwner owner) {
        ChatMessage assistant = state.getOwnedMessage(assistantMessageId, owner);
        ChatMessage question = assistant.getInReplyTo();
        if (question == null) {
            throw new IllegalStateException(
                    "AI message " + assistantMessageId + " has no question");
        }
        QuotaSnapshot snapshot = quota.snapshot(owner);
        boolean allowed = webSearchAllowed(owner);
        return new ChatMessagePairView(
                toView(question, snapshot, allowed),
                toView(assistant, snapshot, allowed));
    }

    @Transactional(readOnly = true)
    public ChatConversationView conversation(UUID sessionId, SessionOwner owner) {
        sessions.requireOwnedSession(sessionId, owner);
        QuotaSnapshot snapshot = quota.snapshot(owner);
        boolean allowed = webSearchAllowed(owner);
        List<ChatMessageView> views = new ArrayList<>();
        for (ChatMessage message : messages.findConversation(sessionId)) {
            views.add(toView(message, snapshot, allowed));
        }
        return new ChatConversationView(
                sessionId, views, snapshot.usedCount(), snapshot.limitCount(), allowed);
    }

    public boolean webSearchAllowed(SessionOwner owner) {
        return owner.type() != SessionOwnerType.ANON || allowAnonymousWebSearch;
    }

    @Transactional(readOnly = true)
    public List<ChatSessionSummaryView> listRecentSessions(SessionOwner owner, UUID activeSessionId) {
        List<ChatSession> activeSessions = sessions.listActiveSessions(owner);
        if (activeSessions.isEmpty()) {
            return List.of();
        }
        List<UUID> sessionIds = activeSessions.stream().map(ChatSession::getId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : messages.countBySessionIds(sessionIds)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        Map<UUID, String> firstQuestions = new HashMap<>();
        for (ChatMessage msg : messages.findUserMessagesForSessions(sessionIds)) {
            firstQuestions.putIfAbsent(msg.getSession().getId(), summarizeTitle(msg.getContent()));
        }

        List<ChatSessionSummaryView> summaries = new ArrayList<>(activeSessions.size());
        for (ChatSession session : activeSessions) {
            UUID id = session.getId();
            summaries.add(new ChatSessionSummaryView(
                    id,
                    firstQuestions.getOrDefault(id, "Hội thoại mới"),
                    session.getLastActivityAt() == null ? "" : SESSION_TIME.format(session.getLastActivityAt()),
                    id.equals(activeSessionId),
                    counts.getOrDefault(id, 0L)));
        }
        return summaries;
    }

    private static String summarizeTitle(String content) {
        if (content == null || content.isBlank()) {
            return "Hội thoại mới";
        }
        String singleLine = content.replaceAll("\\s+", " ").strip();
        if (singleLine.length() <= 46) {
            return singleLine;
        }
        return singleLine.substring(0, 43) + "...";
    }

    private ChatMessageView toView(ChatMessage message,
                                   QuotaSnapshot snapshot,
                                   boolean allowed) {
        if (!message.isAi()) {
            return new ChatMessageView(
                    message.getId(), true, message.getMessageStatus(), message.getContent(),
                    null, null, null, null, null, null,
                    false, false, false, false, allowed, snapshot.hasAvailableSlot(), List.of());
        }

        Instant now = clock.instant();
        boolean consentActive = message.getConfirmExpiresAt() != null
                && message.getConfirmExpiresAt().isAfter(now);
        boolean awaiting = message.getMessageStatus() == MessageStatus.AWAITING_WEB_CONFIRM;
        MessageStatus status = awaiting && !consentActive
                ? MessageStatus.EXPIRED
                : message.getMessageStatus();

        List<WebSearchJob> messageJobs = jobs.findByMessageIdOrderByCreatedAtAsc(message.getId());
        WebSearchJob job = messageJobs.isEmpty() ? null : messageJobs.get(messageJobs.size() - 1);
        boolean hasQuota = snapshot.hasAvailableSlot();
        boolean polling = status == MessageStatus.PROCESSING_LOCAL
                || status == MessageStatus.WEB_QUEUED
                || status == MessageStatus.WEB_SEARCHING;
        boolean canConfirm = status == MessageStatus.AWAITING_WEB_CONFIRM && allowed && hasQuota;
        boolean canCancel = status == MessageStatus.AWAITING_WEB_CONFIRM;
        boolean canRetry = status == MessageStatus.WEB_FAILED
                && allowed
                && hasQuota
                && consentActive
                && messageJobs.size() < MessageStateService.MAX_WEB_JOBS_PER_MESSAGE;

        return new ChatMessageView(
                message.getId(),
                false,
                status,
                message.getContent(),
                message.getSuggestedQuery(),
                message.getQuestionHash(),
                message.getConfirmExpiresAt(),
                job == null ? null : job.getJobStatus(),
                job == null ? null : job.getStage(),
                job == null ? null : job.getErrorCode(),
                polling,
                canConfirm,
                canCancel,
                canRetry,
                allowed,
                hasQuota,
                citationViews(message.getId()));
    }

    private List<ChatMessageView.Citation> citationViews(UUID messageId) {
        List<ChatMessageView.Citation> result = new ArrayList<>();
        for (MessageCitation citation : citations.findByMessageIdOrderByCitationOrderAsc(messageId)) {
            result.add(new ChatMessageView.Citation(
                    citation.getCitationOrder(),
                    citation.getLocator(),
                    citation.getDocumentVersion().getTitle(),
                    citation.getDocumentVersion().getDocNumber(),
                    citation.getVerbatimText(),
                    AnswerRenderer.citationLink(
                            citation.getDocumentVersion().getId(), citation.getArticle().getId())));
        }
        return result;
    }
}
