package vn.edu.hsf302.lawrag.admin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.admin.dto.AdminAuditPageView;
import vn.edu.hsf302.lawrag.admin.dto.AuditRow;
import vn.edu.hsf302.lawrag.admin.dto.CostUsageLogView;
import vn.edu.hsf302.lawrag.admin.dto.CostUsageSummaryView;
import vn.edu.hsf302.lawrag.admin.dto.RagTraceLogView;
import vn.edu.hsf302.lawrag.admin.dto.RagTracePageView;
import vn.edu.hsf302.lawrag.config.TimeConfig;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Queries the three operational log sources for {@code /admin/logs}:
 * 1) {@code admin_audit_log}
 * 2) {@code retrieval_traces} joined with {@code chat_messages}
 * 3) {@code cost_usage_daily}
 */
@Service
public class AdminLogService {

    public static final int PAGE_SIZE = 20;

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(TimeConfig.BUSINESS_ZONE);

    private final JdbcTemplate jdbcTemplate;

    public AdminLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public AdminAuditPageView searchAuditLogs(String actor, String action, String entityType, int requestedPage) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> params = new ArrayList<>();
        if (hasText(actor)) {
            where.append(" and lower(actor) like lower(?) ");
            params.add("%" + actor.strip() + "%");
        }
        if (hasText(action)) {
            where.append(" and action = ? ");
            params.add(action.strip());
        }
        if (hasText(entityType)) {
            where.append(" and entity_type = ? ");
            params.add(entityType.strip());
        }

        Long totalObj = jdbcTemplate.queryForObject(
                "select count(*) from admin_audit_log " + where, Long.class, params.toArray());
        long total = totalObj == null ? 0L : totalObj;
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        int offset = (page - 1) * PAGE_SIZE;

        List<Object> rowParams = new ArrayList<>(params);
        rowParams.add(PAGE_SIZE);
        rowParams.add(offset);

        List<AuditRow> rows = jdbcTemplate.query("""
                select actor, action, entity_type, entity_id, details, created_at
                from admin_audit_log
                """ + where + """
                order by created_at desc
                limit ? offset ?
                """,
                (rs, i) -> new AuditRow(
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("details"),
                        rs.getTimestamp("created_at").toInstant()),
                rowParams.toArray());

        return new AdminAuditPageView(rows, page, totalPages, total, PAGE_SIZE);
    }

    @Transactional(readOnly = true)
    public List<String> distinctAuditActions() {
        return jdbcTemplate.queryForList(
                "select distinct action from admin_audit_log order by action asc", String.class);
    }

    @Transactional(readOnly = true)
    public List<String> distinctAuditEntityTypes() {
        return jdbcTemplate.queryForList(
                "select distinct entity_type from admin_audit_log order by entity_type asc", String.class);
    }

    @Transactional(readOnly = true)
    public RagTracePageView searchRagTraces(String decision, String query, int requestedPage) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> params = new ArrayList<>();
        if (hasText(decision)) {
            String normalized = decision.strip().toUpperCase();
            if ("FAILED_ONLY".equals(normalized)) {
                where.append(" and (t.decision = 'FAILED' or m.message_status in ('FAILED', 'WEB_FAILED')) ");
            } else {
                where.append(" and t.decision = ? ");
                params.add(normalized);
            }
        }
        if (hasText(query)) {
            String like = "%" + query.strip() + "%";
            where.append(" and (lower(t.question) like lower(?) or lower(coalesce(t.reason_code, '')) like lower(?) or lower(coalesce(t.persona_code, '')) like lower(?)) ");
            params.add(like);
            params.add(like);
            params.add(like);
        }

        Long totalObj = jdbcTemplate.queryForObject("""
                select count(*)
                from retrieval_traces t
                left join chat_messages m on m.id = t.message_id
                """ + where, Long.class, params.toArray());
        long total = totalObj == null ? 0L : totalObj;
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        int offset = (page - 1) * PAGE_SIZE;

        List<Object> rowParams = new ArrayList<>(params);
        rowParams.add(PAGE_SIZE);
        rowParams.add(offset);

        List<RagTraceLogView> rows = jdbcTemplate.query("""
                select t.id, t.message_id, t.question, t.applicable_date, t.model_id, t.prompt_version,
                       t.persona_code, t.gate_passed, t.max_cosine, t.decision, t.reason_code,
                       m.message_status, t.candidate_scores, t.created_at
                from retrieval_traces t
                left join chat_messages m on m.id = t.message_id
                """ + where + """
                order by t.created_at desc
                limit ? offset ?
                """,
                (rs, i) -> {
                    Timestamp ts = rs.getTimestamp("created_at");
                    return new RagTraceLogView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("message_id", UUID.class),
                            rs.getString("question"),
                            rs.getObject("applicable_date", LocalDate.class),
                            rs.getString("model_id"),
                            rs.getString("prompt_version"),
                            rs.getString("persona_code"),
                            rs.getBoolean("gate_passed"),
                            rs.getDouble("max_cosine"),
                            rs.getString("decision"),
                            rs.getString("reason_code"),
                            rs.getString("message_status"),
                            rs.getString("candidate_scores"),
                            ts == null ? "" : DISPLAY_TIME.format(ts.toInstant()));
                },
                rowParams.toArray());

        return new RagTracePageView(rows, page, totalPages, total, PAGE_SIZE);
    }

    @Transactional(readOnly = true)
    public List<CostUsageLogView> listCostUsage(LocalDate fromDate, LocalDate toDate, String category) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> params = new ArrayList<>();
        if (fromDate != null) {
            where.append(" and usage_date >= ? ");
            params.add(fromDate);
        }
        if (toDate != null) {
            where.append(" and usage_date <= ? ");
            params.add(toDate);
        }
        if (hasText(category)) {
            where.append(" and category = ? ");
            params.add(category.strip().toUpperCase());
        }
        return jdbcTemplate.query("""
                select usage_date, category, embedding_calls, embedding_input_chars, embedding_cache_hits,
                       grounding_calls, grounding_input_tokens, grounding_output_tokens,
                       web_search_calls, fetched_urls, downloaded_bytes
                from cost_usage_daily
                """ + where + """
                order by usage_date desc, category asc
                limit 200
                """,
                (rs, i) -> new CostUsageLogView(
                        rs.getObject("usage_date", LocalDate.class),
                        rs.getString("category"),
                        rs.getInt("embedding_calls"),
                        rs.getLong("embedding_input_chars"),
                        rs.getInt("embedding_cache_hits"),
                        rs.getInt("grounding_calls"),
                        rs.getLong("grounding_input_tokens"),
                        rs.getLong("grounding_output_tokens"),
                        rs.getInt("web_search_calls"),
                        rs.getInt("fetched_urls"),
                        rs.getLong("downloaded_bytes")),
                params.toArray());
    }

    @Transactional(readOnly = true)
    public CostUsageSummaryView summarizeCostUsage(List<CostUsageLogView> rows) {
        long embeddingCalls = 0;
        long embeddingChars = 0;
        long embeddingCacheHits = 0;
        long groundingCalls = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        long webSearchCalls = 0;
        long fetchedUrls = 0;
        long downloadedBytes = 0;
        if (rows != null) {
            for (CostUsageLogView row : rows) {
                embeddingCalls += row.embeddingCalls();
                embeddingChars += row.embeddingInputChars();
                embeddingCacheHits += row.embeddingCacheHits();
                groundingCalls += row.groundingCalls();
                inputTokens += row.groundingInputTokens();
                outputTokens += row.groundingOutputTokens();
                webSearchCalls += row.webSearchCalls();
                fetchedUrls += row.fetchedUrls();
                downloadedBytes += row.downloadedBytes();
            }
        }
        return new CostUsageSummaryView(
                embeddingCalls,
                embeddingChars,
                embeddingCacheHits,
                groundingCalls,
                inputTokens,
                outputTokens,
                webSearchCalls,
                fetchedUrls,
                downloadedBytes);
    }

    @Transactional(readOnly = true)
    public List<String> distinctUsageCategories() {
        return jdbcTemplate.queryForList(
                "select distinct category from cost_usage_daily order by category asc", String.class);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

