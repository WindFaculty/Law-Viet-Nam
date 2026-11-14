package vn.edu.hsf302.lawrag.forum.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;
import vn.edu.hsf302.lawrag.forum.dto.ForumPostPageView;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.forum.service.ForumService;

@Controller
public class ForumController {

    private final ForumService forumService;

    public ForumController(ForumService forumService) {
        this.forumService = forumService;
    }

    @GetMapping("/forum")
    public String index(@RequestParam(name = "category", required = false) String categoryParam,
                        @RequestParam(name = "q", required = false) String query,
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        Model model) {
        ForumCategory selectedCategory = ForumCategory.fromNullable(categoryParam).orElse(null);
        ForumPostPageView postsPage = forumService.listPosts(selectedCategory, query, page);
        model.addAttribute("postsPage", postsPage);
        model.addAttribute("posts", postsPage.posts());
        model.addAttribute("totalPosts", postsPage.totalPosts());
        model.addAttribute("currentPage", postsPage.page());
        model.addAttribute("totalPages", postsPage.totalPages());
        model.addAttribute("pageSize", postsPage.pageSize());
        model.addAttribute("categories", ForumCategory.values());
        model.addAttribute("selectedCategory", selectedCategory != null ? selectedCategory.name() : "");
        model.addAttribute("query", query == null ? "" : query.strip());
        return "forum/index";
    }

    @GetMapping("/forum/{postId}")
    public String detail(@PathVariable UUID postId,
                         Authentication authentication,
                         Model model) {
        String viewerUsername = authenticatedUsername(authentication);
        boolean viewerAdmin = isAdmin(authentication);
        model.addAttribute("post", forumService.getPost(postId, viewerUsername, viewerAdmin));
        model.addAttribute("categories", ForumCategory.values());
        return "forum/detail";
    }

    @PostMapping("/forum/posts")
    public String createPost(@RequestParam(name = "title", defaultValue = "") String title,
                             @RequestParam(name = "category", defaultValue = "THAO_LUAN") String categoryParam,
                             @RequestParam(name = "content", defaultValue = "") String content,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        String username = authenticatedUsername(authentication);
        if (username == null) {
            return "redirect:/login";
        }
        ForumCategory category = ForumCategory.fromNullable(categoryParam).orElse(ForumCategory.THAO_LUAN);
        try {
            UUID postId = forumService.createPost(username, title, category, content);
            redirectAttributes.addFlashAttribute("message", "Đã đăng bài viết mới.");
            return "redirect:/forum/" + postId;
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            redirectAttributes.addFlashAttribute("draftTitle", title);
            redirectAttributes.addFlashAttribute("draftCategory", category.name());
            redirectAttributes.addFlashAttribute("draftContent", content);
            return "redirect:/forum";
        }
    }

    @PostMapping("/forum/posts/{postId}/edit")
    public String editPost(@PathVariable UUID postId,
                           @RequestParam(name = "title", defaultValue = "") String title,
                           @RequestParam(name = "category", defaultValue = "THAO_LUAN") String categoryParam,
                           @RequestParam(name = "content", defaultValue = "") String content,
                           Authentication authentication,
                           RedirectAttributes redirectAttributes) {
        String username = authenticatedUsername(authentication);
        if (username == null) {
            return "redirect:/login";
        }
        ForumCategory category = ForumCategory.fromNullable(categoryParam).orElse(ForumCategory.THAO_LUAN);
        try {
            forumService.editPost(postId, username, isAdmin(authentication), title, category, content);
            redirectAttributes.addFlashAttribute("message", "Đã cập nhật bài viết.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/forum/" + postId;
    }

    @PostMapping("/forum/posts/{postId}/comments")
    public String addComment(@PathVariable UUID postId,
                             @RequestParam(name = "content", defaultValue = "") String content,
                             @RequestParam(name = "parentCommentId", required = false) UUID parentCommentId,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        String username = authenticatedUsername(authentication);
        if (username == null) {
            return "redirect:/login";
        }
        try {
            UUID commentId = forumService.addComment(
                    postId, username, content, parentCommentId, isAdmin(authentication));
            redirectAttributes.addFlashAttribute("message",
                    parentCommentId != null ? "Đã gửi phản hồi." : "Đã gửi bình luận.");
            return "redirect:/forum/" + postId
                    + (parentCommentId == null ? "#comments" : "#comment-" + commentId);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            redirectAttributes.addFlashAttribute("draftComment", content);
        }
        return "redirect:/forum/" + postId + "#comments";
    }

    @PostMapping("/forum/comments/{commentId}/edit")
    public String editComment(@PathVariable UUID commentId,
                              @RequestParam(name = "content", defaultValue = "") String content,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        String username = authenticatedUsername(authentication);
        if (username == null) {
            return "redirect:/login";
        }
        UUID postId = forumService.editComment(commentId, username, isAdmin(authentication), content);
        redirectAttributes.addFlashAttribute("message", "Đã cập nhật bình luận.");
        return "redirect:/forum/" + postId + "#comment-" + commentId;
    }

    @PostMapping("/forum/comments/{commentId}/accept")
    public String acceptComment(@PathVariable UUID commentId,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        String username = authenticatedUsername(authentication);
        if (username == null) {
            return "redirect:/login";
        }
        UUID postId = forumService.toggleAcceptedAnswer(commentId, username, isAdmin(authentication));
        redirectAttributes.addFlashAttribute("message", "Đã cập nhật trạng thái câu trả lời hữu ích.");
        return "redirect:/forum/" + postId + "#comment-" + commentId;
    }

    @PostMapping("/forum/posts/{postId}/delete")
    public String deletePost(@PathVariable UUID postId,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        String username = authenticatedUsername(authentication);
        if (username == null) {
            return "redirect:/login";
        }
        forumService.deletePost(postId, username, isAdmin(authentication));
        redirectAttributes.addFlashAttribute("message", "Đã xóa bài viết.");
        return "redirect:/forum";
    }

    @PostMapping("/forum/posts/{postId}/pin")
    public String togglePin(@PathVariable UUID postId,
                            Authentication authentication,
                            RedirectAttributes redirectAttributes) {
        String username = authenticatedUsername(authentication);
        if (username == null) {
            return "redirect:/login";
        }
        boolean pinned = forumService.togglePin(postId, username, isAdmin(authentication));
        redirectAttributes.addFlashAttribute("message",
                pinned ? "Đã ghim bài viết lên đầu." : "Đã bỏ ghim bài viết.");
        return "redirect:/forum/" + postId;
    }

    @PostMapping("/forum/comments/{commentId}/delete")
    public String deleteComment(@PathVariable UUID commentId,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        String username = authenticatedUsername(authentication);
        if (username == null) {
            return "redirect:/login";
        }
        UUID postId = forumService.deleteComment(commentId, username, isAdmin(authentication));
        redirectAttributes.addFlashAttribute("message", "Đã xóa bình luận.");
        return "redirect:/forum/" + postId + "#comments";
    }

    private String authenticatedUsername(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}

