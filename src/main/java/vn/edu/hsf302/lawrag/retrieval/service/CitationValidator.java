package vn.edu.hsf302.lawrag.retrieval.service;

import org.springframework.stereotype.Service;
import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalCandidate;
import vn.edu.hsf302.lawrag.retrieval.repository.RetrievalRepository;
import vn.edu.hsf302.lawrag.retrieval.dto.ValidatedCitation;

/**
 * Post-model gate. An ANSWER is accepted only when every selected ID is in the
 * retrieved batch, belongs to a READY document/article with a READY embedding
 * in the active space, is effective on the applicable date, and still matches
 * the immutable snapshot verbatim. Any failure throws and no COMPLETED state is
 * persisted for that answer.
 */
@Service
public class CitationValidator {

    private final RetrievalRepository repository;
    private final EmbeddingClient embeddingClient;

    public CitationValidator(RetrievalRepository repository, EmbeddingClient embeddingClient) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
    }

    public List<ValidatedCitation> validate(GroundingClient.Decision decision,
                                            QueryAnalysis analysis,
                                            List<RetrievalCandidate> retrievedContext) {
        validateDecisionContract(decision);
        if (decision.outcome() != GroundingClient.Outcome.ANSWER) {
            return List.of();
        }

        LinkedHashSet<UUID> selected = new LinkedHashSet<>();
        for (String rawId : decision.selectedArticleIds()) {
            if (rawId == null || rawId.isBlank()) {
                throw new CitationValidationException("selectedArticleIds contains a blank id");
            }
            UUID articleId;
            try {
                articleId = UUID.fromString(rawId.strip());
            } catch (IllegalArgumentException exception) {
                throw new CitationValidationException("selectedArticleIds contains a non-UUID id");
            }
            selected.add(articleId);
        }
        if (selected.isEmpty()) {
            throw new CitationValidationException("an ANSWER decision must select at least one article");
        }

        LinkedHashSet<UUID> batchIds = retrievedContext.stream()
                .map(RetrievalCandidate::articleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!batchIds.containsAll(selected)) {
            throw new CitationValidationException("selectedArticleIds contains an article outside the context batch");
        }

        List<ValidatedCitation> validated = new ArrayList<>(selected.size());
        for (UUID articleId : selected) {
            RetrievalRepository.CitationRow row = repository
                    .findCitationRow(articleId, embeddingClient.spaceId())
                    .orElseThrow(() -> new CitationValidationException(
                            "selected article does not exist: " + articleId));
            validateRow(row, analysis.applicableDate());
            validated.add(new ValidatedCitation(
                    row.articleId(),
                    row.documentVersionId(),
                    row.snapshotId(),
                    row.documentTitle(),
                    row.documentNumber(),
                    row.articleCode(),
                    row.locator(),
                    row.rawText(),
                    row.snapshotContent(),
                    row.effectiveFrom(),
                    row.effectiveTo()));
        }
        return List.copyOf(validated);
    }

    private static void validateDecisionContract(GroundingClient.Decision decision) {
        if (decision == null) {
            throw new CitationValidationException("grading decision is missing");
        }
        if (decision.outcome() == null || decision.reasonCode() == null) {
            throw new CitationValidationException("grading decision is incomplete");
        }
        switch (decision.outcome()) {
            case ANSWER -> {
                if (decision.reasonCode() != GroundingClient.ReasonCode.SUFFICIENT) {
                    throw new CitationValidationException("ANSWER must use reasonCode SUFFICIENT");
                }
            }
            case NEED_WEB_SEARCH -> {
                if (decision.reasonCode() == GroundingClient.ReasonCode.SUFFICIENT
                        || decision.reasonCode() == GroundingClient.ReasonCode.MISSING_FACTS) {
                    throw new CitationValidationException("NEED_WEB_SEARCH has an inconsistent reasonCode");
                }
            }
            case NEED_CLARIFICATION -> {
                if (decision.reasonCode() != GroundingClient.ReasonCode.MISSING_FACTS) {
                    throw new CitationValidationException("NEED_CLARIFICATION must use reasonCode MISSING_FACTS");
                }
            }
        }
    }

    private static void validateRow(RetrievalRepository.CitationRow row, LocalDate applicableDate) {
        if (!"READY".equals(row.articleStatus()) || !"READY".equals(row.documentStatus())) {
            throw new CitationValidationException("selected article/document is not READY");
        }
        if (!row.metadataVerified() || !row.effectiveDatesVerified()) {
            throw new CitationValidationException("selected document metadata/effective dates are not verified");
        }
        if (!row.embeddingReady()) {
            throw new CitationValidationException("selected article has no READY embedding in the active space");
        }
        if (!withinRange(row.documentEffectiveFrom(), row.documentEffectiveTo(), applicableDate)
                || !withinRange(row.effectiveFrom(), row.effectiveTo(), applicableDate)) {
            throw new CitationValidationException("selected article is not effective on the applicable date");
        }
        if (row.rawText() == null || row.rawText().isBlank()) {
            throw new CitationValidationException("selected article has no raw text");
        }
        if (row.snapshotContent() == null || !LegalText.matchesVerbatim(row.snapshotContent(), row.rawText())) {
            throw new CitationValidationException("selected article is not verbatim from the source snapshot");
        }
        if (!row.snapshotContent().contains(row.rawText())) {
            throw new CitationValidationException("selected article has no exact excerpt in the source snapshot");
        }
    }

    private static boolean withinRange(LocalDate from, LocalDate to, LocalDate date) {
        if (from == null || from.isAfter(date)) {
            return false;
        }
        return to == null || !to.isBefore(date);
    }
}
