package vn.edu.hsf302.lawrag.admin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.hsf302.lawrag.ingestion.service.ArticleEmbeddingIndexer;
import vn.edu.hsf302.lawrag.ingestion.service.LegalStructureParser;
import vn.edu.hsf302.lawrag.ingestion.service.WebContentExtractor;
import vn.edu.hsf302.lawrag.ingestion.service.WebMetadataExtractor;
import vn.edu.hsf302.lawrag.legaldata.entity.EffectiveStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.SourceSnapshot;
import vn.edu.hsf302.lawrag.legaldata.repository.SourceSnapshotRepository;
import vn.edu.hsf302.lawrag.legaldata.service.LegalPublicationService;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;
import vn.edu.hsf302.lawrag.legaldata.dto.PublicationDecision;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;
import vn.edu.hsf302.lawrag.retrieval.service.VietnameseText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import vn.edu.hsf302.lawrag.admin.repository.AdminAuditRepository;
import vn.edu.hsf302.lawrag.admin.dto.AdminCandidateView;
import vn.edu.hsf302.lawrag.admin.dto.AdminJobView;
import vn.edu.hsf302.lawrag.admin.dto.ApprovalCommand;
import vn.edu.hsf302.lawrag.admin.dto.Dashboard;
import vn.edu.hsf302.lawrag.admin.dto.PdfUploadCommand;
import vn.edu.hsf302.lawrag.admin.dto.QuarantinedDocumentView;
import vn.edu.hsf302.lawrag.admin.dto.RagDocumentView;

/**
 * Read models for the admin console plus the human approval gate and RAG PDF
 * corpus management (upload, embedding generation, and deletion). Approval
 * never infers missing metadata: the operator must supply/confirm it, and the
 * same publication rules as the import pipeline still run.
 */
@Service
public class AdminService {

    private static final Path RAG_PDF_DIR = Path.of("data", "rag-pdfs");

    private final JdbcTemplate jdbcTemplate;
    private final LegalDocumentRepository documents;
    private final LegalArticleRepository articles;
    private final SourceSnapshotRepository snapshots;
    private final LegalPublicationService publication;
    private final ArticleEmbeddingIndexer embeddingIndexer;
    private final WebContentExtractor contentExtractor;
    private final WebMetadataExtractor metadataExtractor;
    private final LegalStructureParser parser;
    private final AdminAuditRepository audit;
    private final Clock clock;

    public AdminService(JdbcTemplate jdbcTemplate,
                        LegalDocumentRepository documents,
                        LegalArticleRepository articles,
                        SourceSnapshotRepository snapshots,
                        LegalPublicationService publication,
                        ArticleEmbeddingIndexer embeddingIndexer,
                        WebContentExtractor contentExtractor,
                        WebMetadataExtractor metadataExtractor,
                        LegalStructureParser parser,
                        AdminAuditRepository audit,
                        Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.documents = documents;
        this.articles = articles;
        this.snapshots = snapshots;
        this.publication = publication;
        this.embeddingIndexer = embeddingIndexer;
        this.contentExtractor = contentExtractor;
        this.metadataExtractor = metadataExtractor;
        this.parser = parser;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        List<AdminJobView> jobs = jobs();
        return new Dashboard(jobs, ragDocuments(), quarantinedDocuments(), audit.recent(50));
    }

    /** Lists legal/PDF documents currently registered in the RAG corpus with vector counts. */
    @Transactional(readOnly = true)
    public List<RagDocumentView> ragDocuments() {
        return jdbcTemplate.query("""
                select d.id, d.document_key, d.title, d.doc_number, d.issuing_body,
                       d.issue_date, d.effective_from, d.effective_to,
                       d.effective_status, d.verification_status, d.source_url,
                       d.created_at, d.published_at,
                       s.storage_path, s.content_type, s.byte_size, s.content_sha256,
                       (select count(*) from legal_articles a where a.document_id = d.id) as article_count,
                       (select count(*) from legal_articles a where a.document_id = d.id and a.verification_status = 'READY') as ready_article_count,
                       (select count(*) from article_embeddings e join legal_articles a on a.id = e.article_id where a.document_id = d.id and e.status = 'READY') as embedding_count
                from legal_documents d
                join source_snapshots s on s.id = d.snapshot_id
                order by case when d.verification_status = 'READY' then 0 else 1 end,
                         d.created_at desc
                limit 200
                """,
                (resultSet, rowNumber) -> new RagDocumentView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("document_key"),
                        resultSet.getString("title"),
                        resultSet.getString("doc_number"),
                        resultSet.getString("issuing_body"),
                        resultSet.getObject("issue_date", LocalDate.class),
                        resultSet.getObject("effective_from", LocalDate.class),
                        resultSet.getObject("effective_to", LocalDate.class),
                        resultSet.getString("effective_status"),
                        resultSet.getString("verification_status"),
                        resultSet.getString("source_url"),
                        resultSet.getString("storage_path"),
                        resultSet.getString("content_type"),
                        resultSet.getObject("byte_size", Long.class),
                        resultSet.getString("content_sha256"),
                        resultSet.getInt("article_count"),
                        resultSet.getInt("ready_article_count"),
                        resultSet.getInt("embedding_count"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "published_at")));
    }

    /** Extracts a text-layer PDF, persists document + articles, publishes READY, and optionally embeds vectors. */
    @Transactional
    public UUID uploadPdfDocument(MultipartFile file, PdfUploadCommand command, String actor) {
        if (file == null || file.isEmpty()) {
            throw new AdminApprovalException("Vui lòng chọn tệp PDF để tải lên.");
        }
        String originalName = file.getOriginalFilename();
        String contentType = file.getContentType();
        boolean isPdfName = originalName != null && originalName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        boolean isPdfType = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/pdf");
        if (!isPdfName && !isPdfType) {
            throw new AdminApprovalException("Chỉ hỗ trợ tải lên tệp PDF (.pdf).");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new AdminApprovalException("Không thể đọc nội dung tệp PDF vừa tải lên.");
        }

        String sanitizedName = sanitizePdfFilename(originalName);
        WebContentExtractor.ExtractedWebContent extracted = contentExtractor.extract("application/pdf", bytes);
        if (extracted.errorCode() != null || !hasText(extracted.text())) {
            throw new AdminApprovalException(
                    "Không thể trích xuất văn bản từ tệp PDF (tệp lỗi hoặc PDF scan không có lớp văn bản).");
        }

        WebMetadataExtractor.WebDocumentMetadata extractedMeta = metadataExtractor.extract(extracted.text());
        String baseNameTitle = sanitizedName.replaceFirst("(?i)\\.pdf$", "").replace('_', ' ').strip();
        String title = firstNonBlank(command == null ? null : command.title(), extractedMeta.title(),
                baseNameTitle.isBlank() ? "Tài liệu PDF" : baseNameTitle);
        String docNumber = truncate(firstNonBlank(
                command == null ? null : command.docNumber(),
                extractedMeta.docNumber(),
                "PDF-" + LocalDate.now(clock).toString().replace("-", "")), 100);
        String issuingBody = truncate(firstNonBlank(
                command == null ? null : command.issuingBody(),
                extractedMeta.issuingBody(),
                "Cơ quan ban hành"), 255);

        LocalDate issueDate = resolveDate(command == null ? null : command.issueDate(),
                "ngày ban hành", extractedMeta.issueDate(), LocalDate.now(clock));
        LocalDate effectiveFrom = resolveDate(command == null ? null : command.effectiveFrom(),
                "ngày hiệu lực", extractedMeta.effectiveFrom(), issueDate);
        LocalDate effectiveTo = resolveDate(command == null ? null : command.effectiveTo(),
                "ngày hết hiệu lực", extractedMeta.effectiveTo(), null);
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new AdminApprovalException("Ngày hết hiệu lực không thể trước ngày hiệu lực.");
        }

        EffectiveStatus effectiveStatus = resolveEffectiveStatus(
                command == null ? null : command.effectiveStatus(), effectiveFrom, effectiveTo);
        String sourceUrl = hasText(command == null ? null : command.sourceUrl())
                ? command.sourceUrl().strip()
                : "https://vanban.chinhphu.vn/local-pdf/" + sanitizedName;

        ParsedUploadContent parsedContent = parseWithFallback(extracted.text(), title, effectiveFrom, effectiveTo);
        String finalText = parsedContent.text();
        String sha256 = LegalText.sha256Hex(finalText);
        String storagePath = storePdfOnDisk(sanitizedName, sha256, bytes);

        SourceSnapshot snapshot = snapshots.findBySourceUrlAndContentSha256AndParserVersion(
                        sourceUrl, sha256, LegalStructureParser.PARSER_VERSION)
                .orElseGet(() -> snapshots.save(new SourceSnapshot(
                        sourceUrl,
                        finalText,
                        storagePath,
                        sha256,
                        Instant.now(clock),
                        "application/pdf",
                        LegalStructureParser.PARSER_VERSION,
                        (long) bytes.length)));

        String docKeyPart = VietnameseText.docNumberKey(docNumber).replaceAll("[^A-Z0-9-]+", "-");
        if (docKeyPart.isBlank()) {
            docKeyPart = "doc";
        }
        String documentKey = truncate("pdf:" + docKeyPart.toLowerCase(Locale.ROOT) + "-" + sha256.substring(0, 8), 160);

        LegalDocument document = documents.findByDocumentKeyAndSnapshotId(documentKey, snapshot.getId())
                .orElseGet(() -> new LegalDocument(documentKey, title, sourceUrl, snapshot));
        document.setDocNumber(docNumber);
        document.setIssuingBody(issuingBody);
        document.setIssueDate(issueDate);
        document.setEffectiveFrom(effectiveFrom);
        document.setEffectiveTo(effectiveTo);
        document.setEffectiveStatus(effectiveStatus);
        document.setMetadataVerified(true);
        document.setEffectiveDatesVerified(true);
        document.setSourceCheckedBy(actor == null ? "admin" : truncate(actor, 255));
        document.setSourceCheckedAt(Instant.now(clock));
        document.setVerificationNotes(null);
        LegalDocument savedDocument = documents.save(document);

        if (articles.findByDocumentIdOrderByArticleCodeAsc(savedDocument.getId()).isEmpty()) {
            persistParsedUnits(savedDocument, parsedContent.parsed());
        }

        PublicationDecision decision = publication.publishIfEligible(savedDocument.getId());
        if (!decision.ready()) {
            throw new AdminApprovalException("Không thể xuất bản PDF sang READY: "
                    + String.join("; ", decision.failures()));
        }

        int embeddedChunks = 0;
        if (command == null || command.runEmbedding()) {
            try {
                embeddedChunks = embeddingIndexer.indexReadyArticles(savedDocument.getId());
            } catch (RuntimeException exception) {
                throw new AdminApprovalException(
                        "Không thể tạo vector embedding cho PDF vừa tải lên: " + exception.getMessage());
            }
        }

        audit.record(actor, "UPLOAD_PDF_DOCUMENT", "LEGAL_DOCUMENT", savedDocument.getId(),
                "file=" + sanitizedName + "; docNumber=" + docNumber + "; embeddedChunks=" + embeddedChunks);
        return savedDocument.getId();
    }

    /** Generates missing vector embeddings for all READY articles of one document. */
    @Transactional
    public int runEmbedding(UUID documentId, String actor) {
        LegalDocument document = documents.findById(documentId)
                .orElseThrow(() -> new AdminApprovalException("Không tìm thấy tài liệu PDF."));
        if (document.getVerificationStatus() != VerificationStatus.READY) {
            PublicationDecision decision = publication.publishIfEligible(documentId);
            if (!decision.ready()) {
                throw new AdminApprovalException(
                        "Tài liệu đang ở trạng thái QUARANTINED ("
                                + String.join("; ", decision.failures())
                                + "). Vui lòng duyệt sang READY trước khi chạy embedding.");
            }
        }
        int generated;
        try {
            generated = embeddingIndexer.indexReadyArticles(documentId);
        } catch (RuntimeException exception) {
            throw new AdminApprovalException("Lỗi khi chạy embedding: " + exception.getMessage());
        }
        Integer totalVectors = jdbcTemplate.queryForObject("""
                select count(*)
                from article_embeddings e
                join legal_articles a on a.id = e.article_id
                where a.document_id = ? and e.status = 'READY'
                """, Integer.class, documentId);
        int vectorCount = totalVectors == null ? generated : totalVectors;
        audit.record(actor, "EMBED_LEGAL_DOCUMENT", "LEGAL_DOCUMENT", documentId,
                "docNumber=" + document.getDocNumber() + "; generated=" + generated + "; totalVectors=" + vectorCount);
        return vectorCount;
    }

    /** Generates missing vector embeddings across all READY documents in the RAG corpus. */
    @Transactional
    public int runEmbeddingForAllReady(String actor) {
        List<UUID> readyIds = jdbcTemplate.queryForList(
                "select id from legal_documents where verification_status = 'READY' order by created_at",
                UUID.class);
        int generated = 0;
        for (UUID readyId : readyIds) {
            try {
                generated += embeddingIndexer.indexReadyArticles(readyId);
            } catch (RuntimeException exception) {
                throw new AdminApprovalException("Lỗi khi chạy embedding hàng loạt: " + exception.getMessage());
            }
        }
        Integer totalVectors = jdbcTemplate.queryForObject(
                "select count(*) from article_embeddings where status = 'READY'",
                Integer.class);
        int vectorCount = totalVectors == null ? generated : totalVectors;
        audit.record(actor, "EMBED_ALL_READY_DOCUMENTS", "LEGAL_DOCUMENT", null,
                "documents=" + readyIds.size() + "; generated=" + generated + "; totalVectors=" + vectorCount);
        return vectorCount;
    }

    /** Deletes a RAG document along with its articles, embeddings, relations, citations, and uploaded file. */
    @Transactional
    public void deleteDocument(UUID documentId, String actor) {
        LegalDocument document = documents.findById(documentId)
                .orElseThrow(() -> new AdminApprovalException("Không tìm thấy tài liệu PDF cần xóa."));
        UUID snapshotId = document.getSnapshot().getId();
        String storagePath = document.getSnapshot().getStoragePath();
        String docNumber = document.getDocNumber();
        String title = document.getTitle();

        jdbcTemplate.update("""
                delete from message_citations
                where document_version_id = ?
                   or article_id in (select id from legal_articles where document_id = ?)
                """, documentId, documentId);
        jdbcTemplate.update("""
                delete from legal_document_relations
                where source_document_id = ? or target_document_id = ?
                """, documentId, documentId);
        jdbcTemplate.update("""
                delete from article_embeddings
                where article_id in (select id from legal_articles where document_id = ?)
                """, documentId);
        jdbcTemplate.update("delete from legal_articles where document_id = ?", documentId);
        jdbcTemplate.update("delete from legal_documents where id = ?", documentId);

        Integer remainingRefs = jdbcTemplate.queryForObject("""
                select (select count(*) from legal_documents where snapshot_id = ?)
                     + (select count(*) from message_citations where source_snapshot_id = ?)
                """, Integer.class, snapshotId, snapshotId);
        if (remainingRefs != null && remainingRefs == 0) {
            jdbcTemplate.update("delete from source_snapshots where id = ?", snapshotId);
            deleteStoredPdfIfLocal(storagePath);
        }

        audit.record(actor, "DELETE_LEGAL_DOCUMENT", "LEGAL_DOCUMENT", documentId,
                "docNumber=" + docNumber + "; title=" + title);
    }

    public void approve(UUID documentId, ApprovalCommand command, String actor) {
        LegalDocument document = documents.findById(documentId)
                .orElseThrow(() -> new AdminApprovalException("Không tìm thấy văn bản cần duyệt."));
        if (document.getVerificationStatus() != VerificationStatus.QUARANTINED) {
            throw new AdminApprovalException(
                    "Chỉ có thể duyệt văn bản đang ở trạng thái QUARANTINED.");
        }
        if (command == null) {
            throw new AdminApprovalException("Thiếu dữ liệu duyệt văn bản.");
        }
        if (!hasText(command.reason())) {
            throw new AdminApprovalException("Cần ghi lý do/ghi chú kiểm tra trước khi duyệt.");
        }

        LocalDate previousEffectiveFrom = document.getEffectiveFrom();
        LocalDate previousEffectiveTo = document.getEffectiveTo();
        if (hasText(command.docNumber())) {
            document.setDocNumber(command.docNumber().strip());
        }
        if (hasText(command.issuingBody())) {
            document.setIssuingBody(command.issuingBody().strip());
        }
        if (hasText(command.issueDate())) {
            document.setIssueDate(parseDate(command.issueDate(), "ngày ban hành"));
        }
        if (hasText(command.effectiveFrom())) {
            document.setEffectiveFrom(parseDate(command.effectiveFrom(), "ngày hiệu lực"));
        }
        if (hasText(command.effectiveTo())) {
            document.setEffectiveTo(parseDate(command.effectiveTo(), "ngày hết hiệu lực"));
        }
        if (hasText(command.effectiveStatus())) {
            try {
                document.setEffectiveStatus(EffectiveStatus.valueOf(command.effectiveStatus().strip()));
            } catch (IllegalArgumentException exception) {
                throw new AdminApprovalException("Trạng thái hiệu lực không hợp lệ.");
            }
        }

        validate(document);
        document.setMetadataVerified(true);
        document.setEffectiveDatesVerified(true);
        document.setSourceCheckedBy(actor == null ? "admin" : truncate(actor, 255));
        document.setSourceCheckedAt(Instant.now(clock));
        document.setVerificationNotes(null);
        documents.save(document);

        List<LegalArticle> articleRows = articles.findByDocumentIdOrderByArticleCodeAsc(documentId);
        for (LegalArticle article : articleRows) {
            // Preserve an explicit article-level override; only inherited values
            // follow the document effective range the admin just confirmed.
            if (article.getEffectiveFrom() == null
                    || article.getEffectiveFrom().equals(previousEffectiveFrom)) {
                article.setEffectiveFrom(document.getEffectiveFrom());
            }
            if (article.getEffectiveTo() == null
                    || article.getEffectiveTo().equals(previousEffectiveTo)) {
                article.setEffectiveTo(document.getEffectiveTo());
            }
        }
        articles.saveAll(articleRows);

        PublicationDecision decision = publication.publishIfEligible(documentId);
        if (!decision.ready()) {
            audit.record(actor, "APPROVE_REJECTED", "LEGAL_DOCUMENT", documentId,
                    String.join("; ", decision.failures()));
            throw new AdminApprovalException(
                    "Không thể duyệt: " + String.join("; ", decision.failures()));
        }
        try {
            embeddingIndexer.indexReadyArticles(documentId);
        } catch (RuntimeException exception) {
            LegalDocument failed = documents.findById(documentId).orElseThrow();
            failed.setVerificationStatus(VerificationStatus.QUARANTINED);
            failed.setVerificationNotes("EMBEDDING_FAILED: " + exception.getMessage());
            documents.save(failed);
            List<LegalArticle> failedArticles =
                    articles.findByDocumentIdOrderByArticleCodeAsc(documentId);
            failedArticles.forEach(article -> article.setVerificationStatus(VerificationStatus.QUARANTINED));
            articles.saveAll(failedArticles);
            audit.record(actor, "APPROVE_REJECTED", "LEGAL_DOCUMENT", documentId,
                    "EMBEDDING_FAILED: " + exception.getMessage());
            throw new AdminApprovalException("Không thể tạo embedding cho văn bản đã duyệt.");
        }
        String details = "source=" + document.getSourceUrl()
                + "; reason=" + (command.reason() == null ? "" : command.reason().strip())
                + "; docNumber=" + document.getDocNumber();
        audit.record(actor, "APPROVE_LEGAL_DOCUMENT", "LEGAL_DOCUMENT", documentId, details);
    }

    private void validate(LegalDocument document) {
        List<String> failures = new ArrayList<>();
        if (!hasText(document.getDocNumber())) {
            failures.add("thiếu số hiệu văn bản");
        }
        if (!hasText(document.getIssuingBody())) {
            failures.add("thiếu cơ quan ban hành");
        }
        if (document.getIssueDate() == null) {
            failures.add("thiếu ngày ban hành");
        }
        if (document.getEffectiveFrom() == null) {
            failures.add("thiếu ngày hiệu lực");
        }
        if (document.getEffectiveStatus() == null
                || document.getEffectiveStatus() == EffectiveStatus.UNKNOWN) {
            failures.add("chưa xác định trạng thái hiệu lực");
        }
        if (document.getEffectiveFrom() != null && document.getEffectiveTo() != null
                && document.getEffectiveTo().isBefore(document.getEffectiveFrom())) {
            failures.add("ngày hết hiệu lực trước ngày hiệu lực");
        }
        if (!failures.isEmpty()) {
            throw new AdminApprovalException("Không thể duyệt: " + String.join("; ", failures));
        }
    }

    private List<AdminJobView> jobs() {
        Map<UUID, List<AdminCandidateView>> candidatesByJob = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select job_id, candidate_url, rank, accepted, rejection_reason, result_code
                from web_search_candidates
                order by rank, created_at
                """, resultSet -> {
            UUID jobId = resultSet.getObject("job_id", UUID.class);
            candidatesByJob.computeIfAbsent(jobId, key -> new ArrayList<>()).add(new AdminCandidateView(
                    resultSet.getString("candidate_url"),
                    resultSet.getInt("rank"),
                    resultSet.getBoolean("accepted"),
                    resultSet.getString("rejection_reason"),
                    resultSet.getString("result_code")));
        });

        return jdbcTemplate.query("""
                select j.id, j.message_id, m.session_id, j.job_status, j.stage,
                       j.attempt_count, j.lease_owner, j.lease_expires_at, j.error_code,
                       j.created_at, j.deadline, m.suggested_query
                from web_search_jobs j
                join chat_messages m on m.id = j.message_id
                order by j.created_at desc
                limit 100
                """,
                (resultSet, rowNumber) -> new AdminJobView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getString("job_status"),
                        resultSet.getString("stage"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getString("lease_owner"),
                        instant(resultSet, "lease_expires_at"),
                        resultSet.getString("error_code"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "deadline"),
                        resultSet.getString("suggested_query"),
                        candidatesByJob.getOrDefault(
                                resultSet.getObject("id", UUID.class), List.of())));
    }

    private List<QuarantinedDocumentView> quarantinedDocuments() {
        return jdbcTemplate.query("""
                select d.id, d.title, d.doc_number, d.issuing_body, d.issue_date,
                       d.effective_from, d.effective_to, d.effective_status,
                       d.source_url, d.verification_notes, d.created_at,
                       s.content_sha256,
                       (select count(*) from legal_articles a where a.document_id = d.id) as article_count
                from legal_documents d
                join source_snapshots s on s.id = d.snapshot_id
                where d.verification_status = 'QUARANTINED'
                order by d.created_at desc
                limit 100
                """,
                (resultSet, rowNumber) -> new QuarantinedDocumentView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("doc_number"),
                        resultSet.getString("issuing_body"),
                        resultSet.getObject("issue_date", LocalDate.class),
                        resultSet.getObject("effective_from", LocalDate.class),
                        resultSet.getObject("effective_to", LocalDate.class),
                        resultSet.getString("effective_status"),
                        resultSet.getString("source_url"),
                        resultSet.getString("verification_notes"),
                        instant(resultSet, "created_at"),
                        resultSet.getString("content_sha256"),
                        resultSet.getInt("article_count")));
    }

    private ParsedUploadContent parseWithFallback(String rawText,
                                                String title,
                                                LocalDate effectiveFrom,
                                                LocalDate effectiveTo) {
        String candidate = rawText;
        LegalStructureParser.ParsedDocument parsed = null;
        try {
            parsed = parser.parse(candidate, effectiveFrom, effectiveTo, true, Map.of());
        } catch (IllegalArgumentException exception) {
            throw new AdminApprovalException("Cấu trúc điều khoản trong PDF bị trùng mã: " + exception.getMessage());
        }
        if (parsed.articleCount() == 0) {
            String headerTitle = title.replace('\r', ' ').replace('\n', ' ').strip();
            candidate = "Điều 1. " + headerTitle + "\n" + rawText;
            try {
                parsed = parser.parse(candidate, effectiveFrom, effectiveTo, true, Map.of());
            } catch (IllegalArgumentException exception) {
                throw new AdminApprovalException("Không thể phân tích cấu trúc PDF: " + exception.getMessage());
            }
        }
        return new ParsedUploadContent(candidate, parsed);
    }

    private void persistParsedUnits(LegalDocument savedDocument, LegalStructureParser.ParsedDocument parsed) {
        Map<String, LegalArticle> byCode = new LinkedHashMap<>();
        for (LegalStructureParser.ParsedUnit unit : parsed.units()) {
            LegalArticle parent = unit.parentCode() == null ? null : byCode.get(unit.parentCode());
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
    }

    private LocalDate resolveDate(String provided, String label, LocalDate extracted, LocalDate fallback) {
        if (hasText(provided)) {
            return parseDate(provided, label);
        }
        return extracted != null ? extracted : fallback;
    }

    private EffectiveStatus resolveEffectiveStatus(String provided, LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (hasText(provided)) {
            try {
                return EffectiveStatus.valueOf(provided.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new AdminApprovalException("Trạng thái hiệu lực không hợp lệ.");
            }
        }
        LocalDate today = LocalDate.now(clock);
        if (effectiveTo != null && effectiveTo.isBefore(today)) {
            return EffectiveStatus.EXPIRED;
        }
        if (effectiveFrom != null && effectiveFrom.isAfter(today)) {
            return EffectiveStatus.UPCOMING;
        }
        return EffectiveStatus.EFFECTIVE;
    }

    private static String sanitizePdfFilename(String originalName) {
        String base = hasText(originalName) ? Path.of(originalName).getFileName().toString() : "document.pdf";
        String cleaned = base.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (!cleaned.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            cleaned = cleaned + ".pdf";
        }
        return cleaned;
    }

    private static String storePdfOnDisk(String sanitizedName, String sha256, byte[] bytes) {
        String fileName = sha256.substring(0, 8) + "_" + sanitizedName;
        Path target = RAG_PDF_DIR.resolve(fileName);
        try {
            Files.createDirectories(RAG_PDF_DIR);
            Files.write(target, bytes);
            return "data/rag-pdfs/" + fileName;
        } catch (IOException exception) {
            return sanitizedName;
        }
    }

    private static void deleteStoredPdfIfLocal(String storagePath) {
        if (storagePath == null || !storagePath.startsWith("data/rag-pdfs/")) {
            return;
        }
        String fileName = storagePath.substring("data/rag-pdfs/".length());
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return;
        }
        try {
            Files.deleteIfExists(RAG_PDF_DIR.resolve(fileName));
        } catch (IOException ignored) {
            // Best-effort cleanup of local uploaded file.
        }
    }

    private static String firstNonBlank(String primary, String secondary, String fallback) {
        if (hasText(primary)) {
            return primary.strip();
        }
        if (hasText(secondary)) {
            return secondary.strip();
        }
        return fallback;
    }

    private record ParsedUploadContent(String text, LegalStructureParser.ParsedDocument parsed) {
    }

    private static Instant instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException exception) {
            throw new AdminApprovalException("Định dạng " + field + " phải là yyyy-MM-dd.");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
