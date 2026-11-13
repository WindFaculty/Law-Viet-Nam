package vn.edu.hsf302.lawrag.forum.entity;

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
import vn.edu.hsf302.lawrag.auth.entity.AppUser;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "forum_posts")
public class ForumPost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private AppUser author;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 64)
    private ForumCategory category = ForumCategory.THAO_LUAN;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "pinned", nullable = false)
    private boolean pinned = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ForumPost() {
    }

    public ForumPost(AppUser author, String title, ForumCategory category, String content) {
        this.author = author;
        this.title = title;
        this.category = category == null ? ForumCategory.THAO_LUAN : category;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public AppUser getAuthor() {
        return author;
    }

    public String getTitle() {
        return title;
    }

    public ForumCategory getCategory() {
        return category;
    }

    public String getContent() {
        return content;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

