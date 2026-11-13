package vn.edu.hsf302.lawrag.legaldata.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.legaldata.entity.EffectiveStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalHierarchyLevel;
import vn.edu.hsf302.lawrag.legaldata.dto.PublicationDecision;
import vn.edu.hsf302.lawrag.legaldata.entity.SourceSnapshot;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;

/**
 * Enforces the source policy gate QUARANTINED -> READY. A document is published
 * only when its snapshot is intact, all required metadata was explicitly
 * verified, every article is a verbatim substring of the snapshot, and the
 * article hierarchy is valid. Unknown fields are never inferred.
 */
@Service
public class LegalPublicationService {

    private final LegalDocumentRepository documents;
    private final LegalArticleRepository articles;
    private final Clock clock;

    public LegalPublicationService(LegalDocumentRepository documents,
                                   LegalArticleRepository articles,
                                   Clock clock) {
        this.documents = documents;
        this.articles = articles;
        this.clock = clock;
    }

    @Transactional
    public PublicationDecision publishIfEligible(UUID documentId) {
        LegalDocument document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("legal document not found: " + documentId));
        List<LegalArticle> articleRows = articles.findByDocumentIdOrderByArticleCodeAsc(documentId);
        PublicationDecision decision = evaluate(document, articleRows);

        if (decision.ready()) {
            document.setVerificationStatus(VerificationStatus.READY);
            document.setVerificationNotes(null);
            if (document.getPublishedAt() == null) {
                document.setPublishedAt(clock.instant());
            }
            Instant checkedAt = document.getSourceCheckedAt();
            if (checkedAt != null
                    && (document.getLastVerifiedAt() == null
                    || checkedAt.isAfter(document.getLastVerifiedAt()))) {
                document.setLastVerifiedAt(checkedAt);
            }
            articleRows.forEach(article -> article.setVerificationStatus(VerificationStatus.READY));
        } else {
            if (document.getVerificationStatus() == VerificationStatus.READY
                    || document.getVerificationStatus() == VerificationStatus.QUARANTINED) {
                document.setVerificationStatus(VerificationStatus.QUARANTINED);
            }
            document.setVerificationNotes(decision.reasons());
            articleRows.stream()
                    .filter(article -> article.getVerificationStatus() == VerificationStatus.READY)
                    .forEach(article -> article.setVerificationStatus(VerificationStatus.QUARANTINED));
        }

        documents.save(document);
        articles.saveAll(articleRows);
        return decision;
    }

    @Transactional(readOnly = true)
    public PublicationDecision evaluate(UUID documentId) {
        LegalDocument document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("legal document not found: " + documentId));
        return evaluate(document, articles.findByDocumentIdOrderByArticleCodeAsc(documentId));
    }

    private PublicationDecision evaluate(LegalDocument document, List<LegalArticle> articleRows) {
        List<String> failures = new ArrayList<>();

        SourceSnapshot snapshot = document.getSnapshot();
        if (snapshot == null) {
            failures.add("missing source snapshot");
        } else {
            verifySnapshot(document, snapshot, failures);
        }

        if (!document.isMetadataVerified()) {
            failures.add("metadata not verified");
        }
        requireText(document.getSourceCheckedBy(), "source check author missing", failures);
        if (document.getSourceCheckedAt() == null) {
            failures.add("source check timestamp missing");
        }
        requireText(document.getDocNumber(), "missing doc number", failures);
        requireText(document.getIssuingBody(), "missing issuing body", failures);
        if (document.getIssueDate() == null) {
            failures.add("missing issue date");
        }
        if (!document.isEffectiveDatesVerified()) {
            failures.add("effective dates not verified");
        }
        if (document.getEffectiveFrom() == null) {
            failures.add("missing effective-from date");
        }
        if (document.getEffectiveStatus() == null || document.getEffectiveStatus() == EffectiveStatus.UNKNOWN) {
            failures.add("effective status is UNKNOWN");
        }

        if (articleRows.isEmpty()) {
            failures.add("no article structure parsed");
        }
        boolean hasArticle = articleRows.stream()
                .anyMatch(article -> article.getHierarchyLevel() == LegalHierarchyLevel.DIEU);
        if (!articleRows.isEmpty() && !hasArticle) {
            failures.add("no Dieu unit parsed");
        }

        for (LegalArticle article : articleRows) {
            String code = article.getArticleCode();
            if (article.getRawText() == null || article.getRawText().isBlank()) {
                failures.add(code + ": empty raw text");
            } else if (snapshot != null && snapshot.getContent() != null
                    && !LegalText.matchesVerbatim(snapshot.getContent(), article.getRawText())) {
                failures.add(code + ": verbatim text does not match snapshot");
            }
            verifyHierarchy(article, failures);
            if (document.isEffectiveDatesVerified() && article.getEffectiveFrom() == null) {
                failures.add(code + ": effective date not verified");
            }
        }

        return failures.isEmpty()
                ? PublicationDecision.publishable()
                : PublicationDecision.quarantine(failures);
    }

    private void verifySnapshot(LegalDocument document, SourceSnapshot snapshot, List<String> failures) {
        requireText(document.getSourceUrl(), "missing source URL", failures);
        requireText(snapshot.getContentSha256(), "missing snapshot SHA-256", failures);
        requireText(snapshot.getContentType(), "missing snapshot content type", failures);
        requireText(snapshot.getParserVersion(), "missing snapshot parser version", failures);
        if (snapshot.getFetchedAt() == null) {
            failures.add("missing snapshot fetched_at");
        }
        if (snapshot.getContent() == null || snapshot.getContent().isBlank()) {
            failures.add("snapshot has no inline content; cannot verify verbatim text");
            return;
        }
        String actual = LegalText.sha256Hex(snapshot.getContent());
        if (!actual.equalsIgnoreCase(snapshot.getContentSha256())) {
            failures.add("snapshot SHA-256 does not match stored content");
        }
    }

    private void verifyHierarchy(LegalArticle article, List<String> failures) {
        LegalArticle parent = article.getParent();
        if (parent != null && !parent.getDocument().getId().equals(article.getDocument().getId())) {
            failures.add(article.getArticleCode() + ": parent belongs to a different document");
        }
        switch (article.getHierarchyLevel()) {
            case DIEU -> {
                if (parent != null) {
                    failures.add(article.getArticleCode() + ": Dieu must not have a parent");
                }
            }
            case KHOAN -> {
                if (parent == null || parent.getHierarchyLevel() != LegalHierarchyLevel.DIEU) {
                    failures.add(article.getArticleCode() + ": Khoan must belong to a Dieu");
                }
            }
            case DIEM -> {
                if (parent == null || parent.getHierarchyLevel() != LegalHierarchyLevel.KHOAN) {
                    failures.add(article.getArticleCode() + ": Diem must belong to a Khoan");
                }
            }
            default -> failures.add(article.getArticleCode() + ": unknown hierarchy level");
        }
    }

    private void requireText(String value, String message, List<String> failures) {
        if (value == null || value.isBlank()) {
            failures.add(message);
        }
    }
}
