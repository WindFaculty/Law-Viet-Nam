package vn.edu.hsf302.lawrag.observability.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import vn.edu.hsf302.lawrag.observability.dto.DailyUsage;
import vn.edu.hsf302.lawrag.observability.config.ResourceBudgetProperties;
import vn.edu.hsf302.lawrag.observability.dto.Usage;
import vn.edu.hsf302.lawrag.observability.dto.UsageCategory;

/**
 * Records daily provider work and enforces the locked resource ceilings.
 *
 * <p>A reached cap or an off kill switch raises {@link BudgetExceededException}.
 * Callers decide how to degrade: local retrieval falls back to the lexical
 * branch, web confirmation refuses without reserving quota, and grading fails
 * closed instead of publishing an unvalidated answer.
 */
@Service
public class CostBudgetService {

    private static final Logger log = LoggerFactory.getLogger(CostBudgetService.class);
    private static final long JOB_BUDGET_LOCK = 734_219_002L;

    private final JdbcTemplate jdbcTemplate;
    private final ResourceBudgetProperties properties;
    private final Clock clock;
    private final AtomicReference<LocalDate> warningLoggedFor = new AtomicReference<>();

    public CostBudgetService(JdbcTemplate jdbcTemplate,
                             ResourceBudgetProperties properties,
                             Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean crawlerEnabled() {
        return properties.crawlerEnabled();
    }

    public int maxUrlsPerJob() {
        return properties.maxUrlsPerJob();
    }

    public int maxConcurrentWebJobs() {
        return properties.maxConcurrentWebJobs();
    }

    public int maxTokensPerContext() {
        return properties.maxTokensPerContext();
    }

    /**
     * Called inside the confirmation transaction before quota is reserved.
     * The advisory lock makes the global daily job count race-free.
     */
    public void requireWebSearchJob() {
        if (!properties.crawlerEnabled()) {
            throw new BudgetExceededException(
                    "Tính năng tìm kiếm Web tạm ngừng do cấu hình vận hành. "
                            + "Kho nội bộ vẫn hoạt động; bạn không bị trừ lượt.");
        }
        jdbcTemplate.execute("select pg_advisory_xact_lock(" + JOB_BUDGET_LOCK + ")");
        long jobsToday = jdbcTemplate.queryForObject("""
                select count(*) from web_search_jobs where created_at >= ?
                """, Long.class, startOfBusinessDay());
        if (jobsToday >= properties.maxWebJobsPerDay()) {
            throw new BudgetExceededException(
                    "Tính năng tìm kiếm Web tạm thời đạt giới hạn ngân sách trong ngày. "
                            + "Kho nội bộ vẫn hoạt động; bạn không bị trừ lượt. "
                            + "Vui lòng thử lại sau 00:00 giờ Việt Nam.");
        }
    }

    public void requireGeminiCall() {
        if (!properties.geminiEnabled()) {
            throw new BudgetExceededException(
                    "Bộ kiểm tra căn cứ tạm ngừng do cấu hình vận hành. "
                            + "Hệ thống không tạo câu trả lời khi thiếu bước kiểm tra và không trừ lượt.");
        }
        long calls = sumToday("grounding_calls");
        if (calls >= properties.maxGeminiCallsPerDay()) {
            throw new BudgetExceededException(
                    "Bộ kiểm tra căn cứ đã đạt giới hạn ngân sách trong ngày. "
                            + "Hệ thống không tạo câu trả lời khi thiếu bước kiểm tra và không trừ lượt. "
                            + "Vui lòng thử lại sau 00:00 giờ Việt Nam.");
        }
    }

    public void requireEmbeddingCall() {
        if (!properties.geminiEnabled()) {
            throw new BudgetExceededException("Dịch vụ embedding tạm ngừng do cấu hình vận hành.");
        }
        long calls = sumToday("embedding_calls");
        if (calls >= properties.maxEmbeddingCallsPerDay()) {
            throw new BudgetExceededException(
                    "Dịch vụ embedding đã đạt giới hạn ngân sách trong ngày.");
        }
    }

    public void record(UsageCategory category, Usage usage) {
        if (category == null || usage == null || usage.isEmpty()) {
            return;
        }
        jdbcTemplate.update("""
                insert into cost_usage_daily
                    (usage_date, category, embedding_calls, embedding_input_chars, embedding_cache_hits,
                     grounding_calls, grounding_input_tokens, grounding_output_tokens,
                     web_search_calls, fetched_urls, downloaded_bytes)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (usage_date, category) do update set
                    embedding_calls = cost_usage_daily.embedding_calls + excluded.embedding_calls,
                    embedding_input_chars = cost_usage_daily.embedding_input_chars + excluded.embedding_input_chars,
                    embedding_cache_hits = cost_usage_daily.embedding_cache_hits + excluded.embedding_cache_hits,
                    grounding_calls = cost_usage_daily.grounding_calls + excluded.grounding_calls,
                    grounding_input_tokens = cost_usage_daily.grounding_input_tokens + excluded.grounding_input_tokens,
                    grounding_output_tokens = cost_usage_daily.grounding_output_tokens + excluded.grounding_output_tokens,
                    web_search_calls = cost_usage_daily.web_search_calls + excluded.web_search_calls,
                    fetched_urls = cost_usage_daily.fetched_urls + excluded.fetched_urls,
                    downloaded_bytes = cost_usage_daily.downloaded_bytes + excluded.downloaded_bytes,
                    updated_at = now()
                """,
                LocalDate.now(clock), category.name(),
                usage.embeddingCalls(), usage.embeddingInputChars(), usage.embeddingCacheHits(),
                usage.groundingCalls(), usage.groundingInputTokens(), usage.groundingOutputTokens(),
                usage.webSearchCalls(), usage.fetchedUrls(), usage.downloadedBytes());
        warnAtSeventyPercent();
    }

    public DailyUsage dailyUsage() {
        LocalDate today = LocalDate.now(clock);
        Long embedding = sumToday("embedding_calls");
        Long grounding = sumToday("grounding_calls");
        Long search = sumToday("web_search_calls");
        Long urls = sumToday("fetched_urls");
        Long bytes = sumToday("downloaded_bytes");
        Long jobs = jdbcTemplate.queryForObject(
                "select count(*) from web_search_jobs where created_at >= ?",
                Long.class, startOfBusinessDay());
        return new DailyUsage(today, embedding, grounding, jobs, search, urls, bytes);
    }

    private long sumToday(String column) {
        Long value = jdbcTemplate.queryForObject(
                "select coalesce(sum(" + column + "), 0) from cost_usage_daily where usage_date = ?",
                Long.class, LocalDate.now(clock));
        return value == null ? 0 : value;
    }

    private java.sql.Timestamp startOfBusinessDay() {
        return java.sql.Timestamp.from(
                LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant());
    }

    private void warnAtSeventyPercent() {
        LocalDate today = LocalDate.now(clock);
        LocalDate alreadyLogged = warningLoggedFor.get();
        if (today.equals(alreadyLogged)) {
            return;
        }
        boolean nearCap = ratioAtLeastSeventyPercent(sumToday("grounding_calls"),
                        properties.maxGeminiCallsPerDay())
                || ratioAtLeastSeventyPercent(sumToday("embedding_calls"),
                        properties.maxEmbeddingCallsPerDay());
        if (nearCap && warningLoggedFor.compareAndSet(alreadyLogged, today)) {
            log.warn("Provider usage reached 70% of a daily budget on {}; "
                    + "calls will be refused at 100%", today);
        }
    }

    private static boolean ratioAtLeastSeventyPercent(long used, int cap) {
        return cap > 0 && used * 10 >= (long) cap * 7;
    }
}
