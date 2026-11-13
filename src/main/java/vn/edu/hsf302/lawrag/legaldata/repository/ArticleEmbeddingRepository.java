package vn.edu.hsf302.lawrag.legaldata.repository;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import vn.edu.hsf302.lawrag.legaldata.entity.ArticleEmbedding;
import vn.edu.hsf302.lawrag.legaldata.entity.EmbeddingStatus;

/**
 * JDBC access to {@code article_embeddings}. The vector column is written as a
 * pgvector {@code vector} PGobject and never mapped through the ORM.
 */
@Repository
public class ArticleEmbeddingRepository {

    private static final RowMapper<ArticleEmbedding> ROW_MAPPER = (resultSet, rowNumber) -> new ArticleEmbedding(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("article_id", UUID.class),
            resultSet.getInt("chunk_index"),
            resultSet.getString("model_id"),
            resultSet.getString("embedding_space_id"),
            resultSet.getInt("dimensions"),
            resultSet.getString("text_hash"),
            parseVector(resultSet.getString("embedding")),
            EmbeddingStatus.valueOf(resultSet.getString("status")));

    private final JdbcTemplate jdbcTemplate;

    public ArticleEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insert(UUID articleId, int chunkIndex, String modelId, String embeddingSpaceId,
                       int dimensions, String textHash, float[] embedding, EmbeddingStatus status) {
        if (embedding.length != dimensions) {
            throw new IllegalArgumentException("embedding length does not match dimensions");
        }
        PGobject vector = new PGobject();
        vector.setType("vector");
        try {
            vector.setValue(toVectorLiteral(embedding));
        } catch (SQLException exception) {
            throw new IllegalStateException("cannot build pgvector value", exception);
        }
        return jdbcTemplate.queryForObject("""
                insert into article_embeddings
                    (article_id, chunk_index, model_id, embedding_space_id, dimensions, text_hash, embedding, status)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class,
                articleId, chunkIndex, modelId, embeddingSpaceId, dimensions, textHash, vector, status.name());
    }

    public List<ArticleEmbedding> findByArticleId(UUID articleId) {
        return jdbcTemplate.query("""
                select id, article_id, chunk_index, model_id, embedding_space_id, dimensions,
                       text_hash, embedding::text as embedding, status
                from article_embeddings
                where article_id = ?
                order by chunk_index
                """, ROW_MAPPER, articleId);
    }

    public boolean existsByIdentity(UUID articleId, String modelId, String embeddingSpaceId,
                                    int chunkIndex, String textHash) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists (
                    select 1
                    from article_embeddings
                    where article_id = ?
                      and model_id = ?
                      and embedding_space_id = ?
                      and chunk_index = ?
                      and text_hash = ?
                )
                """, Boolean.class, articleId, modelId, embeddingSpaceId, chunkIndex, textHash);
        return Boolean.TRUE.equals(exists);
    }

    public long countEligible(String embeddingSpaceId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from article_embeddings e
                join legal_articles a on a.id = e.article_id
                join legal_documents d on d.id = a.document_id
                where e.embedding_space_id = ?
                  and e.status = 'READY'
                  and a.verification_status = 'READY'
                  and d.verification_status = 'READY'
                  and d.metadata_verified
                  and d.effective_dates_verified
                """, Long.class, embeddingSpaceId);
        return count == null ? 0 : count;
    }

    public long countReady(UUID articleId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from article_embeddings where article_id = ? and status = 'READY'",
                Long.class, articleId);
        return count == null ? 0 : count;
    }

    public long countReady(String embeddingSpaceId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from article_embeddings where embedding_space_id = ? and status = 'READY'",
                Long.class, embeddingSpaceId);
        return count == null ? 0 : count;
    }

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder(embedding.length * 12).append('[');
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(embedding[index]);
        }
        return builder.append(']').toString();
    }

    private static float[] parseVector(String value) {
        if (value == null) {
            return null;
        }
        String body = value.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int index = 0; index < parts.length; index++) {
            vector[index] = Float.parseFloat(parts[index].trim());
        }
        return vector;
    }
}
