package vn.edu.hsf302.lawrag.quota.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Append-only record of a consumed turn. The schema trigger rejects UPDATE and
 * DELETE; {@code answer_message_id} is unique so an answer can never charge twice.
 */
@Entity
@Table(name = "quota_ledger")
public class QuotaLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bucket_id", nullable = false)
    private QuotaBucket bucket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private QuotaReservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_message_id", nullable = false)
    private ChatMessage answerMessage;

    @Column(name = "quantity", nullable = false)
    private int quantity = 1;

    @Column(name = "quota_period", nullable = false)
    private LocalDate quotaPeriod;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuotaLedger() {
    }

    public QuotaLedger(QuotaBucket bucket, QuotaReservation reservation, ChatMessage answerMessage,
                       int quantity, LocalDate quotaPeriod) {
        this.bucket = bucket;
        this.reservation = reservation;
        this.answerMessage = answerMessage;
        this.quantity = quantity;
        this.quotaPeriod = quotaPeriod;
    }

    public UUID getId() {
        return id;
    }

    public QuotaBucket getBucket() {
        return bucket;
    }

    public QuotaReservation getReservation() {
        return reservation;
    }

    public ChatMessage getAnswerMessage() {
        return answerMessage;
    }

    public int getQuantity() {
        return quantity;
    }

    public LocalDate getQuotaPeriod() {
        return quotaPeriod;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
