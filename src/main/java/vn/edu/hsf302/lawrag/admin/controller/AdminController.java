package vn.edu.hsf302.lawrag.admin.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.edu.hsf302.lawrag.auth.entity.UserRole;

import java.util.UUID;
import vn.edu.hsf302.lawrag.admin.service.AdminApprovalException;
import vn.edu.hsf302.lawrag.admin.service.AdminService;
import vn.edu.hsf302.lawrag.admin.service.AdminUserService;
import vn.edu.hsf302.lawrag.admin.dto.ApprovalCommand;
import vn.edu.hsf302.lawrag.admin.dto.Dashboard;

/**
 * Minimal admin console: job/stage visibility, quarantined documents, user role
 * management and the human approval gate. All routes require ROLE_ADMIN; POST is
 * CSRF-protected by the Spring Security filter chain.
 */
@Controller
public class AdminController {

    private final AdminService adminService;
    private final AdminUserService adminUserService;

    public AdminController(AdminService adminService, AdminUserService adminUserService) {
        this.adminService = adminService;
        this.adminUserService = adminUserService;
    }

    @GetMapping({"/admin", "/admin/"})
    public String index(Model model) {
        populate(model);
        return "admin/index";
    }

    @GetMapping("/admin/web-search-jobs")
    public String webSearchJobs(Model model) {
        populate(model);
        return "admin/index";
    }

    @PostMapping("/admin/legal-documents/{documentVersionId}/approve")
    public String approve(@PathVariable UUID documentVersionId,
                          @RequestParam(name = "docNumber", required = false) String docNumber,
                          @RequestParam(name = "issuingBody", required = false) String issuingBody,
                          @RequestParam(name = "issueDate", required = false) String issueDate,
                          @RequestParam(name = "effectiveFrom", required = false) String effectiveFrom,
                          @RequestParam(name = "effectiveTo", required = false) String effectiveTo,
                          @RequestParam(name = "effectiveStatus", required = false) String effectiveStatus,
                          @RequestParam(name = "reason", required = false) String reason,
                          Authentication authentication,
                          RedirectAttributes redirectAttributes) {
        try {
            adminService.approve(documentVersionId,
                    new ApprovalCommand(
                            docNumber, issuingBody, issueDate,
                            effectiveFrom, effectiveTo, effectiveStatus, reason),
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    "Đã duyệt văn bản " + documentVersionId + " sang READY và ghi audit.");
        } catch (AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/web-search-jobs";
    }

    @PostMapping("/admin/users/{userId}/role")
    public String updateUserRole(@PathVariable UUID userId,
                                 @RequestParam("role") String roleParam,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            UserRole targetRole = UserRole.valueOf(roleParam.strip().toUpperCase());
            UserRole updated = adminUserService.updateRole(
                    userId,
                    targetRole,
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    "Đã cập nhật vai trò tài khoản sang " + updated.name() + ".");
        } catch (IllegalArgumentException | AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error",
                    exception instanceof AdminApprovalException ? exception.getMessage() : "Vai trò không hợp lệ.");
        }
        return "redirect:/admin#users-heading";
    }

    @PostMapping("/admin/users/{userId}/toggle-enabled")
    public String toggleUserEnabled(@PathVariable UUID userId,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            boolean enabled = adminUserService.toggleEnabled(
                    userId,
                    authentication == null ? "admin" : authentication.getName());
            redirectAttributes.addFlashAttribute("message",
                    enabled ? "Đã mở khóa tài khoản." : "Đã khóa tài khoản.");
        } catch (AdminApprovalException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin#users-heading";
    }

    private void populate(Model model) {
        Dashboard dashboard = adminService.dashboard();
        model.addAttribute("jobs", dashboard.jobs());
        model.addAttribute("quarantinedDocuments", dashboard.quarantinedDocuments());
        model.addAttribute("auditRows", dashboard.audit());
        model.addAttribute("users", adminUserService.listUsers());
    }
}
