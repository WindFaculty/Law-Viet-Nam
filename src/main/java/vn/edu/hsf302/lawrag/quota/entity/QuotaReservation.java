package vn.edu.hsf302.lawrag.quota.entity;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import vn.edu.hsf302.lawrag.chat.entity.ChatMessage;

import java.time.Instant;
import java.util.UUID;

/** A held slot for one message/processing attempt. */
@Entity
@Table(name = "quota_reservations")
public class QuotaReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bucket_id", nullable = false)
    private QuotaBucket bucket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private QuotaReservationStatus status = QuotaReservationStatus.ACTIVE;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuotaReservation() {
    }

    public QuotaReservation(QuotaBucket bucket, ChatMessage message, String idempotencyKey, Instant expiresAt) {
        this.bucket = bucket;
        this.message = message;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public QuotaBucket getBucket() {
        return bucket;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public QuotaReservationStatus getStatus() {
        return status;
    }

    public void setStatus(QuotaReservationStatus status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    public void setCommittedAt(Instant committedAt) {
        this.committedAt = committedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
