package vn.edu.hsf302.lawrag.forum.entity;

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
import vn.edu.hsf302.lawrag.auth.entity.AppUser;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "forum_comments")
public class ForumComment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private ForumPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private AppUser author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private ForumComment parentComment;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "official_admin_reply", nullable = false)
    private boolean officialAdminReply = false;

    @Column(name = "accepted_answer", nullable = false)
    private boolean acceptedAnswer = false;

    @Column(name = "edited_at")
    private Instant editedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ForumComment() {
    }

    public ForumComment(ForumPost post, AppUser author, String content) {
        this(post, author, null, content, false);
    }

    public ForumComment(ForumPost post,
                        AppUser author,
                        ForumComment parentComment,
                        String content,
                        boolean officialAdminReply) {
        this.post = post;
        this.author = author;
        this.parentComment = parentComment;
        this.content = content;
        this.officialAdminReply = officialAdminReply;
    }

    public UUID getId() {
        return id;
    }

    public ForumPost getPost() {
        return post;
    }

    public AppUser getAuthor() {
        return author;
    }

    public ForumComment getParentComment() {
        return parentComment;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isOfficialAdminReply() {
        return officialAdminReply;
    }

    public void setOfficialAdminReply(boolean officialAdminReply) {
        this.officialAdminReply = officialAdminReply;
    }

    public boolean isAcceptedAnswer() {
        return acceptedAnswer;
    }

    public void setAcceptedAnswer(boolean acceptedAnswer) {
        this.acceptedAnswer = acceptedAnswer;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

