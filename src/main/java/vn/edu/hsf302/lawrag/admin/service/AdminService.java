package vn.edu.hsf302.lawrag.admin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.ingestion.service.ArticleEmbeddingIndexer;
import vn.edu.hsf302.lawrag.legaldata.entity.EffectiveStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.service.LegalPublicationService;
import vn.edu.hsf302.lawrag.legaldata.dto.PublicationDecision;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.edu.hsf302.lawrag.admin.repository.AdminAuditRepository;
import vn.edu.hsf302.lawrag.admin.dto.AdminCandidateView;
import vn.edu.hsf302.lawrag.admin.dto.AdminJobView;
import vn.edu.hsf302.lawrag.admin.dto.ApprovalCommand;
import vn.edu.hsf302.lawrag.admin.dto.Dashboard;
import vn.edu.hsf302.lawrag.admin.dto.QuarantinedDocumentView;

/**
 * Read models for the admin console plus the human approval gate. Approval
 * never infers missing metadata: the operator must supply/confirm it, and the
 * same publication rules as the import pipeline still run.
 */
@Service
public class AdminService {

    private final JdbcTemplate jdbcTemplate;
    private final LegalDocumentRepository documents;
    private final LegalArticleRepository articles;
    private final LegalPublicationService publication;
    private final ArticleEmbeddingIndexer embeddingIndexer;
    private final AdminAuditRepository audit;
    private final Clock clock;

    public AdminService(JdbcTemplate jdbcTemplate,
                        LegalDocumentRepository documents,
                        LegalArticleRepository articles,
                        LegalPublicationService publication,
                        ArticleEmbeddingIndexer embeddingIndexer,
                        AdminAuditRepository audit,
                        Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.documents = documents;
        this.articles = articles;
        this.publication = publication;
        this.embeddingIndexer = embeddingIndexer;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        List<AdminJobView> jobs = jobs();
        return new Dashboard(jobs, quarantinedDocuments(), audit.recent(50));
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
