package vn.edu.hsf302.lawrag.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Manifest of the controlled seed corpus. It is the single source of truth for
 * snapshot paths, hashes, provenance and manual verification flags.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorpusManifest(
        String schemaVersion,
        String corpusVersion,
        String parserVersion,
        String createdAt,
        List<String> notes,
        List<DocumentEntry> documents,
        List<RelationEntry> relations) {

    public CorpusManifest {
        documents = documents == null ? List.of() : List.copyOf(documents);
        relations = relations == null ? List.of() : List.copyOf(relations);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentEntry(
            String documentKey,
            String title,
            String docNumber,
            String issuingBody,
            LocalDate issueDate,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String effectiveStatus,
            String sourceUrl,
            List<OfficialSourceFile> officialSourceFiles,
            SnapshotEntry snapshot,
            VerificationEntry verification,
            Integer expectedArticleCount,
            Map<String, ArticleEffectiveOverride> articleEffectiveOverrides,
            List<String> notes) {

        public DocumentEntry {
            officialSourceFiles = officialSourceFiles == null ? List.of() : List.copyOf(officialSourceFiles);
            articleEffectiveOverrides = articleEffectiveOverrides == null
                    ? Map.of() : Map.copyOf(articleEffectiveOverrides);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OfficialSourceFile(String url, String sha256) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotEntry(
            String path,
            String contentType,
            OffsetDateTime fetchedAt,
            String sha256,
            Long byteSize,
            String sourceKind,
            String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerificationEntry(
            boolean metadataVerified,
            boolean effectiveDatesVerified,
            String sourceCheckedBy,
            OffsetDateTime sourceCheckedAt,
            String legalReviewStatus,
            String notes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArticleEffectiveOverride(
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceRef) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RelationEntry(
            String sourceDocumentKey,
            String targetDocumentKey,
            String relationType,
            String sourceArticleCode,
            String targetArticleCode,
            boolean verified,
            String notes) {
    }
}
