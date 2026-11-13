package vn.edu.hsf302.lawrag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.hsf302.lawrag.gemini.service.EmbeddingClient;
import vn.edu.hsf302.lawrag.ingestion.dto.CorpusImportResult;
import vn.edu.hsf302.lawrag.ingestion.service.CorpusImportService;
import vn.edu.hsf302.lawrag.legaldata.repository.ArticleEmbeddingRepository;
import vn.edu.hsf302.lawrag.legaldata.service.FreshnessPolicy;
import vn.edu.hsf302.lawrag.legaldata.entity.FreshnessStatus;
import vn.edu.hsf302.lawrag.legaldata.service.LegalPublicationService;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;
import vn.edu.hsf302.lawrag.legaldata.dto.PublicationDecision;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G3 evidence against real PostgreSQL + pgvector: traceable snapshots,
 * verbatim raw text, idempotent re-import, text-hash embedding reuse, unknown
 * effective dates staying QUARANTINED, freshness and the citation viewer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class G3AcceptanceTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CorpusImportService corpusImportService;

    @Autowired
    private LegalPublicationService publicationService;

    @Autowired
    private ArticleEmbeddingRepository embeddingRepository;

    @Autowired
    private EmbeddingClient embeddingClient;

    private CorpusImportResult firstImport;

    @BeforeAll
    void seedCorpusOnce() {
        firstImport = corpusImportService.importCorpus();
    }

    @Test
    void readyCorpusHasTraceableSnapshotsMetadataAndVerbatimArticles() {
        List<Map<String, Object>> documents = jdbcTemplate.queryForList("""
                select d.document_key, d.doc_number, d.issuing_body, d.issue_date, d.effective_from,
                       d.effective_status, d.metadata_verified, d.effective_dates_verified, d.last_verified_at,
                       s.content, s.content_sha256, s.fetched_at, s.content_type, s.parser_version
                from legal_documents d
                join source_snapshots s on s.id = d.snapshot_id
                where d.verification_status = 'READY'
                """);

        assertThat(documents).extracting(row -> row.get("document_key"))
                .contains("luat-36-2024-qh15", "luat-23-2008-qh12");
        for (Map<String, Object> row : documents) {
            assertThat((String) row.get("doc_number")).isNotBlank();
            assertThat((String) row.get("issuing_body")).isNotBlank();
            assertThat(row.get("issue_date")).isNotNull();
            assertThat(row.get("effective_from")).isNotNull();
            assertThat(row.get("effective_status")).isNotEqualTo("UNKNOWN");
            assertThat(row.get("metadata_verified")).isEqualTo(Boolean.TRUE);
            assertThat(row.get("effective_dates_verified")).isEqualTo(Boolean.TRUE);
            assertThat(row.get("last_verified_at")).isNotNull();
            assertThat(row.get("fetched_at")).isNotNull();
            assertThat(row.get("content_type")).isNotNull();
            assertThat(row.get("parser_version")).isEqualTo("legal-structure-parser-v1");

            String content = (String) row.get("content");
            assertThat(LegalText.sha256Hex(content)).isEqualTo(row.get("content_sha256"));
        }

        List<Map<String, Object>> articles = jdbcTemplate.queryForList("""
                select a.article_code, a.raw_text, a.effective_from, s.content
                from legal_articles a
                join legal_documents d on d.id = a.document_id
                join source_snapshots s on s.id = d.snapshot_id
                where a.verification_status = 'READY'
                  and d.verification_status = 'READY'
                """);
        assertThat(articles).hasSizeGreaterThanOrEqualTo(36);
        for (Map<String, Object> row : articles) {
            assertThat(LegalText.matchesVerbatim((String) row.get("content"), (String) row.get("raw_text")))
                    .as("verbatim match for %s", row.get("article_code"))
                    .isTrue();
            assertThat(row.get("effective_from")).as("effective date for %s", row.get("article_code")).isNotNull();
        }
    }

    @Test
    void secondImportIsFullyIdempotentAndSkipsUnchangedEmbeddings() {
        Counts before = counts();
        CorpusImportResult second = corpusImportService.importCorpus();
        Counts after = counts();

        assertThat(after).isEqualTo(before);
        assertThat(second.snapshotsCreated()).isZero();
        assertThat(second.documentsCreated()).isZero();
        assertThat(second.articlesCreated()).isZero();
        assertThat(second.relationsCreated()).isZero();
        assertThat(second.embeddingsGenerated()).isZero();
        assertThat(second.embeddingsReused()).isGreaterThan(0);
        assertThat(firstImport.embeddingsGenerated() + firstImport.embeddingsReused()).isGreaterThan(0);
        assertThat(countDuplicateEmbeddingIdentities()).isZero();
    }

    @Test
    void corpusCoversSeedQuestionsAndTemporalCases() {
        assertReadyArticle("luat-36-2024-qh15", "dieu-9-khoan-1");
        assertReadyArticle("luat-36-2024-qh15", "dieu-10-khoan-1");
        assertReadyArticle("luat-36-2024-qh15", "dieu-10-khoan-2");
        assertReadyArticle("luat-36-2024-qh15", "dieu-10-khoan-3");
        assertReadyArticle("luat-36-2024-qh15", "dieu-31-khoan-3");
        assertReadyArticle("luat-36-2024-qh15", "dieu-33-khoan-1-diem-a");
        assertReadyArticle("luat-36-2024-qh15", "dieu-33-khoan-2");
        assertReadyArticle("luat-36-2024-qh15", "dieu-88-khoan-2");
        assertReadyArticle("luat-36-2024-qh15", "dieu-88-khoan-3");
        assertReadyArticle("luat-36-2024-qh15", "dieu-89-khoan-6");
        assertReadyArticle("luat-23-2008-qh12", "dieu-30-khoan-2");

        assertThat(articleEffectiveFrom("luat-36-2024-qh15", "dieu-10-khoan-2"))
                .isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(articleEffectiveFrom("luat-36-2024-qh15", "dieu-10-khoan-3"))
                .isEqualTo(LocalDate.of(2026, 1, 1));

        assertThat(documentStatus("nghi-dinh-168-2024-nd-cp")).isEqualTo("READY");
        assertReadyArticle("nghi-dinh-168-2024-nd-cp", "dieu-1");
        assertReadyArticle("nghi-dinh-168-2024-nd-cp", "dieu-7-khoan-7");
        assertThat(articleEffectiveFrom("nghi-dinh-168-2024-nd-cp", "dieu-7-khoan-7"))
                .isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(articleEffectiveFrom("nghi-dinh-168-2024-nd-cp", "dieu-6-khoan-3-diem-m"))
                .isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(articleCountForDocument("nghi-dinh-168-2024-nd-cp")).isEqualTo(1297);

        assertThat(documentStatus("nghi-dinh-238-2026-nd-cp")).isEqualTo("QUARANTINED");
        assertThat(articleCountForDocument("nghi-dinh-238-2026-nd-cp")).isZero();

        Integer readyReplacements = jdbcTemplate.queryForObject("""
                select count(*)
                from legal_document_relations r
                join legal_documents source_doc on source_doc.id = r.source_document_id
                join legal_documents target_doc on target_doc.id = r.target_document_id
                where r.relation_type = 'REPLACEMENT'
                  and r.verification_status = 'READY'
                  and source_doc.document_key = 'luat-36-2024-qh15'
                  and target_doc.document_key = 'luat-23-2008-qh12'
                """, Integer.class);
        assertThat(readyReplacements).isEqualTo(1);

        Integer quarantinedAmendments = jdbcTemplate.queryForObject("""
                select count(*)
                from legal_document_relations
                where relation_type = 'AMENDMENT' and verification_status = 'QUARANTINED'
                """, Integer.class);
        assertThat(quarantinedAmendments).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unknownEffectiveDatesStayUnverifiedAndCannotBecomeReady() {
        Map<String, Object> document = jdbcTemplate.queryForMap("""
                select id, verification_status, metadata_verified, effective_dates_verified, effective_from
                from legal_documents
                where document_key = 'nghi-dinh-238-2026-nd-cp'
                """);
        UUID documentId = (UUID) document.get("id");
        assertThat(document.get("verification_status")).isEqualTo("QUARANTINED");
        assertThat(document.get("metadata_verified")).isEqualTo(Boolean.FALSE);
        assertThat(document.get("effective_dates_verified")).isEqualTo(Boolean.FALSE);
        assertThat(document.get("effective_from")).isNull();

        PublicationDecision decision = publicationService.publishIfEligible(documentId);
        assertThat(decision.ready()).isFalse();
        assertThat(decision.failures()).anyMatch(failure -> failure.contains("effective dates not verified"));
        assertThat(documentStatus("nghi-dinh-238-2026-nd-cp")).isEqualTo("QUARANTINED");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update legal_documents set verification_status = 'READY' where id = ?", documentId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void publicationRejectsArticleTextThatDiffersFromSnapshot() {
        TemporaryDocument temporary = createTemporaryDocument(
                "Điều 1. Nội dung gốc của văn bản.",
                "Điều 1. Nội dung đã bị sửa khác với snapshot.");
        try {
            PublicationDecision decision = publicationService.publishIfEligible(temporary.documentId());
            assertThat(decision.ready()).isFalse();
            assertThat(decision.failures()).anyMatch(failure -> failure.contains("verbatim"));
            assertThat(temporaryStatus(temporary.documentId())).isEqualTo("QUARANTINED");
        } finally {
            cleanup(temporary);
        }
    }

    @Test
    void publicationRejectsTamperedSnapshotHash() {
        TemporaryDocument temporary = createTemporaryDocument(
                "Điều 1. Nội dung gốc của văn bản.",
                "Điều 1. Nội dung gốc của văn bản.");
        try {
            jdbcTemplate.update("update source_snapshots set content = content || ' tampered' where id = ?",
                    temporary.snapshotId());
            PublicationDecision decision = publicationService.publishIfEligible(temporary.documentId());
            assertThat(decision.ready()).isFalse();
            assertThat(decision.failures()).anyMatch(failure -> failure.contains("SHA-256"));
            assertThat(temporaryStatus(temporary.documentId())).isEqualTo("QUARANTINED");
        } finally {
            cleanup(temporary);
        }
    }

    @Test
    void freshnessFollowsPhaseZeroRules() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        assertThat(FreshnessPolicy.evaluate(null, now)).isEqualTo(FreshnessStatus.UNVERIFIED);
        assertThat(FreshnessPolicy.evaluate(now.minus(29, ChronoUnit.DAYS), now)).isEqualTo(FreshnessStatus.FRESH);
        assertThat(FreshnessPolicy.evaluate(now.minus(31, ChronoUnit.DAYS), now)).isEqualTo(FreshnessStatus.STALE);
        assertThat(documentFreshness("luat-36-2024-qh15")).isIn("FRESH", "STALE");
    }

    @Test
    void readyArticleViewerRendersVerbatimTextMetadataAndSourceLink() throws Exception {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select d.id as document_id, a.id as article_id, a.raw_text, a.locator,
                       d.source_url, s.content_sha256
                from legal_documents d
                join legal_articles a on a.document_id = d.id
                join source_snapshots s on s.id = d.snapshot_id
                where d.document_key = 'luat-36-2024-qh15'
                  and d.verification_status = 'READY'
                  and a.article_code = 'dieu-10-khoan-1'
                """);

        MvcResult result = mockMvc.perform(get("/legal/documents/{documentVersionId}/articles/{articleId}",
                        row.get("document_id"), row.get("article_id")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("Điều 10 khoản 1");
        assertThat(body).contains((String) row.get("raw_text"));
        assertThat(body).contains("data-testid=\"verbatim\"");
        assertThat(body).contains("vanban.chinhphu.vn");
        assertThat(body).contains((String) row.get("content_sha256"));
        assertThat(body).contains("legal-structure-parser-v1");
        assertThat(body).containsAnyOf("FRESH", "STALE");
    }

    @Test
    void quarantinedArticleIsHiddenFromPublicButVisibleToAdmin() throws Exception {
        TemporaryDocument temporary = createTemporaryDocument(
                "Điều 1. Nội dung gốc của văn bản.",
                "Điều 1. Nội dung gốc của văn bản.");
        try {
            mockMvc.perform(get("/legal/documents/{documentVersionId}/articles/{articleId}",
                            temporary.documentId(), temporary.articleId()))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/legal/documents/{documentVersionId}/articles/{articleId}",
                            temporary.documentId(), temporary.articleId())
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("chưa được xuất bản")));
        } finally {
            cleanup(temporary);
        }
    }

    @Test
    void retrievalEligibilityRequiresReadyDocumentArticleAndEmbedding() {
        assertThat(embeddingRepository.countEligible(embeddingClient.spaceId())).isGreaterThan(0);

        Integer ineligibleReadyEmbeddings = jdbcTemplate.queryForObject("""
                select count(*)
                from article_embeddings e
                join legal_articles a on a.id = e.article_id
                join legal_documents d on d.id = a.document_id
                where e.status = 'READY'
                  and d.document_key in (
                      'luat-36-2024-qh15', 'luat-23-2008-qh12',
                      'nghi-dinh-168-2024-nd-cp', 'nghi-dinh-238-2026-nd-cp')
                  and (a.verification_status <> 'READY'
                       or d.verification_status <> 'READY'
                       or d.metadata_verified = false
                       or d.effective_dates_verified = false)
                """, Integer.class);
        assertThat(ineligibleReadyEmbeddings).isZero();

        Integer readyArticlesWithoutEmbeddings = jdbcTemplate.queryForObject("""
                select count(*)
                from legal_articles a
                join legal_documents d on d.id = a.document_id
                where a.verification_status = 'READY'
                  and d.verification_status = 'READY'
                  and not exists (
                      select 1 from article_embeddings e
                      where e.article_id = a.id and e.status = 'READY'
                  )
                """, Integer.class);
        assertThat(readyArticlesWithoutEmbeddings).isZero();

        Integer quarantinedEmbeddings = jdbcTemplate.queryForObject("""
                select count(*)
                from article_embeddings e
                join legal_articles a on a.id = e.article_id
                join legal_documents d on d.id = a.document_id
                where d.verification_status = 'QUARANTINED'
                  and d.document_key in (
                      'luat-36-2024-qh15', 'luat-23-2008-qh12',
                      'nghi-dinh-168-2024-nd-cp', 'nghi-dinh-238-2026-nd-cp')
                """, Integer.class);
        assertThat(quarantinedEmbeddings).isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void assertReadyArticle(String documentKey, String articleCode) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from legal_articles a
                join legal_documents d on d.id = a.document_id
                where d.document_key = ? and a.article_code = ?
                  and a.verification_status = 'READY' and d.verification_status = 'READY'
                """, Integer.class, documentKey, articleCode);
        assertThat(count).as("%s %s", documentKey, articleCode).isEqualTo(1);
    }

    private LocalDate articleEffectiveFrom(String documentKey, String articleCode) {
        return jdbcTemplate.queryForObject("""
                select a.effective_from
                from legal_articles a
                join legal_documents d on d.id = a.document_id
                where d.document_key = ? and a.article_code = ?
                """, LocalDate.class, documentKey, articleCode);
    }

    private String documentStatus(String documentKey) {
        return jdbcTemplate.queryForObject(
                "select verification_status from legal_documents where document_key = ?",
                String.class, documentKey);
    }

    private String documentFreshness(String documentKey) {
        Instant lastVerified = jdbcTemplate.queryForObject(
                "select last_verified_at from legal_documents where document_key = ?",
                Instant.class, documentKey);
        return FreshnessPolicy.evaluate(lastVerified, Instant.now()).name();
    }

    private int articleCountForDocument(String documentKey) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from legal_articles a
                join legal_documents d on d.id = a.document_id
                where d.document_key = ?
                """, Integer.class, documentKey);
        return count == null ? 0 : count;
    }

    private Counts counts() {
        return new Counts(
                count("source_snapshots"),
                count("legal_documents"),
                count("legal_articles"),
                count("legal_document_relations"),
                count("article_embeddings"));
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return count == null ? 0 : count;
    }

    private Integer countDuplicateEmbeddingIdentities() {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from (
                    select article_id, model_id, embedding_space_id, chunk_index, text_hash
                    from article_embeddings
                    group by article_id, model_id, embedding_space_id, chunk_index, text_hash
                    having count(*) > 1
                ) duplicates
                """, Integer.class);
    }

    private String temporaryStatus(UUID documentId) {
        return jdbcTemplate.queryForObject(
                "select verification_status from legal_documents where id = ?", String.class, documentId);
    }

    private TemporaryDocument createTemporaryDocument(String snapshotContent, String articleRawText) {
        UUID snapshotId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        String sha = LegalText.sha256Hex(snapshotContent);
        String sourceUrl = "https://example.test/legal/" + documentId;

        jdbcTemplate.update("""
                insert into source_snapshots
                    (id, source_url, content, content_sha256, fetched_at, content_type, parser_version, byte_size)
                values (?, ?, ?, ?, now(), 'text/plain; charset=utf-8', 'test-fixture-v1', ?)
                """, snapshotId, sourceUrl, snapshotContent, sha,
                (long) snapshotContent.getBytes(StandardCharsets.UTF_8).length);
        jdbcTemplate.update("""
                insert into legal_documents
                    (id, document_key, title, doc_number, issuing_body, issue_date, effective_from,
                     effective_status, source_url, snapshot_id, verification_status, metadata_verified,
                     effective_dates_verified, source_checked_by, source_checked_at)
                values (?, ?, 'Văn bản thử nghiệm', '99/2026/TEST', 'Cơ quan thử nghiệm',
                        current_date, current_date, 'EFFECTIVE', ?, ?, 'QUARANTINED', true, true,
                        'G3 test', now())
                """, documentId, "test-document-" + documentId, sourceUrl, snapshotId);
        jdbcTemplate.update("""
                insert into legal_articles
                    (id, document_id, hierarchy_level, article_code, article_number, title,
                     raw_text, search_text, locator, effective_from, verification_status)
                values (?, ?, 'DIEU', 'dieu-1', '1', 'Điều thử nghiệm',
                        ?, ?, 'Điều 1', current_date, 'QUARANTINED')
                """, articleId, documentId, articleRawText, LegalText.normalizeWhitespace(articleRawText));

        return new TemporaryDocument(snapshotId, documentId, articleId);
    }

    private void cleanup(TemporaryDocument temporary) {
        jdbcTemplate.update("delete from legal_articles where document_id = ?", temporary.documentId());
        jdbcTemplate.update("delete from legal_documents where id = ?", temporary.documentId());
        jdbcTemplate.update("delete from source_snapshots where id = ?", temporary.snapshotId());
    }

    private record Counts(long snapshots, long documents, long articles, long relations, long embeddings) {
    }

    private record TemporaryDocument(UUID snapshotId, UUID documentId, UUID articleId) {
    }
}
