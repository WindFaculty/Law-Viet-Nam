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

import java.time.Instant;
import java.util.UUID;

/**
 * A verified (or quarantined) amendment, replacement or repeal edge between
 * document versions and, when known, the affected articles.
 */
@Entity
@Table(name = "legal_document_relations")
public class LegalDocumentRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_document_id", nullable = false)
    private LegalDocument sourceDocument;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_document_id", nullable = false)
    private LegalDocument targetDocument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_article_id")
    private LegalArticle sourceArticle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_article_id")
    private LegalArticle targetArticle;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 32)
    private LegalRelationType relationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private VerificationStatus verificationStatus = VerificationStatus.QUARANTINED;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LegalDocumentRelation() {
    }

    public LegalDocumentRelation(LegalDocument sourceDocument, LegalDocument targetDocument,
                                 LegalArticle sourceArticle, LegalArticle targetArticle,
                                 LegalRelationType relationType) {
        this.sourceDocument = sourceDocument;
        this.targetDocument = targetDocument;
        this.sourceArticle = sourceArticle;
        this.targetArticle = targetArticle;
        this.relationType = relationType;
    }

    public UUID getId() {
        return id;
    }

    public LegalDocument getSourceDocument() {
        return sourceDocument;
    }

    public LegalDocument getTargetDocument() {
        return targetDocument;
    }

    public LegalArticle getSourceArticle() {
        return sourceArticle;
    }

    public LegalArticle getTargetArticle() {
        return targetArticle;
    }

    public LegalRelationType getRelationType() {
        return relationType;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
