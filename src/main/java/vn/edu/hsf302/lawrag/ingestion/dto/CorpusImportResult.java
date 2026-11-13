package vn.edu.hsf302.lawrag.ingestion.dto;

import vn.edu.hsf302.lawrag.legaldata.dto.PublicationDecision;
import vn.edu.hsf302.lawrag.legaldata.entity.VerificationStatus;

import java.util.List;

/**
 * Idempotency evidence for one corpus import run.
 */
public record CorpusImportResult(
        String corpusVersion,
        int snapshotsCreated,
        int snapshotsReused,
        int documentsCreated,
        int documentsReused,
        int articlesCreated,
        int articlesReused,
        int relationsCreated,
        int relationsReused,
        int embeddingsGenerated,
        int embeddingsReused,
        List<DocumentResult> documents) {

    public CorpusImportResult {
        documents = List.copyOf(documents);
    }

    public record DocumentResult(
            String documentKey,
            VerificationStatus verificationStatus,
            PublicationDecision decision,
            int articleCount) {
    }
}
