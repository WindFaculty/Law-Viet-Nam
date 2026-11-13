package vn.edu.hsf302.lawrag.websearch.entity;

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

/**
 * Durable unit of web-search work. A partial unique index in the schema allows
 * at most one active job per message; worker claims use lease token + expiry.
 */
@Entity
@Table(name = "web_search_jobs")
public class WebSearchJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status", nullable = false, length = 32)
    private WebSearchJobStatus jobStatus = WebSearchJobStatus.QUEUED;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 32)
    private WebSearchJobStage stage = WebSearchJobStage.QUEUED;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "deadline", nullable = false)
    private Instant deadline;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebSearchJob() {
    }

    public static WebSearchJob queued(ChatMessage message, Instant deadline) {
        WebSearchJob job = new WebSearchJob();
        job.message = message;
        job.deadline = deadline;
        return job;
    }

    public UUID getId() {
        return id;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public WebSearchJobStatus getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(WebSearchJobStatus jobStatus) {
        this.jobStatus = jobStatus;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public WebSearchJobStage getStage() {
        return stage;
    }

    public void setStage(WebSearchJobStage stage) {
        this.stage = stage;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(UUID leaseToken) {
        this.leaseToken = leaseToken;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
