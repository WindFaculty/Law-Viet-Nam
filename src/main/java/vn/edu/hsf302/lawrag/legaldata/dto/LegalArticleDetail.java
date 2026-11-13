package vn.edu.hsf302.lawrag.legaldata.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import vn.edu.hsf302.lawrag.legaldata.entity.EffectiveStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.FreshnessStatus;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalHierarchyLevel;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalRelationType;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;

/**
 * Immutable view model for the internal citation viewer. Everything lazy is
 * materialized inside the service transaction before Thymeleaf renders it.
 */
public record LegalArticleDetail(
        String documentKey,
        String documentTitle,
        String docNumber,
        String issuingBody,
        LocalDate issueDate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        EffectiveStatus effectiveStatus,
        VerificationStatus verificationStatus,
        String sourceUrl,
        String sourceCheckedBy,
        String verificationNotes,
        Instant publishedAt,
        Instant lastVerifiedAt,
        FreshnessStatus freshness,
        String articleCode,
        LegalHierarchyLevel hierarchyLevel,
        VerificationStatus articleVerificationStatus,
        String locator,
        String articleTitle,
        String rawText,
        List<ArticleUnit> ancestors,
        List<ArticleUnit> children,
        SnapshotProvenance snapshot,
        List<DocumentRelationView> relations) {

    public LegalArticleDetail {
        ancestors = List.copyOf(ancestors);
        children = List.copyOf(children);
        relations = List.copyOf(relations);
    }

    public record ArticleUnit(
            String articleCode,
            LegalHierarchyLevel hierarchyLevel,
            String locator,
            String rawText) {
    }

    public record SnapshotProvenance(
            Instant fetchedAt,
            String sha256,
            String contentType,
            String parserVersion,
            String storagePath,
            Long byteSize) {
    }

    public record DocumentRelationView(
            LegalRelationType relationType,
            String direction,
            String otherTitle,
            String otherDocNumber,
            VerificationStatus verificationStatus,
            String notes) {
    }
}
