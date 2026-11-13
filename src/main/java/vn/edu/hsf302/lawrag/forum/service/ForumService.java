package vn.edu.hsf302.lawrag.forum.service;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.edu.hsf302.lawrag.forum.dto.CommentView;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.forum.entity.ForumComment;
import vn.edu.hsf302.lawrag.forum.repository.ForumCommentRepository;
import vn.edu.hsf302.lawrag.forum.entity.ForumPost;
import vn.edu.hsf302.lawrag.forum.repository.ForumPostRepository;
import vn.edu.hsf302.lawrag.forum.dto.PostDetailView;
import vn.edu.hsf302.lawrag.forum.dto.PostSummaryView;

@Service
public class ForumService {

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(TimeConfig.BUSINESS_ZONE);

    private final ForumPostRepository posts;
    private final ForumCommentRepository comments;
    private final AppUserRepository users;

    public ForumService(ForumPostRepository posts,
                        ForumCommentRepository comments,
                        AppUserRepository users) {
        this.posts = posts;
        this.comments = comments;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<PostSummaryView> listPosts(ForumCategory category, String rawKeyword) {
        String keyword = (rawKeyword == null || rawKeyword.isBlank()) ? null : rawKeyword.strip();
        List<ForumPost> found;
        if (category == null && keyword == null) {
            found = posts.findAllWithAuthor();
        } else if (category != null && keyword == null) {
            found = posts.findByCategoryWithAuthor(category);
        } else if (category == null) {
            found = posts.searchByKeywordWithAuthor(keyword);
        } else {
            found = posts.searchByCategoryAndKeywordWithAuthor(category, keyword);
        }
        if (found.isEmpty()) {
            return List.of();
        }
        List<UUID> postIds = found.stream().map(ForumPost::getId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : comments.countByPostIds(postIds)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }

        return found.stream()
                .map(post -> new PostSummaryView(
                        post.getId(),
                        post.getTitle(),
                        excerpt(post.getContent()),
                        post.getCategory().name(),
                        post.getCategory().getLabel(),
                        post.getAuthor().getDisplayName(),
                        post.getAuthor().getUsername(),
                        post.isPinned(),
                        counts.getOrDefault(post.getId(), 0L),
                        formatTime(post.getCreatedAt())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PostDetailView getPost(UUID postId, String viewerUsername, boolean viewerIsAdmin) {
        ForumPost post = posts.findByIdWithAuthor(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));

        List<CommentView> commentViews = comments.findByPostIdWithAuthor(postId).stream()
                .map(comment -> new CommentView(
                        comment.getId(),
                        comment.getContent(),
                        comment.getAuthor().getDisplayName(),
                        comment.getAuthor().getUsername(),
                        formatTime(comment.getCreatedAt()),
                        canModerate(comment.getAuthor().getUsername(), viewerUsername, viewerIsAdmin)))
                .toList();

        return new PostDetailView(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getCategory().name(),
                post.getCategory().getLabel(),
                post.getAuthor().getDisplayName(),
                post.getAuthor().getUsername(),
                post.isPinned(),
                formatTime(post.getCreatedAt()),
                canModerate(post.getAuthor().getUsername(), viewerUsername, viewerIsAdmin),
                viewerIsAdmin,
                commentViews);
    }

    @Transactional
    public UUID createPost(String username, String rawTitle, ForumCategory category, String rawContent) {
        AppUser author = requireUser(username);
        String title = rawTitle == null ? "" : rawTitle.strip();
        if (title.length() < 5 || title.length() > 200) {
            throw new IllegalArgumentException("Tiêu đề bài viết phải từ 5 đến 200 ký tự.");
        }
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.length() < 10 || content.length() > 5000) {
            throw new IllegalArgumentException("Nội dung bài viết phải từ 10 đến 5000 ký tự.");
        }
        ForumPost saved = posts.save(new ForumPost(author, title, category, content));
        return saved.getId();
    }

    @Transactional
    public void addComment(UUID postId, String username, String rawContent) {
        AppUser author = requireUser(username);
        ForumPost post = posts.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.length() < 2 || content.length() > 2000) {
            throw new IllegalArgumentException("Bình luận phải từ 2 đến 2000 ký tự.");
        }
        comments.save(new ForumComment(post, author, content));
    }

    @Transactional
    public void deletePost(UUID postId, String username, boolean isAdmin) {
        ForumPost post = posts.findByIdWithAuthor(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        if (!canModerate(post.getAuthor().getUsername(), username, isAdmin)) {
            throw new AccessDeniedException("Bạn không có quyền xóa bài viết này.");
        }
        posts.delete(post);
    }

    @Transactional
    public boolean togglePin(UUID postId, boolean isAdmin) {
        if (!isAdmin) {
            throw new AccessDeniedException("Chỉ quản trị viên mới có quyền ghim bài viết.");
        }
        ForumPost post = posts.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài viết."));
        boolean nextPinned = !post.isPinned();
        post.setPinned(nextPinned);
        posts.save(post);
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
        comments.delete(comment);
        return postId;
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

