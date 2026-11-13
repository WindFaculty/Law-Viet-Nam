package vn.edu.hsf302.lawrag.websearch.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Structured audit of provider results and why each URL was accepted/rejected. */
@Repository
public class WebSearchCandidateRepository {

    private static final RowMapper<CandidateRow> ROW_MAPPER = (resultSet, rowNumber) -> new CandidateRow(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("candidate_url"),
            resultSet.getInt("rank"),
            resultSet.getBoolean("accepted"),
            resultSet.getString("rejection_reason"),
            resultSet.getString("result_code"),
            resultSet.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public WebSearchCandidateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(UUID jobId, String query, String provider, URI url,
                       int rank, boolean accepted, String rejectionReason) {
        jdbcTemplate.update("""
                insert into web_search_candidates
                    (job_id, query, provider, candidate_url, rank, accepted, rejection_reason)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                jobId, query, provider, url.toString(), rank, accepted, rejectionReason);
    }

    public void recordResult(UUID jobId, String candidateUrl, String resultCode) {
        jdbcTemplate.update("""
                update web_search_candidates
                set result_code = ?
                where id = (
                    select id from web_search_candidates
                    where job_id = ? and candidate_url = ?
                    order by created_at desc
                    limit 1
                )
                """, resultCode, jobId, candidateUrl);
    }

    public List<CandidateRow> findByJobId(UUID jobId) {
        return jdbcTemplate.query("""
                select id, candidate_url, rank, accepted, rejection_reason, result_code, created_at
                from web_search_candidates
                where job_id = ?
                order by rank, created_at
                """, ROW_MAPPER, jobId);
    }

    public record CandidateRow(
            UUID id,
            String candidateUrl,
            int rank,
            boolean accepted,
            String rejectionReason,
            String resultCode,
            Instant createdAt) {
    }
}
