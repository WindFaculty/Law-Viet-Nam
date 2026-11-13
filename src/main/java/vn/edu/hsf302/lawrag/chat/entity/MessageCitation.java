package vn.edu.hsf302.lawrag.chat.entity;

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
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.entity.SourceSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * A displayed citation. The verbatim text is copied from the immutable source
 * snapshot; the article/document references allow the reader to open the source.
 */
@Entity
@Table(name = "message_citations")
public class MessageCitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_version_id", nullable = false)
    private LegalDocument documentVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private LegalArticle article;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_snapshot_id", nullable = false)
    private SourceSnapshot sourceSnapshot;

    @Column(name = "citation_order", nullable = false)
    private int citationOrder;

    @Column(name = "excerpt_start", nullable = false)
    private int excerptStart;

    @Column(name = "excerpt_end", nullable = false)
    private int excerptEnd;

    @Column(name = "locator", nullable = false, length = 255)
    private String locator;

    @Column(name = "verbatim_text", nullable = false, columnDefinition = "text")
    private String verbatimText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageCitation() {
    }

    public MessageCitation(ChatMessage message, LegalDocument documentVersion, LegalArticle article,
                           SourceSnapshot sourceSnapshot, int citationOrder, int excerptStart, int excerptEnd,
                           String locator, String verbatimText) {
        this.message = message;
        this.documentVersion = documentVersion;
        this.article = article;
        this.sourceSnapshot = sourceSnapshot;
        this.citationOrder = citationOrder;
        this.excerptStart = excerptStart;
        this.excerptEnd = excerptEnd;
        this.locator = locator;
        this.verbatimText = verbatimText;
    }

    public UUID getId() {
        return id;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public LegalDocument getDocumentVersion() {
        return documentVersion;
    }

    public LegalArticle getArticle() {
        return article;
    }

    public SourceSnapshot getSourceSnapshot() {
        return sourceSnapshot;
    }

    public int getCitationOrder() {
        return citationOrder;
    }

    public int getExcerptStart() {
        return excerptStart;
    }

    public int getExcerptEnd() {
        return excerptEnd;
    }

    public String getLocator() {
        return locator;
    }

    public String getVerbatimText() {
        return verbatimText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
