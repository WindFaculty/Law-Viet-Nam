package vn.edu.hsf302.lawrag.retrieval.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.edu.hsf302.lawrag.legaldata.entity.LegalHierarchyLevel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalCandidate;
import vn.edu.hsf302.lawrag.retrieval.dto.TemporalIssue;
import vn.edu.hsf302.lawrag.retrieval.service.VietnameseText;

/**
 * SQL for the two retrieval branches. Only READY documents/articles with a
 * READY embedding in the active vector space are visible, and both the
 * document and article effective ranges must contain the applicable date.
 */
@Repository
public class RetrievalRepository {

    private static final String EFFECTIVE_SCOPE = """
            CASE
                WHEN parent_art.id IS NULL OR coalesce(parent_art.title, '') = '' THEN a.scope
                WHEN coalesce(a.scope, '') = '' THEN parent_art.locator || '. ' || parent_art.title
                ELSE a.scope || ' — ' || parent_art.locator || '. ' || parent_art.title
            END""";

    private static final String PARENT_ARTICLE_JOIN = """
            LEFT JOIN legal_articles parent_art
              ON parent_art.document_id = a.document_id
             AND parent_art.hierarchy_level = 'DIEU'
             AND parent_art.article_code = split_part(a.article_code, '-khoan-', 1)
             AND a.hierarchy_level <> 'DIEU'""";

    private static final String CANDIDATE_COLUMNS = """
            a.id AS article_id,
            d.id AS document_id,
            d.snapshot_id,
            d.document_key,
            d.doc_number,
            d.title AS document_title,
            a.article_code,
            a.article_number,
            a.title,
            a.hierarchy_level,
            a.raw_text,
            a.search_text,
            a.locator,
            """ + EFFECTIVE_SCOPE + """
             AS scope,
            a.effective_from,
            a.effective_to
            """;

    private static final String ELIGIBILITY = """
            a.verification_status = 'READY'
              AND d.verification_status = 'READY'
              AND d.metadata_verified
              AND d.effective_dates_verified
              AND d.effective_from <= ?
              AND (d.effective_to IS NULL OR d.effective_to >= ?)
              AND a.effective_from IS NOT NULL
              AND a.effective_from <= ?
              AND (a.effective_to IS NULL OR a.effective_to >= ?)
              AND EXISTS (
                  SELECT 1 FROM article_embeddings e
                  WHERE e.article_id = a.id
                    AND e.status = 'READY'
                    AND e.embedding_space_id = ?
              )
            """;

    private static final RowMapper<RetrievalCandidate> CANDIDATE_MAPPER = (resultSet, rowNumber) ->
            new RetrievalCandidate(
                    resultSet.getObject("article_id", UUID.class),
                    resultSet.getObject("document_id", UUID.class),
                    resultSet.getObject("snapshot_id", UUID.class),
                    resultSet.getString("document_key"),
                    resultSet.getString("doc_number"),
                    resultSet.getString("document_title"),
                    resultSet.getString("article_code"),
                    resultSet.getString("article_number"),
                    resultSet.getString("title"),
                    LegalHierarchyLevel.valueOf(resultSet.getString("hierarchy_level")),
                    resultSet.getString("raw_text"),
                    resultSet.getString("search_text"),
                    resultSet.getString("locator"),
                    resultSet.getString("scope"),
                    resultSet.getObject("effective_from", LocalDate.class),
                    resultSet.getObject("effective_to", LocalDate.class),
                    0, 0, false, false, 0, 0, 0);

    private final JdbcTemplate jdbcTemplate;

    public RetrievalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RetrievalCandidate> lexicalCandidates(QueryAnalysis analysis, int limit, String embeddingSpaceId) {
        String lexicalText = (analysis.normalizedQuestion() + " " + analysis.searchQuery())
                .replace("quy định pháp luật giao thông đường bộ", " ")
                .strip();
        boolean overviewBoost = !analysis.documentNumbers().isEmpty()
                && analysis.articleCodes().isEmpty()
                && analysis.topics().isEmpty();

        String diaQuery = VietnameseText.tsQuery(lexicalText, false);
        String normQuery = VietnameseText.tsQuery(lexicalText, true);
        String diaPhraseQuery = VietnameseText.phraseTsQuery(analysis.subjects(), analysis.topics(), false);
        String normPhraseQuery = VietnameseText.phraseTsQuery(analysis.subjects(), analysis.topics(), true);

        if (diaQuery.equals(normQuery)) {
            return queryLexicalBranch(
                    analysis, normQuery, normPhraseQuery, true, overviewBoost, limit, embeddingSpaceId);
        }

        List<RetrievalCandidate> diacritic = queryLexicalBranch(
                analysis, diaQuery, diaPhraseQuery, false, overviewBoost, limit, embeddingSpaceId);
        if (diacritic.size() >= Math.min(5, limit)) {
            return diacritic;
        }

        List<RetrievalCandidate> normalized = queryLexicalBranch(
                analysis, normQuery, normPhraseQuery, true, overviewBoost, limit, embeddingSpaceId);
        return mergeLexicalBranches(diacritic, normalized, limit);
    }

    private List<RetrievalCandidate> queryLexicalBranch(QueryAnalysis analysis,
                                                        String tsQuery,
                                                        String phraseQuery,
                                                        boolean normalizedBranch,
                                                        boolean overviewBoost,
                                                        int limit,
                                                        String embeddingSpaceId) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(tsQuery);
        arguments.add(phraseQuery);

        String locatorPredicate = inPredicate("a.article_code", analysis.articleCodes(), arguments);
        String documentPredicate = inPredicate(
                "translate(upper(replace(coalesce(d.doc_number, ''), ' ', '')), 'Đ', 'D')",
                analysis.documentNumbers().stream().map(VietnameseText::docNumberKey).toList(),
                arguments);
        if (normalizedBranch) {
            arguments.add(VietnameseText.SQL_ACCENTED_LOWER);
            arguments.add(VietnameseText.SQL_BASE_LOWER);
        }
        addEligibilityDates(arguments, analysis.applicableDate());
        arguments.add(embeddingSpaceId);
        arguments.add(overviewBoost);
        arguments.add(limit);

        String vectorExpr = normalizedBranch
                ? "to_tsvector('simple', translate(lower(coalesce(%s, '') || ' ' || coalesce(a.title, '') || ' ' || a.search_text), ?, ?))"
                        .formatted(EFFECTIVE_SCOPE)
                : "(a.search_vector || to_tsvector('simple', coalesce(%s, '')))".formatted(EFFECTIVE_SCOPE);

        String filterExpr = normalizedBranch
                ? "locator_match OR doc_match OR sv @@ tsq"
                : "locator_match OR doc_match OR a_search_match OR scope_match";

        String extraSelect = normalizedBranch
                ? ""
                : ", (a.search_vector @@ q.tsq) AS a_search_match, (to_tsvector('simple', coalesce(%s, '')) @@ q.tsq) AS scope_match"
                        .formatted(EFFECTIVE_SCOPE);

        String sql = """
                WITH queries AS (
                    SELECT to_tsquery('simple', ?) AS tsq,
                           to_tsquery('simple', ?) AS pq
                ),
                base AS MATERIALIZED (
                    SELECT %s,
                           %s AS locator_match,
                           %s AS doc_match,
                           %s AS sv,
                           q.tsq,
                           q.pq
                           %s
                    FROM legal_articles a
                    JOIN legal_documents d ON d.id = a.document_id
                    %s
                    CROSS JOIN queries q
                    WHERE %s
                ),
                filtered AS MATERIALIZED (
                    SELECT * FROM base
                    WHERE %s
                )
                SELECT *,
                       (
                           ts_rank(strip(sv), tsq, 0)
                           + 2.0 * ts_rank(strip(sv), tsq, 1)
                           + 2.0 * ts_rank(sv, pq, 1)
                       ) AS lexical_score
                FROM filtered
                ORDER BY CASE
                             WHEN locator_match THEN 0
                             WHEN doc_match AND ? AND article_code IN ('dieu-1', 'dieu-1-khoan-1') THEN 0
                             WHEN doc_match THEN 1
                             ELSE 2
                         END,
                         lexical_score DESC,
                         article_code
                LIMIT ?
                """.formatted(
                CANDIDATE_COLUMNS, locatorPredicate, documentPredicate,
                vectorExpr, extraSelect, PARENT_ARTICLE_JOIN, ELIGIBILITY, filterExpr);

        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> {
            RetrievalCandidate base = CANDIDATE_MAPPER.mapRow(resultSet, rowNumber);
            double lexicalScore = resultSet.getDouble("lexical_score");
            boolean exact = resultSet.getBoolean("locator_match") || resultSet.getBoolean("doc_match");
            return new RetrievalCandidate(base.articleId(), base.documentVersionId(), base.snapshotId(),
                    base.documentKey(), base.documentNumber(), base.documentTitle(), base.articleCode(),
                    base.articleNumber(), base.title(), base.hierarchyLevel(), base.rawText(),
                    base.searchText(), base.locator(), base.scope(), base.effectiveFrom(), base.effectiveTo(),
                    0, lexicalScore, exact, false, 0, 0, 0);
        }, arguments.toArray());
    }

    private static List<RetrievalCandidate> mergeLexicalBranches(List<RetrievalCandidate> diacritic,
                                                                   List<RetrievalCandidate> normalized,
                                                                   int limit) {
        java.util.LinkedHashMap<UUID, RetrievalCandidate> merged = new java.util.LinkedHashMap<>();
        for (RetrievalCandidate candidate : diacritic) {
            merged.putIfAbsent(candidate.articleId(), candidate);
        }
        for (RetrievalCandidate candidate : normalized) {
            if (merged.size() >= limit) {
                break;
            }
            merged.putIfAbsent(candidate.articleId(), candidate);
        }
        return List.copyOf(merged.values());
    }

    public List<RetrievalCandidate> vectorCandidates(float[] queryVector, QueryAnalysis analysis,
                                                     int limit, String embeddingSpaceId) {
        String vector = toVectorLiteral(queryVector);
        List<Object> arguments = new ArrayList<>();
        arguments.add(vector);
        arguments.add(embeddingSpaceId);
        addEligibilityDates(arguments, analysis.applicableDate());
        arguments.add(embeddingSpaceId);
        arguments.add(vector);
        arguments.add(limit);

        String sql = """
                SELECT * FROM (
                    SELECT DISTINCT ON (a.id) %s,
                           1 - (e.embedding <=> ?::vector) AS cosine_similarity
                    FROM article_embeddings e
                    JOIN legal_articles a ON a.id = e.article_id
                    JOIN legal_documents d ON d.id = a.document_id
                    %s
                    WHERE e.embedding_space_id = ?
                      AND e.status = 'READY'
                      AND %s
                    ORDER BY a.id, e.embedding <=> ?::vector
                ) ranked
                ORDER BY cosine_similarity DESC
                LIMIT ?
                """.formatted(CANDIDATE_COLUMNS, PARENT_ARTICLE_JOIN, ELIGIBILITY);

        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> {
            RetrievalCandidate candidate = CANDIDATE_MAPPER.mapRow(resultSet, rowNumber);
            return candidate.withVector(0, resultSet.getDouble("cosine_similarity"));
        }, arguments.toArray());
    }

    /**
     * Explicitly requested articles that exist and are READY but are outside
     * their effective range on the applicable date.
     */
    public List<TemporalIssue> temporalIssues(QueryAnalysis analysis) {
        if (analysis.articleCodes().isEmpty()) {
            return List.of();
        }
        List<Object> arguments = new ArrayList<>();
        String codePredicate = inPredicate("a.article_code", analysis.articleCodes(), arguments);
        String documentPredicate = analysis.documentNumbers().isEmpty()
                ? "true"
                : inPredicate(
                        "translate(upper(replace(coalesce(d.doc_number, ''), ' ', '')), 'Đ', 'D')",
                        analysis.documentNumbers().stream().map(VietnameseText::docNumberKey).toList(),
                        arguments);
        LocalDate date = analysis.applicableDate();
        arguments.add(date);
        arguments.add(date);
        arguments.add(date);
        arguments.add(date);

        String sql = """
                SELECT a.id AS article_id, a.article_code, a.locator,
                       a.effective_from, a.effective_to,
                       d.effective_from AS document_effective_from,
                       d.effective_to AS document_effective_to
                FROM legal_articles a
                JOIN legal_documents d ON d.id = a.document_id
                WHERE a.verification_status = 'READY'
                  AND d.verification_status = 'READY'
                  AND d.metadata_verified
                  AND d.effective_dates_verified
                  AND %s
                  AND %s
                  AND (
                      a.effective_from IS NULL
                      OR a.effective_from > ?
                      OR (a.effective_to IS NOT NULL AND a.effective_to < ?)
                      OR d.effective_from > ?
                      OR (d.effective_to IS NOT NULL AND d.effective_to < ?)
                  )
                ORDER BY a.article_code
                """.formatted(codePredicate, documentPredicate);

        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> {
            LocalDate articleFrom = resultSet.getObject("effective_from", LocalDate.class);
            LocalDate articleTo = resultSet.getObject("effective_to", LocalDate.class);
            String note;
            if (articleFrom != null && articleFrom.isAfter(date)) {
                note = "Chưa có hiệu lực tại ngày " + date;
            } else if (articleTo != null && articleTo.isBefore(date)) {
                note = "Đã hết hiệu lực tại ngày " + date;
            } else {
                note = "Hiệu lực cấp điều khoản chưa xác định tại ngày " + date;
            }
            return new TemporalIssue(
                    resultSet.getObject("article_id", UUID.class),
                    resultSet.getString("article_code"),
                    resultSet.getString("locator"),
                    articleFrom,
                    articleTo,
                    note);
        }, arguments.toArray());
    }

    public Optional<CitationRow> findCitationRow(UUID articleId, String embeddingSpaceId) {
        String sql = """
                SELECT a.id AS article_id,
                       d.id AS document_id,
                       d.snapshot_id,
                       d.doc_number,
                       d.title AS document_title,
                       a.article_code,
                       a.locator,
                       a.raw_text,
                       s.content AS snapshot_content,
                       a.effective_from,
                       a.effective_to,
                       d.effective_from AS document_effective_from,
                       d.effective_to AS document_effective_to,
                       a.verification_status AS article_status,
                       d.verification_status AS document_status,
                       d.metadata_verified,
                       d.effective_dates_verified,
                       EXISTS (
                           SELECT 1 FROM article_embeddings e
                           WHERE e.article_id = a.id
                             AND e.status = 'READY'
                             AND e.embedding_space_id = ?
                       ) AS embedding_ready
                FROM legal_articles a
                JOIN legal_documents d ON d.id = a.document_id
                JOIN source_snapshots s ON s.id = d.snapshot_id
                WHERE a.id = ?
                """;
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new CitationRow(
                resultSet.getObject("article_id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getObject("snapshot_id", UUID.class),
                resultSet.getString("doc_number"),
                resultSet.getString("document_title"),
                resultSet.getString("article_code"),
                resultSet.getString("locator"),
                resultSet.getString("raw_text"),
                resultSet.getString("snapshot_content"),
                resultSet.getObject("effective_from", LocalDate.class),
                resultSet.getObject("effective_to", LocalDate.class),
                resultSet.getObject("document_effective_from", LocalDate.class),
                resultSet.getObject("document_effective_to", LocalDate.class),
                resultSet.getString("article_status"),
                resultSet.getString("document_status"),
                resultSet.getBoolean("metadata_verified"),
                resultSet.getBoolean("effective_dates_verified"),
                resultSet.getBoolean("embedding_ready")
        ), embeddingSpaceId, articleId).stream().findFirst();
    }

    private static String inPredicate(String expression, List<String> values, List<Object> arguments) {
        if (values == null || values.isEmpty()) {
            return "false";
        }
        String placeholders = String.join(", ", values.stream().map(value -> "?").toList());
        arguments.addAll(values);
        return expression + " IN (" + placeholders + ")";
    }

    private static void addEligibilityDates(List<Object> arguments, LocalDate date) {
        arguments.add(date);
        arguments.add(date);
        arguments.add(date);
        arguments.add(date);
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 12).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    public record CitationRow(
            UUID articleId,
            UUID documentVersionId,
            UUID snapshotId,
            String documentNumber,
            String documentTitle,
            String articleCode,
            String locator,
            String rawText,
            String snapshotContent,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDate documentEffectiveFrom,
            LocalDate documentEffectiveTo,
            String articleStatus,
            String documentStatus,
            boolean metadataVerified,
            boolean effectiveDatesVerified,
            boolean embeddingReady) {
    }
}
