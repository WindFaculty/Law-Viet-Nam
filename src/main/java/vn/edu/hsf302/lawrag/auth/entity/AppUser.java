package vn.edu.hsf302.lawrag.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Registered application account used for login, forum participation and owned chat sessions.
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private UserRole role = UserRole.USER;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    public AppUser(String username, String displayName, String passwordHash, UserRole role) {
        this.username = normalizeUsername(username);
        this.displayName = displayName == null ? "" : displayName.strip();
        this.passwordHash = passwordHash;
        this.role = role == null ? UserRole.USER : role;
        this.enabled = true;
    }

    public static String normalizeUsername(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        if (role == null) {
            throw new IllegalArgumentException("Role must not be null");
        }
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

