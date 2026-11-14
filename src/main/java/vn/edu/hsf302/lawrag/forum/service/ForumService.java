package vn.edu.hsf302.lawrag.forum.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hsf302.lawrag.auth.entity.AppUser;
import vn.edu.hsf302.lawrag.auth.repository.AppUserRepository;
import vn.edu.hsf302.lawrag.config.TimeConfig;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import vn.edu.hsf302.lawrag.admin.repository.AdminAuditRepository;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;
import vn.edu.hsf302.lawrag.forum.dto.CommentView;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.forum.entity.ForumComment;
import vn.edu.hsf302.lawrag.forum.repository.ForumCommentRepository;
import vn.edu.hsf302.lawrag.forum.entity.ForumPost;
import vn.edu.hsf302.lawrag.forum.entity.ForumPostStatus;
import vn.edu.hsf302.lawrag.forum.entity.ForumRequestStatus;
import vn.edu.hsf302.lawrag.forum.repository.ForumPostRepository;
import vn.edu.hsf302.lawrag.forum.dto.ForumPostPageView;
import vn.edu.hsf302.lawrag.forum.dto.PostDetailView;
import vn.edu.hsf302.lawrag.forum.dto.PostSummaryView;

@Service
public class ForumService {

    public static final int PAGE_SIZE = 10;

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(TimeConfig.BUSINESS_ZONE);

    private final ForumPostRepository posts;
    private final ForumCommentRepository comments;
    private final AppUserRepository users;
    private final AdminAuditRepository audit;

    public ForumService(ForumPostRepository posts,
                        ForumCommentRepository comments,
                        AppUserRepository users,
                        AdminAuditRepository audit) {
        this.posts = posts;
        this.comments = comments;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ForumPostPageView listPosts(ForumCategory category, String rawKeyword, int requestedPage) {
        String keyword = (rawKeyword == null || rawKeyword.isBlank()) ? null : rawKeyword.strip();
        int safePage = Math.max(1, requestedPage);
        Page<ForumPost> pageResult = queryPostsPage(category, keyword, safePage);
        if (pageResult.getTotalPages() > 0 && safePage > pageResult.getTotalPages()) {
            safePage = pageResult.getTotalPages();
            pageResult = queryPostsPage(category, keyword, safePage);
        }

        List<ForumPost> found = pageResult.getContent();
        if (found.isEmpty()) {
            return new ForumPostPageView(
                    List.of(),
                    1,
                    Math.max(1, pageResult.getTotalPages()),
                    pageResult.getTotalElements(),
                    PAGE_SIZE);
        }
        List<UUID> postIds = found.stream().map(ForumPost::getId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : comments.countByPostIds(postIds)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        Map<UUID, Long> officialCounts = new HashMap<>();
        for (Object[] row : comments.countOfficialRepliesByPostIds(postIds)) {
            officialCounts.put((UUID) row[0], (Long) row[1]);
        }

        List<PostSummaryView> summaries = found.stream()
                .map(post -> new PostSummaryView(
                        post.getId(),
                        post.getTitle(),
                        excerpt(post.getContent()),
                        post.getCategory().name(),
                        post.getCategory().getLabel(),
                        post.getAuthor().getDisplayName(),
                        post.getAuthor().getUsername(),
                        post.getAuthor().getRole() == UserRole.ADMIN,
                        post.isPinned(),
                        post.getStatus().name(),
                        post.getStatus().getLabel(),
                        post.getRequestStatus().name(),
                        post.getRequestStatus().getLabel(),
                        officialCounts.getOrDefault(post.getId(), 0L) > 0L,
                        counts.getOrDefault(post.getId(), 0L),
                        formatTime(post.getCreatedAt())))
                .toList();

        return new ForumPostPageView(
                summaries,
                safePage,
                Math.max(1, pageResult.getTotalPages()),
                pageResult.getTotalElements(),
                PAGE_SIZE);
    }

    private Page<ForumPost> queryPostsPage(ForumCategory category, String keyword, int oneBasedPage) {
        Pageable pageable = PageRequest.of(oneBasedPage - 1, PAGE_SIZE);
        if (category == null && keyword == null) {
            return posts.findAllWithAuthor(pageable);
        }
        if (category != null && keyword == null) {
            return posts.findByCategoryWithAuthor(category, pageable);
        }
        if (category == null) {
            return posts.searchByKeywordWithAuthor(keyword, pageable);
        }
        return posts.searchByCategoryAndKeywordWithAuthor(category, keyword, pageable);
    }

    @Transactional(readOnly = true)
    public PostDetailView getPost(UUID postId, String viewerUsername, boolean viewerIsAdmin) {
        ForumPost post = posts.findByIdWithAuthor(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        if (post.getStatus() == ForumPostStatus.REMOVED && !viewerIsAdmin) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết.");
        }

        List<ForumComment> flatComments = comments.findByPostIdWithAuthor(postId);
        Map<UUID, List<ForumComment>> childrenByParent = new HashMap<>();
        List<ForumComment> roots = new ArrayList<>();
        Set<UUID> commentIds = flatComments.stream().map(ForumComment::getId).collect(Collectors.toSet());
        for (ForumComment comment : flatComments) {
            ForumComment parent = comment.getParentComment();
            if (parent != null && commentIds.contains(parent.getId())) {
                childrenByParent.computeIfAbsent(parent.getId(), key -> new ArrayList<>()).add(comment);
            } else {
                roots.add(comment);
            }
        }

        boolean viewerSignedIn = viewerUsername != null && !viewerUsername.isBlank();
        boolean postPublished = post.getStatus() == ForumPostStatus.PUBLISHED;
        boolean isPostAuthor = viewerSignedIn && post.getAuthor().getUsername().equalsIgnoreCase(viewerUsername);
        boolean canAccept = postPublished && (isPostAuthor || viewerIsAdmin);
        boolean canReply = postPublished && viewerSignedIn;
        boolean canModeratePost = canModerate(post.getAuthor().getUsername(), viewerUsername, viewerIsAdmin);

        List<CommentView> commentTree = roots.stream()
                .map(root -> toCommentView(root, null, childrenByParent, viewerUsername, viewerIsAdmin, canAccept, canReply))
                .toList();

        return new PostDetailView(
                post.getId(),
                post.getTitle(),
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
                post.getRequestStatus().name(),
                post.getRequestStatus().getLabel(),
                formatTime(post.getCreatedAt()),
                formatTime(post.getEditedAt()),
                canModeratePost,
                canModeratePost,
                viewerIsAdmin,
                flatComments.size(),
                commentTree);
    }

    private CommentView toCommentView(ForumComment comment,
                                      ForumComment parent,
                                      Map<UUID, List<ForumComment>> childrenByParent,
                                      String viewerUsername,
                                      boolean viewerIsAdmin,
                                      boolean canAccept,
                                      boolean canReply) {
        List<CommentView> replies = childrenByParent.getOrDefault(comment.getId(), List.of()).stream()
                .map(child -> toCommentView(child, comment, childrenByParent, viewerUsername, viewerIsAdmin, canAccept, canReply))
                .toList();
        boolean canMod = canModerate(comment.getAuthor().getUsername(), viewerUsername, viewerIsAdmin);
        return new CommentView(
                comment.getId(),
                parent == null ? null : parent.getId(),
                parent == null ? null : parent.getAuthor().getDisplayName(),
                comment.getContent(),
                comment.getAuthor().getDisplayName(),
                comment.getAuthor().getUsername(),
                comment.getAuthor().getRole() == UserRole.ADMIN,
                comment.isOfficialAdminReply(),
                comment.isAcceptedAnswer(),
                formatTime(comment.getCreatedAt()),
                formatTime(comment.getEditedAt()),
                canMod,
                canMod,
                canAccept,
                canReply,
                replies);
    }

    @Transactional
    public UUID createPost(String username, String rawTitle, ForumCategory category, String rawContent) {
        AppUser author = requireUser(username);
        String title = validateTitle(rawTitle);
        String content = validatePostContent(rawContent);
        ForumPost saved = posts.save(new ForumPost(author, title, category, content));
        return saved.getId();
    }

    @Transactional
    public void editPost(UUID postId, String username, boolean isAdmin,
                         String rawTitle, ForumCategory category, String rawContent) {
        ForumPost post = posts.findByIdWithAuthor(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        if (!canModerate(post.getAuthor().getUsername(), username, isAdmin)) {
            throw new AccessDeniedException("Bạn không có quyền chỉnh sửa bài viết này.");
        }
        post.setTitle(validateTitle(rawTitle));
        if (category != null) {
            post.setCategory(category);
        }
        post.setContent(validatePostContent(rawContent));
        post.setEditedAt(Instant.now());
        posts.save(post);
        if (isAdmin && !post.getAuthor().getUsername().equalsIgnoreCase(username)) {
            audit.record(username, "FORUM_EDIT_POST", "FORUM_POST", postId,
                    "{\"title\":\"" + escapeJson(post.getTitle()) + "\"}");
        }
    }

    @Transactional
    public void addComment(UUID postId, String username, String rawContent) {
        addComment(postId, username, rawContent, null, false);
    }

    @Transactional
    public UUID addComment(UUID postId, String username, String rawContent,
                           UUID parentCommentId, boolean officialAdminReply) {
        AppUser author = requireUser(username);
        ForumPost post = posts.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        if (post.getStatus() == ForumPostStatus.REMOVED) {
            throw new IllegalArgumentException("Bài viết đã bị gỡ, không thể bình luận hoặc phản hồi.");
        }
        String content = validateCommentContent(rawContent);
        ForumComment parent = null;
        if (parentCommentId != null) {
            parent = comments.findByIdWithAuthorAndPost(parentCommentId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bình luận cha cần trả lời."));
            if (!parent.getPost().getId().equals(postId)) {
                throw new IllegalArgumentException("Bình luận cha không thuộc bài viết này.");
            }
        }
        boolean markOfficial = officialAdminReply && author.getRole() == UserRole.ADMIN;
        ForumComment saved = comments.saveAndFlush(new ForumComment(post, author, parent, content, markOfficial));
        if (markOfficial && post.getRequestStatus() == ForumRequestStatus.OPEN) {
            post.setRequestStatus(ForumRequestStatus.ANSWERED);
            posts.save(post);
        }
        if (markOfficial) {
            audit.record(author.getUsername(), "FORUM_ADMIN_REPLY", "FORUM_COMMENT", saved.getId(),
                    "{\"postId\":\"" + postId + "\",\"parentCommentId\":"
                            + (parentCommentId == null ? "null" : "\"" + parentCommentId + "\"") + "}");
        }
        return saved.getId();
    }

    @Transactional
    public UUID editComment(UUID commentId, String username, boolean isAdmin, String rawContent) {
        ForumComment comment = comments.findByIdWithAuthorAndPost(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bình luận."));
        if (!canModerate(comment.getAuthor().getUsername(), username, isAdmin)) {
            throw new AccessDeniedException("Bạn không có quyền chỉnh sửa bình luận này.");
        }
        comment.setContent(validateCommentContent(rawContent));
        comment.setEditedAt(Instant.now());
        comments.save(comment);
        if (isAdmin && !comment.getAuthor().getUsername().equalsIgnoreCase(username)) {
            audit.record(username, "FORUM_EDIT_COMMENT", "FORUM_COMMENT", commentId,
                    "{\"postId\":\"" + comment.getPost().getId() + "\"}");
        }
        return comment.getPost().getId();
    }

    @Transactional
    public UUID toggleAcceptedAnswer(UUID commentId, String username, boolean isAdmin) {
        ForumComment comment = comments.findByIdWithAuthorAndPost(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bình luận."));
        ForumPost post = posts.findByIdWithAuthor(comment.getPost().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        boolean isPostAuthor = username != null && post.getAuthor().getUsername().equalsIgnoreCase(username);
        if (!isPostAuthor && !isAdmin) {
            throw new AccessDeniedException("Chỉ tác giả bài viết hoặc quản trị viên mới có thể đánh dấu câu trả lời hữu ích.");
        }
        if (post.getStatus() == ForumPostStatus.REMOVED) {
            throw new IllegalArgumentException("Không thể đánh dấu câu trả lời trên bài viết đã bị gỡ.");
        }

        UUID postId = post.getId();
        boolean nextAccepted = !comment.isAcceptedAnswer();
        if (!nextAccepted) {
            comment.setAcceptedAnswer(false);
            comments.saveAndFlush(comment);
            if (post.getRequestStatus() == ForumRequestStatus.RESOLVED) {
                boolean hasOfficial = comments.existsByPostIdAndOfficialAdminReplyTrue(postId);
                post.setRequestStatus(hasOfficial ? ForumRequestStatus.ANSWERED : ForumRequestStatus.OPEN);
                posts.save(post);
            }
        } else {
            // Partial unique index guarantees at most one accepted_answer = true per post.
            comments.clearAcceptedAnswers(postId);
            ForumComment fresh = comments.findById(commentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bình luận."));
            fresh.setAcceptedAnswer(true);
            comments.saveAndFlush(fresh);
            post.setRequestStatus(ForumRequestStatus.RESOLVED);
            posts.save(post);
        }
        if (isAdmin) {
            audit.record(username, "FORUM_ACCEPT_COMMENT", "FORUM_COMMENT", commentId,
                    "{\"postId\":\"" + postId + "\",\"accepted\":" + nextAccepted + "}");
        }
        return postId;
    }

    @Transactional
    public void removePost(UUID postId, String actor, String rawReason) {
        ForumPost post = posts.findByIdWithAuthor(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        String reason = (rawReason == null || rawReason.isBlank())
                ? "Vi phạm nội quy diễn đàn."
                : rawReason.strip();
        if (reason.length() > 1000) {
            reason = reason.substring(0, 1000);
        }
        post.markRemoved(actor, reason, Instant.now());
        posts.save(post);
        audit.record(actor, "FORUM_REMOVE_POST", "FORUM_POST", postId,
                "{\"title\":\"" + escapeJson(post.getTitle()) + "\",\"reason\":\"" + escapeJson(reason) + "\"}");
    }

    @Transactional
    public void restorePost(UUID postId, String actor) {
        ForumPost post = posts.findByIdWithAuthor(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        post.restore();
        posts.save(post);
        audit.record(actor, "FORUM_RESTORE_POST", "FORUM_POST", postId,
                "{\"title\":\"" + escapeJson(post.getTitle()) + "\"}");
    }

    @Transactional
    public void deletePost(UUID postId, String username, boolean isAdmin) {
        ForumPost post = posts.findByIdWithAuthor(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        if (!canModerate(post.getAuthor().getUsername(), username, isAdmin)) {
            throw new AccessDeniedException("Bạn không có quyền xóa bài viết này.");
        }
        String title = post.getTitle();
        posts.delete(post);
        if (isAdmin) {
            audit.record(username, "FORUM_DELETE_POST", "FORUM_POST", postId,
                    "{\"title\":\"" + escapeJson(title) + "\"}");
        }
    }

    @Transactional
    public boolean togglePin(UUID postId, boolean isAdmin) {
        return togglePin(postId, null, isAdmin);
    }

    @Transactional
    public boolean togglePin(UUID postId, String actor, boolean isAdmin) {
        if (!isAdmin) {
            throw new AccessDeniedException("Chỉ quản trị viên mới có quyền ghim bài viết.");
        }
        ForumPost post = posts.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        boolean nextPinned = !post.isPinned();
        post.setPinned(nextPinned);
        posts.save(post);
        if (actor != null && !actor.isBlank()) {
            audit.record(actor, "FORUM_PIN_POST", "FORUM_POST", postId,
                    "{\"pinned\":" + nextPinned + "}");
        }
        return nextPinned;
    }

    @Transactional
    public UUID deleteComment(UUID commentId, String username, boolean isAdmin) {
        ForumComment comment = comments.findByIdWithAuthorAndPost(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bình luận."));
        if (!canModerate(comment.getAuthor().getUsername(), username, isAdmin)) {
            throw new AccessDeniedException("Bạn không có quyền xóa bình luận này.");
        }
        UUID postId = comment.getPost().getId();
        boolean wasAccepted = comment.isAcceptedAnswer();
        comments.delete(comment);
        comments.flush();
        if (wasAccepted) {
            posts.findById(postId).ifPresent(post -> {
                if (post.getRequestStatus() == ForumRequestStatus.RESOLVED) {
                    boolean hasOfficial = comments.existsByPostIdAndOfficialAdminReplyTrue(postId);
                    post.setRequestStatus(hasOfficial ? ForumRequestStatus.ANSWERED : ForumRequestStatus.OPEN);
                    posts.save(post);
                }
            });
        }
        if (isAdmin && !comment.getAuthor().getUsername().equalsIgnoreCase(username)) {
            audit.record(username, "FORUM_DELETE_COMMENT", "FORUM_COMMENT", commentId,
                    "{\"postId\":\"" + postId + "\"}");
        }
        return postId;
    }

    private static String validateTitle(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.strip();
        if (title.length() < 5 || title.length() > 200) {
            throw new IllegalArgumentException("Tiêu đề bài viết phải từ 5 đến 200 ký tự.");
        }
        return title;
    }

    private static String validatePostContent(String rawContent) {
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.length() < 10 || content.length() > 5000) {
            throw new IllegalArgumentException("Nội dung bài viết phải từ 10 đến 5000 ký tự.");
        }
        return content;
    }

    private static String validateCommentContent(String rawContent) {
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.length() < 2 || content.length() > 2000) {
            throw new IllegalArgumentException("Bình luận phải từ 2 đến 2000 ký tự.");
        }
        return content;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private AppUser requireUser(String username) {
        String normalized = AppUser.normalizeUsername(username);
        return users.findByUsername(normalized)
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new AccessDeniedException("Tài khoản không hợp lệ hoặc đã bị khóa."));
    }

    private boolean canModerate(String authorUsername, String viewerUsername, boolean viewerIsAdmin) {
        if (viewerIsAdmin) {
            return true;
        }
        return viewerUsername != null
                && !viewerUsername.isBlank()
                && authorUsername.equalsIgnoreCase(viewerUsername);
    }

    private String formatTime(Instant instant) {
        return instant == null ? "" : DISPLAY_TIME.format(instant);
    }

    private String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String singleLine = content.replaceAll("\\s+", " ").strip();
        if (singleLine.length() <= 180) {
            return singleLine;
        }
        return singleLine.substring(0, 177) + "...";
    }
}

