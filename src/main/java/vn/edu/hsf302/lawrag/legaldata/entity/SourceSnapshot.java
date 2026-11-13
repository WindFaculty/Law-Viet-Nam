package vn.edu.hsf302.lawrag.legaldata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable raw source payload (inline or stored) captured before parsing.
 * Documents reference a snapshot, so the exact bytes behind a citation remain traceable.
 */
@Entity
@Table(name = "source_snapshots")
public class SourceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_url", nullable = false, columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "storage_path", columnDefinition = "text")
    private String storagePath;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "parser_version", nullable = false, length = 50)
    private String parserVersion;

    @Column(name = "byte_size")
    private Long byteSize;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SourceSnapshot() {
    }

    public SourceSnapshot(String sourceUrl, String content, String storagePath, String contentSha256,
                          Instant fetchedAt, String contentType, String parserVersion, Long byteSize) {
        this.sourceUrl = sourceUrl;
        this.content = content;
        this.storagePath = storagePath;
        this.contentSha256 = contentSha256;
        this.fetchedAt = fetchedAt;
        this.contentType = contentType;
        this.parserVersion = parserVersion;
        this.byteSize = byteSize;
    }

    public UUID getId() {
        return id;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getContent() {
        return content;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public String getContentType() {
        return contentType;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
