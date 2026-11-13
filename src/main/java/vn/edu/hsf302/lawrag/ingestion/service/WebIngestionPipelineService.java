package vn.edu.hsf302.lawrag.ingestion.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hsf302.lawrag.legaldata.entity.EffectiveStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.service.LegalPublicationService;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;
import vn.edu.hsf302.lawrag.legaldata.dto.PublicationDecision;
import vn.edu.hsf302.lawrag.legaldata.entity.SourceSnapshot;
import vn.edu.hsf302.lawrag.legaldata.repository.SourceSnapshotRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;
import vn.edu.hsf302.lawrag.retrieval.service.VietnameseText;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchErrorCode;
import vn.edu.hsf302.lawrag.websearch.service.WebSearchJobService;
import vn.edu.hsf302.lawrag.websearch.entity.WebSearchJobStage;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.edu.hsf302.lawrag.ingestion.config.IngestionProperties;

/**
 * SNAPSHOT -> PARSE -> VALIDATE -> PUBLISH -> EMBED for one fetched URL.
 *
 * <p>Nothing fetched is trusted. The sanitized extracted text becomes the
 * immutable snapshot; the document starts QUARANTINED. Auto-publish is limited
 * to certified hosts with complete metadata and a real Điều structure. Any
 * ambiguity stays QUARANTINED for an admin. All writes are upserts keyed by
 * content hash / (document_key, snapshot_id), so a retried job cannot duplicate
 * snapshots, documents, articles or embeddings.
 */
@Service
@ConditionalOnProperty(prefix = "app.ingestion", name = "enabled", havingValue = "true")
public class WebIngestionPipelineService {

    private final IngestionProperties properties;
    private final SourceAllowlist allowlist;
    private final SourceSnapshotRepository snapshots;
    private final LegalDocumentRepository documents;
    private final LegalArticleRepository articles;
    private final LegalPublicationService publication;
    private final ArticleEmbeddingIndexer embeddingIndexer;
    private final LegalStructureParser parser;
    private final WebContentExtractor contentExtractor;
    private final WebMetadataExtractor metadataExtractor;
    private final WebSearchJobService jobs;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public WebIngestionPipelineService(IngestionProperties properties,
                                       SourceAllowlist allowlist,
                                       SourceSnapshotRepository snapshots,
                                       LegalDocumentRepository documents,
                                       LegalArticleRepository articles,
                                       LegalPublicationService publication,
                                       ArticleEmbeddingIndexer embeddingIndexer,
                                       LegalStructureParser parser,
                                       WebContentExtractor contentExtractor,
                                       WebMetadataExtractor metadataExtractor,
                                       WebSearchJobService jobs,
                                       PlatformTransactionManager transactionManager,
                                       Clock clock) {
        this.properties = properties;
        this.allowlist = allowlist;
        this.snapshots = snapshots;
        this.documents = documents;
        this.articles = articles;
        this.publication = publication;
        this.embeddingIndexer = embeddingIndexer;
        this.parser = parser;
        this.contentExtractor = contentExtractor;
        this.metadataExtractor = metadataExtractor;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public IngestedWebDocument ingest(UUID jobId, UUID leaseToken, URI requestedUrl,
                                      DocumentFetcher.FetchedDocument fetched) {
        URI finalUrl;
        try {
            finalUrl = allowlist.canonicalize(fetched.finalUrl());
        } catch (RuntimeException exception) {
            throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED,
                    "final URL cannot be canonicalized", exception);
        }
        if (!allowlist.isAllowed(finalUrl)) {
            throw new DocumentFetchException(WebSearchErrorCode.SSRF_BLOCKED,
                    "final URL is outside the allowlist");
        }

        jobs.advanceStage(jobId, leaseToken, WebSearchJobStage.SNAPSHOT);
        WebContentExtractor.ExtractedWebContent extracted =
                contentExtractor.extract(fetched.contentType(), fetched.body());
        String text = extracted.text();
        String sha256 = LegalText.sha256Hex(text);
        WebMetadataExtractor.WebDocumentMetadata metadata = metadataExtractor.extract(text);

        jobs.advanceStage(jobId, leaseToken, WebSearchJobStage.PARSE);
        LegalStructureParser.ParsedDocument parsed = parse(text, metadata);
        WebSearchErrorCode parseError = extracted.errorCode();
        if (parseError == null && parsed.units().isEmpty()) {
            parseError = WebSearchErrorCode.PARSE_FAILED;
        }

        PersistedDocument persisted = transactions.execute(status -> persist(
                finalUrl, fetched, text, sha256, metadata, parsed));
        if (persisted == null) {
            throw new IllegalStateException("web ingestion persistence returned no document");
        }

        if (parseError != null) {
            markQuarantined(persisted.documentId(), parseError.name());
            return new IngestedWebDocument(persisted.documentId(), persisted.snapshotId(),
                    VerificationStatus.QUARANTINED, persisted.articleCount(), parseError, parseError.name());
        }

        jobs.advanceStage(jobId, leaseToken, WebSearchJobStage.VALIDATE);
        WebSearchErrorCode eligibilityError = eligibilityError(finalUrl, metadata, persisted.articleCount());
        if (eligibilityError != null) {
            markQuarantined(persisted.documentId(), eligibilityError.name());
            return new IngestedWebDocument(persisted.documentId(), persisted.snapshotId(),
                    VerificationStatus.QUARANTINED, persisted.articleCount(), eligibilityError,
                    eligibilityError.name());
        }

        jobs.advanceStage(jobId, leaseToken, WebSearchJobStage.EMBED);
        markVerified(persisted.documentId());
        PublicationDecision decision = publication.publishIfEligible(persisted.documentId());
        if (!decision.ready()) {
            String reason = String.join("; ", decision.failures());
            return new IngestedWebDocument(persisted.documentId(), persisted.snapshotId(),
                    VerificationStatus.QUARANTINED, persisted.articleCount(),
                    WebSearchErrorCode.MISSING_METADATA, reason);
        }

        try {
            embeddingIndexer.indexReadyArticles(persisted.documentId());
        } catch (RuntimeException exception) {
            markQuarantined(persisted.documentId(), WebSearchErrorCode.EMBEDDING_FAILED.name());
            return new IngestedWebDocument(persisted.documentId(), persisted.snapshotId(),
                    VerificationStatus.QUARANTINED, persisted.articleCount(),
                    WebSearchErrorCode.EMBEDDING_FAILED, "EMBEDDING_FAILED");
        }
        jobs.advanceStage(jobId, leaseToken, WebSearchJobStage.PUBLISH);
        return new IngestedWebDocument(persisted.documentId(), persisted.snapshotId(),
                VerificationStatus.READY, persisted.articleCount(), null, null);
    }

    private LegalStructureParser.ParsedDocument parse(
            String text, WebMetadataExtractor.WebDocumentMetadata metadata) {
        if (text.isBlank()) {
            return new LegalStructureParser.ParsedDocument(List.of());
        }
        try {
            return parser.parse(
                    text,
                    metadata.effectiveFrom(),
                    metadata.effectiveTo(),
                    metadata.effectiveFrom() != null,
                    Map.of());
        } catch (RuntimeException exception) {
            return new LegalStructureParser.ParsedDocument(List.of());
        }
    }

    private PersistedDocument persist(URI finalUrl,
                                      DocumentFetcher.FetchedDocument fetched,
                                      String text,
                                      String sha256,
                                      WebMetadataExtractor.WebDocumentMetadata metadata,
                                      LegalStructureParser.ParsedDocument parsed) {
        SourceSnapshot snapshot = snapshots
                .findFirstByContentSha256AndParserVersion(sha256, properties.parserVersion())
                .orElseGet(() -> snapshots.save(new SourceSnapshot(
                        finalUrl.toString(),
                        text,
                        null,
                        sha256,
                        fetched.fetchedAt(),
                        fetched.contentType(),
                        properties.parserVersion(),
                        (long) fetched.body().length)));

        String documentKey = metadata.docNumber() != null
                ? "web:" + VietnameseText.docNumberKey(metadata.docNumber())
                : "web-sha:" + sha256.substring(0, 32);
        String title = metadata.title() != null
                ? metadata.title()
                : "Văn bản từ " + finalUrl.getHost();

        LegalDocument document = documents
                .findByDocumentKeyAndSnapshotId(documentKey, snapshot.getId())
                .orElseGet(() -> new LegalDocument(documentKey, title, finalUrl.toString(), snapshot));
        // A re-fetch of the same content must not wipe metadata an admin already
        // verified on the existing READY version.
        if (document.getVerificationStatus() != VerificationStatus.READY) {
            document.setDocNumber(metadata.docNumber());
            document.setIssuingBody(metadata.issuingBody());
            document.setIssueDate(metadata.issueDate());
            document.setEffectiveFrom(metadata.effectiveFrom());
            document.setEffectiveTo(metadata.effectiveTo());
            document.setEffectiveStatus(effectiveStatus(metadata));
        }
        LegalDocument savedDocument = documents.save(document);

        List<LegalArticle> existing = articles.findByDocumentIdOrderByArticleCodeAsc(savedDocument.getId());
        if (!existing.isEmpty()) {
            return new PersistedDocument(savedDocument.getId(), snapshot.getId(), existing.size());
        }

        Map<String, LegalArticle> byCode = new HashMap<>();
        for (LegalStructureParser.ParsedUnit unit : parsed.units()) {
            LegalArticle parent = unit.parentCode() == null ? null : byCode.get(unit.parentCode());
            if (unit.parentCode() != null && parent == null) {
                throw new IllegalStateException("missing parent " + unit.parentCode() + " for " + unit.code());
            }
            LegalArticle article = new LegalArticle(
                    savedDocument,
                    parent,
                    unit.level(),
                    unit.code(),
                    unit.number(),
                    unit.title(),
                    unit.rawText(),
                    unit.searchText(),
                    unit.locator());
            article.setScope(unit.scope());
            article.setEffectiveFrom(unit.effectiveFrom());
            article.setEffectiveTo(unit.effectiveTo());
            articles.save(article);
            byCode.put(unit.code(), article);
        }
        return new PersistedDocument(savedDocument.getId(), snapshot.getId(), byCode.size());
    }

    private EffectiveStatus effectiveStatus(WebMetadataExtractor.WebDocumentMetadata metadata) {
        if (metadata.effectiveFrom() == null) {
            return EffectiveStatus.UNKNOWN;
        }
        return metadata.effectiveFrom().isAfter(LocalDate.now(clock))
                ? EffectiveStatus.UPCOMING
                : EffectiveStatus.EFFECTIVE;
    }

    private WebSearchErrorCode eligibilityError(URI finalUrl,
                                                WebMetadataExtractor.WebDocumentMetadata metadata,
                                                int articleCount) {
        if (!allowlist.isAutoPublishHost(finalUrl.getHost())) {
            return WebSearchErrorCode.SOURCE_NOT_CERTIFIED;
        }
        if (metadata.title() == null || metadata.title().isBlank()
                || metadata.docNumber() == null
                || metadata.issuingBody() == null
                || metadata.issueDate() == null
                || metadata.effectiveFrom() == null) {
            return WebSearchErrorCode.MISSING_METADATA;
        }
        if (articleCount == 0) {
            return WebSearchErrorCode.PARSE_FAILED;
        }
        return null;
    }

    private void markQuarantined(UUID documentId, String reason) {
        transactions.executeWithoutResult(status -> {
            LegalDocument document = documents.findById(documentId).orElseThrow();
            document.setVerificationStatus(VerificationStatus.QUARANTINED);
            document.setMetadataVerified(false);
            document.setEffectiveDatesVerified(false);
            document.setVerificationNotes(reason);
            documents.save(document);

            List<LegalArticle> articleRows = articles.findByDocumentIdOrderByArticleCodeAsc(documentId);
            articleRows.forEach(article -> article.setVerificationStatus(VerificationStatus.QUARANTINED));
            articles.saveAll(articleRows);
        });
    }

    private void markVerified(UUID documentId) {
        transactions.executeWithoutResult(status -> {
            LegalDocument document = documents.findById(documentId).orElseThrow();
            document.setMetadataVerified(true);
            document.setEffectiveDatesVerified(true);
            document.setSourceCheckedBy("auto-pipeline:" + properties.parserVersion());
            document.setSourceCheckedAt(Instant.now(clock));
            document.setVerificationNotes(null);
            documents.save(document);

            List<LegalArticle> articleRows = articles.findByDocumentIdOrderByArticleCodeAsc(documentId);
            for (LegalArticle article : articleRows) {
                if (article.getEffectiveFrom() == null) {
                    article.setEffectiveFrom(document.getEffectiveFrom());
                }
                if (article.getEffectiveTo() == null) {
                    article.setEffectiveTo(document.getEffectiveTo());
                }
            }
            articles.saveAll(articleRows);
        });
    }

    public record IngestedWebDocument(
            UUID documentId,
            UUID snapshotId,
            VerificationStatus status,
            int articleCount,
            WebSearchErrorCode errorCode,
            String quarantineReason) {

        public boolean ready() {
            return status == VerificationStatus.READY;
        }
    }

    private record PersistedDocument(UUID documentId, UUID snapshotId, int articleCount) {
    }
}
