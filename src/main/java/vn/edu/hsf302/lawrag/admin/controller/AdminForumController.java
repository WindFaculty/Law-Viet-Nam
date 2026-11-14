package vn.edu.hsf302.lawrag.admin.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.edu.hsf302.lawrag.admin.dto.AdminForumPageView;
import vn.edu.hsf302.lawrag.admin.dto.AiDraftResult;
import vn.edu.hsf302.lawrag.admin.service.AdminForumService;
import vn.edu.hsf302.lawrag.forum.entity.ForumCategory;
import vn.edu.hsf302.lawrag.llm.service.LlmPersonaService;

import java.util.UUID;

/**
 * Admin forum moderation routes: request queue, official replies, RAG reply drafting,
 * soft remove/restore, pin/unpin, and permanent delete.
 */
@Controller
@RequestMapping("/admin/forum")
public class AdminForumController {

    private final AdminForumService adminForumService;
    private final LlmPersonaService personaService;

    public AdminForumController(AdminForumService adminForumService,
                                LlmPersonaService personaService) {
        this.adminForumService = adminForumService;
        this.personaService = personaService;
    }

    @GetMapping
    public String index(@RequestParam(name = "status", defaultValue = "ALL") String status,
                        @RequestParam(name = "requestStatus", defaultValue = "ALL") String requestStatus,
                        @RequestParam(name = "category", required = false) String categoryParam,
                        @RequestParam(name = "q", required = false) String query,
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        Model model) {
        ForumCategory category = ForumCategory.fromNullable(categoryParam).orElse(null);
        AdminForumPageView postsPage = adminForumService.listPosts(status, requestStatus, category, query, page);

        model.addAttribute("postsPage", postsPage);
        model.addAttribute("posts", postsPage.posts());
        model.addAttribute("status", status);
        model.addAttribute("requestStatus", requestStatus);
        model.addAttribute("selectedCategory", category != null ? category.name() : "");
        model.addAttribute("categories", ForumCategory.values());
        model.addAttribute("query", query == null ? "" : query.strip());
        model.addAttribute("personas", personaService.listActivePersonas());
        model.addAttribute("defaultPersonaCode", personaService.getDefaultPersona().getCode());
        model.addAttribute("activeAdminTab", "forum");
        return "admin/forum";
    }

    @PostMapping("/posts/{postId}/reply")
    public String reply(@PathVariable UUID postId,
                        @RequestParam(name = "content", defaultValue = "") String content,
                        @RequestParam(name = "parentCommentId", required = false) UUID parentCommentId,
                        Authentication authentication,
                        RedirectAttributes redirectAttributes) {
        try {
            adminForumService.replyOfficially(postId, actor(authentication), content, parentCommentId);
            redirectAttributes.addFlashAttribute("message", "Đã đăng phản hồi chính thức của Quản trị viên.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/forum";
    }

    @PostMapping("/posts/{postId}/ai-draft")
    @ResponseBody
    public AiDraftResult aiDraft(@PathVariable UUID postId,
                                 @RequestParam(name = "personaCode", required = false) String personaCode) {
        return adminForumService.generateAiDraft(postId, personaCode);
    }

    @PostMapping("/posts/{postId}/remove")
    public String remove(@PathVariable UUID postId,
                         @RequestParam(name = "reason", defaultValue = "") String reason,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        adminForumService.removePost(postId, actor(authentication), reason);
        redirectAttributes.addFlashAttribute("message", "Đã gỡ bài viết khỏi diễn đàn công khai.");
        return "redirect:/admin/forum";
    }

    @PostMapping("/posts/{postId}/restore")
    public String restore(@PathVariable UUID postId,
                          Authentication authentication,
                          RedirectAttributes redirectAttributes) {
        adminForumService.restorePost(postId, actor(authentication));
        redirectAttributes.addFlashAttribute("message", "Đã khôi phục bài viết lên diễn đàn.");
        return "redirect:/admin/forum";
    }

    @PostMapping("/posts/{postId}/delete")
    public String delete(@PathVariable UUID postId,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        adminForumService.deletePost(postId, actor(authentication));
        redirectAttributes.addFlashAttribute("message", "Đã xóa vĩnh viễn bài viết và ghi audit log.");
        return "redirect:/admin/forum";
    }

    @PostMapping("/posts/{postId}/pin")
    public String togglePin(@PathVariable UUID postId,
                            Authentication authentication,
                            RedirectAttributes redirectAttributes) {
        boolean pinned = adminForumService.togglePin(postId, actor(authentication));
        redirectAttributes.addFlashAttribute("message",
                pinned ? "Đã ghim bài viết." : "Đã bỏ ghim bài viết.");
        return "redirect:/admin/forum";
    }

    @PostMapping("/comments/{commentId}/delete")
    public String deleteComment(@PathVariable UUID commentId,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        UUID postId = adminForumService.deleteComment(commentId, actor(authentication));
        redirectAttributes.addFlashAttribute("message", "Đã xóa bình luận trên bài viết " + postId + ".");
        return "redirect:/forum/" + postId + "#comments";
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "admin" : authentication.getName();
    }
}

