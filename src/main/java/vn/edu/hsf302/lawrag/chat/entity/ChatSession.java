package vn.edu.hsf302.lawrag.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;
import vn.edu.hsf302.lawrag.chat.dto.SessionOwner;

/**
 * One conversation. Exactly one owner column is set: an account id or the
 * server-managed anonymous client id.
 */
@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_user_id", length = 100)
    private String ownerUserId;

    @Column(name = "anon_id", length = 64)
    private String anonId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "persona_code", length = 64)
    private String personaCode;

    protected ChatSession() {
    }

    public ChatSession(SessionOwner owner, Instant now, Instant expiresAt) {
        if (owner.type() == SessionOwnerType.ANON) {
            this.anonId = owner.ownerId();
        } else {
            this.ownerUserId = owner.ownerId();
        }
        this.lastActivityAt = now;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public String getAnonId() {
        return anonId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getPersonaCode() {
        return personaCode;
    }

    public void setPersonaCode(String personaCode) {
        this.personaCode = personaCode;
    }

    public SessionOwner owner() {
        return anonId != null ? SessionOwner.anonymous(anonId) : SessionOwner.account(ownerUserId);
    }

    public boolean isOwnedBy(SessionOwner candidate) {
        return candidate != null && candidate.equals(owner());
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void touch(Instant now) {
        this.lastActivityAt = now;
    }

    public void expire(Instant now) {
        this.expiresAt = now;
    }
}
