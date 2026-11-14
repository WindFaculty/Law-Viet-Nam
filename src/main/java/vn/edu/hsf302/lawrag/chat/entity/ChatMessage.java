package vn.edu.hsf302.lawrag.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A USER question or its AI answer. The {@code version} column makes every
 * state transition an optimistic compare-and-set: competing confirm/cancel or
 * worker transitions cannot both commit.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender", nullable = false, length = 16)
    private Sender sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "in_reply_to_id")
    private ChatMessage inReplyTo;

    /**
     * Only USER questions carry the client idempotency key; the unique
     * (session_id, idempotency_key) constraint is the duplicate-submit guard.
     */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "message_status", nullable = false, length = 32)
    private MessageStatus messageStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 32)
    private MessageOutcome outcome;

    @Column(name = "suggested_query", columnDefinition = "text")
    private String suggestedQuery;

    @Column(name = "applicable_date", nullable = false)
    private LocalDate applicableDate;

    @Column(name = "question_hash", length = 64)
    private String questionHash;

    @Column(name = "confirm_expires_at")
    private Instant confirmExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "persona_code", length = 64)
    private String personaCode;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatMessage() {
    }

    public static ChatMessage userQuestion(ChatSession session, String idempotencyKey,
                                           String question, LocalDate applicableDate, String questionHash) {
        ChatMessage message = new ChatMessage();
        message.session = session;
        message.sender = Sender.USER;
        message.idempotencyKey = idempotencyKey;
        message.content = question;
        // A USER row records "question persisted"; the AI row owns the state machine.
        message.messageStatus = MessageStatus.COMPLETED;
        message.applicableDate = applicableDate;
        message.questionHash = questionHash;
        return message;
    }

    public static ChatMessage aiProcessing(ChatSession session, ChatMessage userQuestion,
                                           LocalDate applicableDate, String questionHash) {
        ChatMessage message = new ChatMessage();
        message.session = session;
        message.sender = Sender.AI;
        message.inReplyTo = userQuestion;
        message.content = "";
        message.messageStatus = MessageStatus.PROCESSING_LOCAL;
        message.applicableDate = applicableDate;
        message.questionHash = questionHash;
        return message;
    }

    public UUID getId() {
        return id;
    }

    public ChatSession getSession() {
        return session;
    }

    public Sender getSender() {
        return sender;
    }

    public ChatMessage getInReplyTo() {
        return inReplyTo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public MessageStatus getMessageStatus() {
        return messageStatus;
    }

    public void setMessageStatus(MessageStatus messageStatus) {
        this.messageStatus = messageStatus;
    }

    public MessageOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(MessageOutcome outcome) {
        this.outcome = outcome;
    }

    public String getSuggestedQuery() {
        return suggestedQuery;
    }

    public void setSuggestedQuery(String suggestedQuery) {
        this.suggestedQuery = suggestedQuery;
    }

    public LocalDate getApplicableDate() {
        return applicableDate;
    }

    public void setApplicableDate(LocalDate applicableDate) {
        this.applicableDate = applicableDate;
    }

    public String getQuestionHash() {
        return questionHash;
    }

    public void setQuestionHash(String questionHash) {
        this.questionHash = questionHash;
    }

    public Instant getConfirmExpiresAt() {
        return confirmExpiresAt;
    }

    public void setConfirmExpiresAt(Instant confirmExpiresAt) {
        this.confirmExpiresAt = confirmExpiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getPersonaCode() {
        return personaCode;
    }

    public void setPersonaCode(String personaCode) {
        this.personaCode = personaCode;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isAi() {
        return sender == Sender.AI;
    }
}
