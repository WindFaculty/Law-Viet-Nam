package vn.edu.hsf302.lawrag.legaldata.entity;

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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A node of the Điều/Khoản/Điểm tree. Verbatim {@code rawText} is what a
 * citation displays; {@code searchText} only feeds full-text retrieval.
 */
@Entity
@Table(name = "legal_articles")
public class LegalArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private LegalDocument document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private LegalArticle parent;

    @Enumerated(EnumType.STRING)
    @Column(name = "hierarchy_level", nullable = false, length = 16)
    private LegalHierarchyLevel hierarchyLevel;

    @Column(name = "article_code", nullable = false, length = 64)
    private String articleCode;

    @Column(name = "article_number", nullable = false, length = 32)
    private String articleNumber;

    @Column(name = "title", columnDefinition = "text")
    private String title;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Column(name = "search_text", nullable = false, columnDefinition = "text")
    private String searchText;

    @Column(name = "locator", nullable = false, length = 255)
    private String locator;

    @Column(name = "scope", length = 255)
    private String scope;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private VerificationStatus verificationStatus = VerificationStatus.QUARANTINED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LegalArticle() {
    }

    public LegalArticle(LegalDocument document, LegalArticle parent, LegalHierarchyLevel hierarchyLevel,
                        String articleCode, String articleNumber, String title,
                        String rawText, String searchText, String locator) {
        this.document = document;
        this.parent = parent;
        this.hierarchyLevel = hierarchyLevel;
        this.articleCode = articleCode;
        this.articleNumber = articleNumber;
        this.title = title;
        this.rawText = rawText;
        this.searchText = searchText;
        this.locator = locator;
    }

    public UUID getId() {
        return id;
    }

    public LegalDocument getDocument() {
        return document;
    }

    public LegalArticle getParent() {
        return parent;
    }

    public LegalHierarchyLevel getHierarchyLevel() {
        return hierarchyLevel;
    }

    public String getArticleCode() {
        return articleCode;
    }

    public String getArticleNumber() {
        return articleNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getRawText() {
        return rawText;
    }

    public String getSearchText() {
        return searchText;
    }

    public String getLocator() {
        return locator;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
