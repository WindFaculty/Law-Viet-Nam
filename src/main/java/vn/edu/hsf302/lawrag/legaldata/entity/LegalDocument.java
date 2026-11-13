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
 * One row per source version. {@code documentKey} groups versions of the same
 * legal document, so historical questions can still resolve an older version.
 */
@Entity
@Table(name = "legal_documents")
public class LegalDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_key", nullable = false, length = 160)
    private String documentKey;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "doc_number", length = 100)
    private String docNumber;

    @Column(name = "issuing_body", length = 255)
    private String issuingBody;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "effective_status", nullable = false, length = 32)
    private EffectiveStatus effectiveStatus = EffectiveStatus.UNKNOWN;

    @Column(name = "source_url", nullable = false, columnDefinition = "text")
    private String sourceUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private SourceSnapshot snapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private VerificationStatus verificationStatus = VerificationStatus.QUARANTINED;

    @Column(name = "metadata_verified", nullable = false)
    private boolean metadataVerified;

    @Column(name = "effective_dates_verified", nullable = false)
    private boolean effectiveDatesVerified;

    @Column(name = "source_checked_by", length = 255)
    private String sourceCheckedBy;

    @Column(name = "source_checked_at")
    private Instant sourceCheckedAt;

    @Column(name = "verification_notes", columnDefinition = "text")
    private String verificationNotes;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LegalDocument() {
    }

    public LegalDocument(String documentKey, String title, String sourceUrl, SourceSnapshot snapshot) {
        this.documentKey = documentKey;
        this.title = title;
        this.sourceUrl = sourceUrl;
        this.snapshot = snapshot;
    }

    public UUID getId() {
        return id;
    }

    public String getDocumentKey() {
        return documentKey;
    }

    public String getTitle() {
        return title;
    }

    public String getDocNumber() {
        return docNumber;
    }

    public void setDocNumber(String docNumber) {
        this.docNumber = docNumber;
    }

    public String getIssuingBody() {
        return issuingBody;
    }

    public void setIssuingBody(String issuingBody) {
        this.issuingBody = issuingBody;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
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

    public EffectiveStatus getEffectiveStatus() {
        return effectiveStatus;
    }

    public void setEffectiveStatus(EffectiveStatus effectiveStatus) {
        this.effectiveStatus = effectiveStatus;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public SourceSnapshot getSnapshot() {
        return snapshot;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public boolean isMetadataVerified() {
        return metadataVerified;
    }

    public void setMetadataVerified(boolean metadataVerified) {
        this.metadataVerified = metadataVerified;
    }

    public boolean isEffectiveDatesVerified() {
        return effectiveDatesVerified;
    }

    public void setEffectiveDatesVerified(boolean effectiveDatesVerified) {
        this.effectiveDatesVerified = effectiveDatesVerified;
    }

    public String getSourceCheckedBy() {
        return sourceCheckedBy;
    }

    public void setSourceCheckedBy(String sourceCheckedBy) {
        this.sourceCheckedBy = sourceCheckedBy;
    }

    public Instant getSourceCheckedAt() {
        return sourceCheckedAt;
    }

    public void setSourceCheckedAt(Instant sourceCheckedAt) {
        this.sourceCheckedAt = sourceCheckedAt;
    }

    public String getVerificationNotes() {
        return verificationNotes;
    }

    public void setVerificationNotes(String verificationNotes) {
        this.verificationNotes = verificationNotes;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
