package vn.edu.hsf302.lawrag.admin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hsf302.lawrag.admin.dto.AdminStatsView;
import vn.edu.hsf302.lawrag.forum.entity.ForumPostStatus;
import vn.edu.hsf302.lawrag.forum.entity.ForumRequestStatus;
import vn.edu.hsf302.lawrag.forum.repository.ForumCommentRepository;
import vn.edu.hsf302.lawrag.forum.repository.ForumPostRepository;
import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;
import vn.edu.hsf302.lawrag.llm.service.LlmPersonaService;
import vn.edu.hsf302.lawrag.observability.config.ResourceBudgetProperties;
import vn.edu.hsf302.lawrag.observability.dto.DailyUsage;
import vn.edu.hsf302.lawrag.observability.service.CostBudgetService;

/**
 * Aggregates KPI metrics across users, forum moderation, legal corpus, web jobs,
 * chat messages, LLM personas, and daily budget consumption for the Admin Dashboard.
 */
@Service
public class AdminDashboardService {

    private final JdbcTemplate jdbcTemplate;
    private final ForumPostRepository forumPosts;
    private final ForumCommentRepository forumComments;
    private final LlmPersonaService personas;
    private final CostBudgetService budget;
    private final ResourceBudgetProperties budgetProps;

    public AdminDashboardService(JdbcTemplate jdbcTemplate,
                                 ForumPostRepository forumPosts,
                                 ForumCommentRepository forumComments,
                                 LlmPersonaService personas,
                                 CostBudgetService budget,
                                 ResourceBudgetProperties budgetProps) {
        this.jdbcTemplate = jdbcTemplate;
        this.forumPosts = forumPosts;
        this.forumComments = forumComments;
        this.personas = personas;
        this.budget = budget;
        this.budgetProps = budgetProps;
    }

    @Transactional(readOnly = true)
    public AdminStatsView loadStats() {
        long[] userCounts = jdbcTemplate.queryForObject("""
                select count(*),
                       count(*) filter (where role = 'ADMIN'),
                       count(*) filter (where enabled = false)
                from app_users
                """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});

        long publishedPosts = forumPosts.countByStatus(ForumPostStatus.PUBLISHED);
        long removedPosts = forumPosts.countByStatus(ForumPostStatus.REMOVED);
        long openPosts = forumPosts.countByStatusAndRequestStatus(ForumPostStatus.PUBLISHED, ForumRequestStatus.OPEN);
        long answeredPosts = forumPosts.countByStatusAndRequestStatus(ForumPostStatus.PUBLISHED, ForumRequestStatus.ANSWERED);
        long resolvedPosts = forumPosts.countByStatusAndRequestStatus(ForumPostStatus.PUBLISHED, ForumRequestStatus.RESOLVED);
        long totalComments = forumComments.count();

        long[] docCounts = jdbcTemplate.queryForObject("""
                select count(*) filter (where verification_status = 'READY'),
                       count(*) filter (where verification_status = 'QUARANTINED')
                from legal_documents
                """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)});

        Long totalArticlesCount = jdbcTemplate.queryForObject(
                "select count(*) from legal_articles where verification_status = 'READY'",
                Long.class);
        Long totalEmbeddingsCount = jdbcTemplate.queryForObject(
                "select count(*) from article_embeddings where status = 'READY'",
                Long.class);

        long[] jobCounts = jdbcTemplate.queryForObject("""
                select count(*),
                       count(*) filter (where job_status = 'SUCCEEDED'),
                       count(*) filter (where job_status = 'FAILED')
                from web_search_jobs
                """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});

        long[] msgCounts = jdbcTemplate.queryForObject("""
                select count(*) filter (where sender = 'USER'),
                       count(*) filter (where sender = 'AI' and message_status = 'COMPLETED'),
                       count(*) filter (where sender = 'AI' and message_status in ('FAILED', 'WEB_FAILED'))
                from chat_messages
                """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});

        long activePersonas = personas.listActivePersonas().size();
        LlmPersona defaultPersona = personas.getDefaultPersona();
        DailyUsage todayUsage = budget.dailyUsage();

        return new AdminStatsView(
                safeAt(userCounts, 0), safeAt(userCounts, 1), safeAt(userCounts, 2),
                publishedPosts, openPosts, answeredPosts, resolvedPosts, removedPosts, totalComments,
                safeAt(docCounts, 0), safeAt(docCounts, 1),
                totalArticlesCount == null ? 0L : totalArticlesCount,
                totalEmbeddingsCount == null ? 0L : totalEmbeddingsCount,
                safeAt(jobCounts, 0), safeAt(jobCounts, 1), safeAt(jobCounts, 2),
                safeAt(msgCounts, 0), safeAt(msgCounts, 1), safeAt(msgCounts, 2),
                activePersonas,
                defaultPersona.getName(),
                todayUsage,
                budgetProps.maxGeminiCallsPerDay(),
                budgetProps.maxEmbeddingCallsPerDay(),
                budgetProps.maxWebJobsPerDay(),
                budgetProps.geminiEnabled(),
                budgetProps.crawlerEnabled());
    }

    private static long safeAt(long[] arr, int index) {
        return arr == null || index >= arr.length ? 0L : arr[index];
    }
}

