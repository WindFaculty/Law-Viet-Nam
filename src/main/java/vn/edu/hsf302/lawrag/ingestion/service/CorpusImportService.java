package vn.edu.hsf302.lawrag.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;
import vn.edu.hsf302.lawrag.legaldata.repository.ArticleEmbeddingRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.EffectiveStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.EmbeddingStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocumentRelation;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRelationRepository;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.service.LegalPublicationService;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalRelationType;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;
import vn.edu.hsf302.lawrag.legaldata.dto.PublicationDecision;
import vn.edu.hsf302.lawrag.legaldata.entity.SourceSnapshot;
import vn.edu.hsf302.lawrag.legaldata.repository.SourceSnapshotRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.edu.hsf302.lawrag.ingestion.dto.CorpusImportResult;
import vn.edu.hsf302.lawrag.ingestion.dto.CorpusManifest;

/**
 * Imports the controlled corpus idempotently. Snapshot integrity is checked
 * against the manifest before anything is written. Articles are persisted
 * before publication, embeddings are generated only for READY articles, and a
 * second import reuses every existing row by its natural identity.
 */
@Service
public class CorpusImportService {

    private static final String MANIFEST_LOCATION = "classpath:corpus/manifest.json";
    private static final String CORPUS_ROOT = "classpath:corpus/";

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final TransactionTemplate transactions;
    private final SourceSnapshotRepository snapshots;
    private final LegalDocumentRepository documents;
    private final LegalArticleRepository articles;
    private final LegalDocumentRelationRepository relations;
    private final ArticleEmbeddingRepository embeddings;
    private final LegalStructureParser parser;
    private final LegalPublicationService publication;
    private final EmbeddingClient embeddingClient;
    private final GeminiProperties geminiProperties;

    public CorpusImportService(ObjectMapper objectMapper,
                               ResourceLoader resourceLoader,
                               PlatformTransactionManager transactionManager,
                               SourceSnapshotRepository snapshots,
                               LegalDocumentRepository documents,
                               LegalArticleRepository articles,
                               LegalDocumentRelationRepository relations,
                               ArticleEmbeddingRepository embeddings,
                               LegalStructureParser parser,
                               LegalPublicationService publication,
                               EmbeddingClient embeddingClient,
                               GeminiProperties geminiProperties) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.transactions = new TransactionTemplate(transactionManager);
        this.snapshots = snapshots;
        this.documents = documents;
        this.articles = articles;
        this.relations = relations;
        this.embeddings = embeddings;
        this.parser = parser;
        this.publication = publication;
        this.embeddingClient = embeddingClient;
        this.geminiProperties = geminiProperties;
    }

    public CorpusImportResult importCorpus() {
        CorpusManifest manifest = loadManifest();
        if (!LegalStructureParser.PARSER_VERSION.equals(manifest.parserVersion())) {
            throw new CorpusIntegrityException("manifest parserVersion does not match parser: "
                    + manifest.parserVersion());
        }

        Counters counters = new Counters();
        Map<String, UUID> documentIds = new LinkedHashMap<>();
        List<UUID> readyDocumentIds = new ArrayList<>();
        List<CorpusImportResult.DocumentResult> documentResults = new ArrayList<>();

        for (CorpusManifest.DocumentEntry entry : manifest.documents()) {
            SnapshotPayload payload = readSnapshot(entry);
            UUID snapshotId = transactions.execute(status -> persistSnapshot(entry, payload, counters));
            if (snapshotId == null) {
                throw new IllegalStateException("source snapshot persistence returned no id");
            }

            Map<String, LegalStructureParser.EffectiveDateOverride> overrides = new LinkedHashMap<>();
            entry.articleEffectiveOverrides().forEach((code, override) ->
                    overrides.put(code, new LegalStructureParser.EffectiveDateOverride(
                            override.effectiveFrom(), override.effectiveTo())));

            LocalDates dates = effectiveDates(entry);
            LegalStructureParser.ParsedDocument parsed = parser.parse(
                    payload.content(),
                    dates.from(),
                    dates.to(),
                    entry.verification() != null && entry.verification().effectiveDatesVerified(),
                    overrides);

            if (entry.expectedArticleCount() != null
                    && parsed.articleCount() != entry.expectedArticleCount()) {
                throw new CorpusIntegrityException("article count mismatch for " + entry.documentKey()
                        + ": expected " + entry.expectedArticleCount() + ", parsed " + parsed.articleCount());
            }

            PersistedDocument persisted = transactions.execute(status ->
                    persistDocument(entry, snapshotId, parsed, counters));
            if (persisted == null) {
                throw new IllegalStateException("document persistence returned no result");
            }

            PublicationDecision decision = publication.publishIfEligible(persisted.documentId());
            if (decision.ready()) {
                readyDocumentIds.add(persisted.documentId());
            }

            documentIds.put(entry.documentKey(), persisted.documentId());
            LegalDocument stored = documents.findById(persisted.documentId()).orElseThrow();
            documentResults.add(new CorpusImportResult.DocumentResult(
                    entry.documentKey(), stored.getVerificationStatus(), decision, persisted.articleCount()));
        }

        transactions.executeWithoutResult(status -> persistRelations(manifest, documentIds, counters));
        for (UUID readyDocumentId : readyDocumentIds) {
            generateEmbeddings(readyDocumentId, counters);
        }

        return new CorpusImportResult(
                manifest.corpusVersion(),
                counters.snapshotsCreated,
                counters.snapshotsReused,
                counters.documentsCreated,
                counters.documentsReused,
                counters.articlesCreated,
                counters.articlesReused,
                counters.relationsCreated,
                counters.relationsReused,
                counters.embeddingsGenerated,
                counters.embeddingsReused,
                documentResults);
    }

    private CorpusManifest loadManifest() {
        Resource resource = resourceLoader.getResource(MANIFEST_LOCATION);
        try (var input = resource.getInputStream()) {
            CorpusManifest manifest = objectMapper.readValue(input, CorpusManifest.class);
            if (manifest.corpusVersion() == null || manifest.corpusVersion().isBlank()) {
                throw new CorpusIntegrityException("corpusVersion is required");
            }
            return manifest;
        } catch (IOException exception) {
            throw new CorpusIntegrityException("cannot read corpus manifest", exception);
        }
    }

    private SnapshotPayload readSnapshot(CorpusManifest.DocumentEntry entry) {
        if (entry.snapshot() == null || entry.snapshot().path() == null) {
            throw new CorpusIntegrityException("snapshot is required for " + entry.documentKey());
        }
        String path = entry.snapshot().path().replace('\\', '/');
        if (!path.startsWith("snapshots/") || path.contains("..")) {
            throw new CorpusIntegrityException("snapshot path escapes corpus directory: " + path);
        }
        if (entry.snapshot().sha256() == null || entry.snapshot().sha256().isBlank()) {
            throw new CorpusIntegrityException("snapshot sha256 is required for " + entry.documentKey());
        }
        if (entry.snapshot().contentType() == null || entry.snapshot().fetchedAt() == null) {
            throw new CorpusIntegrityException("snapshot contentType/fetchedAt is required for " + entry.documentKey());
        }

        Resource resource = resourceLoader.getResource(CORPUS_ROOT + path);
        byte[] bytes;
        try (var input = resource.getInputStream()) {
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new CorpusIntegrityException("cannot read snapshot " + path, exception);
        }

        String actualSha = LegalText.sha256Hex(bytes);
        if (!actualSha.equalsIgnoreCase(entry.snapshot().sha256())) {
            throw new CorpusIntegrityException("snapshot hash mismatch for " + entry.documentKey()
                    + ": manifest " + entry.snapshot().sha256() + ", actual " + actualSha);
        }
        if (entry.snapshot().byteSize() != null && entry.snapshot().byteSize() != bytes.length) {
            throw new CorpusIntegrityException("snapshot byte size mismatch for " + entry.documentKey());
        }
        return new SnapshotPayload(decodeUtf8(bytes), actualSha, bytes.length, CORPUS_ROOT + path);
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new CorpusIntegrityException("snapshot is not valid UTF-8", exception);
        }
    }

    private UUID persistSnapshot(CorpusManifest.DocumentEntry entry,
                                 SnapshotPayload payload,
                                 Counters counters) {
        SourceSnapshot snapshot = snapshots
                .findBySourceUrlAndContentSha256AndParserVersion(
                        entry.sourceUrl(), payload.sha256(), LegalStructureParser.PARSER_VERSION)
                .orElse(null);
        if (snapshot == null) {
            snapshot = snapshots.save(new SourceSnapshot(
                    entry.sourceUrl(),
                    payload.content(),
                    payload.storagePath(),
                    payload.sha256(),
                    entry.snapshot().fetchedAt().toInstant(),
                    entry.snapshot().contentType(),
                    LegalStructureParser.PARSER_VERSION,
                    payload.byteSize()));
            counters.snapshotsCreated++;
        } else {
            counters.snapshotsReused++;
        }
        return snapshot.getId();
    }

    private PersistedDocument persistDocument(CorpusManifest.DocumentEntry entry,
                                              UUID snapshotId,
                                              LegalStructureParser.ParsedDocument parsed,
                                              Counters counters) {
        SourceSnapshot snapshot = snapshots.findById(snapshotId)
                .orElseThrow(() -> new IllegalStateException("source snapshot not found: " + snapshotId));

        LegalDocument document = documents
                .findByDocumentKeyAndSnapshotId(entry.documentKey(), snapshot.getId())
                .orElse(null);
        if (document == null) {
            document = new LegalDocument(entry.documentKey(), entry.title(), entry.sourceUrl(), snapshot);
            counters.documentsCreated++;
        } else {
            counters.documentsReused++;
        }
        document.setDocNumber(entry.docNumber());
        document.setIssuingBody(entry.issuingBody());
        document.setIssueDate(entry.issueDate());
        document.setEffectiveFrom(entry.effectiveFrom());
        document.setEffectiveTo(entry.effectiveTo());
        document.setEffectiveStatus(parseEffectiveStatus(entry.effectiveStatus()));
        CorpusManifest.VerificationEntry verification = entry.verification();
        document.setMetadataVerified(verification != null && verification.metadataVerified());
        document.setEffectiveDatesVerified(verification != null && verification.effectiveDatesVerified());
        document.setSourceCheckedBy(verification == null ? null : verification.sourceCheckedBy());
        document.setSourceCheckedAt(verification == null || verification.sourceCheckedAt() == null
                ? null : verification.sourceCheckedAt().toInstant());

        LegalDocument savedDocument = documents.save(document);

        Map<String, LegalArticle> byCode = new HashMap<>();
        for (LegalStructureParser.ParsedUnit unit : parsed.units()) {
            LegalArticle article = articles
                    .findByDocumentIdAndArticleCode(savedDocument.getId(), unit.code())
                    .orElse(null);
            if (article == null) {
                LegalArticle parent = unit.parentCode() == null ? null : byCode.get(unit.parentCode());
                if (unit.parentCode() != null && parent == null) {
                    throw new CorpusIntegrityException("missing parent " + unit.parentCode()
                            + " for " + unit.code());
                }
                article = new LegalArticle(
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
                counters.articlesCreated++;
            } else {
                if (!LegalText.normalizeWhitespace(article.getRawText())
                        .equals(LegalText.normalizeWhitespace(unit.rawText()))) {
                    throw new CorpusIntegrityException("stored article differs from snapshot for " + unit.code());
                }
                article.setScope(unit.scope());
                article.setEffectiveFrom(unit.effectiveFrom());
                article.setEffectiveTo(unit.effectiveTo());
                articles.save(article);
                counters.articlesReused++;
            }
            byCode.put(unit.code(), article);
        }

        List<LegalArticle> storedArticles = articles.findByDocumentIdOrderByArticleCodeAsc(savedDocument.getId());
        for (LegalArticle stored : storedArticles) {
            if (!byCode.containsKey(stored.getArticleCode())) {
                throw new CorpusIntegrityException("stored article is missing from parser output: "
                        + stored.getArticleCode());
            }
        }

        return new PersistedDocument(savedDocument.getId(), storedArticles.size());
    }

    private static final int EMBEDDING_BATCH_SIZE = 50;

    private void generateEmbeddings(UUID documentId, Counters counters) {
        LegalDocument document = documents.findById(documentId).orElseThrow();
        List<LegalArticle> readyArticles = articles
                .findByDocumentIdAndVerificationStatus(documentId, VerificationStatus.READY);
        List<PendingEmbedding> pending = new ArrayList<>();
        for (LegalArticle article : readyArticles) {
            String header = embeddingHeader(document, article);
            List<String> chunks = RetrievalChunker.chunk(header, article.getSearchText());
            for (int index = 0; index < chunks.size(); index++) {
                String text = chunks.get(index);
                String textHash = LegalText.sha256Hex(text);
                boolean exists = embeddings.existsByIdentity(
                        article.getId(),
                        geminiProperties.embeddingModelId(),
                        embeddingClient.spaceId(),
                        index,
                        textHash);
                if (exists) {
                    counters.embeddingsReused++;
                } else {
                    pending.add(new PendingEmbedding(article.getId(), article.getArticleCode(), index, textHash, text));
                }
            }
        }

        for (int start = 0; start < pending.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(pending.size(), start + EMBEDDING_BATCH_SIZE);
            List<PendingEmbedding> batch = pending.subList(start, end);
            List<String> texts = batch.stream().map(PendingEmbedding::text).toList();
            List<EmbeddingClient.Embedding> vectors = embeddingClient.embedBatch(
                    texts, EmbeddingClient.EmbeddingTaskType.RETRIEVAL_DOCUMENT);
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("embedding batch size mismatch: expected "
                        + batch.size() + ", got " + vectors.size());
            }
            for (int offset = 0; offset < batch.size(); offset++) {
                PendingEmbedding item = batch.get(offset);
                EmbeddingClient.Embedding embedding = vectors.get(offset);
                if (embedding.dimensions() != geminiProperties.embeddingDimensions()) {
                    throw new CorpusIntegrityException("embedding dimension mismatch for " + item.articleCode()
                            + ": expected " + geminiProperties.embeddingDimensions()
                            + ", got " + embedding.dimensions());
                }
                embeddings.insert(
                        item.articleId(),
                        item.chunkIndex(),
                        geminiProperties.embeddingModelId(),
                        embedding.spaceId(),
                        embedding.dimensions(),
                        item.textHash(),
                        embedding.values(),
                        EmbeddingStatus.READY);
                counters.embeddingsGenerated++;
            }
        }
    }

    private void persistRelations(CorpusManifest manifest,
                                  Map<String, UUID> documentIds,
                                  Counters counters) {
        for (CorpusManifest.RelationEntry entry : manifest.relations()) {
            UUID sourceId = requireDocumentId(documentIds, entry.sourceDocumentKey());
            UUID targetId = requireDocumentId(documentIds, entry.targetDocumentKey());
            LegalDocument source = documents.findById(sourceId).orElseThrow();
            LegalDocument target = documents.findById(targetId).orElseThrow();
            LegalRelationType relationType;
            try {
                relationType = LegalRelationType.valueOf(entry.relationType());
            } catch (IllegalArgumentException exception) {
                throw new CorpusIntegrityException("unsupported relation type: " + entry.relationType());
            }
            LegalArticle sourceArticle = entry.sourceArticleCode() == null ? null
                    : articles.findByDocumentIdAndArticleCode(sourceId, entry.sourceArticleCode())
                    .orElseThrow(() -> new CorpusIntegrityException(
                            "relation source article not found: " + entry.sourceArticleCode()));
            LegalArticle targetArticle = entry.targetArticleCode() == null ? null
                    : articles.findByDocumentIdAndArticleCode(targetId, entry.targetArticleCode())
                    .orElseThrow(() -> new CorpusIntegrityException(
                            "relation target article not found: " + entry.targetArticleCode()));

            VerificationStatus relationStatus = entry.verified()
                    && source.getVerificationStatus() == VerificationStatus.READY
                    && target.getVerificationStatus() == VerificationStatus.READY
                    ? VerificationStatus.READY
                    : VerificationStatus.QUARANTINED;

            LegalDocumentRelation relation = relations.findBySourceDocumentId(sourceId).stream()
                    .filter(candidate -> candidate.getRelationType() == relationType)
                    .filter(candidate -> Objects.equals(
                            candidate.getTargetDocument().getId(), targetId))
                    .filter(candidate -> Objects.equals(
                            candidate.getSourceArticle() == null ? null : candidate.getSourceArticle().getId(),
                            sourceArticle == null ? null : sourceArticle.getId()))
                    .filter(candidate -> Objects.equals(
                            candidate.getTargetArticle() == null ? null : candidate.getTargetArticle().getId(),
                            targetArticle == null ? null : targetArticle.getId()))
                    .findFirst()
                    .orElse(null);

            if (relation == null) {
                relation = new LegalDocumentRelation(source, target, sourceArticle, targetArticle, relationType);
                counters.relationsCreated++;
            } else {
                counters.relationsReused++;
            }
            relation.setVerificationStatus(relationStatus);
            relation.setNotes(entry.notes());
            relations.save(relation);
        }
    }

    private static UUID requireDocumentId(Map<String, UUID> documentIds, String documentKey) {
        UUID id = documentIds.get(documentKey);
        if (id == null) {
            throw new CorpusIntegrityException("relation references unknown document: " + documentKey);
        }
        return id;
    }

    private static String embeddingHeader(LegalDocument document, LegalArticle article) {
        StringBuilder header = new StringBuilder(document.getTitle());
        if (document.getDocNumber() != null && !document.getDocNumber().isBlank()) {
            header.append(" (").append(document.getDocNumber()).append(')');
        }
        header.append('\n').append(article.getLocator());
        if (article.getEffectiveFrom() != null) {
            header.append("\nHiệu lực từ: ").append(article.getEffectiveFrom());
        }
        return header.toString();
    }

    private static EffectiveStatus parseEffectiveStatus(String value) {
        if (value == null || value.isBlank()) {
            return EffectiveStatus.UNKNOWN;
        }
        try {
            return EffectiveStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new CorpusIntegrityException("unknown effective status: " + value);
        }
    }

    private static LocalDates effectiveDates(CorpusManifest.DocumentEntry entry) {
        return new LocalDates(entry.effectiveFrom(), entry.effectiveTo());
    }

    private record SnapshotPayload(String content, String sha256, long byteSize, String storagePath) {
    }

    private record PersistedDocument(UUID documentId, int articleCount) {
    }

    private record PendingEmbedding(UUID articleId, String articleCode, int chunkIndex, String textHash, String text) {
    }

    private record LocalDates(java.time.LocalDate from, java.time.LocalDate to) {
    }

    private static final class Counters {
        private int snapshotsCreated;
        private int snapshotsReused;
        private int documentsCreated;
        private int documentsReused;
        private int articlesCreated;
        private int articlesReused;
        private int relationsCreated;
        private int relationsReused;
        private int embeddingsGenerated;
        private int embeddingsReused;
    }
}
