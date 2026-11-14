package vn.edu.hsf302.lawrag.admin.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hsf302.lawrag.admin.dto.AdminForumPageView;
import vn.edu.hsf302.lawrag.admin.dto.AdminForumPostView;
import vn.edu.hsf302.lawrag.admin.dto.AiDraftResult;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;
import vn.edu.hsf302.lawrag.config.TimeConfig;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.forum.entity.ForumPost;
import vn.edu.hsf302.lawrag.forum.entity.ForumPostStatus;
import vn.edu.hsf302.lawrag.forum.entity.ForumRequestStatus;
import vn.edu.hsf302.lawrag.forum.repository.ForumCommentRepository;
import vn.edu.hsf302.lawrag.forum.repository.ForumPostRepository;
import vn.edu.hsf302.lawrag.forum.service.ForumService;
import vn.edu.hsf302.lawrag.gemini.service.GroundingClient;
import vn.edu.hsf302.lawrag.gemini.service.GroundingException;
import vn.edu.hsf302.lawrag.llm.entity.LlmPersona;
import vn.edu.hsf302.lawrag.llm.service.LlmPersonaService;
import vn.edu.hsf302.lawrag.observability.service.BudgetExceededException;
import vn.edu.hsf302.lawrag.retrieval.dto.QueryAnalysis;
import vn.edu.hsf302.lawrag.retrieval.dto.RenderedAnswer;
import vn.edu.hsf302.lawrag.retrieval.dto.RetrievalResult;
import vn.edu.hsf302.lawrag.retrieval.dto.ValidatedCitation;
import vn.edu.hsf302.lawrag.retrieval.service.AnswerRenderer;
import vn.edu.hsf302.lawrag.retrieval.service.CitationValidationException;
import vn.edu.hsf302.lawrag.retrieval.service.CitationValidator;
import vn.edu.hsf302.lawrag.retrieval.service.ContextGrader;
import vn.edu.hsf302.lawrag.retrieval.service.HybridRetrievalService;
import vn.edu.hsf302.lawrag.retrieval.service.QueryAnalyzer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Moderation queue, official admin responses, soft remove/restore, hard delete,
 * and optional RAG-assisted reply drafting for {@code /admin/forum}.
 */
@Service
public class AdminForumService {

    public static final int PAGE_SIZE = 12;

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(TimeConfig.BUSINESS_ZONE);

    private final ForumPostRepository posts;
    private final ForumCommentRepository comments;
    private final ForumService forumService;
    private final QueryAnalyzer queryAnalyzer;
    private final HybridRetrievalService retrieval;
    private final ContextGrader grader;
    private final CitationValidator citationValidator;
    private final AnswerRenderer answerRenderer;
    private final LlmPersonaService personas;

    public AdminForumService(ForumPostRepository posts,
                             ForumCommentRepository comments,
                             ForumService forumService,
                             QueryAnalyzer queryAnalyzer,
                             HybridRetrievalService retrieval,
                             ContextGrader grader,
                             CitationValidator citationValidator,
                             AnswerRenderer answerRenderer,
                             LlmPersonaService personas) {
        this.posts = posts;
        this.comments = comments;
        this.forumService = forumService;
        this.queryAnalyzer = queryAnalyzer;
        this.retrieval = retrieval;
        this.grader = grader;
        this.citationValidator = citationValidator;
        this.answerRenderer = answerRenderer;
        this.personas = personas;
    }

    @Transactional(readOnly = true)
    public AdminForumPageView listPosts(String statusParam,
                                        String requestStatusParam,
                                        ForumCategory category,
                                        String rawKeyword,
                                        int requestedPage) {
        ForumPostStatus statusFilter = parsePostStatus(statusParam);
        ForumRequestStatus requestFilter = parseRequestStatus(requestStatusParam);
        String keyword = (rawKeyword == null || rawKeyword.isBlank()) ? null : rawKeyword.strip();

        Specification<ForumPost> spec = buildSpec(statusFilter, requestFilter, category, keyword);
        Sort sort = Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("createdAt"));

        int safePage = Math.max(1, requestedPage);
        Pageable pageable = PageRequest.of(safePage - 1, PAGE_SIZE, sort);
        Page<ForumPost> pageResult = posts.findAll(spec, pageable);
        if (pageResult.getTotalPages() > 0 && safePage > pageResult.getTotalPages()) {
            safePage = pageResult.getTotalPages();
            pageResult = posts.findAll(spec, PageRequest.of(safePage - 1, PAGE_SIZE, sort));
        }

        List<ForumPost> found = pageResult.getContent();
        Map<UUID, Long> commentCounts = new HashMap<>();
        Map<UUID, Long> officialCounts = new HashMap<>();
        if (!found.isEmpty()) {
            List<UUID> ids = found.stream().map(ForumPost::getId).toList();
            for (Object[] row : comments.countByPostIds(ids)) {
                commentCounts.put((UUID) row[0], (Long) row[1]);
            }
            for (Object[] row : comments.countOfficialRepliesByPostIds(ids)) {
                officialCounts.put((UUID) row[0], (Long) row[1]);
            }
        }

        List<AdminForumPostView> items = found.stream()
                .map(post -> new AdminForumPostView(
                        post.getId(),
                        post.getTitle(),
                        excerpt(post.getContent()),
                        post.getContent(),
                        post.getCategory().name(),
                        post.getCategory().getLabel(),
                        post.getAuthor().getDisplayName(),
                        post.getAuthor().getUsername(),
                        post.getAuthor().getRole() == UserRole.ADMIN,
                        post.isPinned(),
                        post.getStatus().name(),
                        post.getStatus().getLabel(),
                        post.getRemovedReason(),
                        post.getRemovedBy(),
                        post.getRequestStatus().name(),
                        post.getRequestStatus().getLabel(),
                        commentCounts.getOrDefault(post.getId(), 0L),
                        officialCounts.getOrDefault(post.getId(), 0L),
                        formatTime(post.getCreatedAt())))
                .toList();

        return new AdminForumPageView(
                items,
                safePage,
                Math.max(1, pageResult.getTotalPages()),
                pageResult.getTotalElements(),
                PAGE_SIZE,
                posts.countByStatus(ForumPostStatus.PUBLISHED),
                posts.countByStatusAndRequestStatus(ForumPostStatus.PUBLISHED, ForumRequestStatus.OPEN),
                posts.countByStatusAndRequestStatus(ForumPostStatus.PUBLISHED, ForumRequestStatus.ANSWERED),
                posts.countByStatusAndRequestStatus(ForumPostStatus.PUBLISHED, ForumRequestStatus.RESOLVED),
                posts.countByStatus(ForumPostStatus.REMOVED));
    }

    public UUID replyOfficially(UUID postId, String adminUsername, String rawContent, UUID parentCommentId) {
        return forumService.addComment(postId, adminUsername, rawContent, parentCommentId, true);
    }

    public void removePost(UUID postId, String actor, String reason) {
        forumService.removePost(postId, actor, reason);
    }

    public void restorePost(UUID postId, String actor) {
        forumService.restorePost(postId, actor);
    }

    public void deletePost(UUID postId, String actor) {
        forumService.deletePost(postId, actor, true);
    }

    public boolean togglePin(UUID postId, String actor) {
        return forumService.togglePin(postId, actor, true);
    }

    public UUID deleteComment(UUID commentId, String actor) {
        return forumService.deleteComment(commentId, actor, true);
    }

    /**
     * Generates a grounded draft reply using internal RAG + the selected persona without
     * publishing to the forum; the admin reviews/edits before submitting.
     */
    @Transactional(readOnly = true)
    public AiDraftResult generateAiDraft(UUID postId, String personaCode) {
        ForumPost post = posts.findByIdWithAuthor(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));

        String query = buildDraftQuery(post.getTitle(), post.getContent());
        QueryAnalysis analysis = queryAnalyzer.analyze(query, LocalDate.now(TimeConfig.BUSINESS_ZONE));
        if (analysis.outOfScope()) {
            return new AiDraftResult(false, "",
                    "Nội dung bài viết ngoài phạm vi pháp luật giao thông đường bộ nên RAG không thể gợi ý.");
        }

        RetrievalResult retrievalResult = retrieval.retrieve(analysis);
        if (retrievalResult.contextCandidates().isEmpty() && retrievalResult.temporalIssues().isEmpty()) {
            return new AiDraftResult(false, "",
                    "Kho văn bản nội bộ chưa có điều khoản phù hợp để tạo gợi ý cho bài viết này.");
        }

        LlmPersona persona = personas.resolveOrDefault(personaCode);
        try {
            GroundingClient.Decision decision = grader.grade(analysis, retrievalResult, persona);
            if (decision.outcome() != GroundingClient.Outcome.ANSWER) {
                return new AiDraftResult(false, "",
                        "Kho văn bản hiện tại chưa đủ căn cứ chắc chắn (" + decision.reasonCode() + ").");
            }
            List<ValidatedCitation> validated =
                    citationValidator.validate(decision, analysis, retrievalResult.contextCandidates());
            RenderedAnswer rendered = answerRenderer.render(validated, decision.answer(), persona);
            return new AiDraftResult(
                    true,
                    rendered.content(),
                    "Đã tạo bản nháp từ " + validated.size()
                            + " điều khoản xác thực theo tính cách " + persona.getName()
                            + ". Vui lòng rà soát trước khi gửi.");
        } catch (BudgetExceededException | GroundingException ex) {
            return new AiDraftResult(false, "", "Không thể gọi mô hình RAG: " + ex.getMessage());
        } catch (CitationValidationException ex) {
            return new AiDraftResult(false, "", "Gợi ý RAG chưa vượt qua kiểm định trích dẫn nguyên văn.");
        }
    }

    private static Specification<ForumPost> buildSpec(ForumPostStatus status,
                                                      ForumRequestStatus requestStatus,
                                                      ForumCategory category,
                                                      String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (requestStatus != null) {
                predicates.add(cb.equal(root.get("requestStatus"), requestStatus));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (keyword != null) {
                String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                var authorJoin = root.join("author");
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("content")), pattern),
                        cb.like(cb.lower(authorJoin.get("username")), pattern),
                        cb.like(cb.lower(authorJoin.get("displayName")), pattern)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static ForumPostStatus parsePostStatus(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        try {
            return ForumPostStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ForumRequestStatus parseRequestStatus(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        try {
            return ForumRequestStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String buildDraftQuery(String title, String content) {
        String combined = ((title == null ? "" : title) + ". " + (content == null ? "" : content))
                .replaceAll("\\s+", " ")
                .strip();
        return combined.length() > 600 ? combined.substring(0, 600) : combined;
    }

    private static String formatTime(Instant instant) {
        return instant == null ? "" : DISPLAY_TIME.format(instant);
    }

    private static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String singleLine = content.replaceAll("\\s+", " ").strip();
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 157) + "...";
    }
}

