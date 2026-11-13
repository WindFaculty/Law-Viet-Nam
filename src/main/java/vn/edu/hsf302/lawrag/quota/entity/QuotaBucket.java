package vn.edu.hsf302.lawrag.quota.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;
import vn.edu.hsf302.lawrag.chat.entity.SessionOwnerType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily quota counter for one subject. The database check
 * {@code used_count + reserved_count <= limit_count} is the final safety net;
 * the service additionally locks this row with SELECT ... FOR UPDATE.
 */
@Entity
@Table(name = "quota_buckets")
public class QuotaBucket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private QuotaSubjectType subjectType;

    @Column(name = "anon_id", length = 64)
    private String anonId;

    @Column(name = "owner_user_id", length = 100)
    private String ownerUserId;

    @Column(name = "quota_period", nullable = false)
    private LocalDate quotaPeriod;

    @Column(name = "limit_count", nullable = false)
    private int limitCount;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuotaBucket() {
    }

    public QuotaBucket(SessionOwner owner, LocalDate quotaPeriod, int limitCount) {
        this.subjectType = owner.type() == SessionOwnerType.ANON
                ? QuotaSubjectType.ANON : QuotaSubjectType.USER;
        if (owner.type() == SessionOwnerType.ANON) {
            this.anonId = owner.ownerId();
        } else {
            this.ownerUserId = owner.ownerId();
        }
        this.quotaPeriod = quotaPeriod;
        this.limitCount = limitCount;
    }

    public UUID getId() {
        return id;
    }

    public QuotaSubjectType getSubjectType() {
        return subjectType;
    }

    public String getAnonId() {
        return anonId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public LocalDate getQuotaPeriod() {
        return quotaPeriod;
    }

    public int getLimitCount() {
        return limitCount;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(int usedCount) {
        this.usedCount = usedCount;
    }

    public int getReservedCount() {
        return reservedCount;
    }

    public void setReservedCount(int reservedCount) {
        this.reservedCount = reservedCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
