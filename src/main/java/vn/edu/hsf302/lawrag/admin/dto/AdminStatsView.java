package vn.edu.hsf302.lawrag.admin.dto;

import vn.edu.hsf302.lawrag.observability.dto.DailyUsage;

/** Aggregate system metrics shown on the admin dashboard. */
public record AdminStatsView(
        long totalUsers,
        long adminUsers,
        long disabledUsers,
        long publishedPosts,
        long openPosts,
        long answeredPosts,
        long resolvedPosts,
        long removedPosts,
        long totalComments,
        long readyDocuments,
        long quarantinedDocuments,
        long totalArticles,
        long totalEmbeddings,
        long totalWebJobs,
        long succeededWebJobs,
        long failedWebJobs,
        long totalQuestions,
        long answeredQuestions,
        long failedQuestions,
        long activePersonas,
        String defaultPersonaName,
        DailyUsage todayUsage,
        long maxGeminiCallsPerDay,
        long maxEmbeddingCallsPerDay,
        long maxWebJobsPerDay,
        boolean geminiEnabled,
        boolean crawlerEnabled) {
}

