package vn.edu.hsf302.lawrag.retrieval.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalCandidate;
import vn.edu.hsf302.lawrag.retrieval.dto.TemporalIssue;

/** Persists the reproducibility record for one local RAG attempt. */
@Repository
public class RetrievalTraceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RetrievalTraceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(UUID messageId,
                       String question,
                       LocalDate applicableDate,
                       String modelId,
                       String promptVersion,
                       String corpusVersion,
                       String embeddingSpaceId,
                       boolean gatePassed,
                       double maxCosine,
                       String decision,
                       String reasonCode,
                       List<RetrievalCandidate> candidates,
                       List<String> selectedArticleIds,
                       List<TemporalIssue> temporalIssues) {
        jdbcTemplate.update("""
                insert into retrieval_traces
                    (message_id, question, applicable_date, model_id, prompt_version, corpus_version,
                     embedding_space_id, gate_passed, max_cosine, decision, reason_code, candidate_scores)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                messageId, question, applicableDate, modelId, promptVersion, corpusVersion,
                embeddingSpaceId, gatePassed, maxCosine, decision, reasonCode,
                serialize(candidates, selectedArticleIds, temporalIssues));
    }

    private String serialize(List<RetrievalCandidate> candidates,
                             List<String> selectedArticleIds,
                             List<TemporalIssue> temporalIssues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("selectedArticleIds", selectedArticleIds == null ? List.of() : selectedArticleIds);
        payload.put("candidates", (candidates == null ? List.<RetrievalCandidate>of() : candidates)
                .stream()
                .map(candidate -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("articleId", String.valueOf(candidate.articleId()));
                    row.put("articleCode", candidate.articleCode());
                    row.put("documentNumber", candidate.documentNumber());
                    row.put("locator", candidate.locator());
                    row.put("lexicalRank", candidate.lexicalRank());
                    row.put("lexicalScore", candidate.lexicalScore());
                    row.put("exactMatch", candidate.exactMatch());
                    row.put("strongLexical", candidate.strongLexical());
                    row.put("vectorRank", candidate.vectorRank());
                    row.put("cosineSimilarity", candidate.cosineSimilarity());
                    row.put("rrfScore", candidate.rrfScore());
                    return row;
                })
                .toList());
        payload.put("temporalIssues", (temporalIssues == null ? List.<TemporalIssue>of() : temporalIssues)
                .stream()
                .map(issue -> Map.of(
                        "articleId", String.valueOf(issue.articleId()),
                        "articleCode", issue.articleCode(),
                        "locator", issue.locator(),
                        "note", issue.note()))
                .toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize retrieval trace", exception);
        }
    }
}
