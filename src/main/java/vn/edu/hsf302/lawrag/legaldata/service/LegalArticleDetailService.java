package vn.edu.hsf302.lawrag.legaldata.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalArticle;
import vn.edu.hsf302.lawrag.legaldata.dto.LegalArticleDetail;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalArticleRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocument;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalDocumentRelation;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRelationRepository;
import vn.edu.hsf302.lawrag.legaldata.repository.LegalDocumentRepository;
import vn.edu.hsf302.lawrag.legaldata.entity.SourceSnapshot;

@Service
public class LegalArticleDetailService {

    private final LegalDocumentRepository documents;
    private final LegalArticleRepository articles;
    private final LegalDocumentRelationRepository relations;
    private final FreshnessPolicy freshnessPolicy;

    public LegalArticleDetailService(LegalDocumentRepository documents,
                                     LegalArticleRepository articles,
                                     LegalDocumentRelationRepository relations,
                                     FreshnessPolicy freshnessPolicy) {
        this.documents = documents;
        this.articles = articles;
        this.relations = relations;
        this.freshnessPolicy = freshnessPolicy;
    }

    @Transactional(readOnly = true)
    public Optional<LegalArticleDetail> find(UUID documentVersionId, UUID articleId) {
        LegalDocument document = documents.findById(documentVersionId).orElse(null);
        if (document == null) {
            return Optional.empty();
        }
        LegalArticle article = articles.findById(articleId).orElse(null);
        if (article == null || !article.getDocument().getId().equals(documentVersionId)) {
            return Optional.empty();
        }

        List<LegalArticleDetail.ArticleUnit> ancestors = ancestors(article);
        List<LegalArticleDetail.ArticleUnit> children = articles
                .findByParentIdOrderByArticleCodeAsc(articleId)
                .stream()
                .map(this::toUnit)
                .toList();

        SourceSnapshot snapshot = document.getSnapshot();
        LegalArticleDetail.SnapshotProvenance provenance = new LegalArticleDetail.SnapshotProvenance(
                snapshot.getFetchedAt(),
                snapshot.getContentSha256(),
                snapshot.getContentType(),
                snapshot.getParserVersion(),
                snapshot.getStoragePath(),
                snapshot.getByteSize());

        return Optional.of(new LegalArticleDetail(
                document.getDocumentKey(),
                document.getTitle(),
                document.getDocNumber(),
                document.getIssuingBody(),
                document.getIssueDate(),
                document.getEffectiveFrom(),
                document.getEffectiveTo(),
                document.getEffectiveStatus(),
                document.getVerificationStatus(),
                document.getSourceUrl(),
                document.getSourceCheckedBy(),
                document.getVerificationNotes(),
                document.getPublishedAt(),
                document.getLastVerifiedAt(),
                freshnessPolicy.status(document.getLastVerifiedAt()),
                article.getArticleCode(),
                article.getHierarchyLevel(),
                article.getVerificationStatus(),
                article.getLocator(),
                article.getTitle(),
                article.getRawText(),
                ancestors,
                children,
                provenance,
                relationViews(document)));
    }

    private List<LegalArticleDetail.ArticleUnit> ancestors(LegalArticle article) {
        List<LegalArticleDetail.ArticleUnit> result = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        LegalArticle parent = article.getParent();
        while (parent != null && seen.add(parent.getId())) {
            result.add(toUnit(parent));
            parent = parent.getParent();
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private LegalArticleDetail.ArticleUnit toUnit(LegalArticle article) {
        return new LegalArticleDetail.ArticleUnit(
                article.getArticleCode(),
                article.getHierarchyLevel(),
                article.getLocator(),
                article.getRawText());
    }

    private List<LegalArticleDetail.DocumentRelationView> relationViews(LegalDocument document) {
        List<LegalArticleDetail.DocumentRelationView> result = new ArrayList<>();
        for (LegalDocumentRelation relation : relations.findBySourceDocumentId(document.getId())) {
            result.add(new LegalArticleDetail.DocumentRelationView(
                    relation.getRelationType(),
                    "OUTGOING",
                    relation.getTargetDocument().getTitle(),
                    relation.getTargetDocument().getDocNumber(),
                    relation.getVerificationStatus(),
                    relation.getNotes()));
        }
        for (LegalDocumentRelation relation : relations.findByTargetDocumentId(document.getId())) {
            result.add(new LegalArticleDetail.DocumentRelationView(
                    relation.getRelationType(),
                    "INCOMING",
                    relation.getSourceDocument().getTitle(),
                    relation.getSourceDocument().getDocNumber(),
                    relation.getVerificationStatus(),
                    relation.getNotes()));
        }
        return result;
    }
}
